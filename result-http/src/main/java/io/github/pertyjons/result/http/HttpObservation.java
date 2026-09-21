package io.github.pertyjons.result.http;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Metadata for one completed send, delivered to {@link ResultHttpClient#observe}.
 * Contains no URL path, query, headers, body, error message or exception object.
 * The application controls the operation name and should keep it free of sensitive data.
 *
 * <p>Duration includes response handling and JSON decoding. For streaming body handlers it ends
 * when the response becomes available, not when the application finishes consuming the stream.
 *
 * @param method the HTTP method
 * @param operation the explicit operation name, or the HTTP method when no name was set
 * @param host the original request's host, excluding credentials and port
 * @param duration elapsed time measured with a monotonic clock, excluding observer execution
 * @param statusCode the response status if headers arrived, otherwise empty
 * @param outcome how the send finished
 * @param exceptionType the exception's class name, if there was an exception; never its message
 */
public record HttpObservation(
    String method,
    String operation,
    String host,
    Duration duration,
    OptionalInt statusCode,
    Outcome outcome,
    Optional<String> exceptionType) {

  /** Validates non-null components and a non-negative duration. */
  public HttpObservation {
    requireNonNull(method, "method must not be null");
    requireNonNull(operation, "operation must not be null");
    requireNonNull(host, "host must not be null");
    requireNonNull(duration, "duration must not be null");
    requireNonNull(statusCode, "statusCode must not be null");
    requireNonNull(outcome, "outcome must not be null");
    requireNonNull(exceptionType, "exceptionType must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative");
    }
  }

  /** The final outcome, including failures outside the Result error channel. */
  public enum Outcome {
    /** The send returned Ok, including successful decoding when requested. */
    SUCCESS,
    /** The status predicate rejected the response. */
    STATUS,
    /** A network or body I/O failure occurred. */
    TRANSPORT,
    /** The request or connection timed out. */
    TIMEOUT,
    /** The synchronous caller was interrupted. */
    INTERRUPTED,
    /** JSON decoding returned a CodecError. */
    DECODE,
    /** The asynchronous future was cancelled, or the exchange failed with cancellation. */
    CANCELLED,
    /** A programming exception or Error escaped the send. */
    EXCEPTION
  }
}
