package io.github.pertyjons.result.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LimitedBodySubscriberTest {
  private final AtomicBoolean cancelled = new AtomicBoolean();

  private LimitedBodySubscriber subscriber(int limit) {
    var subscriber = new LimitedBodySubscriber(BodySubscribers.ofString(UTF_8), limit);
    subscriber.onSubscribe(new Flow.Subscription() {
      @Override public void request(long n) {}
      @Override public void cancel() { cancelled.set(true); }
    });
    return subscriber;
  }

  private static ByteBuffer bytes(String text) {
    return ByteBuffer.wrap(text.getBytes(UTF_8));
  }

  @Test
  void exactLimitIsCompleteAndNotTruncated() throws Exception {
    var subscriber = subscriber(4);
    subscriber.onNext(List.of(bytes("ab"), bytes("cd")));
    assertThat(subscriber.getBody().toCompletableFuture()).isNotDone();
    subscriber.onComplete();
    assertThat(subscriber.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .isEqualTo(new LimitedBodySubscriber.Body("abcd", false));
    assertThat(cancelled.get()).isFalse();
  }

  @Test
  void truncatesAcrossChunksAndCancelsWithoutWaitingForTheRemainder() throws Exception {
    var subscriber = subscriber(4);
    subscriber.onNext(List.of(bytes("abc")));
    subscriber.onNext(List.of(bytes("def"), bytes("must not be retained")));
    assertThat(subscriber.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .isEqualTo(new LimitedBodySubscriber.Body("abcd", true));
    assertThat(cancelled.get()).isTrue();
    subscriber.onError(new IllegalStateException("late cancellation signal"));
    subscriber.onNext(List.of(bytes("late bytes")));
    subscriber.onComplete();
    assertThat(subscriber.getBody().toCompletableFuture().join().text()).isEqualTo("abcd");
  }

  @Test
  void limitCountsBytesAndUsesReplacementForAnIncompleteCharacter() throws Exception {
    var subscriber = subscriber(3);
    subscriber.onNext(List.of(bytes("éé")));
    assertThat(subscriber.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .isEqualTo(new LimitedBodySubscriber.Body("é\ufffd", true));
  }

  @Test
  void zeroLimitDistinguishesEmptyAndNonEmptyBodies() throws Exception {
    var empty = subscriber(0);
    empty.onNext(List.of(bytes("")));
    empty.onComplete();
    assertThat(empty.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .isEqualTo(new LimitedBodySubscriber.Body("", false));
    var nonEmpty = subscriber(0);
    nonEmpty.onNext(List.of(bytes("x")));
    assertThat(nonEmpty.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .isEqualTo(new LimitedBodySubscriber.Body("", true));
    assertThat(cancelled.get()).isTrue();
  }

  @Test
  void honorsByteBufferPositionAndLeavesTheInputUntouched() throws Exception {
    var subscriber = subscriber(2);
    var input = bytes("prefixBODYsuffix");
    input.position(6).limit(10);
    subscriber.onNext(List.of(input));
    assertThat(subscriber.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .isEqualTo(new LimitedBodySubscriber.Body("BO", true));
    assertThat(input.position()).isEqualTo(6);
    assertThat(input.limit()).isEqualTo(10);
  }
}
