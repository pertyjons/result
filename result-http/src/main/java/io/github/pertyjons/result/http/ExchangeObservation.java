package io.github.pertyjons.result.http;

import static java.util.Objects.requireNonNull;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.http.HttpObservation.Outcome;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Per-send state; never shared by repeated sends of an immutable Request. */
final class ExchangeObservation {
  private final Optional<Consumer<HttpObservation>> observer;
  private final String method;
  private final String operation;
  private final String host;
  private final long start;
  private final AtomicBoolean completed = new AtomicBoolean();
  private volatile int statusCode;
  private volatile long elapsedNanos = -1;

  ExchangeObservation(
      Optional<Consumer<HttpObservation>> observer, HttpRequest request, String operation) {
    this.observer = observer;
    this.method = request.method();
    this.operation = operation;
    this.host = requireNonNull(request.uri().getHost());
    this.start = observer.isPresent() ? System.nanoTime() : 0;
  }

  void status(int code) {
    statusCode = code;
  }

  void finishTiming() {
    if (observer.isPresent()) {
      elapsedNanos = System.nanoTime() - start;
    }
  }

  void complete(@Nullable Result<?, HttpError> result, @Nullable Throwable failure) {
    if (observer.isEmpty() || !completed.compareAndSet(false, true)) {
      return;
    }
    Outcome outcome;
    Optional<String> exceptionType = Optional.empty();
    if (failure != null) {
      while (failure instanceof CompletionException && failure.getCause() != null) {
        failure = failure.getCause();
      }
      outcome = failure instanceof CancellationException ? Outcome.CANCELLED : Outcome.EXCEPTION;
      exceptionType = Optional.of(failure.getClass().getName());
    } else if (requireNonNull(result) instanceof Result.Err<?, HttpError>(var error)) {
      outcome = switch (error) {
        case HttpError.Status _ -> Outcome.STATUS;
        case HttpError.Transport _ -> Outcome.TRANSPORT;
        case HttpError.Timeout _ -> Outcome.TIMEOUT;
        case HttpError.Interrupted _ -> Outcome.INTERRUPTED;
        case HttpError.Decode _ -> Outcome.DECODE;
      };
      exceptionType = switch (error) {
        case HttpError.Transport e -> Optional.of(e.cause().getClass().getName());
        case HttpError.Timeout e -> Optional.of(e.cause().getClass().getName());
        case HttpError.Decode e -> e.error().cause().map(cause -> cause.getClass().getName());
        default -> Optional.empty();
      };
    } else {
      outcome = Outcome.SUCCESS;
    }
    long elapsed = elapsedNanos;
    var event = new HttpObservation(
        method, operation, host, Duration.ofNanos(elapsed < 0 ? System.nanoTime() - start : elapsed),
        statusCode == 0 ? OptionalInt.empty() : OptionalInt.of(statusCode), outcome, exceptionType);
    try {
      observer.orElseThrow().accept(event);
    } catch (RuntimeException ignored) {
      // Diagnostics must not replace the HTTP outcome or start a recursive logging failure.
    }
  }
}
