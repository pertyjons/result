package io.github.pertyjons.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** Executable examples using controlled stages: no network, sleeps or executor required. */
class AsyncResultExampleTest {
  record User(int latestOrderId) {}
  record Order(int total) {}
  record CacheError(String message) {}
  record NetworkError(String message) {}

  @Test
  void loadUserThenOrderAndUseTheSynchronousResultApi() {
    var userResponse = new CompletableFuture<Result<User, NetworkError>>();
    var orderResponse = new CompletableFuture<Result<Order, NetworkError>>();
    List<Integer> requestedOrders = new ArrayList<>();
    List<Integer> totals = new ArrayList<>();

    CompletionStage<String> message = AsyncResult.from(userResponse)
        .flatMap(user -> {
          requestedOrders.add(user.latestOrderId());
          return AsyncResult.from(orderResponse);
        })
        .transformResult(result -> result.map(Order::total).onOk(totals::add))
        .fold(total -> "Total: " + total, error -> "Failed: " + error.message());

    assertThat(requestedOrders).isEmpty();
    userResponse.complete(Result.ok(new User(42)));
    assertThat(requestedOrders).containsExactly(42);
    orderResponse.complete(Result.ok(new Order(250)));
    assertThat(message.toCompletableFuture().join()).isEqualTo("Total: 250");
    assertThat(totals).containsExactly(250);
  }

  @Test
  void cacheFailureFallsBackToAnAsynchronousDownload() {
    var download = new CompletableFuture<Result<User, NetworkError>>();
    AsyncResult<User, NetworkError> user =
        AsyncResult.completed(Result.<User, CacheError>error(new CacheError("miss")))
            .recover(_ -> AsyncResult.from(download));

    download.complete(Result.ok(new User(42)));
    assertThat(user.stage().toCompletableFuture().join()).isEqualTo(Result.ok(new User(42)));
  }
}
