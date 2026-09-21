package io.github.pertyjons.result;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pertyjons.result.Result.Err;
import io.github.pertyjons.result.Result.Ok;
import io.github.pertyjons.result.Result.Partitioned;
import io.github.pertyjons.result.Result.Unit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for variance, null handling, the error-type witness overloads and other API contracts. */
@SuppressWarnings("java:S5778")
class ResultApiTest {

  @Nested
  class Variance {

    private final Function<Object, String> describe = Object::toString;
    private final Consumer<Object> sink = _ -> {};
    private final Supplier<CharSequence> errorSupplier = () -> "err";

    @Test
    void acceptsSuperTypedFunctionsAndConsumers() {
      Result<Integer, String> r = Result.ok(1);
      assertThat(r.map(describe)).hasValue("1");
      assertThat(r.mapError(describe)).hasValue(1);
      assertThat(r.mapBoth(describe, describe)).hasValue("1");
      assertThat(r.onOk(sink).onError(sink)).hasValue(1);
      assertThat(r.fold(describe, describe)).isEqualTo("1");
      assertThat(r.filter(o -> o != null, describe)).hasValue(1);
      r.match(sink, sink);
    }

    @Test
    void flatMapAcceptsCovariantResult() {
      Result<Number, CharSequence> r = Result.ok(1);
      Function<Object, Result<Integer, String>> f = o -> Result.ok(o.hashCode());
      Result<Number, CharSequence> mapped = r.flatMap(f);
      assertThat(mapped).hasValue(1);
    }

    @Test
    void recoverAcceptsCovariantResult() {
      Result<Number, CharSequence> r = Result.error("boom");
      Function<Object, Result<Integer, String>> f = _ -> Result.ok(42);
      assertThat(r.recover(f)).hasValue(42);
    }

    @Test
    void ensureAndOfNullableAcceptCovariantSuppliers() {
      Result<Unit, CharSequence> e = Result.ensure(false, errorSupplier);
      assertThat(e).hasError("err");
      Result<String, CharSequence> n = Result.ofNullable(null, errorSupplier);
      assertThat(n).hasError("err");
    }

    @Test
    void collectionMethodsAcceptSubtypesAndAnyCollection() {
      List<Ok<Integer, String>> oks = List.of(new Ok<>(1), new Ok<>(2));
      assertThat(Result.sequence(oks)).hasValue(List.of(1, 2));
      assertThat(Result.sequenceAll(oks)).hasValue(List.of(1, 2));
      assertThat(Result.partition(oks).values()).containsExactly(1, 2);

      Set<Result<Integer, String>> set = new LinkedHashSet<>(List.of(Result.ok(3), Result.ok(4)));
      assertThat(Result.sequence(set)).hasValue(List.of(3, 4));
      assertThat(Result.traverse(set, r -> r.map(i -> i * 10))).hasValue(List.of(30, 40));
      assertThat(Result.traverseAll(set, r -> r.map(i -> i * 10))).hasValue(List.of(30, 40));
    }

    @Test
    void map2AndMap3AcceptCovariantResults() {
      Result<Integer, String> a = Result.ok(1);
      Result<Long, RuntimeException> b = Result.ok(2L);
      Result<Number, Object> sum = Result.map2(a, b, (x, y) -> x.intValue() + y.intValue());
      assertThat(sum).hasValue(3);
      Result<Number, Object> sum3 =
          Result.map3(a, b, a, (x, y, z) -> x.intValue() + y.intValue() + z.intValue());
      assertThat(sum3).hasValue(4);
    }

    @Test
    void accumulatingCombinersAcceptCovariantResults() {
      Result<Integer, String> a = Result.ok(1);
      Result<Long, RuntimeException> b = Result.ok(2L);
      Result<Number, List<Object>> sum =
          Result.map2All(a, b, (x, y) -> x.intValue() + y.intValue());
      assertThat(sum).hasValue(3);
      Result<Number, List<Object>> sum3 =
          Result.map3All(a, b, a, (x, y, z) -> x.intValue() + y.intValue() + z.intValue());
      assertThat(sum3).hasValue(4);
    }
  }

  @Nested
  class OrElseThrowChecked {

    @Test
    void canThrowCheckedException() {
      Result<String, String> r = Result.error("bad");
      assertThatThrownBy(() -> r.orElseThrow(IOException::new))
          .isInstanceOf(IOException.class)
          .hasMessage("bad");
    }

    @Test
    void returnsValueWithoutThrowing() throws IOException {
      Result<String, String> r = Result.ok("fine");
      assertThat(r.orElseThrow(IOException::new)).isEqualTo("fine");
    }

    @Test
    void rejectsNullExceptionFromMapper() {
      Result<String, String> r = Result.error("bad");
      assertThatThrownBy(() -> r.orElseThrow(_ -> null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("must not return null");
    }
  }

  @Nested
  class ResultExceptionCause {

    @Test
    void throwableErrorBecomesCause() {
      var io = new IOException("disk full");
      Result<String, IOException> r = Result.error(io);
      assertThatThrownBy(r::orElseThrow)
          .isInstanceOf(Result.ResultException.class)
          .hasCause(io)
          .hasMessage(io.toString());
    }

    @Test
    void nonThrowableErrorHasNoCause() {
      Result<String, String> r = Result.error("plain");
      assertThatThrownBy(r::orElseThrow)
          .isInstanceOf(Result.ResultException.class)
          .hasNoCause()
          .hasMessage("plain");
    }
  }

  @Nested
  class NullReturningFunctions {

    @Test
    void flatMapRejectsNull() {
      assertThatThrownBy(() -> Result.ok(1).flatMap(_ -> null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("flatMap");
    }

    @Test
    void recoverRejectsNull() {
      assertThatThrownBy(() -> Result.error("x", Integer.class).recover(_ -> null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("recover");
    }

    @Test
    void traverseRejectsNull() {
      assertThatThrownBy(() -> Result.traverse(List.of(1), _ -> null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("traverse");
    }

    @Test
    void mapRejectsNull() {
      assertThatThrownBy(() -> Result.ok(1).map(_ -> null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class PassThroughIdentity {

    @Test
    void mapOnErrReturnsSameInstance() {
      Result<Integer, String> err = Result.error("x");
      assertThat(err.map(i -> i + 1)).isSameAs(err);
    }

    @Test
    void mapErrorOnOkReturnsSameInstance() {
      Result<Integer, String> ok = Result.ok(1);
      assertThat(ok.mapError(String::length)).isSameAs(ok);
    }

    @Test
    void flatMapOnErrReturnsSameInstance() {
      Result<Integer, String> err = Result.error("x");
      assertThat(err.flatMap(i -> Result.ok(i + 1))).isSameAs(err);
    }
  }

  @Nested
  class OkUnit {

    @Test
    void okWithoutArgumentsCarriesUnit() {
      Result<Unit, String> r = Result.ok();
      assertThat(r).hasValue(Unit.INSTANCE);
    }

    @Test
    void ensureUsesPrimitiveBoolean() {
      assertThat(Result.ensure(true, "e")).hasValue(Unit.INSTANCE);
      assertThat(Result.ensure(false, "e")).hasError("e");
    }

    @Test
    void ensureRejectsNullError() {
      assertThatThrownBy(() -> Result.ensure(true, (String) null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class OfNullable {

    @Test
    void wrapsNonNullValue() {
      assertThat(Result.ofNullable("v", "missing")).hasValue("v");
    }

    @Test
    void usesErrorForNull() {
      assertThat(Result.<String, String>ofNullable(null, "missing")).hasError("missing");
    }

    @Test
    void lazyVariantOnlyCallsSupplierForNull() {
      var calls = new AtomicReference<>(0);
      Supplier<String> counting =
          () -> {
            calls.set(calls.get() + 1);
            return "missing";
          };
      assertThat(Result.ofNullable("v", counting)).hasValue("v");
      assertThat(calls.get()).isZero();
      assertThat(Result.<String, String>ofNullable(null, counting)).hasError("missing");
      assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsNullError() {
      assertThatThrownBy(() -> Result.ofNullable("v", (String) null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class OfOptional {

    @Test
    void presentBecomesOk() {
      assertThat(Result.ofOptional(Optional.of(5), () -> "none")).hasValue(5);
    }

    @Test
    void emptyBecomesErr() {
      assertThat(Result.<Integer, String>ofOptional(Optional.empty(), () -> "none"))
          .hasError("none");
    }

    @Test
    void acceptsCovariantOptional() {
      Optional<Integer> opt = Optional.of(5);
      Result<Number, String> r = Result.ofOptional(opt, () -> "none");
      assertThat(r).hasValue(5);
    }
  }

  @Nested
  class StreamAndOptionalError {

    @Test
    void streamYieldsValueForOk() {
      assertThat(Result.ok(1).stream()).containsExactly(1);
    }

    @Test
    void streamIsEmptyForErr() {
      assertThat(Result.error("x", Integer.class).stream()).isEmpty();
    }

    @Test
    void streamKeepsOnlySuccesses() {
      List<Result<Integer, String>> rs = List.of(Result.ok(1), Result.error("x"), Result.ok(3));
      assertThat(rs.stream().flatMap(Result::stream)).containsExactly(1, 3);
    }

    @Test
    void toOptionalErrorMirrorsToOptional() {
      Result<Integer, String> ok = Result.ok(1);
      Result<Integer, String> err = Result.error("x");
      assertThat(ok.toOptionalError()).isEmpty();
      assertThat(err.toOptionalError()).contains("x");
    }
  }

  @Nested
  class MatchMapBothSwap {

    @Test
    void matchRunsExactlyOneBranch() {
      var seen = new ArrayList<String>();
      Result.ok(1, String.class).match(v -> seen.add("ok:" + v), e -> seen.add("err:" + e));
      Result.error("x", Integer.class).match(v -> seen.add("ok:" + v), e -> seen.add("err:" + e));
      assertThat(seen).containsExactly("ok:1", "err:x");
    }

    @Test
    void mapBothTransformsEitherSide() {
      assertThat(Result.ok(2, String.class).mapBoth(i -> i * 2, String::length)).hasValue(4);
      assertThat(Result.error("abc", Integer.class).mapBoth(i -> i * 2, String::length))
          .hasError(3);
    }

    @Test
    void swapExchangesVariants() {
      assertThat(Result.ok(1, String.class).swap()).hasError(1);
      assertThat(Result.error("x", Integer.class).swap()).hasValue("x");
    }
  }

  @Nested
  class PartitionedImmutability {

    @Test
    void constructorCopiesLists() {
      var values = new ArrayList<>(List.of(1));
      var errors = new ArrayList<>(List.of("e"));
      var p = new Partitioned<>(values, errors);
      values.add(2);
      errors.add("f");
      assertThat(p.values()).containsExactly(1);
      assertThat(p.errors()).containsExactly("e");
      assertThatThrownBy(() -> p.values().add(3)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructorRejectsNullElements() {
      var withNull = new ArrayList<Integer>();
      withNull.add(null);
      assertThatThrownBy(() -> new Partitioned<>(withNull, List.of()))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void booleanQueriesArePrimitive() {
      var p = new Partitioned<Integer, String>(List.of(), List.of());
      boolean allOk = p.allOk();
      boolean allErrors = p.allErrors();
      assertThat(allOk && allErrors).isTrue();
    }
  }

  @Nested
  class ErrNaming {

    @Test
    void errDoesNotShadowJavaLangError() {
      // Both Err and java.lang.Error are usable in the same file without qualification.
      Result<String, String> r = new Err<>("x");
      assertThat(r).hasError("x");
      try {
        throw new AssertionError("still java.lang.Error");
      } catch (Error e) {
        assertThat(e).isInstanceOf(AssertionError.class);
      }
    }

    @Test
    void errRejectsNull() {
      assertThatThrownBy(() -> new Err<>(null)).isInstanceOf(NullPointerException.class);
    }
  }

  /**
   * The {@code Class<E>} overloads of ensure, ofNullable, ofOptional and ofCallable. Without the
   * witness, E would be inferred as the single case the supplier builds, and the chain could not
   * continue with another case of the same sealed hierarchy. Most of these tests would not compile
   * without the witness; the assertions pin the runtime behaviour.
   */
  @Nested
  class ErrorClassWitness {

    sealed interface ParseError {
      record Missing(String key) implements ParseError {}

      record Invalid(String raw) implements ParseError {}
    }

    private Result<Integer, ParseError> parse(String raw) {
      return raw.chars().allMatch(Character::isDigit) && !raw.isEmpty()
          ? Result.ok(Integer.parseInt(raw))
          : Result.error(new ParseError.Invalid(raw));
    }

    private static <T> Supplier<T> failIfCalled() {
      return () -> {
        throw new AssertionError("supplier must not be called");
      };
    }

    @Test
    void ofNullableChainContinuesWithAnotherCase() {
      var port =
          Result.ofNullable("80x", () -> new ParseError.Missing("PORT"), ParseError.class)
              .flatMap(ErrorClassWitness.this::parse);
      Result<Integer, ParseError> declared = port;
      assertThat(declared).hasError(new ParseError.Invalid("80x"));
    }

    @Test
    void ofNullableOkAndErr() {
      assertThat(Result.ofNullable("8080", failIfCalled(), ParseError.class).flatMap(this::parse))
          .hasValue(8080);
      assertThat(Result.ofNullable(null, () -> new ParseError.Missing("PORT"), ParseError.class))
          .hasError(new ParseError.Missing("PORT"));
    }

    @Test
    void ensureChainContinuesWithAnotherCase() {
      Result<Integer, ParseError> r =
          Result.ensure(true, failIfCalled(), ParseError.class).flatMap(_ -> parse("x"));
      assertThat(r).hasError(new ParseError.Invalid("x"));
      assertThat(
              Result.ensure(false, () -> new ParseError.Missing("k"), ParseError.class)
                  .flatMap(_ -> parse("1")))
          .hasError(new ParseError.Missing("k"));
    }

    @Test
    void ofOptionalChainContinuesWithAnotherCase() {
      var present =
          Result.ofOptional(Optional.of("42"), failIfCalled(), ParseError.class)
              .flatMap(this::parse);
      assertThat(present).hasValue(42);
      var empty =
          Result.ofOptional(
                  Optional.<String>empty(), () -> new ParseError.Missing("id"), ParseError.class)
              .flatMap(this::parse);
      assertThat(empty).hasError(new ParseError.Missing("id"));
    }

    @Test
    void ofCallableChainContinuesWithAnotherCase() {
      var negative =
          Result.ofCallable(
                  () -> Integer.parseInt("-5"),
                  e -> new ParseError.Invalid(e.getMessage()),
                  ParseError.class)
              .filter(n -> n >= 0, n -> new ParseError.Missing("non-negative, got " + n));
      assertThat(negative).hasError(new ParseError.Missing("non-negative, got -5"));
      assertThat(
              Result.ofCallable(
                  () -> Integer.parseInt("nope"),
                  _ -> new ParseError.Invalid("nope"),
                  ParseError.class))
          .hasError(new ParseError.Invalid("nope"));
    }

    @Test
    void ofCallableWithWitnessRestoresInterruptFlag() {
      var r =
          Result.ofCallable(
              () -> {
                throw new InterruptedException();
              },
              _ -> new ParseError.Invalid("interrupted"),
              ParseError.class);
      assertThat(r).hasError(new ParseError.Invalid("interrupted"));
      assertThat(Thread.interrupted()).isTrue(); // also clears the flag for later tests
    }

    @Test
    void rejectsNullWitnessEvenOnTheOkPath() {
      assertThatThrownBy(() -> Result.ensure(true, failIfCalled(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("errorClass must not be null");
      assertThatThrownBy(() -> Result.ofNullable("v", failIfCalled(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("errorClass must not be null");
      assertThatThrownBy(() -> Result.ofOptional(Optional.of("v"), failIfCalled(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("errorClass must not be null");
      assertThatThrownBy(() -> Result.ofCallable(() -> "v", _ -> "e", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("errorClass must not be null");
    }

    @Test
    void supplierReturningNullIsRejected() {
      assertThatThrownBy(() -> Result.ofNullable(null, () -> null, ParseError.class))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
