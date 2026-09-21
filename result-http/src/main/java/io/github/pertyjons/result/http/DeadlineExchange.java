package io.github.pertyjons.result.http;

import io.github.pertyjons.result.Result;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** One terminal outcome wins; cancellation interrupts the virtual thread waiting on the exchange. */
final class DeadlineExchange<T> {
  private static final ScheduledThreadPoolExecutor TIMER = timer();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final CompletableFuture<Result<T, HttpError>> result;
  private final FutureTask<Void> work;
  private final Runnable finishTiming;
  private volatile @Nullable ScheduledFuture<?> timeout;

  DeadlineExchange(
      Duration deadline, Supplier<Result<T, HttpError>> operation, Runnable finishTiming) {
    this.finishTiming = finishTiming;
    this.result = new CompletableFuture<>() {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        if (!finished.compareAndSet(false, true)) {
          return isCancelled();
        }
        cancelWork();
        finishTiming.run();
        return super.cancel(mayInterruptIfRunning);
      }
    };
    this.work = new FutureTask<>(() -> {
      try {
        complete(operation.get());
      } catch (RuntimeException | Error failure) {
        if (finished.compareAndSet(false, true)) {
          cancelTimer();
          finishTiming.run();
          result.completeExceptionally(failure);
        }
      }
      return null;
    });
    // Completion invokes application callbacks inline. Keep them off the shared timer thread.
    this.timeout = TIMER.schedule(
        () -> Thread.startVirtualThread(this::expire), nanos(deadline), TimeUnit.NANOSECONDS);
    Thread.ofVirtual().name("result-http-exchange").start(work);
  }

  CompletableFuture<Result<T, HttpError>> result() {
    return result;
  }

  void interrupt() {
    abort(Result.error(HttpError.Interrupted.INSTANCE));
  }

  private void expire() {
    abort(Result.error(new HttpError.Timeout(new HttpTimeoutException("Total request deadline exceeded"))));
  }

  private void abort(Result<T, HttpError> outcome) {
    if (finished.compareAndSet(false, true)) {
      cancelWork();
      finishTiming.run();
      result.complete(outcome);
    }
  }

  private void complete(Result<T, HttpError> outcome) {
    if (finished.compareAndSet(false, true)) {
      cancelTimer();
      finishTiming.run();
      result.complete(outcome);
    } else if (outcome instanceof Result.Ok<T, HttpError>(var value)
        && value instanceof HttpResponse<?> response
        && response.body() instanceof AutoCloseable body) {
      // A streaming response can arrive concurrently with timeout/cancellation. It was never
      // handed to the caller, so close it here instead of abandoning the connection.
      try {
        body.close();
      } catch (Exception ignored) {
        // The winning timeout/cancellation must remain the outcome.
      }
    }
  }

  private void cancelWork() {
    cancelTimer();
    work.cancel(true);
  }

  private void cancelTimer() {
    var task = timeout;
    if (task != null) {
      task.cancel(false);
    }
  }

  private static long nanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException _) {
      return Long.MAX_VALUE;
    }
  }

  private static ScheduledThreadPoolExecutor timer() {
    var timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
        .daemon(true).name("result-http-deadline").factory());
    timer.setRemoveOnCancelPolicy(true);
    return timer;
  }
}
