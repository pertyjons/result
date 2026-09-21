package io.github.pertyjons.result.http;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

/**
 * Why a {@link JsonCodec} could not convert between JSON and an object. A cause is present only
 * when an underlying library actually threw; a codec that detects a problem itself reports it with
 * a message alone, so no exception is ever constructed just to be carried as a value.
 *
 * <pre>{@code
 * CodecError.of("not a Person: " + json)        // a codec's own finding, no exception involved
 * CodecError.of(jacksonException)               // a library's exception, kept as the cause
 * }</pre>
 *
 * @param message what went wrong, never null
 * @param cause the exception a library threw, if any
 */
public record CodecError(String message, Optional<Throwable> cause) {

  /** Validates that both components are non-null. */
  public CodecError {
    requireNonNull(message, "CodecError message must not be null");
    requireNonNull(cause, "CodecError cause must not be null");
  }

  /**
   * An error the codec found itself, with no exception behind it.
   *
   * @param message what went wrong, must not be null
   * @return the error
   */
  public static CodecError of(String message) {
    return new CodecError(message, Optional.empty());
  }

  /**
   * An error caused by an exception a library threw. The message is the exception's message, or its
   * class name if it has none.
   *
   * @param thrown the exception, must not be null
   * @return the error
   */
  public static CodecError of(Throwable thrown) {
    requireNonNull(thrown, "thrown must not be null");
    String message = thrown.getMessage();
    return new CodecError(
        message == null ? thrown.getClass().getName() : message, Optional.of(thrown));
  }

  @Override
  public String toString() {
    return cause.map(c -> message + " (" + c.getClass().getName() + ")").orElse(message);
  }
}
