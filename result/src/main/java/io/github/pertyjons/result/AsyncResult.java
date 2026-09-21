package io.github.pertyjons.result;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A non-blocking composition of a {@link CompletionStage} and a {@link Result}.
 * Use {@link #transformResult} for synchronous Result operations and {@link #flatMap} for
 * dependent asynchronous operations. This type is immutable; it does not implement Future.
 *
 * <pre>{@code
 * AsyncResult.from(loadUser(id))
 *     .flatMap(user -> AsyncResult.from(loadOrder(user.latestOrderId())))
 *     .transformResult(result -> result.map(Order::total).onOk(System.out::println))
 *     .fold(total -> "Total: " + total, error -> "Failed: " + error);
 * }</pre>
 *
 * <p>An Err is an expected failure. Exceptional completion is a separate channel: exceptions
 * thrown by callbacks propagate through the stage and are not handled by {@link #recover}.
 * Null results and null callback returns also cause exceptional completion.
 *
 * <p>No executor is selected and no thread is blocked. Callbacks use the underlying stage's
 * non-async continuation methods, so they may run on the completing thread or immediately on the
 * registering thread. Schedule expensive work explicitly in the supplied operations. Cancellation
 * follows the underlying CompletionStage implementation; cancelling a derived future is not
 * guaranteed to cancel upstream work or an HTTP exchange.
 *
 * @param <T> success value type
 * @param <E> expected error type
 */
public final class AsyncResult<T, E> {
  private final CompletionStage<Result<T, E>> stage;

  private AsyncResult(CompletionStage<Result<T, E>> stage) {
    this.stage = stage;
  }

  /**
   * Wrap a stage, rejecting a null Result when it completes. Does not start or reschedule work.
   *
   * @param <T> success value type
   * @param <E> error type
   * @param stage the existing stage, not null
   * @return the asynchronous Result
   */
  public static <T, E> AsyncResult<T, E> from(
      CompletionStage<? extends Result<? extends T, ? extends E>> stage) {
    requireNonNull(stage, "stage must not be null");
    return new AsyncResult<>(
        stage.thenApply(result -> narrow(requireNonNull(result, "stage Result must not be null"))));
  }

  /**
   * Wrap an already available Result.
   *
   * @param <T> success value type
   * @param <E> error type
   * @param result the Result, not null
   * @return a completed asynchronous Result
   */
  public static <T, E> AsyncResult<T, E> completed(Result<? extends T, ? extends E> result) {
    return new AsyncResult<>(
        CompletableFuture.completedFuture(narrow(requireNonNull(result, "result must not be null"))));
  }

  /**
   * Apply synchronous Result operations to either variant, allowing both types to change.
   *
   * @param <U> new success type
   * @param <F> new error type
   * @param transform transformation, must not return null
   * @return the transformed asynchronous Result
   */
  public <U, F> AsyncResult<U, F> transformResult(
      Function<? super Result<T, E>, ? extends Result<? extends U, ? extends F>> transform) {
    requireNonNull(transform, "transform must not be null");
    return from(stage.thenApply(transform));
  }

  /**
   * Run an asynchronous operation on Ok, passing Err through without invoking the operation.
   *
   * @param <U> new success type
   * @param operation the operation, must not return null
   * @return the composed asynchronous Result
   */
  public <U> AsyncResult<U, E> flatMap(
      Function<? super T, ? extends AsyncResult<? extends U, ? extends E>> operation) {
    requireNonNull(operation, "operation must not be null");
    return from(
        stage.thenCompose(result -> result.fold(
            value -> AsyncResult.<U, E>from(
                requireNonNull(operation.apply(value), "flatMap operation must not return null")
                    .stage()).stage(),
            error -> CompletableFuture.completedFuture(Result.<U, E>error(error)))));
  }

  /**
   * Recover an Err asynchronously, possibly changing its error type. Ok passes through and
   * exceptional completion bypasses recovery.
   *
   * @param <F> new error type
   * @param recovery the recovery operation, must not return null
   * @return the recovered asynchronous Result
   */
  public <F> AsyncResult<T, F> recover(
      Function<? super E, ? extends AsyncResult<? extends T, ? extends F>> recovery) {
    requireNonNull(recovery, "recovery must not be null");
    return from(
        stage.thenCompose(result -> result.fold(
            value -> CompletableFuture.completedFuture(Result.<T, F>ok(value)),
            error -> AsyncResult.<T, F>from(
                requireNonNull(recovery.apply(error), "recover operation must not return null")
                    .stage()).stage())));
  }

  /**
   * Handle either variant and produce a stage of a plain value. Exceptional completion bypasses
   * both callbacks. The selected callback must return a non-null value.
   *
   * @param <R> output type
   * @param onOk success handler
   * @param onError error handler
   * @return the stage containing the selected handler's value
   */
  public <R> CompletionStage<R> fold(
      Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onError) {
    requireNonNull(onOk, "onOk must not be null");
    requireNonNull(onError, "onError must not be null");
    return stage.thenApply(
        result -> requireNonNull(result.fold(onOk, onError), "fold handler must not return null"));
  }

  /**
   * Access the stage for standard CompletionStage composition and exceptional-failure handling.
   * No blocking or copying is performed; this is not an upstream cancellation handle.
   *
   * @return the underlying stage
   */
  public CompletionStage<Result<T, E>> stage() {
    return stage;
  }

  // Safe widening: Result exposes immutable variant payload references.
  @SuppressWarnings("unchecked")
  private static <T, E> Result<T, E> narrow(Result<? extends T, ? extends E> result) {
    return (Result<T, E>) result;
  }
}
