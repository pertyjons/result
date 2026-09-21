package io.github.pertyjons.result.http;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.pertyjons.result.Result;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeadlineExchangeTest {
  @Test
  void timeoutInterruptsWorkAndCompletesOnce() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var timing = new AtomicInteger();
    var exchange = new DeadlineExchange<String>(Duration.ofMillis(300), () -> {
      entered.countDown();
      try {
        new CountDownLatch(1).await();
        throw new AssertionError("unreachable");
      } catch (InterruptedException _) {
        interrupted.countDown();
        return Result.error(HttpError.Interrupted.INSTANCE);
      }
    }, timing::incrementAndGet);
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(exchange.result().get(2, TimeUnit.SECONDS)).hasErrorInstanceOf(HttpError.Timeout.class);
    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(timing.get()).isEqualTo(1);
  }

  @Test
  void cancellationInterruptsWorkAndCannotBeOverwrittenByItsResult() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var exchange = new DeadlineExchange<String>(Duration.ofSeconds(5), () -> {
      entered.countDown();
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException _) {
        interrupted.countDown();
      }
      return Result.ok("late success");
    }, () -> {});
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(exchange.result().cancel(true)).isTrue();
    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(exchange.result()).isCancelled();
  }

  @Test
  void slowCompletionCallbackCannotBlockOtherDeadlines() throws Exception {
    var callbackEntered = new CountDownLatch(1);
    var callbackRelease = new CompletableFuture<Void>();
    var workRelease = new CompletableFuture<Void>();
    var first = new DeadlineExchange<String>(Duration.ofMillis(100), () -> {
      workRelease.join();
      return Result.ok("late");
    }, () -> {});
    // Registration may itself run the callback if the deadline has already elapsed.
    // Keep it off the test thread so cleanup always remains reachable.
    var registration = CompletableFuture.runAsync(() -> first.result().thenAccept(_ -> {
      callbackEntered.countDown();
      callbackRelease.join();
    }));
    try {
      assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();
      var second = new DeadlineExchange<String>(Duration.ofMillis(100), () -> {
        workRelease.join();
        return Result.ok("late");
      }, () -> {});
      assertThat(second.result().get(2, TimeUnit.SECONDS)).hasErrorInstanceOf(HttpError.Timeout.class);
    } finally {
      callbackRelease.complete(null);
      workRelease.complete(null);
      registration.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void completedExchangeCannotBeCancelledLater() throws Exception {
    var exchange = new DeadlineExchange<String>(Duration.ofSeconds(1), () -> Result.ok("ok"), () -> {});
    assertThat(exchange.result().get(2, TimeUnit.SECONDS)).hasValue("ok");
    assertThat(exchange.result().cancel(true)).isFalse();
    assertThat(exchange.result().join()).hasValue("ok");
  }
}
