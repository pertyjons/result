package io.github.pertyjons.result.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.Result.Unit;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fluent, immutable adapter over {@link HttpClient} whose every exchange ends in a {@link
 * Result}. The API is used in three phases, and each phase only exposes the methods that make sense
 * in it:
 *
 * <ol>
 *   <li><b>Configure a client.</b> Start from {@link #DEFAULT} or one of the {@code create}
 *       factories and derive specialised instances with {@link #baseUrl}, {@link #header}, {@link
 *       #timeout}, {@link #deadline(Duration)}, {@link #maxErrorBodyBytes(int)}, {@link
 *       #successWhen}, {@link #codec}, {@link #observe} and {@link #client}. Every such call
 *       returns a new instance; the original is untouched, so a shared constant can be refined per
 *       team or per call site without locking.
 *   <li><b>Build a request.</b> {@link #get}, {@link #delete} and {@link #head} return a {@link
 *       Request}. {@link #post}, {@link #put} and {@link #patch} return a {@link NeedsBody}, whose
 *       only methods choose the body, so the chain cannot continue until that decision is made.
 *   <li><b>Send and handle.</b> {@link Request#send(BodyHandler)} and its overloads are the only
 *       methods with side effects. They return {@code Result<..., HttpError>}, after which the
 *       chain continues with {@code map}, {@code flatMap}, {@code mapError} and friends.
 * </ol>
 *
 * <pre>{@code
 * static final ResultHttpClient API = ResultHttpClient.DEFAULT
 *     .baseUrl("https://api.example.com")
 *     .header("Accept", "application/json")
 *     .timeout(Duration.ofSeconds(5));
 *
 * Result<Person, HttpError> person = API
 *     .get("/people/{id}", 42)
 *     .send(Person.class);
 *
 * Result<HttpResponse<Unit>, HttpError> created = API
 *     .post("/people")
 *     .json(new Person("Ada"))
 *     .send();
 * }</pre>
 *
 * <p><b>What is an error and what is a bug.</b> Anything that depends on the network or the server
 * becomes an {@link HttpError}: connection failures, timeouts, interruption, rejected status codes
 * and undecodable bodies. Anything the programmer controls throws instead: a relative path without
 * a base URL, a path template whose placeholder count does not match its arguments, a JSON method
 * without a configured {@link JsonCodec}.
 *
 * <p><b>Division of responsibility with {@link HttpClient}.</b> Settings that the JDK client owns,
 * such as connect timeout, redirects, proxy, SSL context, executor and HTTP version, are configured
 * on the {@code HttpClient} and passed in through {@link #create(HttpClient)} or {@link
 * #client(HttpClient)}. Settings about how requests are built and responses interpreted live here.
 * Nothing is configured in two places.
 */
public final class ResultHttpClient {

  private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);
  private static final int DEFAULT_MAX_ERROR_BODY_BYTES = 64 * 1024;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  private static final IntPredicate DEFAULT_SUCCESS = status -> status >= 200 && status < 300;
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^/{}]*}");
  private static final Pattern ABSOLUTE_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

  /**
   * A shared client built on {@link HttpClient#newHttpClient()}: no base URL, no default headers, a
   * 10 second response-header timeout, 30 second total deadline, 64 KiB error-body limit, 2xx
   * counted as success, and whichever {@link JsonCodec} is registered through {@link
   * ServiceLoader}, if any. Derive application-specific clients from it:
   *
   * <pre>{@code
   * static final ResultHttpClient API = ResultHttpClient.DEFAULT.baseUrl("https://api.example.com");
   * }</pre>
   */
  public static final ResultHttpClient DEFAULT = create();

  private final HttpClient client;
  private final Optional<URI> baseUrl;
  private final Map<String, String> headers;
  private final Duration timeout;
  private final IntPredicate successWhen;
  private final Optional<JsonCodec> codec;
  private final Optional<Consumer<HttpObservation>> observer;
  private final Duration deadline;
  private final int maxErrorBodyBytes;

  private ResultHttpClient(
      HttpClient client,
      Optional<URI> baseUrl,
      Map<String, String> headers,
      Duration timeout,
      IntPredicate successWhen,
      Optional<JsonCodec> codec,
      Optional<Consumer<HttpObservation>> observer,
      Duration deadline,
      int maxErrorBodyBytes) {
    this.client = client;
    this.baseUrl = baseUrl;
    this.headers = headers;
    this.timeout = timeout;
    this.successWhen = successWhen;
    this.codec = codec;
    this.observer = observer;
    this.deadline = deadline;
    this.maxErrorBodyBytes = maxErrorBodyBytes;
  }

  // --- Factories ------------------------------------------------------------------------------

  /**
   * Creates a client over {@link HttpClient#newHttpClient()} with the defaults described on {@link
   * #DEFAULT}. Looks up a {@link JsonCodec} through {@link ServiceLoader}.
   *
   * @return a new client
   */
  public static ResultHttpClient create() {
    return create(HttpClient.newHttpClient());
  }

  /**
   * Creates a client over an {@link HttpClient} the application has already configured, for example
   * with a connect timeout, redirect policy, proxy, SSL context or executor. Looks up a {@link
   * JsonCodec} through {@link ServiceLoader}.
   *
   * @param client the JDK client to send requests with, must not be null
   * @return a new client
   */
  public static ResultHttpClient create(HttpClient client) {
    requireNonNull(client, "client must not be null");
    return new ResultHttpClient(
        client,
        Optional.empty(),
        emptyHeaders(),
        DEFAULT_TIMEOUT,
        DEFAULT_SUCCESS,
        discoverCodec(),
        Optional.empty(),
        DEFAULT_DEADLINE,
        DEFAULT_MAX_ERROR_BODY_BYTES);
  }

  /**
   * Creates a client over a new {@link HttpClient} configured inline.
   *
   * <pre>{@code
   * ResultHttpClient.create(b -> b.connectTimeout(Duration.ofSeconds(2)).followRedirects(Redirect.NORMAL))
   * }</pre>
   *
   * @param configure receives the {@link HttpClient.Builder} before it is built, must not be null
   * @return a new client
   */
  public static ResultHttpClient create(Consumer<HttpClient.Builder> configure) {
    requireNonNull(configure, "configure must not be null");
    HttpClient.Builder builder = HttpClient.newBuilder();
    configure.accept(builder);
    return create(builder.build());
  }

  private static Optional<JsonCodec> discoverCodec() {
    return ServiceLoader.load(JsonCodec.class).findFirst();
  }

  private static Map<String, String> emptyHeaders() {
    return Collections.unmodifiableMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
  }

  // --- Configuration --- every method returns a new instance ----------------------------------

  /**
   * Returns a copy that sends requests through {@code client}. All other settings are kept.
   *
   * @param client the JDK client, must not be null
   * @return a new client
   */
  public ResultHttpClient client(HttpClient client) {
    requireNonNull(client, "client must not be null");
    return new ResultHttpClient(client, baseUrl, headers, timeout, successWhen, codec, observer, deadline, maxErrorBodyBytes);
  }

  /**
   * Returns a copy that resolves relative paths against {@code baseUrl}. Trailing slashes on the
   * base and leading slashes on paths are normalised, so {@code "https://h/api/"} and {@code
   * "https://h/api"} behave the same. Absolute URLs passed to {@link #get} and friends ignore the
   * base.
   *
   * @param baseUrl an absolute URL such as {@code https://api.example.com/v1}, must not be null
   * @return a new client
   * @throws IllegalArgumentException if {@code baseUrl} is not an absolute URL or contains a query
   *     or fragment
   */
  public ResultHttpClient baseUrl(String baseUrl) {
    requireNonNull(baseUrl, "baseUrl must not be null");
    if (!ABSOLUTE_URL.matcher(baseUrl).matches()) {
      throw new IllegalArgumentException(
          "baseUrl must be absolute, e.g. https://host/path: " + baseUrl);
    }
    URI parsed = URI.create(baseUrl);
    if (parsed.getRawQuery() != null) {
      throw new IllegalArgumentException("baseUrl must not contain a query: " + baseUrl);
    }
    if (parsed.getRawFragment() != null) {
      throw new IllegalArgumentException("baseUrl must not contain a fragment: " + baseUrl);
    }
    return new ResultHttpClient(
        client,
        Optional.of(URI.create(stripTrailingSlash(baseUrl))),
        headers,
        timeout,
        successWhen,
        codec,
        observer,
        deadline,
        maxErrorBodyBytes);
  }

  /**
   * Returns a copy with a default header applied to every request. Header names are
   * case-insensitive; a header with the same name is replaced, so a derived client can override
   * what it inherited. A header set on an individual {@link Request} takes precedence over this.
   *
   * @param name the header name, must not be null
   * @param value the header value, must not be null
   * @return a new client
   */
  public ResultHttpClient header(String name, String value) {
    requireNonNull(name, "name must not be null");
    requireNonNull(value, "value must not be null");
    return new ResultHttpClient(
        client, baseUrl, with(headers, name, value), timeout, successWhen, codec, observer, deadline, maxErrorBodyBytes);
  }

  /**
   * Returns a copy without the named default header. Has no effect if the header is not set.
   *
   * @param name the header name, case-insensitive, must not be null
   * @return a new client
   */
  public ResultHttpClient withoutHeader(String name) {
    requireNonNull(name, "name must not be null");
    var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    copy.putAll(headers);
    copy.remove(name);
    return new ResultHttpClient(
        client, baseUrl, Collections.unmodifiableMap(copy), timeout, successWhen, codec, observer, deadline, maxErrorBodyBytes);
  }

  /**
   * Returns a copy with a different request timeout. This is the timeout for receiving the response
   * headers, as defined by {@link HttpRequest.Builder#timeout(Duration)}. The connect timeout
   * belongs to the underlying {@link HttpClient}.
   *
   * @param timeout the timeout, must be positive and not null
   * @return a new client
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public ResultHttpClient timeout(Duration timeout) {
    return new ResultHttpClient(
        client, baseUrl, headers, requirePositive(timeout), successWhen, codec, observer, deadline, maxErrorBodyBytes);
  }

  /**
   * Returns a copy that treats a response as successful when {@code successWhen} accepts its status
   * code. Any other status becomes {@link HttpError.Status}. The default accepts 2xx. Can be
   * overridden for one call with {@link Request#successWhen(IntPredicate)}.
   *
   * @param successWhen the predicate on the status code, must not be null
   * @return a new client
   */
  public ResultHttpClient successWhen(IntPredicate successWhen) {
    requireNonNull(successWhen, "successWhen must not be null");
    return new ResultHttpClient(client, baseUrl, headers, timeout, successWhen, codec, observer, deadline, maxErrorBodyBytes);
  }

  /**
   * Returns a copy that uses {@code codec} for {@link NeedsBody#json(Object)}, {@link
   * Request#send(Class)} and {@link Request#send(TypeRef)}. Use this to supply a mapper that the
   * rest of the application has configured instead of the one discovered through {@link
   * ServiceLoader}.
   *
   * @param codec the codec, must not be null
   * @return a new client
   */
  public ResultHttpClient codec(JsonCodec codec) {
    requireNonNull(codec, "codec must not be null");
    return new ResultHttpClient(client, baseUrl, headers, timeout, successWhen, Optional.of(codec), observer, deadline, maxErrorBodyBytes);
  }

  /**
   * Returns a copy with a completion observer for logging or metrics. Replaces any previous
   * observer; clients without one are silent. The callback receives exactly one metadata event
   * per send that passes argument, codec and request validation, including JSON decoding,
   * expected errors, exceptional failure, interruption and asynchronous cancellation.
   *
   * <p>Callbacks run inline and must be fast and thread-safe. Synchronous callbacks run before
   * return; asynchronous callbacks may run on a completion or cancellation thread, or immediately
   * on the registering thread. Waiting for an asynchronous result does not wait for its observer.
   * Thread-local logging context is not propagated. Runtime exceptions from the observer are
   * ignored so they cannot change the HTTP outcome. This callback is not a durable audit sink.
   *
   * @param observer the observer, must not be null
   * @return a new client
   */
  public ResultHttpClient observe(Consumer<HttpObservation> observer) {
    requireNonNull(observer, "observer must not be null");
    return new ResultHttpClient(
        client, baseUrl, headers, timeout, successWhen, codec, Optional.of(observer), deadline, maxErrorBodyBytes);
  }

  /**
   * Sets the total deadline for an exchange, including body handling and JSON decoding.
   * Defaults to 30 seconds. For streaming handlers the deadline ends when the response is
   * handed to the caller; subsequent stream consumption is the caller's responsibility.
   * On expiry the result is Timeout and in-flight work is interrupted on a best-effort basis.
   *
   * @param deadline the total duration, must be positive and not null
   * @return a new client
   * @throws IllegalArgumentException if {@code deadline} is zero or negative
   */
  public ResultHttpClient deadline(Duration deadline) {
    return new ResultHttpClient(client, baseUrl, headers, timeout, successWhen, codec, observer,
        requirePositive(deadline, "deadline"), maxErrorBodyBytes);
  }

  /**
   * Limits the retained prefix of rejected response bodies in bytes (default 65536).
   * Further reading is cancelled once truncation is detected. Zero retains no body text.
   *
   * @param bytes the byte limit, must not be negative
   * @return a new client
   * @throws IllegalArgumentException if {@code bytes} is negative
   */
  public ResultHttpClient maxErrorBodyBytes(int bytes) {
    return new ResultHttpClient(client, baseUrl, headers, timeout, successWhen, codec, observer,
        deadline, requireNonNegative(bytes));
  }

  /**
   * Returns the total deadline applied to new requests.
   *
   * @return the total deadline
   */
  public Duration deadline() {
    return deadline;
  }

  /**
   * Returns the error-body byte limit applied to new requests.
   *
   * @return the maximum retained error body bytes
   */
  public int maxErrorBodyBytes() {
    return maxErrorBodyBytes;
  }

  private static int requireNonNegative(int bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("maxErrorBodyBytes must not be negative");
    }
    return bytes;
  }

  // --- Accessors ------------------------------------------------------------------------------

  /**
   * The underlying JDK client, for callers that need to bypass this adapter.
   *
   * @return the client, never null
   */
  public HttpClient client() {
    return client;
  }

  /**
   * The base URL relative paths are resolved against, if one is set.
   *
   * @return the base URL, or empty
   */
  public Optional<URI> baseUrl() {
    return baseUrl;
  }

  /**
   * The default headers applied to every request, keyed case-insensitively.
   *
   * @return an unmodifiable map, never null
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * The request timeout.
   *
   * @return the timeout, never null
   */
  public Duration timeout() {
    return timeout;
  }

  /**
   * The JSON codec, if one was discovered or configured.
   *
   * @return the codec, or empty
   */
  public Optional<JsonCodec> codec() {
    return codec;
  }

  // --- Request entry points -------------------------------------------------------------------

  /**
   * Starts a {@code GET} request.
   *
   * @param path a path relative to the base URL, or an absolute URL; may contain {@code {name}}
   *     placeholders that are filled positionally from {@code pathArgs}
   * @param pathArgs values for the placeholders, URL-encoded with {@code toString()}
   * @return the request, ready to send or refine
   * @throws IllegalArgumentException if the placeholder count does not match {@code pathArgs}, or
   *     the path is relative and no base URL is set
   */
  public Request get(String path, Object... pathArgs) {
    return new RequestSpec(this, "GET", resolve(path, pathArgs), BodyPublishers.noBody());
  }

  /**
   * Starts a {@code DELETE} request. See {@link #get(String, Object...)} for path handling.
   *
   * @param path the path or absolute URL, with optional placeholders
   * @param pathArgs values for the placeholders
   * @return the request, ready to send or refine
   * @throws IllegalArgumentException as for {@link #get(String, Object...)}
   */
  public Request delete(String path, Object... pathArgs) {
    return new RequestSpec(this, "DELETE", resolve(path, pathArgs), BodyPublishers.noBody());
  }

  /**
   * Starts a {@code HEAD} request. See {@link #get(String, Object...)} for path handling.
   *
   * @param path the path or absolute URL, with optional placeholders
   * @param pathArgs values for the placeholders
   * @return the request, ready to send or refine
   * @throws IllegalArgumentException as for {@link #get(String, Object...)}
   */
  public Request head(String path, Object... pathArgs) {
    return new RequestSpec(this, "HEAD", resolve(path, pathArgs), BodyPublishers.noBody());
  }

  /**
   * Starts a {@code POST} request. The body must be chosen next. See {@link #get(String,
   * Object...)} for path handling.
   *
   * @param path the path or absolute URL, with optional placeholders
   * @param pathArgs values for the placeholders
   * @return the body step
   * @throws IllegalArgumentException as for {@link #get(String, Object...)}
   */
  public NeedsBody post(String path, Object... pathArgs) {
    return new BodyStep(this, "POST", resolve(path, pathArgs));
  }

  /**
   * Starts a {@code PUT} request. The body must be chosen next. See {@link #get(String, Object...)}
   * for path handling.
   *
   * @param path the path or absolute URL, with optional placeholders
   * @param pathArgs values for the placeholders
   * @return the body step
   * @throws IllegalArgumentException as for {@link #get(String, Object...)}
   */
  public NeedsBody put(String path, Object... pathArgs) {
    return new BodyStep(this, "PUT", resolve(path, pathArgs));
  }

  /**
   * Starts a {@code PATCH} request. The body must be chosen next. See {@link #get(String,
   * Object...)} for path handling.
   *
   * @param path the path or absolute URL, with optional placeholders
   * @param pathArgs values for the placeholders
   * @return the body step
   * @throws IllegalArgumentException as for {@link #get(String, Object...)}
   */
  public NeedsBody patch(String path, Object... pathArgs) {
    return new BodyStep(this, "PATCH", resolve(path, pathArgs));
  }

  /**
   * Starts a request with any method, for example {@code OPTIONS}. The body must be chosen next;
   * use {@link NeedsBody#noBody()} for methods without one.
   *
   * @param method the HTTP method, must not be null
   * @param path the path or absolute URL, with optional placeholders
   * @param pathArgs values for the placeholders
   * @return the body step
   * @throws IllegalArgumentException as for {@link #get(String, Object...)}
   */
  public NeedsBody method(String method, String path, Object... pathArgs) {
    requireNonNull(method, "method must not be null");
    return new BodyStep(this, method, resolve(path, pathArgs));
  }

  // --- URL building ---------------------------------------------------------------------------

  private String resolve(String path, Object... pathArgs) {
    requireNonNull(path, "path must not be null");
    requireNonNull(pathArgs, "pathArgs must not be null");
    String filled = fillTemplate(path, pathArgs);
    if (ABSOLUTE_URL.matcher(filled).matches()) {
      return filled;
    }
    URI base =
        baseUrl.orElseThrow(
            () ->
                new IllegalArgumentException(
                    "path is relative and no baseUrl is configured: " + path));
    return filled.isEmpty() ? base.toString() : base + "/" + stripLeadingSlash(filled);
  }

  private static String fillTemplate(String template, Object[] args) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    int index = 0;
    while (matcher.find()) {
      if (index >= args.length) {
        throw new IllegalArgumentException(
            "path template has more placeholders than arguments: " + template);
      }
      Object arg = requireNonNull(args[index++], "pathArgs must not contain null");
      matcher.appendReplacement(out, Matcher.quoteReplacement(encodeSegment(arg.toString())));
    }
    if (index < args.length) {
      throw new IllegalArgumentException(
          "path template has fewer placeholders than arguments: " + template);
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String encodeSegment(String value) {
    return URLEncoder.encode(value, UTF_8).replace("+", "%20");
  }

  private static String encodeQuery(String value) {
    return URLEncoder.encode(value, UTF_8);
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String stripLeadingSlash(String s) {
    return s.startsWith("/") ? s.substring(1) : s;
  }

  private static Map<String, String> with(Map<String, String> headers, String name, String value) {
    var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    copy.putAll(headers);
    copy.put(name, value);
    return Collections.unmodifiableMap(copy);
  }

  private static Duration requirePositive(Duration timeout) {
    return requirePositive(timeout, "timeout");
  }

  private static Duration requirePositive(Duration duration, String name) {
    requireNonNull(duration, name + " must not be null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive: " + duration);
    }
    return duration;
  }

  private JsonCodec requireCodec() {
    return codec.orElseThrow(
        () ->
            new IllegalStateException(
                "No JsonCodec configured. Add result-http-jackson to the classpath or call"
                    + " codec(...)"));
  }

  // --- Steps ----------------------------------------------------------------------------------

  /**
   * The step after {@link #post}, {@link #put}, {@link #patch} and {@link #method}. Its only
   * methods choose the request body, so headers cannot be set and the request cannot be sent until
   * that choice is explicit.
   */
  public sealed interface NeedsBody permits BodyStep {

    /**
     * Uses a JDK body publisher as-is. No {@code Content-Type} is set.
     *
     * @param publisher the body, must not be null
     * @return the request, ready to send or refine
     */
    Request body(BodyPublisher publisher);

    /**
     * Sends {@code text} encoded as UTF-8 with the given {@code Content-Type}.
     *
     * @param text the body text, must not be null
     * @param contentType the media type, for example {@code text/plain}, must not be null
     * @return the request, ready to send or refine
     */
    Request body(String text, String contentType);

    /**
     * Serialises {@code value} with the configured {@link JsonCodec} and sends it as {@code
     * application/json}. This convenience method throws if encoding fails; use {@link
     * #tryJson(Object)} to keep the {@link CodecError} as a {@link Result}.
     *
     * @param value the object to serialise, must not be null
     * @return the request, ready to send or refine
     * @throws IllegalStateException if no codec is configured
     * @throws IllegalArgumentException if the codec cannot serialise {@code value}
     */
    Request json(Object value);

    /**
     * Attempts to serialise {@code value} with the configured {@link JsonCodec}. A successful
     * encoding produces a request with an {@code application/json} body; a codec failure remains an
     * {@link Result.Err} and no request is sent.
     *
     * @param value the object to serialise, must not be null
     * @return {@code Ok(request)} or {@code Err(codecError)}
     * @throws IllegalStateException if no codec is configured
     */
    Result<Request, CodecError> tryJson(Object value);

    /**
     * Sends no body. Makes the choice explicit rather than silent.
     *
     * @return the request, ready to send or refine
     */
    Request noBody();
  }

  /**
   * A request that is complete and can be sent. Query parameters, headers, timeout and success
   * predicate are optional refinements and may be given in any order. The {@code send} methods are
   * the only ones with side effects; after them the chain continues on {@link Result}.
   */
  public sealed interface Request permits RequestSpec {

    /**
     * Overrides the client's total deadline, including body handling and decoding. Streaming
     * consumption after the response is handed to the caller is not covered.
     *
     * @param deadline the total duration, must be positive and not null
     * @return a request with the deadline set
     * @throws IllegalArgumentException if {@code deadline} is zero or negative
     */
    Request deadline(Duration deadline);

    /**
     * Overrides the retained byte limit for rejected response bodies.
     *
     * @param bytes the byte limit, must not be negative
     * @return a request with the limit set
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    Request maxErrorBodyBytes(int bytes);

    /**
     * Names this operation in observations, for example {@code "get-user"} or
     * {@code "GET /users/{id}"}. Defaults to the HTTP method. Use a stable name without secrets
     * or individual identifiers; the name is passed to the observer unchanged.
     *
     * @param name the operation name, must not be blank or null
     * @return a request with the operation name set
     */
    Request operation(String name);

    /**
     * Appends a query parameter. Repeating a name appends another value, giving {@code ?a=1&a=2}.
     * Name and value are URL-encoded.
     *
     * @param name the parameter name, must not be null
     * @param value the parameter value, converted with {@code toString()}, must not be null
     * @return a request with the parameter added
     */
    Request query(String name, Object value);

    /**
     * Sets a header for this request only. Overrides a default header with the same name,
     * case-insensitively.
     *
     * @param name the header name, must not be null
     * @param value the header value, must not be null
     * @return a request with the header set
     */
    Request header(String name, String value);

    /**
     * Overrides the client's request timeout for this request.
     *
     * @param timeout the timeout, must be positive and not null
     * @return a request with the timeout set
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    Request timeout(Duration timeout);

    /**
     * Overrides the client's success predicate for this request, for example to accept 404 as a
     * legitimate answer.
     *
     * @param successWhen the predicate on the status code, must not be null
     * @return a request with the predicate set
     */
    Request successWhen(IntPredicate successWhen);

    /**
     * Builds the {@link HttpRequest} that {@code send} would submit, for inspection or for sending
     * through the JDK client directly.
     *
     * @return the request, never null
     */
    HttpRequest toHttpRequest();

    /**
     * Sends the request and reads the body with {@code handler}. The handler must produce a
     * non-null body; to ignore the body use {@link #send()}, which yields {@link Unit}, rather than
     * {@link BodyHandlers#discarding()}, which yields null.
     *
     * <p>Runs the exchange on a virtual thread and waits up to the total deadline. Programming
     * exceptions retain their original type. Interruption attempts to cancel the exchange and
     * restores the caller's interrupt flag. The first terminal outcome wins when completion,
     * interruption or deadline race. Streaming consumption after return is the caller's responsibility.
     *
     * @param <T> the body type
     * @param handler the JDK body handler, must not be null
     * @return {@code Ok(response)} if the status is accepted, otherwise an {@link HttpError}
     */
    <T> Result<HttpResponse<T>, HttpError> send(BodyHandler<T> handler);

    /**
     * Sends the request and ignores the body, which is reported as {@link Unit#INSTANCE}. Useful
     * for {@code HEAD}, {@code DELETE} and writes where only the status and headers matter. A
     * rejected status still has its body read as text into {@link HttpError.Status}.
     *
     * @return {@code Ok(response)} if the status is accepted, otherwise an {@link HttpError}
     */
    Result<HttpResponse<Unit>, HttpError> send();

    /**
     * Sends the request and decodes the body to {@code type} with the configured {@link JsonCodec}.
     * The status is checked before decoding, so an error body from the server becomes {@link
     * HttpError.Status} with its raw text rather than a decoding failure.
     *
     * @param <T> the body type
     * @param type the class to decode to, must not be null
     * @return {@code Ok(value)}, or an {@link HttpError}; {@link HttpError.Decode} if the body
     *     cannot be converted
     * @throws IllegalStateException if no codec is configured
     */
    <T> Result<T, HttpError> send(Class<T> type);

    /**
     * Sends the request and decodes the body to the generic type captured by {@code type}.
     *
     * @param <T> the body type
     * @param type the captured type, must not be null
     * @return as for {@link #send(Class)}
     * @throws IllegalStateException if no codec is configured
     */
    <T> Result<T, HttpError> send(TypeRef<T> type);

    /**
     * Asynchronous form of {@link #send(BodyHandler)}. The future completes normally with an {@code
     * Err} for every {@link HttpError}; it only completes exceptionally if it is cancelled or the
     * exchange fails with something that is not an {@link IOException}. The exchange and JSON
     * decoding run on a virtual thread so the total deadline can interrupt them. Argument, codec
     * and request validation errors still throw before the future is returned.
     *
     * @param <T> the body type
     * @param handler the JDK body handler, must not be null
     * @return a future holding the result
     */
    <T> CompletableFuture<Result<HttpResponse<T>, HttpError>> sendAsync(BodyHandler<T> handler);

    /**
     * Asynchronous form of {@link #send()}.
     *
     * @return a future holding the result
     */
    CompletableFuture<Result<HttpResponse<Unit>, HttpError>> sendAsync();

    /**
     * Asynchronous form of {@link #send(Class)}.
     *
     * @param <T> the body type
     * @param type the class to decode to, must not be null
     * @return a future holding the result
     * @throws IllegalStateException if no codec is configured
     */
    <T> CompletableFuture<Result<T, HttpError>> sendAsync(Class<T> type);

    /**
     * Asynchronous form of {@link #send(TypeRef)}.
     *
     * @param <T> the body type
     * @param type the captured type, must not be null
     * @return a future holding the result
     * @throws IllegalStateException if no codec is configured
     */
    <T> CompletableFuture<Result<T, HttpError>> sendAsync(TypeRef<T> type);
  }

  private static final class BodyStep implements NeedsBody {
    private final ResultHttpClient client;
    private final String method;
    private final String url;

    private BodyStep(ResultHttpClient client, String method, String url) {
      this.client = client;
      this.method = method;
      this.url = url;
    }

    @Override
    public Request body(BodyPublisher publisher) {
      requireNonNull(publisher, "publisher must not be null");
      return new RequestSpec(client, method, url, publisher);
    }

    @Override
    public Request body(String text, String contentType) {
      requireNonNull(text, "text must not be null");
      requireNonNull(contentType, "contentType must not be null");
      return new RequestSpec(client, method, url, BodyPublishers.ofString(text, UTF_8))
          .header("Content-Type", contentType);
    }

    @Override
    public Request json(Object value) {
      requireNonNull(value, "value must not be null");
      return tryJson(value)
          .orElseThrow(
              e -> {
                String message =
                    "Could not encode " + value.getClass().getName() + ": " + e.message();
                return e.cause()
                    .map(cause -> new IllegalArgumentException(message, cause))
                    .orElseGet(() -> new IllegalArgumentException(message));
              });
    }

    @Override
    public Result<Request, CodecError> tryJson(Object value) {
      requireNonNull(value, "value must not be null");
      return client.requireCodec().encode(value).map(json -> body(json, "application/json"));
    }

    @Override
    public Request noBody() {
      return new RequestSpec(client, method, url, BodyPublishers.noBody());
    }
  }

  private static final class RequestSpec implements Request {
    private final ResultHttpClient client;
    private final String method;
    private final String operation;
    private final String url;
    private final BodyPublisher body;
    private final List<String> query;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final IntPredicate successWhen;
    private final Duration deadline;
    private final int maxErrorBodyBytes;

    private RequestSpec(ResultHttpClient client, String method, String url, BodyPublisher body) {
      this(
          client, method, url, body, List.of(), client.headers, client.timeout, client.successWhen, method,
          client.deadline, client.maxErrorBodyBytes);
    }

    private RequestSpec(
        ResultHttpClient client,
        String method,
        String url,
        BodyPublisher body,
        List<String> query,
        Map<String, String> headers,
        Duration timeout,
        IntPredicate successWhen,
        String operation,
        Duration deadline,
        int maxErrorBodyBytes) {
      this.client = client;
      this.method = method;
      this.operation = operation;
      this.deadline = deadline;
      this.maxErrorBodyBytes = maxErrorBodyBytes;
      this.url = url;
      this.body = body;
      this.query = query;
      this.headers = headers;
      this.timeout = timeout;
      this.successWhen = successWhen;
    }

    @Override
    public Request deadline(Duration deadline) {
      return new RequestSpec(client, method, url, body, query, headers, timeout, successWhen,
          operation, requirePositive(deadline, "deadline"), maxErrorBodyBytes);
    }

    @Override
    public Request maxErrorBodyBytes(int bytes) {
      return new RequestSpec(client, method, url, body, query, headers, timeout, successWhen,
          operation, deadline, requireNonNegative(bytes));
    }

    @Override
    public Request operation(String name) {
      requireNonNull(name, "operation name must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("operation name must not be blank");
      }
      return new RequestSpec(
          client, method, url, body, query, headers, timeout, successWhen, name, deadline, maxErrorBodyBytes);
    }

    @Override
    public Request query(String name, Object value) {
      requireNonNull(name, "name must not be null");
      requireNonNull(value, "value must not be null");
      var copy = new ArrayList<>(query);
      copy.add(encodeQuery(name) + "=" + encodeQuery(value.toString()));
      return new RequestSpec(
          client, method, url, body, List.copyOf(copy), headers, timeout, successWhen, operation, deadline, maxErrorBodyBytes);
    }

    @Override
    public Request header(String name, String value) {
      requireNonNull(name, "name must not be null");
      requireNonNull(value, "value must not be null");
      return new RequestSpec(
          client, method, url, body, query, with(headers, name, value), timeout, successWhen, operation, deadline, maxErrorBodyBytes);
    }

    @Override
    public Request timeout(Duration timeout) {
      return new RequestSpec(
          client, method, url, body, query, headers, requirePositive(timeout), successWhen, operation, deadline, maxErrorBodyBytes);
    }

    @Override
    public Request successWhen(IntPredicate successWhen) {
      requireNonNull(successWhen, "successWhen must not be null");
      return new RequestSpec(client, method, url, body, query, headers, timeout, successWhen, operation, deadline, maxErrorBodyBytes);
    }

    @Override
    public HttpRequest toHttpRequest() {
      String full = query.isEmpty() ? url : appendQuery(url, query);
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(full)).timeout(timeout);
      headers.forEach(builder::header);
      return builder.method(method, body).build();
    }

    private static String appendQuery(String url, List<String> query) {
      int fragmentStart = url.indexOf('#');
      String beforeFragment = fragmentStart < 0 ? url : url.substring(0, fragmentStart);
      String fragment = fragmentStart < 0 ? "" : url.substring(fragmentStart);
      String separator =
          beforeFragment.endsWith("?") || beforeFragment.endsWith("&")
              ? ""
              : beforeFragment.contains("?") ? "&" : "?";
      return beforeFragment + separator + String.join("&", query) + fragment;
    }

    // --- Sending ------------------------------------------------------------------------------

    @Override
    public <T> Result<HttpResponse<T>, HttpError> send(BodyHandler<T> handler) {
      requireNonNull(handler, "handler must not be null");
      return execute(handler, Result::ok);
    }

    private <B, T> Result<T, HttpError> execute(
        BodyHandler<B> handler, Function<HttpResponse<B>, Result<T, HttpError>> convert) {
      HttpRequest request = toHttpRequest();
      var observation = new ExchangeObservation(client.observer, request, operation);
      try {
        Result<T, HttpError> result;
        if (Thread.currentThread().isInterrupted()) {
          result = Result.error(HttpError.Interrupted.INSTANCE);
        } else {
          var exchange = new DeadlineExchange<>(deadline,
              () -> sendResponse(request, handler, observation).flatMap(convert), observation::finishTiming);
          try {
            result = exchange.result().get();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            exchange.interrupt();
            result = exchange.result().join();
          } catch (ExecutionException failure) {
            throw propagate(requireNonNull(failure.getCause()));
          }
        }
        observation.complete(result, null);
        return result;
      } catch (RuntimeException | Error failure) {
        observation.complete(null, failure);
        throw failure;
      }
    }

    private <T> Result<HttpResponse<T>, HttpError> sendResponse(
        HttpRequest request, BodyHandler<T> handler, ExchangeObservation observation) {
      if (Thread.currentThread().isInterrupted()) {
        return Result.error(HttpError.Interrupted.INSTANCE);
      }
      // HttpClient.send wraps callback exceptions in IOException. Waiting on the async
      // exchange preserves the original failure, so both APIs use the same classification.
      var exchange = client.client.sendAsync(request, statusAware(handler, observation));
      try {
        return toResult(exchange.get());
      } catch (ExecutionException e) {
        return failed(requireNonNull(e.getCause()));
      } catch (InterruptedException e) {
        exchange.cancel(true);
        Thread.currentThread().interrupt();
        return Result.error(HttpError.Interrupted.INSTANCE);
      }
    }

    @Override
    public Result<HttpResponse<Unit>, HttpError> send() {
      return send(BodyHandlers.replacing(Unit.INSTANCE));
    }

    @Override
    public <T> Result<T, HttpError> send(Class<T> type) {
      requireNonNull(type, "type must not be null");
      JsonCodec codec = client.requireCodec();
      return execute(BodyHandlers.ofString(),
          response -> decoded(response, codec.decode(response.body(), type)));
    }

    @Override
    public <T> Result<T, HttpError> send(TypeRef<T> type) {
      requireNonNull(type, "type must not be null");
      JsonCodec codec = client.requireCodec();
      return execute(BodyHandlers.ofString(),
          response -> decoded(response, codec.decode(response.body(), type)));
    }

    @Override
    public <T> CompletableFuture<Result<HttpResponse<T>, HttpError>> sendAsync(
        BodyHandler<T> handler) {
      requireNonNull(handler, "handler must not be null");
      return executeAsync(handler, Result::ok);
    }

    private <B, T> CompletableFuture<Result<T, HttpError>> executeAsync(
        BodyHandler<B> handler, Function<HttpResponse<B>, Result<T, HttpError>> convert) {
      HttpRequest request = toHttpRequest();
      var observation = new ExchangeObservation(client.observer, request, operation);
      try {
        var exchange = new DeadlineExchange<>(deadline,
            () -> sendResponse(request, handler, observation).flatMap(convert), observation::finishTiming);
        var future = exchange.result();
        // Observe the returned future itself so caller cancellation is also reported, exactly once.
        // Keep the future unchanged: an observer must not replace its result or cancellation state.
        future.whenComplete(observation::complete);
        return future;
      } catch (RuntimeException | Error failure) {
        observation.complete(null, failure);
        throw failure;
      }
    }

    @Override
    public CompletableFuture<Result<HttpResponse<Unit>, HttpError>> sendAsync() {
      return sendAsync(BodyHandlers.replacing(Unit.INSTANCE));
    }

    @Override
    public <T> CompletableFuture<Result<T, HttpError>> sendAsync(Class<T> type) {
      requireNonNull(type, "type must not be null");
      JsonCodec codec = client.requireCodec();
      return executeAsync(BodyHandlers.ofString(),
          response -> decoded(response, codec.decode(response.body(), type)));
    }

    @Override
    public <T> CompletableFuture<Result<T, HttpError>> sendAsync(TypeRef<T> type) {
      requireNonNull(type, "type must not be null");
      JsonCodec codec = client.requireCodec();
      return executeAsync(BodyHandlers.ofString(),
          response -> decoded(response, codec.decode(response.body(), type)));
    }

    private static RuntimeException propagate(Throwable failure) {
      return switch (failure) {
        case RuntimeException exception -> exception;
        case Error error -> throw error;
        default -> new CompletionException(failure);
      };
    }

    /**
     * Maps an exceptionally completed send to a Result, or rethrows what is not a network error.
     */
    private static <T> Result<HttpResponse<T>, HttpError> failed(Throwable failure) {
      Throwable cause =
          failure instanceof CompletionException ce && ce.getCause() instanceof Throwable inner
              ? inner
              : failure;
      return switch (cause) {
        case HttpTimeoutException e -> Result.error(new HttpError.Timeout(e));
        case IOException e -> Result.error(new HttpError.Transport(e));
        case RuntimeException e -> throw e;
        case Error e -> throw e;
        default -> throw new CompletionException(cause);
      };
    }

    /**
     * Wraps the caller's handler so that the success predicate decides, once the status line and
     * headers have arrived, whether to read the body the caller's way or as text for the error.
     * This is the single place the predicate is evaluated.
     */
    private <T> BodyHandler<Outcome<T>> statusAware(
        BodyHandler<T> handler, ExchangeObservation observation) {
      return info -> {
        observation.status(info.statusCode());
        return successWhen.test(info.statusCode())
              ? BodySubscribers.mapping(handler.apply(info), Outcome.Accepted<T>::new)
              : BodySubscribers.mapping(
                  new LimitedBodySubscriber(BodyHandlers.ofString().apply(info), maxErrorBodyBytes),
                  Outcome.Rejected<T>::new);
      };
    }

    private static <T> Result<HttpResponse<T>, HttpError> toResult(
        HttpResponse<Outcome<T>> response) {
      return switch (response.body()) {
        case Outcome.Accepted<T>(var body) -> Result.ok(new Mapped<>(response, body));
        case Outcome.Rejected<T>(var limited) ->
            Result.error(new HttpError.Status(new Mapped<>(response, limited.text()), limited.truncated()));
      };
    }

    private static <T> Result<T, HttpError> decoded(
        HttpResponse<String> response, Result<T, CodecError> parsed) {
      return parsed.mapError(e -> new HttpError.Decode(e, response));
    }
  }

  /** What the status-aware body handler produced: the caller's body, or the error text. */
  private sealed interface Outcome<T> {
    record Accepted<T>(T body) implements Outcome<T> {}

    record Rejected<T>(LimitedBodySubscriber.Body body) implements Outcome<T> {}
  }

  /**
   * An {@link HttpResponse} that delegates everything but the body. Redirect history is not
   * exposed: the JDK never reads those bodies, so {@link #previousResponse()} is always empty.
   */
  private record Mapped<T>(HttpResponse<?> delegate, T body) implements HttpResponse<T> {
    @Override
    public int statusCode() {
      return delegate.statusCode();
    }

    @Override
    public HttpRequest request() {
      return delegate.request();
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public java.net.http.HttpHeaders headers() {
      return delegate.headers();
    }

    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return delegate.sslSession();
    }

    @Override
    public URI uri() {
      return delegate.uri();
    }

    @Override
    public HttpClient.Version version() {
      return delegate.version();
    }

    @Override
    public String toString() {
      return "(" + request().method() + " " + uri() + ") " + statusCode();
    }
  }
}
