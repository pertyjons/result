package io.github.pertyjons.result;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.util.AbstractSequentialList;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResultTest {

  @Nested
  class OkFactory {

    @Test
    void containsValue() {
      Result<String, String> result = Result.ok("hello");
      assertThat(result).hasValue("hello");
    }

    @Test
    void isOkReturnsTrue() {
      assertThat(Result.ok("hello").isOk()).isTrue();
    }

    @Test
    void isErrorReturnsFalse() {
      assertThat(Result.ok("hello").isError()).isFalse();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void rejectsNull() {
      assertThatThrownBy(() -> Result.ok(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("must not be null");
    }

    @Test
    void withErrorClassHelpsTypeInference() {
      var result = Result.ok("hello", String.class);
      assertThat(result).hasValue("hello");
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void withErrorClassRejectsNull() {
      assertThatThrownBy(() -> Result.ok(null, String.class))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class ErrorFactory {

    @Test
    void containsError() {
      Result<String, String> result = Result.error("oops");
      assertThat(result).hasError("oops");
    }

    @Test
    void isOkReturnsFalse() {
      assertThat(Result.error("oops").isOk()).isFalse();
    }

    @Test
    void isErrorReturnsTrue() {
      assertThat(Result.error("oops").isError()).isTrue();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void rejectsNull() {
      assertThatThrownBy(() -> Result.error(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("must not be null");
    }

    @Test
    void withValueClassHelpsTypeInference() {
      var result = Result.error("oops", Integer.class);
      Result<Integer, String> typed = result; // compiles only if T was inferred as Integer
      assertThat(typed).hasError("oops");
    }

    @Test
    void withValueClassMirrorsOkWitness() {
      var ok = Result.ok(1, String.class);
      var err = Result.error("oops", Integer.class);
      assertThat(ok.recover(_ -> err)).hasValue(1); // both are Result<Integer, String>
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void withValueClassRejectsNullError() {
      assertThatThrownBy(() -> Result.error(null, Integer.class))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("must not be null");
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void withValueClassRejectsNullClass() {
      assertThatThrownBy(() -> Result.error("oops", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("valueClass");
    }
  }

  @Nested
  class Map {

    @Test
    void transformsOkValue() {
      var result = Result.ok(5, String.class).map(n -> n * 2);
      assertThat(result).hasValue(10);
    }

    @Test
    void preservesErrorUntouched() {
      var result = Result.error("fail", Integer.class).map(n -> n * 2);
      assertThat(result).hasError("fail");
    }

    @Test
    void changesValueType() {
      var result = Result.ok(42, String.class).map(n -> "number: " + n);
      assertThat(result).hasValue("number: 42");
    }
  }

  @Nested
  class MapError {

    @Test
    void transformsErrorValue() {
      var result = Result.error(42, String.class).mapError(e -> "error: " + e);
      assertThat(result).hasError("error: 42");
    }

    @Test
    void preservesOkUntouched() {
      var result = Result.ok("hello", Integer.class).mapError(e -> "error: " + e);
      assertThat(result).hasValue("hello");
    }

    @Test
    void changesErrorType() {
      var result = Result.error("404", String.class).mapError(Integer::parseInt);
      assertThat(result).hasError(404);
    }
  }

  @Nested
  class FlatMap {

    @Test
    void chainsSuccessfulOperations() {
      var result = Result.ok(5, String.class).flatMap(n -> Result.ok(n + 1));
      assertThat(result).hasValue(6);
    }

    @Test
    void shortCircuitsOnError() {
      var result = Result.error("fail", Integer.class).flatMap(n -> Result.ok(n + 1));
      assertThat(result).hasError("fail");
    }

    @Test
    void canProduceErrorFromOk() {
      var result = Result.ok(5, String.class).flatMap(_ -> Result.error("went wrong"));
      assertThat(result).hasError("went wrong");
    }

    @Test
    void chainsMultipleOperations() {
      var result =
          Result.ok(1, String.class).flatMap(n -> Result.ok(n + 1)).flatMap(n -> Result.ok(n * 10));
      assertThat(result).hasValue(20);
    }
  }

  @Nested
  class Recover {

    @Test
    void transformsErrorToOk() {
      var result = Result.error("fail", String.class).recover(_ -> Result.ok("recovered"));
      assertThat(result).hasValue("recovered");
    }

    @Test
    void canReturnNewError() {
      var result = Result.error("fail", String.class).recover(_ -> Result.error("still bad"));
      assertThat(result).hasError("still bad");
    }

    @Test
    void passesThroughOk() {
      var result = Result.ok("fine", String.class).recover(_ -> Result.ok("recovered"));
      assertThat(result).hasValue("fine");
    }

    @Test
    void receivesOriginalError() {
      var result =
          Result.error("original", String.class).recover(e -> Result.ok("recovered from: " + e));
      assertThat(result).hasValue("recovered from: original");
    }

    @Test
    void canChangeErrorType() {
      record CacheError(String message) {}
      record NetworkError(String message) {}

      Result<Integer, CacheError> cached = Result.error(new CacheError("cache unavailable"));
      Result<Integer, NetworkError> recovered =
          cached.recover(_ -> Result.error(new NetworkError("network unavailable")));

      assertThat(recovered).hasError(new NetworkError("network unavailable"));
    }

    @Test
    void passesThroughOkWhileChangingErrorType() {
      record CacheError(String message) {}
      record NetworkError(String message) {}

      Result<Integer, CacheError> cached = Result.ok(42);
      Result<Integer, NetworkError> recovered =
          cached.recover(_ -> Result.error(new NetworkError("network unavailable")));

      assertThat(recovered).hasValue(42);
      assertThat((Object) recovered).isSameAs(cached);
    }
  }

  @Nested
  class Filter {

    @Test
    void keepsMatchingOk() {
      var result = Result.ok(10, String.class).filter(n -> n > 5, n -> "too small: " + n);
      assertThat(result).hasValue(10);
    }

    @Test
    void rejectsNonMatchingOk() {
      var result = Result.ok(3, String.class).filter(n -> n > 5, n -> "too small: " + n);
      assertThat(result).hasError("too small: 3");
    }

    @Test
    void passesThroughError() {
      var result =
          Result.error("already bad", Integer.class).filter(n -> n > 5, n -> "too small: " + n);
      assertThat(result).hasError("already bad");
    }
  }

  @Nested
  class OnOk {

    @Test
    void executesConsumerForOk() {
      var sideEffects = new ArrayList<String>();
      var result = Result.ok("hello", String.class).onOk(sideEffects::add);
      assertThat(result).isOk();
      assertThat(sideEffects).containsExactly("hello");
    }

    @Test
    void skipsConsumerForError() {
      var sideEffects = new ArrayList<String>();
      var result = Result.error("fail", String.class).onOk(sideEffects::add);
      assertThat(result).isError();
      assertThat(sideEffects).isEmpty();
    }

    @Test
    void returnsSameResult() {
      var original = Result.ok("hello", String.class);
      var returned = original.onOk(_ -> {});
      assertThat(returned).isSameAs(original);
    }
  }

  @Nested
  class OnError {

    @Test
    void executesConsumerForError() {
      var sideEffects = new ArrayList<String>();
      var result = Result.error("fail", String.class).onError(sideEffects::add);
      assertThat(result).isError();
      assertThat(sideEffects).containsExactly("fail");
    }

    @Test
    void skipsConsumerForOk() {
      var sideEffects = new ArrayList<String>();
      var result = Result.ok("hello", String.class).onError(sideEffects::add);
      assertThat(result).isOk();
      assertThat(sideEffects).isEmpty();
    }

    @Test
    void returnsSameResult() {
      var original = Result.error("fail", String.class);
      var returned = original.onError(_ -> {});
      assertThat(returned).isSameAs(original);
    }
  }

  @Nested
  class Fold {

    @Test
    void appliesOkFunctionForOk() {
      var result = Result.ok("hello", String.class).fold(String::length, e -> -1);
      assertThat(result).isEqualTo(5);
    }

    @Test
    void appliesErrorFunctionForError() {
      var result = Result.error("fail", String.class).fold(String::length, e -> -1);
      assertThat(result).isEqualTo(-1);
    }

    @Test
    void returnsUnifiedType() {
      Result<Integer, String> ok = Result.ok(42);
      Result<Integer, String> err = Result.error("bad");
      String okResult = ok.fold(n -> "Got: " + n, e -> "Error: " + e);
      String errResult = err.fold(n -> "Got: " + n, e -> "Error: " + e);
      assertThat(okResult).isEqualTo("Got: 42");
      assertThat(errResult).isEqualTo("Error: bad");
    }
  }

  @Nested
  class OrElseThrow {

    @Test
    void returnsValueForOk() {
      assertThat(Result.ok("hello").orElseThrow()).isEqualTo("hello");
    }

    @Test
    void throwsResultExceptionForError() {
      var result = Result.error("bad", String.class);
      assertThatThrownBy(result::orElseThrow)
          .isInstanceOf(Result.ResultException.class)
          .hasMessageContaining("bad");
    }

    @Test
    void resultExceptionCarriesOriginalError() {
      var original = List.of("structured", "error");
      var result = Result.error(original, String.class);
      assertThatThrownBy(result::orElseThrow)
          .isInstanceOf(Result.ResultException.class)
          .satisfies(ex -> assertThat(((Result.ResultException) ex).error()).isSameAs(original));
    }
  }

  @Nested
  class OrElseThrowWithMapping {

    @Test
    void returnsValueForOk() {
      var result = Result.ok("hello", String.class);
      assertThat(result.orElseThrow(IllegalStateException::new)).isEqualTo("hello");
    }

    @Test
    void throwsCustomExceptionForError() {
      var result = Result.error("bad", String.class);
      assertThatThrownBy(() -> result.orElseThrow(e -> new IllegalStateException("Got: " + e)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Got: bad");
    }
  }

  @Nested
  class OrElse {

    @Test
    void returnsValueForOk() {
      assertThat(Result.ok("hello", String.class).orElse("default")).isEqualTo("hello");
    }

    @Test
    void returnsDefaultForError() {
      assertThat(Result.error("fail", String.class).orElse("default")).isEqualTo("default");
    }
  }

  @Nested
  class OrElseGet {

    @Test
    void returnsValueForOk() {
      var result = Result.ok("hello", String.class);
      assertThat(result.orElseGet(e -> "fallback: " + e)).isEqualTo("hello");
    }

    @Test
    void computesFallbackForError() {
      var result = Result.error("fail", String.class);
      assertThat(result.orElseGet(e -> "fallback: " + e)).isEqualTo("fallback: fail");
    }

    @Test
    void doesNotEvaluateFallbackForOk() {
      var evaluated = new AtomicBoolean(false);
      Result.ok("hello", String.class)
          .orElseGet(
              _ -> {
                evaluated.set(true);
                return "fallback";
              });
      assertThat(evaluated.get()).isFalse();
    }
  }

  @Nested
  class RecordComponents {

    @Test
    void okExposesValueAfterMatching() {
      if (Result.ok("hello", String.class) instanceof Result.Ok<String, String> ok) {
        assertThat(ok.value()).isEqualTo("hello");
      } else {
        fail("expected Ok");
      }
    }

    @Test
    void errExposesErrorAfterMatching() {
      if (Result.error("oops", String.class) instanceof Result.Err<String, String> err) {
        assertThat(err.error()).isEqualTo("oops");
      } else {
        fail("expected Err");
      }
    }
  }

  @Nested
  class ToOptional {

    @Test
    void returnsPresentForOk() {
      assertThat(Result.ok("hello", String.class).toOptional()).contains("hello");
    }

    @Test
    void returnsEmptyForError() {
      assertThat(Result.error("fail", String.class).toOptional()).isEmpty();
    }
  }

  @Nested
  class Ensure {

    @Test
    void returnsOkUnitWhenTrue() {
      var result = Result.ensure(true, "should not see this");
      assertThat(result).hasValue(Result.Unit.INSTANCE);
    }

    @Test
    void returnsErrorWhenFalse() {
      var result = Result.ensure(false, "validation failed");
      assertThat(result).hasError("validation failed");
    }

    @Test
    @SuppressWarnings("ConstantValue")
    void chainsWithFlatMapOnSuccess() {
      var result = Result.ensure(5 > 3, "math is broken").flatMap(_ -> Result.ok("all good"));
      assertThat(result).hasValue("all good");
    }

    @Test
    void chainsWithFlatMapOnFailureShortCircuits() {
      var flatMapped = new AtomicBoolean(false);
      var result =
          Result.ensure(false, "guard failed")
              .flatMap(
                  _ -> {
                    flatMapped.set(true);
                    return Result.ok(Result.Unit.INSTANCE);
                  });
      assertThat(result).isError();
      assertThat(flatMapped.get()).isFalse();
    }
  }

  @Nested
  class EnsureLazy {

    @Test
    void doesNotEvaluateSupplierOnTrue() {
      var evaluated = new AtomicBoolean(false);
      var result =
          Result.ensure(
              true,
              () -> {
                evaluated.set(true);
                return "error";
              });
      assertThat(result).isOk();
      assertThat(evaluated.get()).isFalse();
    }

    @Test
    void evaluatesSupplierOnFalse() {
      var result = Result.ensure(false, () -> "lazy error");
      assertThat(result).hasError("lazy error");
    }
  }

  @Nested
  class OfCallable {

    @Test
    void returnsOkOnSuccess() {
      var result = Result.ofCallable(() -> "hello");
      assertThat(result).hasValue("hello");
    }

    @Test
    void returnsTheThrownExceptionAsError() {
      var boom = new RuntimeException("boom");
      var result =
          Result.ofCallable(
              (Callable<String>)
                  () -> {
                    throw boom;
                  });
      assertThat(result).hasErrorSatisfying(e -> assertThat(e).isSameAs(boom));
    }

    @Test
    void handlesCheckedExceptions() {
      var result =
          Result.ofCallable(
              (Callable<String>)
                  () -> {
                    throw new IOException("checked");
                  });
      assertThat(result).hasErrorInstanceOf(IOException.class);
      assertThat(result).extractingError(Exception::getMessage).isEqualTo("checked");
    }

    @Test
    void preservesStackTraceViaOrElseThrow() {
      var result =
          Result.ofCallable(
              (Callable<String>)
                  () -> {
                    throw new IOException("disk full");
                  });
      assertThatThrownBy(result::orElseThrow)
          .isInstanceOf(Result.ResultException.class)
          .hasCauseInstanceOf(IOException.class)
          .hasMessageContaining("disk full");
    }

    @Test
    void restoresInterruptFlagOnInterruptedException() throws InterruptedException {
      var result =
          Result.ofCallable(
              (Callable<String>)
                  () -> {
                    throw new InterruptedException("stop");
                  });
      try {
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(result).hasErrorInstanceOf(InterruptedException.class);
      } finally {
        assertThat(Thread.interrupted()).isTrue(); // clear the flag so later tests are unaffected
      }
    }

    @Test
    void doesNotCatchErrors() {
      assertThatThrownBy(
              () ->
                  Result.ofCallable(
                      () -> {
                        throw new AssertionError("not an Exception");
                      }))
          .isInstanceOf(AssertionError.class);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void rejectsNullReturnFromCallable() {
      assertThatThrownBy(() -> Result.ofCallable(() -> null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("must not be null");
    }
  }

  @Nested
  class OfCallableWithMapper {

    @Test
    void returnsOkOnSuccess() {
      var result = Result.ofCallable(() -> "hello", Exception::getMessage);
      assertThat(result).hasValue("hello");
    }

    @Test
    void returnsMappedErrorOnException() {
      var result =
          Result.<String, String>ofCallable(
              () -> {
                throw new RuntimeException("boom");
              },
              e -> "caught: " + e.getMessage());
      assertThat(result).hasError("caught: boom");
    }

    @Test
    void mapsToCustomErrorType() {
      var result =
          Result.<String, Integer>ofCallable(
              () -> {
                throw new RuntimeException("boom");
              },
              _ -> 500);
      assertThat(result).hasError(500);
    }

    @Test
    void restoresInterruptFlagBeforeMapperRuns() {
      var interruptedInMapper = new AtomicBoolean(false);
      Result.<String, String>ofCallable(
          () -> {
            throw new InterruptedException("stop");
          },
          e -> {
            interruptedInMapper.set(Thread.currentThread().isInterrupted());
            return "mapped";
          });
      assertThat(Thread.interrupted()).isTrue(); // read-and-clear
      assertThat(interruptedInMapper).isTrue();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void rejectsNullReturnFromCallable() {
      assertThatThrownBy(() -> Result.ofCallable(() -> null, Exception::getMessage))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("must not be null");
    }
  }

  @Nested
  class Map2 {

    @Test
    void combinesTwoOkValues() {
      var result = Result.map2(Result.ok(1), Result.ok(2), Integer::sum);
      assertThat(result).hasValue(3);
    }

    @Test
    void shortCircuitsOnFirstError() {
      var result = Result.map2(Result.error("first", Integer.class), Result.ok(2), Integer::sum);
      assertThat(result).hasError("first");
    }

    @Test
    void shortCircuitsOnSecondError() {
      var result = Result.map2(Result.ok(1), Result.error("second", Integer.class), Integer::sum);
      assertThat(result).hasError("second");
    }

    @Test
    void returnsFirstErrorWhenBothFail() {
      var result =
          Result.map2(
              Result.error("first", Integer.class),
              Result.error("second", Integer.class),
              Integer::sum);
      assertThat(result).hasError("first");
    }
  }

  @Nested
  class Map3 {

    @Test
    void combinesThreeOkValues() {
      var result =
          Result.map3(Result.ok("a"), Result.ok("b"), Result.ok("c"), (a, b, c) -> a + b + c);
      assertThat(result).hasValue("abc");
    }

    @Test
    void shortCircuitsOnFirstError() {
      var result =
          Result.map3(
              Result.error("first", String.class),
              Result.ok("b"),
              Result.ok("c"),
              (a, b, c) -> a + b + c);
      assertThat(result).hasError("first");
    }

    @Test
    void shortCircuitsOnSecondError() {
      var result =
          Result.map3(
              Result.ok("a"),
              Result.error("second", String.class),
              Result.ok("c"),
              (a, b, c) -> a + b + c);
      assertThat(result).hasError("second");
    }

    @Test
    void shortCircuitsOnThirdError() {
      var result =
          Result.map3(
              Result.ok("a"),
              Result.ok("b"),
              Result.error("third", String.class),
              (a, b, c) -> a + b + c);
      assertThat(result).hasError("third");
    }

    @Test
    void returnsFirstErrorWhenMultipleFail() {
      var result =
          Result.map3(
              Result.error("first", String.class),
              Result.error("second", String.class),
              Result.error("third", String.class),
              (a, b, c) -> a + b + c);
      assertThat(result).hasError("first");
    }
  }

  @Nested
  class Map2All {

    @Test
    void combinesTwoOkValues() {
      assertThat(Result.map2All(Result.ok(1), Result.ok(2), Integer::sum)).hasValue(3);
    }

    @Test
    void collectsEveryErrorInArgumentOrder() {
      var result =
          Result.map2All(
              Result.error("first", Integer.class),
              Result.error("second", Integer.class),
              Integer::sum);

      assertThat(result).hasError(List.of("first", "second"));
    }

    @Test
    void doesNotCallCombinerWhenEitherResultFails() {
      var called = new AtomicBoolean(false);
      Result.map2All(
          Result.ok(1),
          Result.error("second", Integer.class),
          (a, b) -> {
            called.set(true);
            return a + b;
          });

      assertThat(called).isFalse();
    }

    @Test
    void errorListIsImmutable() {
      var result =
          Result.map2All(
              Result.error("first", Integer.class), Result.ok(2), Integer::sum);

      assertThat(result)
          .hasErrorSatisfying(
              errors ->
                  assertThatThrownBy(() -> errors.add("another"))
                      .isInstanceOf(UnsupportedOperationException.class));
    }

    @Test
    void rejectsNullFromCombiner() {
      assertThatThrownBy(() -> Result.map2All(Result.ok(1), Result.ok(2), (_, _) -> null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Map3All {

    @Test
    void combinesDifferentSuccessTypes() {
      record User(String name, int age, boolean active) {}

      var result =
          Result.map3All(
              Result.ok("Ada"), Result.ok(36), Result.ok(true), User::new);

      assertThat(result).hasValue(new User("Ada", 36, true));
    }

    @Test
    void collectsEveryErrorInArgumentOrder() {
      var result =
          Result.map3All(
              Result.error("first", String.class),
              Result.ok("ok"),
              Result.error("third", String.class),
              (a, b, c) -> a + b + c);

      assertThat(result).hasError(List.of("first", "third"));
    }

    @Test
    void doesNotCallCombinerWhenAnyResultFails() {
      var called = new AtomicBoolean(false);
      Result.map3All(
          Result.ok(1),
          Result.error("second", Integer.class),
          Result.error("third", Integer.class),
          (a, b, c) -> {
            called.set(true);
            return a + b + c;
          });

      assertThat(called).isFalse();
    }

    @Test
    void rejectsNullFromCombiner() {
      assertThatThrownBy(
              () -> Result.map3All(Result.ok(1), Result.ok(2), Result.ok(3), (_, _, _) -> null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Sequence {

    @Test
    void collectsAllOkValues() {
      var results = List.<Result<Integer, String>>of(Result.ok(1), Result.ok(2), Result.ok(3));
      var combined = Result.sequence(results);
      assertThat(combined).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 2, 3));
    }

    @Test
    void shortCircuitsOnFirstError() {
      var results =
          List.<Result<Integer, String>>of(Result.ok(1), Result.error("boom"), Result.ok(3));
      var combined = Result.sequence(results);
      assertThat(combined).hasError("boom");
    }

    @Test
    void returnsFirstErrorOnly() {
      var results = List.<Result<Integer, String>>of(Result.error("first"), Result.error("second"));
      var combined = Result.sequence(results);
      assertThat(combined).hasError("first");
    }

    @Test
    void emptyListReturnsOkEmptyList() {
      var combined = Result.sequence(List.<Result<Integer, String>>of());
      assertThat(combined).hasValueSatisfying(v -> assertThat(v).isEmpty());
    }

    @Test
    void singleOkReturnsSingleElementList() {
      var results = List.<Result<Integer, String>>of(Result.ok(42));
      assertThat(Result.sequence(results))
          .hasValueSatisfying(v -> assertThat(v).containsExactly(42));
    }

    @Test
    void singleErrorReturnsError() {
      var results = List.<Result<Integer, String>>of(Result.error("only"));
      assertThat(Result.sequence(results)).hasError("only");
    }

    @Test
    void returnsImmutableList() {
      var results = List.<Result<Integer, String>>of(Result.ok(1));
      var list = Result.sequence(results).orElseThrow();
      assertThatThrownBy(() -> list.add(2)).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class Traverse {

    @Test
    void mapsAndCollectsAllSuccesses() {
      var items = List.of("1", "2", "3");
      var result = Result.traverse(items, s -> Result.ok(Integer.parseInt(s), String.class));
      assertThat(result).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 2, 3));
    }

    @Test
    void shortCircuitsOnFirstError() {
      var items = List.of("1", "nope", "3");
      var result =
          Result.traverse(
              items,
              s -> {
                try {
                  return Result.ok(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                  return Result.error("not a number: " + s);
                }
              });
      assertThat(result).hasError("not a number: nope");
    }

    @Test
    void emptyListReturnsOkEmptyList() {
      var result = Result.traverse(List.<String>of(), _ -> Result.ok(1, String.class));
      assertThat(result).hasValueSatisfying(v -> assertThat(v).isEmpty());
    }

    @Test
    void singleElementSuccess() {
      var result = Result.traverse(List.of("hello"), s -> Result.ok(s.toUpperCase(), String.class));
      assertThat(result).hasValueSatisfying(v -> assertThat(v).containsExactly("HELLO"));
    }

    @Test
    void returnsImmutableList() {
      var list = Result.traverse(List.of("a"), Result::<String, String>ok).orElseThrow();
      assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class TraverseIndexed {

    @Test
    void traversesSequentialListsWithoutRepeatedSeeking() {
      var values = new LinkedList<Integer>();
      for (int i = 0; i < 1_000; i++) {
        values.add(i);
      }
      // AbstractSequentialList.get(i) obtains an iterator at i. Count the distance each
      // iterator must seek so a quadratic implementation fails without a timing threshold.
      class SequentialList extends AbstractSequentialList<Integer> {
        long seekDistance;

        @Override
        public int size() {
          return values.size();
        }

        @Override
        public ListIterator<Integer> listIterator(int index) {
          seekDistance += Math.min(index, size() - index);
          return values.listIterator(index);
        }
      }
      var items = new SequentialList();
      var result = Result.traverseIndexed(items, (i, value) -> Result.ok(i + value));
      assertThat(result).hasValue(values.stream().map(value -> value * 2).toList());
      assertThat(items.seekDistance).as("total iterator seeking is at most linear")
          .isLessThanOrEqualTo(items.size());
    }

    @Test
    void shortCircuitsSequentialListsWithTheCorrectIndex() {
      var visited = new ArrayList<Integer>();
      var result = Result.traverseIndexed(new LinkedList<>(List.of("a", "bad", "c")),
          (i, value) -> {
            visited.add(i);
            return value.equals("bad") ? Result.error("index " + i) : Result.ok(value);
          });
      assertThat(result).hasError("index 1");
      assertThat(visited).containsExactly(0, 1);
    }

    @Test
    void mapsWithIndexAndCollectsAllSuccesses() {
      var items = List.of("a", "b", "c");
      var result = Result.traverseIndexed(items, (i, s) -> Result.ok(i + ":" + s, String.class));
      assertThat(result)
          .hasValueSatisfying(v -> assertThat(v).containsExactly("0:a", "1:b", "2:c"));
    }

    @Test
    void shortCircuitsOnFirstError() {
      var items = List.of("ok", "bad", "ok");
      var result =
          Result.traverseIndexed(
              items,
              (i, s) ->
                  s.equals("bad") ? Result.error("error at index %d".formatted(i)) : Result.ok(s));
      assertThat(result).hasError("error at index 1");
    }

    @Test
    void emptyListReturnsOkEmptyList() {
      var result = Result.traverseIndexed(List.<String>of(), (i, s) -> Result.ok(s, String.class));
      assertThat(result).hasValueSatisfying(v -> assertThat(v).isEmpty());
    }

    @Test
    void indexIncludesPositionInErrorMessage() {
      var items = List.of("1", "nope", "3");
      var result =
          Result.traverseIndexed(
              items,
              (i, s) -> {
                try {
                  return Result.ok(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                  return Result.error("Item %d: not a number: %s".formatted(i, s));
                }
              });
      assertThat(result).hasError("Item 1: not a number: nope");
    }

    @Test
    void returnsImmutableList() {
      var list =
          Result.traverseIndexed(List.of("a"), (i, s) -> Result.ok(s, String.class)).orElseThrow();
      assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class Partition {

    @Test
    void separatesOkAndErrorValues() {
      var results =
          List.<Result<Integer, String>>of(
              Result.ok(1), Result.error("a"), Result.ok(2), Result.error("b"));
      var p = Result.partition(results);
      assertThat(p.values()).containsExactly(1, 2);
      assertThat(p.errors()).containsExactly("a", "b");
    }

    @Test
    void allOkWhenNoErrors() {
      var results = List.<Result<Integer, String>>of(Result.ok(1), Result.ok(2));
      var p = Result.partition(results);
      assertThat(p.allOk()).isTrue();
      assertThat(p.allErrors()).isFalse();
      assertThat(p.values()).containsExactly(1, 2);
      assertThat(p.errors()).isEmpty();
    }

    @Test
    void allErrorsWhenNoOk() {
      var results = List.<Result<Integer, String>>of(Result.error("a"), Result.error("b"));
      var p = Result.partition(results);
      assertThat(p.allOk()).isFalse();
      assertThat(p.allErrors()).isTrue();
      assertThat(p.values()).isEmpty();
      assertThat(p.errors()).containsExactly("a", "b");
    }

    @Test
    void emptyListProducesEmptyPartition() {
      var p = Result.partition(List.<Result<Integer, String>>of());
      assertThat(p.allOk()).isTrue();
      assertThat(p.allErrors()).isTrue();
      assertThat(p.values()).isEmpty();
      assertThat(p.errors()).isEmpty();
    }

    @Test
    void preservesOrder() {
      var results =
          List.<Result<Integer, String>>of(
              Result.ok(3), Result.error("x"), Result.ok(1), Result.error("y"), Result.ok(2));
      var p = Result.partition(results);
      assertThat(p.values()).containsExactly(3, 1, 2);
      assertThat(p.errors()).containsExactly("x", "y");
    }

    @Test
    void returnsImmutableLists() {
      var results = List.<Result<Integer, String>>of(Result.ok(1), Result.error("a"));
      var p = Result.partition(results);
      assertThatThrownBy(() -> p.values().add(2)).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> p.errors().add("b"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class SequenceAll {

    @Test
    void collectsAllOkValues() {
      var results = List.<Result<Integer, String>>of(Result.ok(1), Result.ok(2), Result.ok(3));
      var combined = Result.sequenceAll(results);
      assertThat(combined).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 2, 3));
    }

    @Test
    void accumulatesAllErrors() {
      var results =
          List.<Result<Integer, String>>of(
              Result.ok(1), Result.error("a"), Result.ok(3), Result.error("b"));
      var combined = Result.sequenceAll(results);
      assertThat(combined)
          .hasErrorSatisfying(errors -> assertThat(errors).containsExactly("a", "b"));
    }

    @Test
    void singleErrorReturnsListWithOneError() {
      var results =
          List.<Result<Integer, String>>of(Result.ok(1), Result.error("only"), Result.ok(3));
      var combined = Result.sequenceAll(results);
      assertThat(combined).hasErrorSatisfying(errors -> assertThat(errors).containsExactly("only"));
    }

    @Test
    void emptyListReturnsOkEmptyList() {
      var combined = Result.sequenceAll(List.<Result<Integer, String>>of());
      assertThat(combined).hasValueSatisfying(v -> assertThat(v).isEmpty());
    }

    @Test
    void allErrorsReturnsAllErrors() {
      var results =
          List.<Result<Integer, String>>of(Result.error("a"), Result.error("b"), Result.error("c"));
      var combined = Result.sequenceAll(results);
      assertThat(combined)
          .hasErrorSatisfying(errors -> assertThat(errors).containsExactly("a", "b", "c"));
    }

    @Test
    void okResultContainsImmutableList() {
      var results = List.<Result<Integer, String>>of(Result.ok(1));
      var list = Result.sequenceAll(results).orElseThrow();
      assertThatThrownBy(() -> list.add(2)).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class TraverseAll {

    @Test
    void mapsAndCollectsAllSuccesses() {
      var items = List.of("1", "2", "3");
      var result = Result.traverseAll(items, s -> Result.ok(Integer.parseInt(s), String.class));
      assertThat(result).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 2, 3));
    }

    @Test
    void accumulatesAllErrors() {
      var items = List.of("1", "nope", "3", "bad");
      var result =
          Result.traverseAll(
              items,
              s -> {
                try {
                  return Result.ok(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                  return Result.error("not a number: " + s);
                }
              });
      assertThat(result)
          .hasErrorSatisfying(
              errors ->
                  assertThat(errors).containsExactly("not a number: nope", "not a number: bad"));
    }

    @Test
    void emptyListReturnsOkEmptyList() {
      var result =
          Result.traverseAll(List.<String>of(), s -> Result.ok(Integer.parseInt(s), String.class));
      assertThat(result).hasValueSatisfying(v -> assertThat(v).isEmpty());
    }

    @Test
    void singleErrorReturnsListWithOneError() {
      var result =
          Result.traverseAll(
              List.of("bad"),
              s -> {
                try {
                  return Result.ok(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                  return Result.error("invalid: " + s);
                }
              });
      assertThat(result)
          .hasErrorSatisfying(errors -> assertThat(errors).containsExactly("invalid: bad"));
    }

    @Test
    void processesEveryElementEvenAfterError() {
      var processed = new ArrayList<String>();
      var items = List.of("a", "b", "c");
      Result.traverseAll(
          items,
          s -> {
            processed.add(s);
            return Result.error(s, String.class);
          });
      assertThat(processed).containsExactly("a", "b", "c");
    }
  }

  @Nested
  class PatternMatching {

    @Test
    void matchesOkVariant() {
      Result<String, Integer> result = Result.ok("hello");
      var output =
          switch (result) {
            case Result.Ok<String, Integer>(var value) -> "Got: " + value;
            case Result.Err<String, Integer>(var error) -> "Error: " + error;
          };
      assertThat(output).isEqualTo("Got: hello");
    }

    @Test
    void matchesErrorVariant() {
      Result<String, Integer> result = Result.error(404);
      var output =
          switch (result) {
            case Result.Ok<String, Integer>(var value) -> "Got: " + value;
            case Result.Err<String, Integer>(var error) -> "Error: " + error;
          };
      assertThat(output).isEqualTo("Error: 404");
    }
  }

  /**
   * Documents the immutability contract: the container never changes variant or payload reference,
   * but the payload itself is neither copied nor frozen — a mutable payload stays mutable.
   */
  @Nested
  class Immutability {

    @Test
    void okStoresPayloadReferenceWithoutCopying() {
      var values = new ArrayList<>(List.of("a"));
      Result<ArrayList<String>, String> result = Result.ok(values);
      values.add("b");
      assertThat(result.toOptional().orElseThrow()).isSameAs(values).containsExactly("a", "b");
    }

    @Test
    void errorStoresPayloadReferenceWithoutCopying() {
      var errors = new ArrayList<>(List.of("a"));
      Result<String, ArrayList<String>> result = Result.error(errors);
      errors.add("b");
      assertThat(result.toOptionalError().orElseThrow()).isSameAs(errors).containsExactly("a", "b");
    }

    @Test
    void equalityFollowsMutablePayload() {
      var values = new ArrayList<>(List.of("a"));
      Result<ArrayList<String>, String> result = Result.ok(values);
      Result<ArrayList<String>, String> snapshot = Result.ok(new ArrayList<>(List.of("a")));
      assertThat(result).isEqualTo(snapshot);
      values.add("b");
      assertThat(result).isNotEqualTo(snapshot);
    }
  }
}
