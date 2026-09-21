package io.github.pertyjons.result;

import io.github.pertyjons.result.Result.Err;
import io.github.pertyjons.result.Result.Ok;
import io.github.pertyjons.result.Result.Partitioned;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collector;
import org.jspecify.annotations.Nullable;

/**
 * Accumulator implementations behind {@link Result#toResult()}, {@link Result#toResultAll()} and
 * {@link Result#toPartitioned()}. Package-private: interfaces cannot declare private nested
 * classes, and the accumulators are an implementation detail hidden behind the {@code ?} in the
 * collector signatures.
 */
final class ResultCollectors {

  private ResultCollectors() {}

  static <T, E> Collector<Result<T, E>, ?, Result<List<T>, E>> toResult() {
    return Collector.of(
        FirstError<T, E>::new, FirstError::add, FirstError::merge, FirstError::finish);
  }

  static <T, E> Collector<Result<T, E>, ?, Result<List<T>, List<E>>> toResultAll() {
    return Collector.of(
        Separated<T, E>::new, Separated::add, Separated::merge, Separated::finishAll);
  }

  static <T, E> Collector<Result<T, E>, ?, Partitioned<T, E>> toPartitioned() {
    return Collector.of(
        Separated<T, E>::new, Separated::add, Separated::merge, Separated::finishPartitioned);
  }

  /** Keeps values until the first error is seen, then ignores everything after it. */
  private static final class FirstError<T, E> {
    private final List<T> values = new ArrayList<>();
    private @Nullable E error;

    void add(Result<T, E> result) {
      if (error != null) {
        return;
      }
      switch (result) {
        case Ok(var value) -> values.add(value);
        case Err(var e) -> error = e;
      }
    }

    // Left-to-right merge preserves encounter order, so the first error in the stream wins.
    FirstError<T, E> merge(FirstError<T, E> right) {
      if (error != null) {
        return this;
      }
      if (right.error != null) {
        error = right.error;
        return this;
      }
      values.addAll(right.values);
      return this;
    }

    Result<List<T>, E> finish() {
      return error != null ? Result.error(error) : Result.ok(List.copyOf(values));
    }
  }

  /** Keeps every value and every error. */
  private static final class Separated<T, E> {
    private final List<T> values = new ArrayList<>();
    private final List<E> errors = new ArrayList<>();

    void add(Result<T, E> result) {
      switch (result) {
        case Ok(var value) -> values.add(value);
        case Err(var error) -> errors.add(error);
      }
    }

    Separated<T, E> merge(Separated<T, E> right) {
      values.addAll(right.values);
      errors.addAll(right.errors);
      return this;
    }

    Result<List<T>, List<E>> finishAll() {
      return errors.isEmpty() ? Result.ok(List.copyOf(values)) : Result.error(List.copyOf(errors));
    }

    Partitioned<T, E> finishPartitioned() {
      return new Partitioned<>(values, errors);
    }
  }
}
