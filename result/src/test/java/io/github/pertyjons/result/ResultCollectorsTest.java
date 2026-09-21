package io.github.pertyjons.result;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResultCollectorsTest {

  private static Result<Integer, String> parse(String s) {
    return Result.ofCallable(() -> Integer.parseInt(s), e -> "bad: " + s);
  }

  @Nested
  class ToResult {

    @Test
    void collectsAllOkValuesInOrder() {
      var result =
          Stream.of("1", "2", "3").map(ResultCollectorsTest::parse).collect(Result.toResult());
      assertThat(result).hasValue(List.of(1, 2, 3));
    }

    @Test
    void returnsFirstErrorInEncounterOrder() {
      var result =
          Stream.of("1", "x", "3", "y").map(ResultCollectorsTest::parse).collect(Result.toResult());
      assertThat(result).hasError("bad: x");
    }

    @Test
    void emptyStreamIsOkEmptyList() {
      var result = Stream.<Result<Integer, String>>empty().collect(Result.toResult());
      assertThat(result).hasValue(List.of());
    }

    @Test
    void okListIsImmutable() {
      var result = Stream.of("1").map(ResultCollectorsTest::parse).collect(Result.toResult());
      var list = result.orElseThrow();
      assertThatThrownBy(() -> list.add(2)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parallelStreamPreservesOrderAndFirstError() {
      var values =
          IntStream.range(0, 10_000)
              .parallel()
              .mapToObj(i -> Result.ok(i, String.class))
              .collect(Result.toResult());
      assertThat(values).hasValue(IntStream.range(0, 10_000).boxed().toList());

      var firstError =
          IntStream.range(0, 10_000)
              .parallel()
              .mapToObj(
                  i ->
                      i % 1000 == 999
                          ? Result.error("e" + i, Integer.class)
                          : Result.ok(i, String.class))
              .collect(Result.toResult());
      assertThat(firstError).hasError("e999");
    }

    @Test
    void matchesSequenceSemantics() {
      var inputs = List.of("1", "x", "3");
      var viaCollector =
          inputs.stream().map(ResultCollectorsTest::parse).collect(Result.toResult());
      var viaSequence = Result.sequence(inputs.stream().map(ResultCollectorsTest::parse).toList());
      assertThat(viaCollector).isEqualTo(viaSequence);
    }
  }

  @Nested
  class ToResultAll {

    @Test
    void collectsAllOkValues() {
      var result =
          Stream.of("1", "2").map(ResultCollectorsTest::parse).collect(Result.toResultAll());
      assertThat(result).hasValue(List.of(1, 2));
    }

    @Test
    void accumulatesEveryErrorInOrder() {
      var result =
          Stream.of("1", "x", "3", "y")
              .map(ResultCollectorsTest::parse)
              .collect(Result.toResultAll());
      assertThat(result).hasError(List.of("bad: x", "bad: y"));
    }

    @Test
    void emptyStreamIsOkEmptyList() {
      var result = Stream.<Result<Integer, String>>empty().collect(Result.toResultAll());
      assertThat(result).hasValue(List.of());
    }

    @Test
    void parallelStreamPreservesErrorOrder() {
      var result =
          IntStream.range(0, 10_000)
              .parallel()
              .mapToObj(
                  i ->
                      i % 100 == 0
                          ? Result.error("e" + i, Integer.class)
                          : Result.ok(i, String.class))
              .collect(Result.toResultAll());
      var expected = IntStream.range(0, 100).mapToObj(i -> "e" + i * 100).toList();
      assertThat(result).hasError(expected);
    }

    @Test
    void matchesSequenceAllSemantics() {
      var inputs = List.of("1", "x", "3", "y");
      var viaCollector =
          inputs.stream().map(ResultCollectorsTest::parse).collect(Result.toResultAll());
      var viaSequenceAll =
          Result.sequenceAll(inputs.stream().map(ResultCollectorsTest::parse).toList());
      assertThat(viaCollector).isEqualTo(viaSequenceAll);
    }
  }

  @Nested
  class ToPartitioned {

    @Test
    void separatesValuesAndErrorsInOrder() {
      var p =
          Stream.of("1", "x", "3", "y")
              .map(ResultCollectorsTest::parse)
              .collect(Result.toPartitioned());
      assertThat(p.values()).containsExactly(1, 3);
      assertThat(p.errors()).containsExactly("bad: x", "bad: y");
      assertThat(p.allOk()).isFalse();
    }

    @Test
    void emptyStreamGivesEmptyPartition() {
      var p = Stream.<Result<Integer, String>>empty().collect(Result.toPartitioned());
      assertThat(p.allOk()).isTrue();
      assertThat(p.allErrors()).isTrue();
    }

    @Test
    void listsAreImmutable() {
      var p = Stream.of("1").map(ResultCollectorsTest::parse).collect(Result.toPartitioned());
      assertThatThrownBy(() -> p.values().add(2)).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> p.errors().add("e"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void matchesPartitionSemantics() {
      var inputs = List.of("1", "x", "3");
      var viaCollector =
          inputs.stream().map(ResultCollectorsTest::parse).collect(Result.toPartitioned());
      var viaPartition =
          Result.partition(inputs.stream().map(ResultCollectorsTest::parse).toList());
      assertThat(viaCollector).isEqualTo(viaPartition);
    }
  }
}
