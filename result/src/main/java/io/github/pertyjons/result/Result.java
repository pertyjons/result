package io.github.pertyjons.result;

import static java.util.Objects.requireNonNull;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A Rust-inspired sum type representing either a success value ({@link Ok}) or an error value
 * ({@link Err}). Both variants reject null — use {@link Unit} as the success type for operations
 * that produce no meaningful value.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * Result<String, IoError> result = readFile(path);
 * switch (result) {
 *     case Ok(var content) -> process(content);
 *     case Err(var err)    -> log(err);
 * }
 * }</pre>
 *
 * <p>A {@code Result} never changes variant or payload reference after construction, so the
 * container itself is immutable and safe to share between threads. The payload is stored as-is —
 * neither copied nor frozen — so a mutable value or error (for example an {@code ArrayList})
 * remains mutable through the reference the caller kept, and {@code equals}/{@code hashCode} follow
 * such changes. Immutability and thread-safety of the payload are the caller's responsibility.
 * Functions passed to transformation methods must not return null; doing so throws {@link
 * NullPointerException} at the call site.
 *
 * @param <T> the success value type
 * @param <E> the error value type
 */
public sealed interface Result<T, E> {

  /**
   * Successful outcome carrying a non-null value.
   *
   * @param <T> the success value type
   * @param <E> the error value type (phantom — never held by this variant)
   * @param value the success value, never null
   */
  record Ok<T, E>(T value) implements Result<T, E> {
    /** Validates that {@code value} is non-null. */
    public Ok {
      requireNonNull(value, "Ok value must not be null");
    }
  }

  /**
   * Failed outcome carrying a non-null error description. Named {@code Err} (as in Rust) rather
   * than {@code Error} so that importing it never shadows {@link java.lang.Error}.
   *
   * @param <T> the success value type (phantom — never held by this variant)
   * @param <E> the error value type
   * @param error the error value, never null
   */
  record Err<T, E>(E error) implements Result<T, E> {
    /** Validates that {@code error} is non-null. */
    public Err {
      requireNonNull(error, "Err value must not be null");
    }
  }

  /**
   * A void-equivalent value type for use with Result. Since {@code Result.ok(null)} is rejected at
   * runtime, operations that succeed without producing a value (e.g. writing a file) should return
   * {@code Result<Unit, E>} via {@link #ok()}.
   */
  record Unit() {
    /** The single shared instance. All {@code Unit} values are equal. */
    public static final Unit INSTANCE = new Unit();
  }

  /**
   * Thrown by {@link #orElseThrow()} when the Result is an Err. If the error value is itself a
   * {@link Throwable} it is attached as the {@linkplain #getCause() cause}, so stack traces are
   * preserved. The exception is only serializable if the error value is.
   */
  final class ResultException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** The original error value; serializable only if its runtime type is. */
    @SuppressWarnings("serial")
    private final Object error;

    /**
     * Creates an exception carrying {@code error}. Uses {@code error.toString()} as the message.
     *
     * @param error the error value, must not be null
     */
    public ResultException(Object error) {
      super(requireNonNull(error, "ResultException error must not be null").toString());
      this.error = error;
      if (error instanceof Throwable cause) {
        initCause(cause);
      }
    }

    /**
     * The original error value.
     *
     * @return the error value, never null
     */
    public Object error() {
      return error;
    }
  }

  // --- Internal helpers -----------------------------------------------------------------------
  //
  // Ok<T, E> never holds an E and Err<T, E> never holds a T, so re-typing the phantom parameter is
  // safe and avoids re-allocating the pass-through variant (same technique as Optional.flatMap).

  @SuppressWarnings("unchecked")
  private <U> Result<U, E> retainErr() {
    return (Result<U, E>) this;
  }

  @SuppressWarnings("unchecked")
  private <F> Result<T, F> retainOk() {
    return (Result<T, F>) this;
  }

  @SuppressWarnings("unchecked")
  private static <T, E> Result<T, E> narrow(Result<? extends T, ? extends E> result) {
    return (Result<T, E>) result;
  }

  // --- Transformations --- apply a function to the inner value without unwrapping -------------

  /**
   * Transform the success value, leaving errors untouched.
   *
   * <pre>{@code
   * Result.ok(5).map(n -> n * 2)                          // → Ok(10)
   * Result.<Integer, String>error("fail").map(n -> n * 2) // → Err("fail")
   * }</pre>
   *
   * @param <U> the new success value type
   * @param f the mapping function, must not return null
   * @return the mapped Result
   * @throws NullPointerException if {@code f} returns null
   */
  default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
    return switch (this) {
      case Ok<T, E>(var value) -> new Ok<>(f.apply(value));
      case Err<T, E> _ -> retainErr();
    };
  }

  /**
   * Transform the error value, leaving successes untouched.
   *
   * <pre>{@code
   * Result.<String, Integer>error(404).mapError(e -> "HTTP " + e) // → Err("HTTP 404")
   * Result.ok("hello").mapError(e -> "HTTP " + e)                 // → Ok("hello")
   * }</pre>
   *
   * @param <F> the new error value type
   * @param f the mapping function, must not return null
   * @return the mapped Result
   * @throws NullPointerException if {@code f} returns null
   */
  default <F> Result<T, F> mapError(Function<? super E, ? extends F> f) {
    return switch (this) {
      case Ok<T, E> _ -> retainOk();
      case Err<T, E>(var error) -> new Err<>(f.apply(error));
    };
  }

  /**
   * Transform both variants in a single call.
   *
   * <pre>{@code
   * result.mapBoth(String::length, IoError::message) // Result<Integer, String>
   * }</pre>
   *
   * @param <U> the new success value type
   * @param <F> the new error value type
   * @param onOk applied to the success value, must not return null
   * @param onError applied to the error value, must not return null
   * @return the mapped Result
   * @throws NullPointerException if the applied function returns null
   */
  default <U, F> Result<U, F> mapBoth(
      Function<? super T, ? extends U> onOk, Function<? super E, ? extends F> onError) {
    return switch (this) {
      case Ok<T, E>(var value) -> new Ok<>(onOk.apply(value));
      case Err<T, E>(var error) -> new Err<>(onError.apply(error));
    };
  }

  /**
   * Chain a fallible operation on the success value (monadic bind).
   *
   * <pre>{@code
   * Result.ok(5)
   *     .flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive"))
   *     // → Ok(5)
   *
   * Result.ok(-1)
   *     .flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive"))
   *     // → Err("must be positive")
   * }</pre>
   *
   * @param <U> the new success value type
   * @param f the fallible operation, must not return null
   * @return the Result produced by {@code f}, or this Err
   * @throws NullPointerException if {@code f} returns null
   */
  default <U> Result<U, E> flatMap(
      Function<? super T, ? extends Result<? extends U, ? extends E>> f) {
    return switch (this) {
      case Ok<T, E>(var value) ->
          narrow(requireNonNull(f.apply(value), "flatMap function must not return null"));
      case Err<T, E> _ -> retainErr();
    };
  }

  /**
   * Attempt to recover from an error by producing a new Result. The recovery may use a different
   * error type: the original error is consumed when this is an Err, while an Ok contains no error
   * value and can therefore be safely re-typed.
   *
   * <pre>{@code
   * Result.<String, String>error("fail")
   *     .recover(e -> Result.ok("default"))   // → Ok("default")
   *
   * Result.ok("hello")
   *     .recover(e -> Result.ok("default"))   // → Ok("hello") — untouched
   *
   * Result<User, NetworkError> user =
   *     readCache(id)                         // Result<User, CacheError>
   *         .recover(e -> downloadUser(id));  // Result<User, NetworkError>
   * }</pre>
   *
   * @param <F> the error type after recovery
   * @param f the recovery operation, must not return null
   * @return this Ok re-typed to {@code F}, or the Result produced by {@code f}
   * @throws NullPointerException if {@code f} returns null
   */
  default <F> Result<T, F> recover(
      Function<? super E, ? extends Result<? extends T, ? extends F>> f) {
    return switch (this) {
      case Ok<T, E> _ -> retainOk();
      case Err<T, E>(var error) ->
          narrow(requireNonNull(f.apply(error), "recover function must not return null"));
    };
  }

  /**
   * Convert an Ok to Err if the predicate fails.
   *
   * <pre>{@code
   * Result.ok(10).filter(n -> n > 5, n -> "too small: " + n) // → Ok(10)
   * Result.ok(3).filter(n -> n > 5, n -> "too small: " + n)  // → Err("too small: 3")
   * }</pre>
   *
   * @param predicate the condition the success value must satisfy
   * @param errorFunction produces the error when the predicate fails, must not return null
   * @return this Result if Err or the predicate holds, otherwise a new Err
   * @throws NullPointerException if {@code errorFunction} returns null
   */
  default Result<T, E> filter(
      Predicate<? super T> predicate, Function<? super T, ? extends E> errorFunction) {
    return switch (this) {
      case Ok<T, E>(var value) ->
          predicate.test(value) ? this : new Err<>(errorFunction.apply(value));
      case Err<T, E> _ -> this;
    };
  }

  /**
   * Exchange the variants: Ok becomes Err and vice versa.
   *
   * <pre>{@code
   * Result.<Integer, String>ok(1).swap()      // → Err(1)
   * Result.<Integer, String>error("x").swap() // → Ok("x")
   * }</pre>
   *
   * @return the swapped Result
   */
  default Result<E, T> swap() {
    return switch (this) {
      case Ok<T, E>(var value) -> new Err<>(value);
      case Err<T, E>(var error) -> new Ok<>(error);
    };
  }

  // --- Side effects --- run a callback without altering the Result ----------------------------

  /**
   * Execute a consumer on the success value (for logging, metrics, etc.).
   *
   * <pre>{@code
   * Result.ok("saved").onOk(msg -> log.info(msg))   // logs "saved", returns Ok("saved")
   * Result.error("fail").onOk(msg -> log.info(msg)) // no-op, returns Err("fail")
   * }</pre>
   *
   * @param consumer invoked with the success value
   * @return this Result
   */
  default Result<T, E> onOk(Consumer<? super T> consumer) {
    if (this instanceof Ok<T, E>(var value)) {
      consumer.accept(value);
    }
    return this;
  }

  /**
   * Execute a consumer on the error value (for logging, metrics, etc.).
   *
   * <pre>{@code
   * Result.error("fail").onError(e -> log.warn(e)) // logs "fail", returns Err("fail")
   * Result.ok("hello").onError(e -> log.warn(e))   // no-op, returns Ok("hello")
   * }</pre>
   *
   * @param consumer invoked with the error value
   * @return this Result
   */
  default Result<T, E> onError(Consumer<? super E> consumer) {
    if (this instanceof Err<T, E>(var error)) {
      consumer.accept(error);
    }
    return this;
  }

  /**
   * Run exactly one of two side effects depending on the variant. The void counterpart of {@link
   * #fold}; analogous to {@link Optional#ifPresentOrElse}.
   *
   * <pre>{@code
   * result.match(
   *     value -> response.ok(value),
   *     error -> response.fail(error));
   * }</pre>
   *
   * @param onOk invoked with the success value
   * @param onError invoked with the error value
   */
  default void match(Consumer<? super T> onOk, Consumer<? super E> onError) {
    switch (this) {
      case Ok<T, E>(var value) -> onOk.accept(value);
      case Err<T, E>(var error) -> onError.accept(error);
    }
  }

  // --- Unwrapping --- extract the inner value, escaping the Result wrapper --------------------

  /**
   * Collapse both variants into a single value by applying the appropriate function.
   *
   * <pre>{@code
   * Result.ok("hello").fold(String::length, e -> -1)   // → 5
   * Result.error("fail").fold(String::length, e -> -1) // → -1
   *
   * String message = result.fold(
   *     value -> "Got: " + value,
   *     error -> "Failed: " + error);
   * }</pre>
   *
   * @param <R> the unified result type
   * @param onOk applied to the success value
   * @param onError applied to the error value
   * @return the value produced by whichever function ran
   */
  default <R> R fold(
      Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onError) {
    return switch (this) {
      case Ok<T, E>(var value) -> onOk.apply(value);
      case Err<T, E>(var error) -> onError.apply(error);
    };
  }

  /**
   * Return the success value or throw a {@link ResultException} containing the error. If the error
   * is a {@link Throwable} it becomes the cause of the thrown exception.
   *
   * <pre>{@code
   * Result.ok("hello").orElseThrow()  // → "hello"
   * Result.error("bad").orElseThrow() // throws ResultException("bad")
   * }</pre>
   *
   * @return the success value
   * @throws ResultException if this is an Err
   */
  default T orElseThrow() {
    return switch (this) {
      case Ok<T, E>(var value) -> value;
      case Err<T, E>(var error) -> throw new ResultException(error);
    };
  }

  /**
   * Return the success value or throw an exception derived from the error. Checked exceptions are
   * supported: the method declares {@code throws X}, exactly like {@link Optional#orElseThrow}.
   *
   * <pre>{@code
   * Result.error("bad").orElseThrow(e -> new IOException("Got: " + e))
   *     // throws IOException("Got: bad") — caller must handle or declare it
   * }</pre>
   *
   * @param <X> the exception type
   * @param errorToException builds the exception from the error value, must not return null
   * @return the success value
   * @throws X if this is an Err
   * @throws NullPointerException if {@code errorToException} returns null
   */
  default <X extends Throwable> T orElseThrow(Function<? super E, ? extends X> errorToException)
      throws X {
    return switch (this) {
      case Ok<T, E>(var value) -> value;
      case Err<T, E>(var error) ->
          throw requireNonNull(
              errorToException.apply(error), "orElseThrow function must not return null");
    };
  }

  /**
   * Return the success value or a provided default.
   *
   * <pre>{@code
   * Result.ok("hello").orElse("fallback")   // → "hello"
   * Result.error("fail").orElse("fallback") // → "fallback"
   * }</pre>
   *
   * @param defaultValue returned when this is an Err
   * @return the success value or {@code defaultValue}
   */
  default T orElse(T defaultValue) {
    return switch (this) {
      case Ok<T, E>(var value) -> value;
      case Err<T, E> _ -> defaultValue;
    };
  }

  /**
   * Return the success value or compute a fallback from the error.
   *
   * <pre>{@code
   * Result.error("fail").orElseGet(e -> "recovered from: " + e)
   *     // → "recovered from: fail"
   * }</pre>
   *
   * @param fallback computes the fallback from the error value
   * @return the success value or the computed fallback
   */
  default T orElseGet(Function<? super E, ? extends T> fallback) {
    return switch (this) {
      case Ok<T, E>(var value) -> value;
      case Err<T, E>(var error) -> fallback.apply(error);
    };
  }

  /**
   * Convert to Optional, discarding any error information.
   *
   * <pre>{@code
   * Result.ok("hello").toOptional()  // → Optional.of("hello")
   * Result.error("fail").toOptional() // → Optional.empty()
   * }</pre>
   *
   * @return the success value as an Optional
   */
  default Optional<T> toOptional() {
    return switch (this) {
      case Ok<T, E>(var value) -> Optional.of(value);
      case Err<T, E> _ -> Optional.empty();
    };
  }

  /**
   * Convert the error to Optional, discarding any success value. Analogous to Rust's {@code
   * Result::err}.
   *
   * <pre>{@code
   * Result.error("fail").toOptionalError() // → Optional.of("fail")
   * Result.ok("hello").toOptionalError()   // → Optional.empty()
   * }</pre>
   *
   * @return the error value as an Optional
   */
  default Optional<E> toOptionalError() {
    return switch (this) {
      case Ok<T, E> _ -> Optional.empty();
      case Err<T, E>(var error) -> Optional.of(error);
    };
  }

  /**
   * A stream of zero or one elements: the success value if Ok, empty if Err. Enables {@code
   * results.stream().flatMap(Result::stream)} to keep only successes, like {@link Optional#stream}.
   *
   * @return a stream containing the success value, or an empty stream
   */
  default Stream<T> stream() {
    return switch (this) {
      case Ok<T, E>(var value) -> Stream.of(value);
      case Err<T, E> _ -> Stream.empty();
    };
  }

  // --- Queries --- check the outcome without unwrapping ---------------------------------------

  /**
   * Check if this is a successful outcome.
   *
   * @return {@code true} if this is an {@link Ok}
   */
  default boolean isOk() {
    return this instanceof Ok;
  }

  /**
   * Check if this is a failed outcome.
   *
   * @return {@code true} if this is an {@link Err}
   */
  default boolean isError() {
    return this instanceof Err;
  }

  // --- Factory methods --- preferred entry points for creating Results ------------------------

  /**
   * Create a successful Result.
   *
   * <pre>{@code
   * Result<String, String> result = Result.ok("hello"); // → Ok("hello")
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param value the success value, must not be null
   * @return an Ok
   * @throws NullPointerException if {@code value} is null
   */
  static <T, E> Result<T, E> ok(T value) {
    return new Ok<>(value);
  }

  /**
   * Create a successful Result carrying {@link Unit}, for operations with no meaningful value.
   *
   * <pre>{@code
   * Result<Unit, IoError> write(Path p) {
   *   ...
   *   return Result.ok();
   * }
   * }</pre>
   *
   * @param <E> the error value type
   * @return {@code Ok(Unit.INSTANCE)}
   */
  static <E> Result<Unit, E> ok() {
    return new Ok<>(Unit.INSTANCE);
  }

  /**
   * Variant of {@link #ok(Object)} that takes a witness for the error type, so that {@code var} and
   * generic-method arguments infer {@code E} without the {@code Result.<T, E>ok(...)} syntax. A
   * {@link Class} witness only works for non-generic error types — for {@code List<String>} and the
   * like, use the explicit form. Mirrored by {@link #error(Object, Class)}.
   *
   * <pre>{@code
   * var result = Result.ok("hello", IoError.class); // Result<String, IoError>
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param value the success value, must not be null
   * @param errorClass witness for {@code E}, must not be null
   * @return an Ok
   * @throws NullPointerException if either argument is null
   */
  static <T, E> Result<T, E> ok(T value, Class<E> errorClass) {
    requireNonNull(errorClass, "errorClass must not be null");
    return new Ok<>(value);
  }

  /**
   * Create a failed Result.
   *
   * <pre>{@code
   * Result<String, String> result = Result.error("oops"); // → Err("oops")
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param error the error value, must not be null
   * @return an Err
   * @throws NullPointerException if {@code error} is null
   */
  static <T, E> Result<T, E> error(E error) {
    return new Err<>(error);
  }

  /**
   * Variant of {@link #error(Object)} that takes a witness for the success type, so that {@code
   * var} and generic-method arguments infer {@code T} without the {@code Result.<T, E>error(...)}
   * syntax. A {@link Class} witness only works for non-generic success types — for {@code
   * List<String>} and the like, use the explicit form. Mirrors {@link #ok(Object, Class)}.
   *
   * <pre>{@code
   * var result = Result.error(new IoError("disk full"), String.class); // Result<String, IoError>
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param error the error value, must not be null
   * @param valueClass witness for {@code T}, must not be null
   * @return an Err
   * @throws NullPointerException if either argument is null
   */
  static <T, E> Result<T, E> error(E error, Class<T> valueClass) {
    requireNonNull(valueClass, "valueClass must not be null");
    return new Err<>(error);
  }

  /**
   * Return {@code Ok(Unit)} if the condition holds, otherwise {@code Err(error)}. Similar to Rust's
   * {@code ensure!} macro (anyhow) and Scala's {@code Either.cond}.
   *
   * <pre>{@code
   * Result.ensure(age >= 18, "Must be 18 or older") // → Ok(Unit) or Err("Must be 18 or older")
   *     .flatMap(_ -> createAccount(name));
   * }</pre>
   *
   * @param <E> the error value type
   * @param condition the condition to check
   * @param error the error value used when the condition is false, must not be null
   * @return {@code Ok(Unit)} or {@code Err(error)}
   * @throws NullPointerException if {@code error} is null
   */
  static <E> Result<Unit, E> ensure(boolean condition, E error) {
    requireNonNull(error, "ensure error must not be null");
    return condition ? ok() : error(error);
  }

  /**
   * Lazy variant of {@link #ensure(boolean, Object)} — avoids constructing the error object in the
   * happy path.
   *
   * <pre>{@code
   * Result.ensure(file.exists(), () -> new IoError("Not found: " + path))
   *     .flatMap(_ -> readFile(path));
   * }</pre>
   *
   * @param <E> the error value type
   * @param condition the condition to check
   * @param errorSupplier invoked only when the condition is false, must not return null
   * @return {@code Ok(Unit)} or {@code Err(errorSupplier.get())}
   * @throws NullPointerException if {@code errorSupplier} returns null
   */
  static <E> Result<Unit, E> ensure(boolean condition, Supplier<? extends E> errorSupplier) {
    return condition ? ok() : error(errorSupplier.get());
  }

  /**
   * Variant of {@link #ensure(boolean, Supplier)} that takes a witness for the error type. Without
   * it, a supplier that builds one case of a sealed error hierarchy makes {@code E} that case rather
   * than the hierarchy, which breaks the rest of a chain. The compiler checks that the supplier
   * produces an {@code E}. Like {@link #ok(Object, Class)}, this only works for non-generic error
   * types.
   *
   * <pre>{@code
   * Result.ensure(age >= 18, () -> new ValidationError.TooYoung(age), ValidationError.class)
   *     .flatMap(_ -> register(name)) // Result<Unit, ValidationError>, not ...<Unit, TooYoung>
   * }</pre>
   *
   * @param <E> the error value type
   * @param condition the condition to check
   * @param errorSupplier invoked only when the condition is false, must not return null
   * @param errorClass witness for {@code E}, must not be null
   * @return {@code Ok(Unit)} or {@code Err(errorSupplier.get())}
   * @throws NullPointerException if {@code errorClass} is null or {@code errorSupplier} returns null
   */
  static <E> Result<Unit, E> ensure(
      boolean condition, Supplier<? extends E> errorSupplier, Class<E> errorClass) {
    requireNonNull(errorClass, "errorClass must not be null");
    return ensure(condition, errorSupplier);
  }

  /**
   * Lift a possibly-null value into a Result: {@code Ok(value)} if non-null, otherwise {@code
   * Err(error)}. The natural bridge from null-returning APIs such as {@code Map.get}.
   *
   * <pre>{@code
   * Result.ofNullable(map.get(key), "missing " + key)
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param value the possibly-null value
   * @param error the error value used when {@code value} is null, must not be null
   * @return {@code Ok(value)} or {@code Err(error)}
   * @throws NullPointerException if {@code error} is null
   */
  static <T, E> Result<T, E> ofNullable(@Nullable T value, E error) {
    requireNonNull(error, "ofNullable error must not be null");
    return value != null ? ok(value) : error(error);
  }

  /**
   * Lazy variant of {@link #ofNullable(Object, Object)} — the error is only built when needed.
   *
   * <pre>{@code
   * Result.ofNullable(System.getenv(name), () -> new ConfigError("unset: " + name))
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param value the possibly-null value
   * @param errorSupplier invoked only when {@code value} is null, must not return null
   * @return {@code Ok(value)} or {@code Err(errorSupplier.get())}
   * @throws NullPointerException if {@code errorSupplier} returns null
   */
  static <T, E> Result<T, E> ofNullable(@Nullable T value, Supplier<? extends E> errorSupplier) {
    return value != null ? ok(value) : error(errorSupplier.get());
  }

  /**
   * Variant of {@link #ofNullable(Object, Supplier)} that takes a witness for the error type, so a
   * chain that starts here gets the error type {@code E} instead of the one case the supplier
   * happens to build. The compiler checks that the supplier produces an {@code E}. Like {@link
   * #ok(Object, Class)}, this only works for non-generic error types.
   *
   * <pre>{@code
   * Result.ofNullable(env.get("PORT"), () -> new ParseError.Missing("PORT"), ParseError.class)
   *     .flatMap(raw -> parsePort(raw)) // may fail with any ParseError, not only Missing
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param value the possibly-null value
   * @param errorSupplier invoked only when {@code value} is null, must not return null
   * @param errorClass witness for {@code E}, must not be null
   * @return {@code Ok(value)} or {@code Err(errorSupplier.get())}
   * @throws NullPointerException if {@code errorClass} is null or {@code errorSupplier} returns null
   */
  static <T, E> Result<T, E> ofNullable(
      @Nullable T value, Supplier<? extends E> errorSupplier, Class<E> errorClass) {
    requireNonNull(errorClass, "errorClass must not be null");
    return ofNullable(value, errorSupplier);
  }

  /**
   * Convert an Optional into a Result, supplying the error for the empty case.
   *
   * <pre>{@code
   * Result.ofOptional(repo.findById(id), () -> new NotFound(id))
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param optional the optional to convert
   * @param errorSupplier invoked only when {@code optional} is empty, must not return null
   * @return {@code Ok(optional.get())} or {@code Err(errorSupplier.get())}
   * @throws NullPointerException if {@code errorSupplier} returns null
   */
  static <T, E> Result<T, E> ofOptional(
      Optional<? extends T> optional, Supplier<? extends E> errorSupplier) {
    return optional.<Result<T, E>>map(Result::ok).orElseGet(() -> error(errorSupplier.get()));
  }

  /**
   * Variant of {@link #ofOptional(Optional, Supplier)} that takes a witness for the error type, so
   * a chain that starts here gets the error type {@code E} instead of the one case the supplier
   * happens to build. The compiler checks that the supplier produces an {@code E}. Like {@link
   * #ok(Object, Class)}, this only works for non-generic error types.
   *
   * <pre>{@code
   * Result.ofOptional(repo.findById(id), () -> new ApiError.NotFound(id), ApiError.class)
   *     .flatMap(this::authorize) // Result<User, ApiError>
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param optional the optional to convert
   * @param errorSupplier invoked only when {@code optional} is empty, must not return null
   * @param errorClass witness for {@code E}, must not be null
   * @return {@code Ok(optional.get())} or {@code Err(errorSupplier.get())}
   * @throws NullPointerException if {@code errorClass} is null or {@code errorSupplier} returns null
   */
  static <T, E> Result<T, E> ofOptional(
      Optional<? extends T> optional, Supplier<? extends E> errorSupplier, Class<E> errorClass) {
    requireNonNull(errorClass, "errorClass must not be null");
    return ofOptional(optional, errorSupplier);
  }

  /**
   * Wrap a throwing operation, capturing any thrown {@link Exception} as the error. Errors ({@link
   * java.lang.Error}) are not caught. If the callable throws {@link InterruptedException} the
   * thread's interrupt flag is restored before returning.
   *
   * <pre>{@code
   * Result.ofCallable(() -> Integer.parseInt("42"))   // → Ok(42)
   * Result.ofCallable(() -> Integer.parseInt("nope")) // → Err(NumberFormatException)
   * }</pre>
   *
   * @param <T> the success value type
   * @param callable the operation to run, must not return null
   * @return {@code Ok(result)} or {@code Err(exception)}
   * @throws NullPointerException if {@code callable} returns null
   */
  static <T> Result<T, Exception> ofCallable(Callable<? extends T> callable) {
    return ofCallable(callable, Function.identity());
  }

  /**
   * Wrap a throwing operation, mapping any thrown {@link Exception} to a custom error type. Errors
   * ({@link java.lang.Error}) are not caught. If the callable throws {@link InterruptedException}
   * the thread's interrupt flag is restored before the mapper runs.
   *
   * <pre>{@code
   * Result.ofCallable(() -> readFile(path), e -> new IoError(e.getMessage()))
   *     // → Ok(content) or Err(IoError(...))
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param callable the operation to run, must not return null
   * @param errorMapper converts the caught exception to an error value, must not return null
   * @return {@code Ok(result)} or {@code Err(errorMapper.apply(exception))}
   * @throws NullPointerException if {@code callable} or {@code errorMapper} returns null
   */
  static <T, E> Result<T, E> ofCallable(
      Callable<? extends T> callable, Function<? super Exception, ? extends E> errorMapper) {
    T value;
    try {
      value = callable.call();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Err<>(errorMapper.apply(e));
    } catch (Exception e) {
      return new Err<>(errorMapper.apply(e));
    }
    return new Ok<>(value);
  }

  /**
   * Variant of {@link #ofCallable(Callable, Function)} that takes a witness for the error type, so
   * a chain that starts here gets the error type {@code E} instead of the one case the mapper
   * happens to build. The compiler checks that the mapper produces an {@code E}. Like {@link
   * #ok(Object, Class)}, this only works for non-generic error types.
   *
   * <pre>{@code
   * Result.ofCallable(
   *         () -> Integer.parseInt(raw), _ -> new ParseError.NotANumber(raw), ParseError.class)
   *     .filter(p -> p > 0, p -> new ParseError.OutOfRange(p)) // another ParseError case
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param callable the operation to run, must not return null
   * @param errorMapper converts the caught exception to an error value, must not return null
   * @param errorClass witness for {@code E}, must not be null
   * @return {@code Ok(result)} or {@code Err(errorMapper.apply(exception))}
   * @throws NullPointerException if {@code errorClass} is null, or {@code callable} or {@code
   *     errorMapper} returns null
   */
  static <T, E> Result<T, E> ofCallable(
      Callable<? extends T> callable,
      Function<? super Exception, ? extends E> errorMapper,
      Class<E> errorClass) {
    requireNonNull(errorClass, "errorClass must not be null");
    return ofCallable(callable, errorMapper);
  }

  // --- Combining --- merge multiple Results into one ------------------------------------------

  /**
   * A three-argument function, used by {@link #map3}.
   *
   * @param <A> first argument type
   * @param <B> second argument type
   * @param <C> third argument type
   * @param <R> result type
   */
  @FunctionalInterface
  interface TriFunction<A, B, C, R> {
    /**
     * Applies this function.
     *
     * @param a first argument
     * @param b second argument
     * @param c third argument
     * @return the function result
     */
    R apply(A a, B b, C c);
  }

  /**
   * Combine two Results using a mapping function. Short-circuits on the first error.
   *
   * <pre>{@code
   * Result.map2(Result.ok(1), Result.ok(2), Integer::sum)         // → Ok(3)
   * Result.map2(Result.ok(1), Result.error("fail"), Integer::sum) // → Err("fail")
   * }</pre>
   *
   * @param <A> first success value type
   * @param <B> second success value type
   * @param <R> combined value type
   * @param <E> the error value type
   * @param ra first Result
   * @param rb second Result
   * @param f combines the two success values, must not return null
   * @return {@code Ok(f(a, b))} or the first Err
   * @throws NullPointerException if {@code f} returns null
   */
  static <A, B, R, E> Result<R, E> map2(
      Result<? extends A, ? extends E> ra,
      Result<? extends B, ? extends E> rb,
      BiFunction<? super A, ? super B, ? extends R> f) {
    return Result.<A, E>narrow(ra).flatMap(a -> Result.<B, E>narrow(rb).map(b -> f.apply(a, b)));
  }

  /**
   * Combine three Results using a mapping function. Short-circuits on the first error.
   *
   * <pre>{@code
   * Result.map3(Result.ok("a"), Result.ok("b"), Result.ok("c"),
   *         (a, b, c) -> a + b + c) // → Ok("abc")
   * }</pre>
   *
   * @param <A> first success value type
   * @param <B> second success value type
   * @param <C> third success value type
   * @param <R> combined value type
   * @param <E> the error value type
   * @param ra first Result
   * @param rb second Result
   * @param rc third Result
   * @param f combines the three success values, must not return null
   * @return {@code Ok(f(a, b, c))} or the first Err
   * @throws NullPointerException if {@code f} returns null
   */
  static <A, B, C, R, E> Result<R, E> map3(
      Result<? extends A, ? extends E> ra,
      Result<? extends B, ? extends E> rb,
      Result<? extends C, ? extends E> rc,
      TriFunction<? super A, ? super B, ? super C, ? extends R> f) {
    return Result.<A, E>narrow(ra)
        .flatMap(
            a ->
                Result.<B, E>narrow(rb)
                    .flatMap(b -> Result.<C, E>narrow(rc).map(c -> f.apply(a, b, c))));
  }

  /**
   * Combine two Results using a mapping function, accumulating every error in argument order. The
   * mapping function runs only when both Results are Ok.
   *
   * <pre>{@code
   * Result.map2All(Result.ok(1), Result.ok(2), Integer::sum) // → Ok(3)
   * Result.map2All(Result.error("a"), Result.error("b"), Integer::sum)
   *     // → Err(["a", "b"])
   * }</pre>
   *
   * @param <A> first success value type
   * @param <B> second success value type
   * @param <R> combined value type
   * @param <E> the error value type
   * @param ra first Result
   * @param rb second Result
   * @param f combines the two success values, must not return null
   * @return {@code Ok(f(a, b))} when both succeed, otherwise every error as an immutable list
   * @throws NullPointerException if {@code f} returns null
   */
  static <A, B, R, E> Result<R, List<E>> map2All(
      Result<? extends A, ? extends E> ra,
      Result<? extends B, ? extends E> rb,
      BiFunction<? super A, ? super B, ? extends R> f) {
    Result<A, E> a = narrow(ra);
    Result<B, E> b = narrow(rb);
    if (a instanceof Ok<A, E>(var valueA) && b instanceof Ok<B, E>(var valueB)) {
      return ok(f.apply(valueA, valueB));
    }
    var errors = new ArrayList<E>(2);
    if (a instanceof Err<A, E>(var error)) {
      errors.add(error);
    }
    if (b instanceof Err<B, E>(var error)) {
      errors.add(error);
    }
    return error(List.copyOf(errors));
  }

  /**
   * Combine three Results using a mapping function, accumulating every error in argument order.
   * The mapping function runs only when all three Results are Ok. This is useful for constructing
   * one value from independently validated fields with different success types.
   *
   * <pre>{@code
   * Result.map3All(validName, validAge, validEmail, User::new)
   *     // → Ok(User) or Err(List<ValidationError>)
   * }</pre>
   *
   * @param <A> first success value type
   * @param <B> second success value type
   * @param <C> third success value type
   * @param <R> combined value type
   * @param <E> the error value type
   * @param ra first Result
   * @param rb second Result
   * @param rc third Result
   * @param f combines the three success values, must not return null
   * @return {@code Ok(f(a, b, c))} when all succeed, otherwise every error as an immutable list
   * @throws NullPointerException if {@code f} returns null
   */
  static <A, B, C, R, E> Result<R, List<E>> map3All(
      Result<? extends A, ? extends E> ra,
      Result<? extends B, ? extends E> rb,
      Result<? extends C, ? extends E> rc,
      TriFunction<? super A, ? super B, ? super C, ? extends R> f) {
    Result<A, E> a = narrow(ra);
    Result<B, E> b = narrow(rb);
    Result<C, E> c = narrow(rc);
    if (a instanceof Ok<A, E>(var valueA)
        && b instanceof Ok<B, E>(var valueB)
        && c instanceof Ok<C, E>(var valueC)) {
      return ok(f.apply(valueA, valueB, valueC));
    }
    var errors = new ArrayList<E>(3);
    if (a instanceof Err<A, E>(var error)) {
      errors.add(error);
    }
    if (b instanceof Err<B, E>(var error)) {
      errors.add(error);
    }
    if (c instanceof Err<C, E>(var error)) {
      errors.add(error);
    }
    return error(List.copyOf(errors));
  }

  // --- Collectors --- stream terminal operations ----------------------------------------------

  /**
   * A {@link Collector} equivalent of {@link #sequence}: collects a stream of Results into {@code
   * Ok} of an immutable list, or the first {@code Err} in encounter order. Elements after the first
   * error are ignored (the stream itself still runs to completion — use {@link #traverse} on a
   * collection to stop calling the mapping function early).
   *
   * <pre>{@code
   * lines.stream().map(this::parse).collect(Result.toResult()) // Result<List<Row>, ParseError>
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @return a collector producing {@code Ok(List<T>)} or the first {@code Err(E)}
   */
  static <T, E> Collector<Result<T, E>, ?, Result<List<T>, E>> toResult() {
    return ResultCollectors.toResult();
  }

  /**
   * A {@link Collector} equivalent of {@link #sequenceAll}: collects a stream of Results into
   * {@code Ok} of every value if all are Ok, otherwise {@code Err} of every error in encounter
   * order.
   *
   * <pre>{@code
   * fields.stream().map(this::validate).collect(Result.toResultAll())
   *     // Result<List<Field>, List<ValidationError>>
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @return a collector producing {@code Ok(List<T>)} or {@code Err(List<E>)}
   */
  static <T, E> Collector<Result<T, E>, ?, Result<List<T>, List<E>>> toResultAll() {
    return ResultCollectors.toResultAll();
  }

  /**
   * A {@link Collector} equivalent of {@link #partition}: separates a stream of Results into ok
   * values and errors, both in encounter order.
   *
   * <pre>{@code
   * var p = jobs.stream().map(this::run).collect(Result.toPartitioned());
   * log.info("{} succeeded, {} failed", p.values().size(), p.errors().size());
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @return a collector producing a {@link Partitioned}
   */
  static <T, E> Collector<Result<T, E>, ?, Partitioned<T, E>> toPartitioned() {
    return ResultCollectors.toPartitioned();
  }

  // --- Collection operations --- combine multiple Results -------------------------------------

  /**
   * Collect Results into a Result of list. Short-circuits on the first error. Analogous to Rust's
   * {@code Iterator::collect::<Result<Vec<T>, E>>()}.
   *
   * <pre>{@code
   * var results = List.of(Result.ok(1), Result.ok(2), Result.ok(3));
   * Result.sequence(results) // → Ok([1, 2, 3])
   *
   * var mixed = List.of(Result.ok(1), Result.error("boom"), Result.ok(3));
   * Result.sequence(mixed) // → Err("boom")
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param results the Results to collect, iterated in encounter order
   * @return {@code Ok} of an immutable list of every value, or the first Err
   */
  static <T, E> Result<List<T>, E> sequence(
      Collection<? extends Result<? extends T, ? extends E>> results) {
    var values = new ArrayList<T>(results.size());
    for (var result : results) {
      switch (result) {
        case Ok(var value) -> values.add(value);
        case Err(var error) -> {
          return error(error);
        }
      }
    }
    return ok(List.copyOf(values));
  }

  /**
   * Map each element through a fallible function and collect into a single Result. Short-circuits
   * on the first error. Equivalent to {@code sequence(items.stream().map(f).toList())} but stops
   * calling {@code f} after the first Err. Analogous to Haskell's {@code traverse}.
   *
   * <pre>{@code
   * Result.traverse(List.of("1", "2", "3"), s -> Result.ofCallable(() -> Integer.parseInt(s)))
   *     // → Ok([1, 2, 3])
   *
   * Result.traverse(List.of("1", "nope", "3"), s -> Result.ofCallable(() -> Integer.parseInt(s)))
   *     // → Err(NumberFormatException)
   * }</pre>
   *
   * @param <T> the element type
   * @param <U> the success value type produced by {@code f}
   * @param <E> the error value type
   * @param items the elements to map, iterated in encounter order
   * @param f the fallible mapping, must not return null
   * @return {@code Ok} of an immutable list of every mapped value, or the first Err
   * @throws NullPointerException if {@code f} returns null
   */
  static <T, U, E> Result<List<U>, E> traverse(
      Collection<? extends T> items,
      Function<? super T, ? extends Result<? extends U, ? extends E>> f) {
    var values = new ArrayList<U>(items.size());
    for (var item : items) {
      switch (requireNonNull(f.apply(item), "traverse function must not return null")) {
        case Ok(var value) -> values.add(value);
        case Err(var error) -> {
          return error(error);
        }
      }
    }
    return ok(List.copyOf(values));
  }

  /**
   * Map each element (with its index) through a fallible function and collect into a single Result.
   * Short-circuits on the first error. Useful when error messages need to include the position.
   *
   * <pre>{@code
   * Result.traverseIndexed(nodes, (i, node) -> parseNode(node)
   *     .mapError(e -> "Step %d: %s".formatted(i + 1, e)))
   *     // → Ok([parsed1, parsed2, ...]) or Err("Step 3: invalid format")
   * }</pre>
   *
   * @param <T> the element type
   * @param <U> the success value type produced by {@code f}
   * @param <E> the error value type
   * @param items the elements to map
   * @param f the fallible mapping receiving the zero-based index, must not return null
   * @return {@code Ok} of an immutable list of every mapped value, or the first Err
   * @throws NullPointerException if {@code f} returns null
   */
  static <T, U, E> Result<List<U>, E> traverseIndexed(
      List<? extends T> items,
      BiFunction<Integer, ? super T, ? extends Result<? extends U, ? extends E>> f) {
    var values = new ArrayList<U>(items.size());
    int index = 0;
    for (var item : items) {
      switch (requireNonNull(
          f.apply(index++, item), "traverseIndexed function must not return null")) {
        case Ok(var value) -> values.add(value);
        case Err(var error) -> {
          return error(error);
        }
      }
    }
    return ok(List.copyOf(values));
  }

  /**
   * The result of {@link #partition}: ok values and errors separated into two immutable lists. The
   * canonical constructor copies its arguments, so the lists are immutable however constructed.
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param values every success value, in encounter order
   * @param errors every error value, in encounter order
   */
  record Partitioned<T, E>(List<T> values, List<E> errors) {
    /** Copies both lists into immutable, null-free lists. */
    public Partitioned {
      values = List.copyOf(requireNonNull(values, "Partitioned values must not be null"));
      errors = List.copyOf(requireNonNull(errors, "Partitioned errors must not be null"));
    }

    /**
     * Whether there are no errors.
     *
     * @return {@code true} if {@code errors} is empty
     */
    public boolean allOk() {
      return errors.isEmpty();
    }

    /**
     * Whether there are no successes.
     *
     * @return {@code true} if {@code values} is empty
     */
    public boolean allErrors() {
      return values.isEmpty();
    }
  }

  /**
   * Separate Results into ok values and errors. Unlike {@link #sequence}, this processes every
   * element — nothing is short-circuited.
   *
   * <pre>{@code
   * var results = List.of(Result.ok(1), Result.error("a"), Result.ok(2), Result.error("b"));
   * var p = Result.partition(results);
   * // p.values()  → [1, 2]
   * // p.errors()  → ["a", "b"]
   * // p.allOk()   → false
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param results the Results to partition, iterated in encounter order
   * @return the separated values and errors
   */
  static <T, E> Partitioned<T, E> partition(
      Collection<? extends Result<? extends T, ? extends E>> results) {
    var values = new ArrayList<T>();
    var errors = new ArrayList<E>();
    for (var result : results) {
      switch (result) {
        case Ok(var value) -> values.add(value);
        case Err(var error) -> errors.add(error);
      }
    }
    return new Partitioned<>(values, errors);
  }

  /**
   * Collect Results into a Result of list, accumulating <em>all</em> errors. Unlike {@link
   * #sequence} which short-circuits on the first error, this processes every element.
   *
   * <pre>{@code
   * var results = List.of(Result.ok(1), Result.ok(2), Result.ok(3));
   * Result.sequenceAll(results) // → Ok([1, 2, 3])
   *
   * var mixed = List.of(Result.ok(1), Result.error("a"), Result.ok(3), Result.error("b"));
   * Result.sequenceAll(mixed) // → Err(["a", "b"])
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param results the Results to collect, iterated in encounter order
   * @return {@code Ok} of every value if all are Ok, otherwise {@code Err} of every error
   */
  static <T, E> Result<List<T>, List<E>> sequenceAll(
      Collection<? extends Result<? extends T, ? extends E>> results) {
    var partitioned = Result.<T, E>partition(results);
    return partitioned.allOk() ? ok(partitioned.values()) : error(partitioned.errors());
  }

  /**
   * Map each element through a fallible function and collect into a single Result, accumulating
   * <em>all</em> errors. Unlike {@link #traverse} which short-circuits on the first error, this
   * calls {@code f} for every element.
   *
   * <pre>{@code
   * Result.traverseAll(List.of("1", "nope", "3", "bad"),
   *     s -> Result.ofCallable(() -> Integer.parseInt(s)))
   *     // → Err([NumberFormatException, NumberFormatException])
   * }</pre>
   *
   * @param <T> the element type
   * @param <U> the success value type produced by {@code f}
   * @param <E> the error value type
   * @param items the elements to map, iterated in encounter order
   * @param f the fallible mapping, must not return null
   * @return {@code Ok} of every mapped value if all are Ok, otherwise {@code Err} of every error
   * @throws NullPointerException if {@code f} returns null
   */
  static <T, U, E> Result<List<U>, List<E>> traverseAll(
      Collection<? extends T> items,
      Function<? super T, ? extends Result<? extends U, ? extends E>> f) {
    var values = new ArrayList<U>(items.size());
    var errors = new ArrayList<E>();
    for (var item : items) {
      switch (requireNonNull(f.apply(item), "traverseAll function must not return null")) {
        case Ok(var value) -> values.add(value);
        case Err(var error) -> errors.add(error);
      }
    }
    return errors.isEmpty() ? ok(List.copyOf(values)) : error(List.copyOf(errors));
  }
}
