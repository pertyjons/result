package io.github.pertyjons.result.http;

import static java.util.Objects.requireNonNull;

import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/** Reads only a byte-bounded prefix, retaining the JDK's Content-Type charset handling. */
final class LimitedBodySubscriber implements BodySubscriber<LimitedBodySubscriber.Body> {
  record Body(String text, boolean truncated) {}

  private final BodySubscriber<String> delegate;
  private final CompletionStage<Body> body;
  private int remaining;
  private boolean truncated;
  private boolean done;
  private Flow.@Nullable Subscription subscription;

  LimitedBodySubscriber(BodySubscriber<String> delegate, int limit) {
    this.delegate = delegate;
    this.remaining = limit;
    this.body = delegate.getBody().thenApply(text -> new Body(text, truncated));
  }

  @Override
  public CompletionStage<Body> getBody() {
    return body;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    delegate.onSubscribe(subscription);
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    if (done) {
      return;
    }
    var prefix = new ArrayList<ByteBuffer>();
    for (var buffer : buffers) {
      int length = Math.min(remaining, buffer.remaining());
      if (length > 0) {
        // Copy the retained bytes so a tiny prefix cannot retain a large backing buffer.
        var copy = ByteBuffer.allocate(length);
        copy.put(buffer.slice(buffer.position(), length)).flip();
        prefix.add(copy);
        remaining -= length;
      }
      if (buffer.remaining() > length) {
        truncated = true;
        break;
      }
    }
    if (!prefix.isEmpty()) {
      delegate.onNext(prefix);
    }
    if (truncated) {
      done = true;
      requireNonNull(subscription).cancel();
      delegate.onComplete();
    }
  }

  @Override
  public void onError(Throwable failure) {
    if (!done) {
      done = true;
      delegate.onError(failure);
    }
  }

  @Override
  public void onComplete() {
    if (!done) {
      done = true;
      delegate.onComplete();
    }
  }
}
