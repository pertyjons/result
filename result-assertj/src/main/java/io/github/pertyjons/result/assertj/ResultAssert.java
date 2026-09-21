package io.github.pertyjons.result.assertj;

import io.github.pertyjons.result.Result;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Condition;
import org.assertj.core.api.ObjectAssert;
import org.jspecify.annotations.Nullable;

/**
 * Custom AssertJ assertion for {@link Result}. Provides fluent, single-expression assertions with
 * clear failure messages instead of multi-step unwrap-then-assert patterns.
 *
 * <p>Entry point is the static {@link #assertThat} factory method:
 *
 * <pre>{@code
 * // Variant check + exact value
 * ResultAssert.assertThat(result).hasValue("hello");
 *
 * // Variant check + error
 * ResultAssert.assertThat(result).hasError("oops");
 *
 * // Fluent chaining
 * ResultAssert.assertThat(result)
 *     .isOk()
 *     .hasValueInstanceOf(String.class)
 *     .hasValue("hello");
 *
 * // Extract and continue with standard AssertJ
 * ResultAssert.assertThat(result)
 *     .extractingValue()
 *     .asString()
 *     .startsWith("hel");
 *
 * // Extract a nested property
 * ResultAssert.assertThat(result)
 *     .extractingValue(Payload::toUtf8String)
 *     .isEqualTo("{\"event\":\"sign-on\"}");
 *
 * // Transform value and stay in ResultAssert
 * ResultAssert.assertThat(result)
 *     .map(Payload::toUtf8String)
 *     .hasValueMatching(s -> s.contains("sign-on"), "contains sign-on");
 *
 * // Reusable Condition
 * Condition<String> positive = new Condition<>(s -> !s.isEmpty(), "non-empty");
 * ResultAssert.assertThat(result).hasValueSatisfying(positive);
 * }</pre>
 *
 * @param <T> the success value type
 * @param <E> the error value type
 */
@SuppressWarnings("UnusedReturnValue")
public final class ResultAssert<T, E> extends AbstractAssert<ResultAssert<T, E>, Result<T, E>> {

  private ResultAssert(@Nullable Result<T, E> actual) {
    super(actual, ResultAssert.class);
  }

  /**
   * Create a new assertion for the given {@link Result}.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("hello")).isOk();
   * }</pre>
   *
   * @param <T> the success value type
   * @param <E> the error value type
   * @param actual the Result under test, may be null (then every assertion fails with isNotNull)
   * @return a new assertion on {@code actual}
   */
  public static <T, E> ResultAssert<T, E> assertThat(@Nullable Result<T, E> actual) {
    return new ResultAssert<>(actual);
  }

  // --- Internal helpers ---

  /** Assert Ok and return the value. Safe cast — {@code isOk()} guarantees the variant. */
  private T requireOkValue() {
    isOk();
    return ((Result.Ok<T, E>) actual).value();
  }

  /** Assert Error and return the error. Safe cast — {@code isError()} guarantees the variant. */
  private E requireErrorValue() {
    isError();
    return ((Result.Err<T, E>) actual).error();
  }

  /**
   * Copy this assertion's state (description from {@code as(...)}, representation and overriding
   * error message) onto {@code target}, so failures after a transformation or extraction still
   * report the caller's context. Mirrors AssertJ's package-private {@code withAssertionState}.
   */
  private <A extends AbstractAssert<A, ?>> A propagateStateTo(A target) {
    var source = getWritableAssertionInfo();
    if (source.description() != null) {
      target.as(source.description());
    }
    target.withRepresentation(source.representation());
    if (source.overridingErrorMessage() != null) {
      target.overridingErrorMessage(source.overridingErrorMessage());
    }
    return target;
  }

  /** Create a new {@code ResultAssert} on {@code result} that inherits this assertion's state. */
  private <U, F> ResultAssert<U, F> nested(Result<U, F> result) {
    return propagateStateTo(new ResultAssert<>(result));
  }

  /** Create an {@link ObjectAssert} on {@code value} that inherits this assertion's state. */
  private <U> ObjectAssert<U> extracted(U value) {
    return propagateStateTo(new ObjectAssert<>(value));
  }

  // --- Variant assertions ---

  /**
   * Assert that the Result is an {@link Result.Ok Ok} variant. Fails with a message showing the
   * actual error if the Result is Error.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("hello")).isOk();   // passes
   * ResultAssert.assertThat(Result.error("bad")).isOk();  // fails: "Expected Result to be Ok but was Error<bad>"
   * }</pre>
   *
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> isOk() {
    isNotNull();
    if (actual instanceof Result.Err<T, E>(var error)) {
      failWithMessage("Expected Result to be Ok but was Error<%s>", error);
    }
    return myself;
  }

  /**
   * Assert that the Result is an {@link Result.Err Err} variant. Fails with a message showing the
   * actual value if the Result is Ok.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.error("bad")).isError();  // passes
   * ResultAssert.assertThat(Result.ok("hello")).isError();   // fails: "Expected Result to be Error but was Ok<hello>"
   * }</pre>
   *
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> isError() {
    isNotNull();
    if (actual instanceof Result.Ok<T, E>(var value)) {
      failWithMessage("Expected Result to be Error but was Ok<%s>", value);
    }
    return myself;
  }

  // --- Value/error equality assertions ---

  /**
   * Assert that the Result is Ok and contains a value equal to {@code expected}. Calls {@link
   * #isOk()} internally, so the failure message is clear if the Result is Error.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("hello")).hasValue("hello");   // passes
   * ResultAssert.assertThat(Result.ok("hello")).hasValue("world");   // fails: value mismatch
   * ResultAssert.assertThat(Result.error("bad")).hasValue("hello");  // fails: not Ok
   * }</pre>
   *
   * @param expected the expected value, compared with {@code equals}
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasValue(T expected) {
    var value = requireOkValue();
    if (!value.equals(expected)) {
      failWithMessage("Expected Result to contain value <%s> but was <%s>", expected, value);
    }
    return myself;
  }

  /**
   * Assert that the Result is Error and contains an error equal to {@code expected}. Calls {@link
   * #isError()} internally, so the failure message is clear if the Result is Ok.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.error("bad")).hasError("bad");     // passes
   * ResultAssert.assertThat(Result.error("bad")).hasError("other");   // fails: error mismatch
   * ResultAssert.assertThat(Result.ok("hello")).hasError("bad");      // fails: not Error
   * }</pre>
   *
   * @param expected the expected value, compared with {@code equals}
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasError(E expected) {
    var error = requireErrorValue();
    if (!error.equals(expected)) {
      failWithMessage("Expected Result to contain error <%s> but was <%s>", expected, error);
    }
    return myself;
  }

  // --- Consumer-based assertions ---

  /**
   * Assert that the Result is Ok and the value satisfies the given consumer. The consumer typically
   * contains further AssertJ assertions on the value.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("hello"))
   *     .hasValueSatisfying(v -> assertThat(v).startsWith("hel").endsWith("llo"));
   * }</pre>
   *
   * @param requirements assertions to run on the extracted value
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasValueSatisfying(Consumer<? super T> requirements) {
    requirements.accept(requireOkValue());
    return myself;
  }

  /**
   * Assert that the Result is Error and the error satisfies the given consumer. The consumer
   * typically contains further AssertJ assertions on the error.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.error("file not found"))
   *     .hasErrorSatisfying(e -> assertThat(e).contains("not found"));
   * }</pre>
   *
   * @param requirements assertions to run on the extracted value
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasErrorSatisfying(Consumer<? super E> requirements) {
    requirements.accept(requireErrorValue());
    return myself;
  }

  // --- Condition-based assertions ---

  /**
   * Assert that the Result is Ok and the value satisfies the given AssertJ {@link Condition}.
   * Conditions are reusable, carry a built-in description, and can be composed with {@code
   * allOf()}, {@code anyOf()}, and {@code not()}.
   *
   * <pre>{@code
   * Condition<String> startsWithHello = new Condition<>(s -> s.startsWith("Hello"), "starts with Hello");
   *
   * ResultAssert.assertThat(Result.ok("Hello world"))
   *     .hasValueSatisfying(startsWithHello);
   * // Failure: "Expected Result value to satisfy [starts with Hello] but <Goodbye> did not"
   * }</pre>
   *
   * @param condition the AssertJ condition the extracted value must satisfy
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasValueSatisfying(Condition<? super T> condition) {
    var value = requireOkValue();
    if (!condition.matches(value)) {
      failWithMessage(
          "Expected Result value to satisfy [%s] but <%s> did not", condition.description(), value);
    }
    return myself;
  }

  /**
   * Assert that the Result is Error and the error satisfies the given AssertJ {@link Condition}.
   * Conditions are reusable, carry a built-in description, and can be composed with {@code
   * allOf()}, {@code anyOf()}, and {@code not()}.
   *
   * <pre>{@code
   * Condition<String> containsTimeout = new Condition<>(s -> s.contains("timeout"), "contains timeout");
   *
   * ResultAssert.assertThat(Result.error("timeout after 30s"))
   *     .hasErrorSatisfying(containsTimeout);
   * }</pre>
   *
   * @param condition the AssertJ condition the extracted value must satisfy
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasErrorSatisfying(Condition<? super E> condition) {
    var error = requireErrorValue();
    if (!condition.matches(error)) {
      failWithMessage(
          "Expected Result error to satisfy [%s] but <%s> did not", condition.description(), error);
    }
    return myself;
  }

  // --- Predicate-based assertions ---

  /**
   * Assert that the Result is Ok and the value matches the given predicate. Simpler than {@link
   * #hasValueSatisfying(Consumer)} when you only need a boolean check. The {@code description} is
   * shown in the failure message to explain what was expected.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok(42))
   *     .hasValueMatching(v -> v > 0, "a positive number");
   * // Failure: "Expected Result value to match [a positive number] but was <-1>"
   * }</pre>
   *
   * @param predicate the predicate the extracted value must satisfy
   * @param description shown in the failure message to explain what was expected
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasValueMatching(Predicate<? super T> predicate, String description) {
    var value = requireOkValue();
    if (!predicate.test(value)) {
      failWithMessage("Expected Result value to match [%s] but was <%s>", description, value);
    }
    return myself;
  }

  /**
   * Assert that the Result is Error and the error matches the given predicate. Simpler than {@link
   * #hasErrorSatisfying(Consumer)} when you only need a boolean check. The {@code description} is
   * shown in the failure message to explain what was expected.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.error("timeout after 30s"))
   *     .hasErrorMatching(e -> e.contains("timeout"), "a timeout message");
   * // Failure: "Expected Result error to match [a timeout message] but was <connection refused>"
   * }</pre>
   *
   * @param predicate the predicate the extracted value must satisfy
   * @param description shown in the failure message to explain what was expected
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasErrorMatching(Predicate<? super E> predicate, String description) {
    var error = requireErrorValue();
    if (!predicate.test(error)) {
      failWithMessage("Expected Result error to match [%s] but was <%s>", description, error);
    }
    return myself;
  }

  // --- Type assertions ---

  /**
   * Assert that the Result is Ok and the value is an instance of the given class. Useful when the
   * value type is a sealed hierarchy or {@code Object}.
   *
   * <pre>{@code
   * Result<Object, String> result = Result.ok("hello");
   * ResultAssert.assertThat(result).hasValueInstanceOf(String.class);
   * }</pre>
   *
   * @param expectedClass the class the extracted value must be an instance of
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasValueInstanceOf(Class<?> expectedClass) {
    var value = requireOkValue();
    if (!expectedClass.isInstance(value)) {
      failWithMessage(
          "Expected Result value to be instance of <%s> but was <%s>",
          expectedClass.getName(), value.getClass().getName());
    }
    return myself;
  }

  /**
   * Assert that the Result is Error and the error is an instance of the given class. Useful when
   * the error type is a sealed hierarchy (e.g. {@code CredentialError}).
   *
   * <pre>{@code
   * Result<String, Exception> result = Result.error(new IOException("disk full"));
   * ResultAssert.assertThat(result).hasErrorInstanceOf(IOException.class);
   * }</pre>
   *
   * @param expectedClass the class the extracted value must be an instance of
   * @return {@code this} for chaining
   */
  public ResultAssert<T, E> hasErrorInstanceOf(Class<?> expectedClass) {
    var error = requireErrorValue();
    if (!expectedClass.isInstance(error)) {
      failWithMessage(
          "Expected Result error to be instance of <%s> but was <%s>",
          expectedClass.getName(), error.getClass().getName());
    }
    return myself;
  }

  // --- Transformation methods (stay in ResultAssert) ---

  /**
   * Transform the Ok value and return a new {@code ResultAssert} on the mapped Result. Unlike
   * {@link #extractingValue(Function)} which escapes to {@link ObjectAssert}, this method stays in
   * the Result assertion domain, allowing further Result-specific assertions after the
   * transformation.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("hello"))
   *     .map(String::length)
   *     .hasValue(5);
   *
   * // Stays in ResultAssert — can chain more Result assertions:
   * ResultAssert.assertThat(result)
   *     .map(Payload::toUtf8String)
   *     .hasValueMatching(s -> s.contains("sign-on"), "contains sign-on event");
   *
   * // Error passes through unchanged:
   * ResultAssert.assertThat(Result.<String, String>error("bad"))
   *     .map(String::length)
   *     .isError()
   *     .hasError("bad");
   * }</pre>
   *
   * @param <U> the mapped value type
   * @param mapper the mapping function applied to the extracted value
   * @return a new assertion on the mapped Result
   */
  public <U> ResultAssert<U, E> map(Function<? super T, ? extends U> mapper) {
    isNotNull();
    return nested(actual.map(mapper));
  }

  /**
   * Chain a fallible operation on the Ok value and return a new {@code ResultAssert} on the
   * resulting Result. Short-circuits on the first error.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok(5))
   *     .flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive"))
   *     .hasValue(5);
   *
   * // Error passes through unchanged:
   * ResultAssert.assertThat(Result.<Integer, String>error("bad"))
   *     .flatMap(n -> Result.ok(n * 2))
   *     .isError()
   *     .hasError("bad");
   * }</pre>
   *
   * @param <U> the mapped value type
   * @param mapper the mapping function applied to the extracted value
   * @return a new assertion on the Result produced by {@code mapper}
   */
  public <U> ResultAssert<U, E> flatMap(
      Function<? super T, ? extends Result<? extends U, ? extends E>> mapper) {
    isNotNull();
    return nested(actual.flatMap(mapper));
  }

  /**
   * Combine the current Result with another using a mapping function and return a new {@code
   * ResultAssert} on the combined Result. Short-circuits on the first error.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok(1))
   *     .map2(Result.ok(2), Integer::sum)
   *     .hasValue(3);
   * }</pre>
   *
   * @param <B> the second success value type
   * @param <R> the combined value type
   * @param rb the second Result to combine with
   * @param f combines the success values
   * @return a new assertion on the combined Result
   */
  public <B, R> ResultAssert<R, E> map2(
      Result<? extends B, ? extends E> rb, BiFunction<? super T, ? super B, ? extends R> f) {
    isNotNull();
    return nested(Result.map2(actual, rb, f));
  }

  /**
   * Combine the current Result with two others using a mapping function and return a new {@code
   * ResultAssert} on the combined Result. Short-circuits on the first error.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("a"))
   *     .map3(Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c)
   *     .hasValue("abc");
   * }</pre>
   *
   * @param <B> the second success value type
   * @param <C> the third success value type
   * @param <R> the combined value type
   * @param rb the second Result to combine with
   * @param rc the third Result to combine with
   * @param f combines the success values
   * @return a new assertion on the combined Result
   */
  public <B, C, R> ResultAssert<R, E> map3(
      Result<? extends B, ? extends E> rb,
      Result<? extends C, ? extends E> rc,
      Result.TriFunction<? super T, ? super B, ? super C, ? extends R> f) {
    isNotNull();
    return nested(Result.map3(actual, rb, rc, f));
  }

  /**
   * Combine the current Result with another, accumulating both errors in argument order, and
   * return a new {@code ResultAssert} on the combined Result.
   *
   * @param <B> the second success value type
   * @param <R> the combined value type
   * @param rb the second Result to combine with
   * @param f combines the success values
   * @return a new assertion whose error type is an immutable list of every error
   */
  public <B, R> ResultAssert<R, List<E>> map2All(
      Result<? extends B, ? extends E> rb, BiFunction<? super T, ? super B, ? extends R> f) {
    isNotNull();
    return nested(Result.map2All(actual, rb, f));
  }

  /**
   * Combine the current Result with two others, accumulating every error in argument order, and
   * return a new {@code ResultAssert} on the combined Result.
   *
   * @param <B> the second success value type
   * @param <C> the third success value type
   * @param <R> the combined value type
   * @param rb the second Result to combine with
   * @param rc the third Result to combine with
   * @param f combines the success values
   * @return a new assertion whose error type is an immutable list of every error
   */
  public <B, C, R> ResultAssert<R, List<E>> map3All(
      Result<? extends B, ? extends E> rb,
      Result<? extends C, ? extends E> rc,
      Result.TriFunction<? super T, ? super B, ? super C, ? extends R> f) {
    isNotNull();
    return nested(Result.map3All(actual, rb, rc, f));
  }

  /**
   * Transform the Error value and return a new {@code ResultAssert} on the mapped Result. Unlike
   * {@link #extractingError(Function)} which escapes to {@link ObjectAssert}, this method stays in
   * the Result assertion domain.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.<String, Integer>error(404))
   *     .mapError(code -> "HTTP " + code)
   *     .hasError("HTTP 404");
   *
   * // Ok passes through unchanged:
   * ResultAssert.assertThat(Result.<String, Integer>ok("hello"))
   *     .mapError(code -> "HTTP " + code)
   *     .isOk()
   *     .hasValue("hello");
   * }</pre>
   *
   * @param <F> the mapped error type
   * @param mapper the mapping function applied to the extracted value
   * @return a new assertion on the mapped Result
   */
  public <F> ResultAssert<T, F> mapError(Function<? super E, ? extends F> mapper) {
    isNotNull();
    return nested(actual.mapError(mapper));
  }

  // --- Extraction methods (break the Result chain, return standard AssertJ asserts) ---

  /**
   * Extract the Ok value and return a standard {@link ObjectAssert} for full AssertJ chaining.
   * Asserts that the Result is Ok before extracting. This breaks the {@code ResultAssert} chain —
   * further calls use standard AssertJ methods.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.ok("hello world"))
   *     .extractingValue()
   *     .asString()
   *     .startsWith("hello")
   *     .contains("world");
   * }</pre>
   *
   * @return a standard AssertJ assertion on the extracted value
   */
  public ObjectAssert<T> extractingValue() {
    return extracted(requireOkValue());
  }

  /**
   * Extract a property from the Ok value using the given mapper and return a standard {@link
   * ObjectAssert} on the mapped result. Asserts that the Result is Ok before extracting. Useful for
   * asserting on nested properties without intermediate variables.
   *
   * <pre>{@code
   * ResultAssert.assertThat(result)
   *     .extractingValue(Payload::toUtf8String)
   *     .isEqualTo("{\"event\":\"sign-on\"}");
   *
   * ResultAssert.assertThat(result)
   *     .extractingValue(User::name)
   *     .isEqualTo("Alice");
   * }</pre>
   *
   * @param <U> the mapped value type
   * @param mapper the mapping function applied to the extracted value
   * @return a standard AssertJ assertion on the extracted value
   */
  public <U> ObjectAssert<U> extractingValue(Function<? super T, ? extends U> mapper) {
    return extracted(mapper.apply(requireOkValue()));
  }

  /**
   * Extract the Error value and return a standard {@link ObjectAssert} for full AssertJ chaining.
   * Asserts that the Result is Error before extracting. This breaks the {@code ResultAssert} chain
   * — further calls use standard AssertJ methods.
   *
   * <pre>{@code
   * ResultAssert.assertThat(Result.error("file not found"))
   *     .extractingError()
   *     .asString()
   *     .contains("not found");
   * }</pre>
   *
   * @return a standard AssertJ assertion on the extracted value
   */
  public ObjectAssert<E> extractingError() {
    return extracted(requireErrorValue());
  }

  /**
   * Extract a property from the Error value using the given mapper and return a standard {@link
   * ObjectAssert} on the mapped result. Asserts that the Result is Error before extracting. Useful
   * for asserting on nested fields in structured error types.
   *
   * <pre>{@code
   * ResultAssert.assertThat(result)
   *     .extractingError(CredentialError::message)
   *     .asString()
   *     .contains("tls.crt");
   * }</pre>
   *
   * @param <U> the mapped value type
   * @param mapper the mapping function applied to the extracted value
   * @return a standard AssertJ assertion on the extracted value
   */
  public <U> ObjectAssert<U> extractingError(Function<? super E, ? extends U> mapper) {
    return extracted(mapper.apply(requireErrorValue()));
  }
}
