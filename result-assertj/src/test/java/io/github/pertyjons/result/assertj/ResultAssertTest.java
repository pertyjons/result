package io.github.pertyjons.result.assertj;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pertyjons.result.Result;
import org.assertj.core.api.Condition;
import org.assertj.core.presentation.StandardRepresentation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5778")
class ResultAssertTest {

  @Nested
  class IsOk {

    @Test
    void succeedsForOk() {
      assertThat(Result.ok("hello", String.class)).isOk();
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).isOk())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok but was Error<oops>");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(() -> assertThat((Result<String, String>) null).isOk())
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class IsError {

    @Test
    void succeedsForError() {
      assertThat(Result.error("oops", String.class)).isError();
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).isError())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error but was Ok<hello>");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(() -> assertThat((Result<String, String>) null).isError())
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class HasValue {

    @Test
    void succeedsWhenValueMatches() {
      assertThat(Result.ok("hello", String.class)).hasValue("hello");
    }

    @Test
    void failsWhenValueDiffers() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).hasValue("world"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to contain value <world> but was <hello>");
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).hasValue("hello"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class HasError {

    @Test
    void succeedsWhenErrorMatches() {
      assertThat(Result.error("oops", String.class)).hasError("oops");
    }

    @Test
    void failsWhenErrorDiffers() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).hasError("other"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to contain error <other> but was <oops>");
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).hasError("oops"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class HasValueSatisfying {

    @Test
    void succeedsWhenConsumerPasses() {
      assertThat(Result.ok("hello", String.class))
          .hasValueSatisfying(v -> assertThat(v).startsWith("hel"));
    }

    @Test
    void failsWhenConsumerFails() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(
              () -> assertThat(result).hasValueSatisfying(v -> assertThat(v).startsWith("xyz")))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(
              () -> assertThat(result).hasValueSatisfying(v -> assertThat(v).isNotNull()))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class HasErrorSatisfying {

    @Test
    void succeedsWhenConsumerPasses() {
      assertThat(Result.error("oops", String.class))
          .hasErrorSatisfying(e -> assertThat(e).startsWith("oo"));
    }

    @Test
    void failsWhenConsumerFails() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(
              () -> assertThat(result).hasErrorSatisfying(e -> assertThat(e).startsWith("xyz")))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(
              () -> assertThat(result).hasErrorSatisfying(e -> assertThat(e).isNotNull()))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class HasValueSatisfyingCondition {

    private final Condition<String> startsWithHello =
        new Condition<>(s -> s.startsWith("Hello"), "starts with Hello");

    @Test
    void succeedsWhenConditionMatches() {
      assertThat(Result.ok("Hello world", String.class)).hasValueSatisfying(startsWithHello);
    }

    @Test
    void failsWhenConditionDoesNotMatch() {
      var result = Result.ok("Goodbye world", String.class);
      assertThatThrownBy(() -> assertThat(result).hasValueSatisfying(startsWithHello))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("starts with Hello")
          .hasMessageContaining("Goodbye world");
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).hasValueSatisfying(startsWithHello))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class HasErrorSatisfyingCondition {

    private final Condition<String> containsTimeout =
        new Condition<>(s -> s.contains("timeout"), "contains timeout");

    @Test
    void succeedsWhenConditionMatches() {
      assertThat(Result.error("timeout after 30s", String.class))
          .hasErrorSatisfying(containsTimeout);
    }

    @Test
    void failsWhenConditionDoesNotMatch() {
      var result = Result.error("connection refused", String.class);
      assertThatThrownBy(() -> assertThat(result).hasErrorSatisfying(containsTimeout))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("contains timeout")
          .hasMessageContaining("connection refused");
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).hasErrorSatisfying(containsTimeout))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class HasValueMatching {

    @Test
    void succeedsWhenPredicateMatches() {
      assertThat(Result.ok(42, String.class)).hasValueMatching(v -> v > 0, "a positive number");
    }

    @Test
    void failsWhenPredicateDoesNotMatch() {
      var result = Result.ok(-1, String.class);
      assertThatThrownBy(() -> assertThat(result).hasValueMatching(v -> v > 0, "a positive number"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result value to match [a positive number] but was <-1>");
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", Integer.class);
      assertThatThrownBy(() -> assertThat(result).hasValueMatching(v -> v > 0, "a positive number"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class HasErrorMatching {

    @Test
    void succeedsWhenPredicateMatches() {
      assertThat(Result.error("timeout after 30s", String.class))
          .hasErrorMatching(e -> e.contains("timeout"), "a timeout message");
    }

    @Test
    void failsWhenPredicateDoesNotMatch() {
      var result = Result.error("connection refused", String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .hasErrorMatching(e -> e.contains("timeout"), "a timeout message"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(
              "Expected Result error to match [a timeout message] but was <connection refused>");
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .hasErrorMatching(e -> e.contains("timeout"), "a timeout message"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class HasValueInstanceOf {

    @Test
    void succeedsWhenTypeMatches() {
      assertThat(Result.<Object, String>ok("hello")).hasValueInstanceOf(String.class);
    }

    @Test
    void failsWhenTypeDiffers() {
      var result = Result.<Object, String>ok("hello");
      assertThatThrownBy(() -> assertThat(result).hasValueInstanceOf(Integer.class))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result value to be instance of")
          .hasMessageContaining("Integer")
          .hasMessageContaining("String");
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", Object.class);
      assertThatThrownBy(() -> assertThat(result).hasValueInstanceOf(String.class))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class HasErrorInstanceOf {

    @Test
    void succeedsWhenTypeMatches() {
      assertThat(Result.<String, Object>error("oops")).hasErrorInstanceOf(String.class);
    }

    @Test
    void failsWhenTypeDiffers() {
      var result = Result.<String, Object>error("oops");
      assertThatThrownBy(() -> assertThat(result).hasErrorInstanceOf(Integer.class))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result error to be instance of")
          .hasMessageContaining("Integer")
          .hasMessageContaining("String");
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", Object.class);
      assertThatThrownBy(() -> assertThat(result).hasErrorInstanceOf(String.class))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class ExtractingValue {

    @Test
    void returnsObjectAssertForOkValue() {
      assertThat(Result.ok("hello", String.class)).extractingValue().isEqualTo("hello");
    }

    @Test
    void allowsFullAssertJChaining() {
      assertThat(Result.ok("hello world", String.class))
          .extractingValue()
          .asString()
          .startsWith("hello")
          .contains("world");
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).extractingValue())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class ExtractingValueWithMapper {

    @Test
    void mapsAndReturnsObjectAssert() {
      assertThat(Result.ok("hello", String.class)).extractingValue(String::length).isEqualTo(5);
    }

    @Test
    void allowsChainingOnMappedValue() {
      assertThat(Result.ok("hello world", String.class))
          .extractingValue(String::toUpperCase)
          .asString()
          .startsWith("HELLO")
          .contains("WORLD");
    }

    @Test
    void failsForError() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).extractingValue(String::length))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Ok");
    }
  }

  @Nested
  class ExtractingError {

    @Test
    void returnsObjectAssertForErrorValue() {
      assertThat(Result.error("oops", String.class)).extractingError().isEqualTo("oops");
    }

    @Test
    void allowsFullAssertJChaining() {
      assertThat(Result.error("something went wrong", String.class))
          .extractingError()
          .asString()
          .startsWith("something")
          .contains("wrong");
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).extractingError())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class ExtractingErrorWithMapper {

    @Test
    void mapsAndReturnsObjectAssert() {
      assertThat(Result.error("oops", String.class)).extractingError(String::length).isEqualTo(4);
    }

    @Test
    void allowsChainingOnMappedError() {
      assertThat(Result.error("file not found", String.class))
          .extractingError(String::toUpperCase)
          .asString()
          .contains("NOT FOUND");
    }

    @Test
    void failsForOk() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).extractingError(String::length))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected Result to be Error");
    }
  }

  @Nested
  class Map {

    @Test
    void transformsValueAndStaysInResultAssert() {
      assertThat(Result.ok("hello", String.class)).map(String::length).hasValue(5);
    }

    @Test
    void passesErrorThrough() {
      assertThat(Result.error("bad", String.class)).map(String::length).isError().hasError("bad");
    }

    @Test
    void allowsChainingResultAssertionsAfterMap() {
      assertThat(Result.ok("hello", String.class))
          .map(String::toUpperCase)
          .isOk()
          .hasValueMatching(s -> s.equals("HELLO"), "uppercased value");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(() -> assertThat((Result<String, String>) null).map(String::length))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class FlatMap {

    @Test
    void chainsOperationAndStaysInResultAssert() {
      assertThat(Result.ok(5, String.class))
          .flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive"))
          .hasValue(5);
    }

    @Test
    void canProduceErrorFromOk() {
      assertThat(Result.ok(-1, String.class))
          .flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive"))
          .isError()
          .hasError("must be positive");
    }

    @Test
    void passesErrorThrough() {
      assertThat(Result.error("bad", Integer.class))
          .flatMap(n -> Result.ok(n * 2))
          .isError()
          .hasError("bad");
    }

    @Test
    void allowsChainingAfterFlatMap() {
      assertThat(Result.ok("hello", String.class))
          .flatMap(s -> Result.ok(s.toUpperCase()))
          .isOk()
          .hasValueMatching(s -> s.equals("HELLO"), "uppercased value");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(
              () -> assertThat((Result<String, String>) null).flatMap(s -> Result.ok(s.length())))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class Map2 {

    @Test
    void combinesTwoOkValuesAndStaysInResultAssert() {
      assertThat(Result.ok(1, String.class)).map2(Result.ok(2), Integer::sum).hasValue(3);
    }

    @Test
    void shortCircuitsOnFirstError() {
      assertThat(Result.error("first", Integer.class))
          .map2(Result.ok(2), Integer::sum)
          .isError()
          .hasError("first");
    }

    @Test
    void shortCircuitsOnSecondError() {
      assertThat(Result.ok(1, String.class))
          .map2(Result.error("second", Integer.class), Integer::sum)
          .isError()
          .hasError("second");
    }

    @Test
    void allowsChainingAfterMap2() {
      assertThat(Result.ok("hello", String.class))
          .map2(Result.ok(" world"), String::concat)
          .isOk()
          .hasValueMatching(s -> s.contains("world"), "contains world");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(
              () -> assertThat((Result<Integer, String>) null).map2(Result.ok(2), Integer::sum))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class Map3 {

    @Test
    void combinesThreeOkValuesAndStaysInResultAssert() {
      assertThat(Result.ok("a", String.class))
          .map3(Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c)
          .hasValue("abc");
    }

    @Test
    void shortCircuitsOnFirstError() {
      assertThat(Result.error("first", String.class))
          .map3(Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c)
          .isError()
          .hasError("first");
    }

    @Test
    void shortCircuitsOnSecondError() {
      assertThat(Result.ok("a", String.class))
          .map3(Result.error("second", String.class), Result.ok("c"), (a, b, c) -> a + b + c)
          .isError()
          .hasError("second");
    }

    @Test
    void shortCircuitsOnThirdError() {
      assertThat(Result.ok("a", String.class))
          .map3(Result.ok("b"), Result.error("third", String.class), (a, b, c) -> a + b + c)
          .isError()
          .hasError("third");
    }

    @Test
    void allowsChainingAfterMap3() {
      assertThat(Result.ok(1, String.class))
          .map3(Result.ok(2), Result.ok(3), (a, b, c) -> a + b + c)
          .isOk()
          .hasValueMatching(v -> v == 6, "sum of 1+2+3");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(
              () ->
                  assertThat((Result<String, String>) null)
                      .map3(Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class Map2All {

    @Test
    void combinesValuesAndStaysInResultAssert() {
      assertThat(Result.ok(1, String.class))
          .map2All(Result.ok(2), Integer::sum)
          .hasValue(3);
    }

    @Test
    void accumulatesBothErrors() {
      assertThat(Result.error("first", Integer.class))
          .map2All(Result.error("second", Integer.class), Integer::sum)
          .hasErrorSatisfying(errors -> assertThat(errors).containsExactly("first", "second"));
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(
              () -> assertThat((Result<Integer, String>) null)
                  .map2All(Result.ok(2), Integer::sum))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class Map3All {

    @Test
    void combinesValuesAndStaysInResultAssert() {
      assertThat(Result.ok("a", String.class))
          .map3All(Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c)
          .hasValue("abc");
    }

    @Test
    void accumulatesEveryError() {
      assertThat(Result.error("first", String.class))
          .map3All(
              Result.error("second", String.class),
              Result.error("third", String.class),
              (a, b, c) -> a + b + c)
          .hasErrorSatisfying(
              errors -> assertThat(errors).containsExactly("first", "second", "third"));
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(
              () -> assertThat((Result<String, String>) null)
                  .map3All(Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class MapError {

    @Test
    void transformsErrorAndStaysInResultAssert() {
      assertThat(Result.error(404, String.class))
          .mapError(code -> "HTTP " + code)
          .hasError("HTTP 404");
    }

    @Test
    void passesOkThrough() {
      assertThat(Result.ok("hello", Integer.class))
          .mapError(code -> "HTTP " + code)
          .isOk()
          .hasValue("hello");
    }

    @Test
    void allowsChainingResultAssertionsAfterMapError() {
      assertThat(Result.error("fail", String.class))
          .mapError(String::toUpperCase)
          .isError()
          .hasErrorMatching(e -> e.equals("FAIL"), "uppercased error");
    }

    @Test
    void failsForNull() {
      assertThatThrownBy(() -> assertThat((Result<String, String>) null).mapError(String::length))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  class FluentChaining {

    @Test
    void isOkThenHasValue() {
      assertThat(Result.ok("hello", String.class)).isOk().hasValue("hello");
    }

    @Test
    void isErrorThenHasError() {
      assertThat(Result.error("oops", String.class)).isError().hasError("oops");
    }

    @Test
    void hasValueThenHasValueInstanceOf() {
      assertThat(Result.ok("hello", String.class))
          .hasValue("hello")
          .hasValueInstanceOf(String.class);
    }

    @Test
    void hasValueMatchingThenHasValueSatisfying() {
      assertThat(Result.ok("hello", String.class))
          .hasValueMatching(v -> v.length() == 5, "a 5-char string")
          .hasValueSatisfying(v -> assertThat(v).startsWith("hel"));
    }

    @Test
    void mapThenHasValueThenExtract() {
      assertThat(Result.ok("hello world", String.class))
          .map(String::toUpperCase)
          .hasValueMatching(s -> s.startsWith("HELLO"), "starts with HELLO")
          .extractingValue()
          .asString()
          .contains("WORLD");
    }
  }

  /**
   * Regression tests: the description set with {@code as(...)} (and other assertion state such as a
   * custom representation) must survive every transition that creates a new assertion object.
   */
  @Nested
  class PreservesAssertionState {

    private static final String DESCRIPTION = "result from user lookup";

    @Test
    void mapKeepsDescription() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).as(DESCRIPTION).map(String::trim).hasValue("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void flatMapKeepsDescription() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .as(DESCRIPTION)
                      .flatMap(v -> Result.ok(v, String.class))
                      .hasValue("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void map2KeepsDescription() {
      var result = Result.ok(1, String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .as(DESCRIPTION)
                      .map2(Result.ok(2, String.class), Integer::sum)
                      .hasValue(0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void map3KeepsDescription() {
      var result = Result.ok("a", String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .as(DESCRIPTION)
                      .map3(
                          Result.ok("b", String.class),
                          Result.ok("c", String.class),
                          (a, b, c) -> a + b + c)
                      .hasValue("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void map2AllKeepsDescription() {
      var result = Result.ok(1, String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .as(DESCRIPTION)
                      .map2All(Result.ok(2, String.class), Integer::sum)
                      .hasValue(0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void map3AllKeepsDescription() {
      var result = Result.ok("a", String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .as(DESCRIPTION)
                      .map3All(
                          Result.ok("b", String.class),
                          Result.ok("c", String.class),
                          (a, b, c) -> a + b + c)
                      .hasValue("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void mapErrorKeepsDescription() {
      var result = Result.error(404, String.class);
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .as(DESCRIPTION)
                      .mapError(code -> "HTTP " + code)
                      .hasError("HTTP 500"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void extractingValueKeepsDescription() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(() -> assertThat(result).as(DESCRIPTION).extractingValue().isEqualTo("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void extractingValueWithMapperKeepsDescription() {
      var result = Result.ok("hello", String.class);
      assertThatThrownBy(
              () -> assertThat(result).as(DESCRIPTION).extractingValue(String::length).isEqualTo(0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void extractingErrorKeepsDescription() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(() -> assertThat(result).as(DESCRIPTION).extractingError().isEqualTo("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void extractingErrorWithMapperKeepsDescription() {
      var result = Result.error("oops", String.class);
      assertThatThrownBy(
              () -> assertThat(result).as(DESCRIPTION).extractingError(String::length).isEqualTo(0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(DESCRIPTION);
    }

    @Test
    void mapKeepsCustomRepresentation() {
      var result = Result.ok("hello", String.class);
      var representation =
          new StandardRepresentation() {
            @Override
            public String toStringOf(Object object) {
              return "<<custom:" + object + ">>";
            }
          };
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .withRepresentation(representation)
                      .map(String::trim)
                      .isEqualTo("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("<<custom:");
    }

    @Test
    void extractingValueKeepsCustomRepresentation() {
      var result = Result.ok("hello", String.class);
      var representation =
          new StandardRepresentation() {
            @Override
            public String toStringOf(Object object) {
              return "<<custom:" + object + ">>";
            }
          };
      assertThatThrownBy(
              () ->
                  assertThat(result)
                      .withRepresentation(representation)
                      .extractingValue()
                      .isEqualTo("x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("<<custom:");
    }
  }
}
