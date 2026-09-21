package io.github.pertyjons.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncResultTest {
  @Test
  void waitsForBothStagesAndTransformsBothTypes() {
    var source = new CompletableFuture<Result<Integer, String>>();
    var next = new CompletableFuture<Result<Long, String>>();
    var calls = new AtomicInteger();
    AsyncResult<Number, Integer> result = AsyncResult.from(source)
        .<Number>flatMap(value -> {
          calls.addAndGet(value);
          return AsyncResult.from(next);
        })
        .transformResult(r -> r.mapError(String::length));
    assertThat(calls.get()).isZero();
    assertThat(result.stage().toCompletableFuture()).isNotDone();
    source.complete(Result.ok(3));
    assertThat(calls.get()).isEqualTo(3);
    assertThat(result.stage().toCompletableFuture()).isNotDone();
    next.complete(Result.ok(42L));
    assertThat(result.stage().toCompletableFuture().join()).isEqualTo(Result.ok(42L));
  }

  @Test
  void errorSkipsFlatMapButCanBeTransformed() {
    var calls = new AtomicInteger();
    var result = AsyncResult.completed(Result.<Integer, String>error("bad"))
        .flatMap(value -> {
          calls.incrementAndGet();
          return AsyncResult.completed(Result.ok(value));
        })
        .transformResult(r -> r.mapError(String::length));
    assertThat(result.stage().toCompletableFuture().join()).isEqualTo(Result.error(3));
    assertThat(calls.get()).isZero();
  }

  @Test
  void recoveryCanChangeErrorTypeAndRemainsAsynchronous() {
    var fallback = new CompletableFuture<Result<Integer, Integer>>();
    AsyncResult<Number, Integer> result = AsyncResult.completed(Result.<Number, String>error("bad"))
        .recover(error -> {
          assertThat(error).isEqualTo("bad");
          return AsyncResult.from(fallback);
        });
    assertThat(result.stage().toCompletableFuture()).isNotDone();
    fallback.complete(Result.error(503));
    assertThat(result.stage().toCompletableFuture().join()).isEqualTo(Result.error(503));
  }

  @Test
  void successSkipsRecoveryAndFoldSelectsOneHandler() {
    AsyncResult<Integer, Integer> result = AsyncResult.completed(Result.<Integer, String>ok(7))
        .recover(_ -> { throw new AssertionError("must not recover Ok"); });
    assertThat(result.fold(Object::toString, _ -> { throw new AssertionError(); })
        .toCompletableFuture().join()).isEqualTo("7");
    assertThat(AsyncResult.completed(Result.<Integer, String>error("bad"))
        .fold(_ -> { throw new AssertionError(); }, String::length)
        .toCompletableFuture().join()).isEqualTo(3);
  }

  @Test
  void exceptionalCompletionBypassesResultCallbacks() {
    var failure = new IllegalStateException("broken");
    var source = new CompletableFuture<Result<Integer, String>>();
    var result = AsyncResult.from(source)
        .transformResult(_ -> { throw new AssertionError(); })
        .flatMap(_ -> { throw new AssertionError(); })
        .recover(_ -> { throw new AssertionError(); })
        .fold(_ -> { throw new AssertionError(); }, _ -> { throw new AssertionError(); });
    source.completeExceptionally(failure);
    assertThatThrownBy(() -> result.toCompletableFuture().join())
        .isInstanceOf(CompletionException.class).hasCause(failure);
  }

  @Test
  void thrownCallbackAndCancellationStayExceptional() {
    var failure = new IllegalArgumentException("callback");
    var result = AsyncResult.completed(Result.ok(1)).transformResult(_ -> { throw failure; });
    assertThatThrownBy(() -> result.stage().toCompletableFuture().join()).hasCause(failure);
    var source = new CompletableFuture<Result<Integer, String>>();
    var cancelled = AsyncResult.from(source);
    source.cancel(false);
    assertThat(cancelled.stage().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void rejectsNullArgumentsImmediatelyAndNullOutputsExceptionally() {
    assertThatThrownBy(() -> AsyncResult.from(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AsyncResult.completed(null)).isInstanceOf(NullPointerException.class);
    var result = AsyncResult.completed(Result.<Integer, String>ok(1));
    assertThatThrownBy(() -> result.transformResult(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.flatMap(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.recover(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.fold(null, String::length)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.fold(Object::toString, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AsyncResult.from(CompletableFuture.completedFuture(null))
        .stage().toCompletableFuture().join()).hasCauseInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.transformResult(_ -> null).stage().toCompletableFuture().join())
        .hasCauseInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.flatMap(_ -> null).stage().toCompletableFuture().join())
        .hasCauseInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AsyncResult.completed(Result.error("bad")).recover(_ -> null)
        .stage().toCompletableFuture().join()).hasCauseInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> result.fold(_ -> null, _ -> null).toCompletableFuture().join())
        .hasCauseInstanceOf(NullPointerException.class);
  }
}
