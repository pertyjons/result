package io.github.pertyjons.result.http;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/**
 * The ways an HTTP exchange made through {@link ResultHttpClient} can fail at runtime. The type is
 * sealed so that a {@code switch} over it is exhaustive:
 *
 * <pre>{@code
 * String message = switch (error) {
 *   case Transport(var cause)      -> "network: " + cause.getMessage();
 *   case Timeout(var cause)        -> "timed out";
 *   case Interrupted()             -> "interrupted";
 *   case Status s                  -> "HTTP " + s.code() + ": " + s.body();
 *   case Decode(var error, var r)  -> "unreadable body: " + error.message();
 * };
 * }</pre>
 *
 * <p>{@link Status} and {@link Decode} both carry the response, always with its body read as text
 * regardless of which body handler the caller asked for, and share the {@link WithResponse}
 * interface so the two can be handled together:
 *
 * <pre>{@code
 * case WithResponse r -> log.warn("{} {} -> {}", r.code(), r.uri(), r.body());
 * }</pre>
 *
 * <p>Only conditions that depend on the network or the server become an {@code HttpError}.
 * Programming errors such as a malformed URL, a mismatched path template or a missing {@link
 * JsonCodec} throw {@link IllegalArgumentException} or {@link IllegalStateException} instead, in
 * the same way that {@link io.github.pertyjons.result.Result} rejects {@code null}.
 */
public sealed interface HttpError {

  /**
   * The request never produced a response: DNS failure, connection refused or reset, TLS handshake
   * failure, or a stream error while reading the body.
   *
   * @param cause the underlying exception, never null
   */
  record Transport(IOException cause) implements HttpError {
    /** Validates that {@code cause} is non-null. */
    public Transport {
      requireNonNull(cause, "Transport cause must not be null");
    }
  }

  /**
   * The exchange, request or connection attempt exceeded its limit. The total deadline set through
   * {@link ResultHttpClient#deadline(java.time.Duration)}, the response-header timeout set through
   * {@link ResultHttpClient#timeout(java.time.Duration)} and the connect timeout configured
   * on the underlying {@link java.net.http.HttpClient} end up here, since {@link
   * java.net.http.HttpConnectTimeoutException} extends {@link HttpTimeoutException}.
   *
   * @param cause the underlying exception, never null
   */
  record Timeout(HttpTimeoutException cause) implements HttpError {
    /** Validates that {@code cause} is non-null. */
    public Timeout {
      requireNonNull(cause, "Timeout cause must not be null");
    }
  }

  /**
   * The calling thread was interrupted while waiting for the response. The thread's interrupt flag
   * has been restored before this error is returned.
   */
  record Interrupted() implements HttpError {
    /** The single shared instance. All {@code Interrupted} values are equal. */
    public static final Interrupted INSTANCE = new Interrupted();
  }

  /**
   * An error for which a response was received: {@link Status} and {@link Decode}. The body is
   * always available as text, so both can be logged or mapped to a domain error in one branch.
   */
  sealed interface WithResponse extends HttpError permits Status, Decode {

    /**
     * The response, with its body read as text.
     *
     * @return the response, never null
     */
    HttpResponse<String> response();

    /**
     * The HTTP status code.
     *
     * @return the status code, for example 404
     */
    default int code() {
      return response().statusCode();
    }

    /**
     * The URI the response was received from, after any redirects.
     *
     * @return the request URI
     */
    default URI uri() {
      return response().uri();
    }

    /**
     * The response headers.
     *
     * @return the headers, never null
     */
    default HttpHeaders headers() {
      return response().headers();
    }

    /**
     * The response body as text, decoded with the charset from {@code Content-Type} or UTF-8. Empty
     * if the server sent no body.
     *
     * @return the body, never null
     */
    default String body() {
      return response().body();
    }
  }

  /**
   * A response arrived but its status code was not accepted by the client's or the request's {@code
   * successWhen} predicate. The body is read as text whichever body handler the caller asked for,
   * including {@code send()}, up to the configured byte limit. Truncated text may end with a
   * replacement character if the byte limit splits a multibyte character.
   *
   * @param response the rejected response, never null
   * @param bodyTruncated whether the server sent more bytes than the retained prefix
   */
  record Status(HttpResponse<String> response, boolean bodyTruncated) implements WithResponse {
    /**
     * Creates a status error with a complete body.
     *
     * @param response the rejected response, must not be null
     */
    public Status(HttpResponse<String> response) {
      this(response, false);
    }

    /** Validates that {@code response} is non-null. */
    public Status {
      requireNonNull(response, "Status response must not be null");
    }
  }

  /**
   * The response was accepted but its body could not be converted to the requested type by the
   * configured {@link JsonCodec}. The response is kept with its raw text body so that what could
   * not be parsed can be logged; the library's exception, if there was one, is in {@code
   * error().cause()}.
   *
   * @param error what the codec reported, never null
   * @param response the response whose body failed to decode, never null
   */
  record Decode(CodecError error, HttpResponse<String> response) implements WithResponse {
    /** Validates that both components are non-null. */
    public Decode {
      requireNonNull(error, "Decode error must not be null");
      requireNonNull(response, "Decode response must not be null");
    }
  }
}
