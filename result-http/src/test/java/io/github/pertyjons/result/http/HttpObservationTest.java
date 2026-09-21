package io.github.pertyjons.result.http;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.http.FakeCodec.Person;
import io.github.pertyjons.result.http.HttpObservation.Outcome;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpObservationTest {
  private TestServer server;
  private ResultHttpClient api;
  private final BlockingQueue<HttpObservation> events = new LinkedBlockingQueue<>();

  @BeforeEach
  void start() throws Exception {
    server = new TestServer();
    api = ResultHttpClient.DEFAULT.baseUrl(server.url()).observe(events::add);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpObservation event() throws Exception {
    var event = events.poll(5, TimeUnit.SECONDS);
    assertThat(event).as("one completion observation").isNotNull();
    assertThat(events).isEmpty();
    return event;
  }

  private Result<HttpResponse<String>, HttpError> send(
      ResultHttpClient.Request request, boolean async) throws Exception {
    return async
        ? request.sendAsync(BodyHandlers.ofString()).get(5, TimeUnit.SECONDS)
        : request.send(BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesSuccessWithoutExposingRequestOrResponseData(boolean async) throws Exception {
    server.record("/users", 200, "response-secret");
    var request = api.post("/users/{id}?token=query-secret#fragment-secret", "person-secret")
        .body("body-secret", "text/plain")
        .operation("create-user")
        .query("password", "query2-secret")
        .header("Authorization", "Bearer header-secret")
        .timeout(Duration.ofSeconds(3))
        .successWhen(code -> code == 200);
    assertThat(send(request, async)).extractingValue(HttpResponse::body).isEqualTo("response-secret");
    var event = event();
    assertThat(event.method()).isEqualTo("POST");
    assertThat(event.operation()).isEqualTo("create-user");
    assertThat(event.host()).isEqualTo("127.0.0.1");
    assertThat(event.statusCode()).hasValue(200);
    assertThat(event.duration()).isPositive();
    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(event.exceptionType()).isEmpty();
    assertThat(event.toString()).doesNotContain("secret", "/users", "Authorization", "http://");
  }

  @Test
  void defaultLabelIsTheMethodAndRequestRefinementIsImmutable() throws Exception {
    server.record("/private-id", 200, "");
    var original = api.get("/private-id");
    original.operation("lookup").send();
    assertThat(event().operation()).isEqualTo("lookup");
    original.send();
    assertThat(event().operation()).isEqualTo("GET");
    assertThatThrownBy(() -> original.operation(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> original.operation(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void observerIsOptInReplacedAndPreservedAcrossClientConfiguration() throws Exception {
    server.record("/p", 200, "{\"name\":\"Ada\"}");
    var silent = ResultHttpClient.DEFAULT.baseUrl(server.url());
    var replaced = new AtomicInteger();
    var configured = silent.observe(_ -> replaced.incrementAndGet()).observe(events::add)
        .client(silent.client()).baseUrl(server.url()).header("X-Test", "value")
        .withoutHeader("X-Test").timeout(Duration.ofSeconds(3))
        .successWhen(code -> code == 200).codec(new FakeCodec());
    assertThat(configured.get("/p").send(Person.class)).hasValue(new Person("Ada"));
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    silent.get("/p").send();
    assertThat(events).isEmpty();
    assertThat(replaced.get()).isZero();
    assertThatThrownBy(() -> silent.observe(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void everySendingOverloadEmitsExactlyOneEvent() throws Exception {
    server.record("/p", 200, "{\"name\":\"Ada\"}");
    server.record("/list", 200, "[{\"name\":\"Ada\"}]");
    var request = api.codec(new FakeCodec()).get("/p");
    var list = api.codec(new FakeCodec()).get("/list");
    assertThat(request.send()).isOk();
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(request.send(BodyHandlers.ofString())).isOk();
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(request.send(Person.class)).hasValue(new Person("Ada"));
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(list.send(new TypeRef<List<Person>>() {})).hasValue(List.of(new Person("Ada")));
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(request.sendAsync().get(5, TimeUnit.SECONDS)).isOk();
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(request.sendAsync(BodyHandlers.ofString()).get(5, TimeUnit.SECONDS)).isOk();
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(request.sendAsync(Person.class).get(5, TimeUnit.SECONDS)).hasValue(new Person("Ada"));
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(list.sendAsync(new TypeRef<List<Person>>() {}).get(5, TimeUnit.SECONDS))
        .hasValue(List.of(new Person("Ada")));
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesStatusFailuresAndHonorsTheSuccessPredicate(boolean async) throws Exception {
    server.record("/missing", 404, "sensitive error body");
    var request = api.get("/missing");
    assertThat(send(request, async)).hasErrorInstanceOf(HttpError.Status.class);
    var rejected = event();
    assertThat(rejected.outcome()).isEqualTo(Outcome.STATUS);
    assertThat(rejected.statusCode()).hasValue(404);
    assertThat(rejected.toString()).doesNotContain("sensitive");
    assertThat(send(request.successWhen(code -> code == 404), async)).isOk();
    assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesDecodeErrorsAfterStatusAcceptance(boolean async) throws Exception {
    server.record("/bad-json", 200, "private invalid JSON");
    var request = api.codec(new FakeCodec()).get("/bad-json");
    var result = async ? request.sendAsync(Person.class).get(5, TimeUnit.SECONDS)
        : request.send(Person.class);
    assertThat(result).hasErrorInstanceOf(HttpError.Decode.class);
    var event = event();
    assertThat(event.outcome()).isEqualTo(Outcome.DECODE);
    assertThat(event.statusCode()).hasValue(200);
    assertThat(event.exceptionType()).isEmpty();
    assertThat(event.toString()).doesNotContain("private");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesTransportErrorsWithoutAStatus(boolean async) throws Exception {
    var request = api.get("http://127.0.0.1:1/");
    assertThat(send(request, async)).hasErrorInstanceOf(HttpError.Transport.class);
    var event = event();
    assertThat(event.outcome()).isEqualTo(Outcome.TRANSPORT);
    assertThat(event.statusCode()).isEmpty();
    assertThat(event.exceptionType()).contains("java.net.ConnectException");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesTimeouts(boolean async) throws Exception {
    var release = new CountDownLatch(1);
    server.on("/slow", exchange -> {
      try {
        release.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    try {
      assertThat(send(api.get("/slow").timeout(Duration.ofMillis(100)), async))
          .hasErrorInstanceOf(HttpError.Timeout.class);
      var event = event();
      assertThat(event.outcome()).isEqualTo(Outcome.TIMEOUT);
      assertThat(event.statusCode()).isEmpty();
      assertThat(event.exceptionType()).isPresent();
    } finally {
      release.countDown();
    }
  }

  @Test
  void observesInterruptedCallerAndPreservesInterruptFlag() throws Exception {
    Thread.currentThread().interrupt();
    try {
      assertThat(api.get("/unused").send()).hasError(HttpError.Interrupted.INSTANCE);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    var event = event();
    assertThat(event.outcome()).isEqualTo(Outcome.INTERRUPTED);
    assertThat(event.statusCode()).isEmpty();
    assertThat(server.last.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesProgrammingExceptionsWithoutChangingThem(boolean async) throws Exception {
    server.record("/p", 200, "");
    var failure = new IllegalStateException("secret exception message");
    var request = api.get("/p").successWhen(_ -> { throw failure; });
    if (async) {
      assertThatThrownBy(() -> send(request, true))
          .isInstanceOf(ExecutionException.class).hasCause(failure);
    } else {
      assertThatThrownBy(() -> send(request, false)).isSameAs(failure);
    }
    var event = event();
    assertThat(event.outcome()).isEqualTo(Outcome.EXCEPTION);
    assertThat(event.statusCode()).hasValue(200);
    assertThat(event.exceptionType()).contains(IllegalStateException.class.getName());
    assertThat(event.toString()).doesNotContain("secret");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observerRuntimeExceptionCannotChangeAnyOutcome(boolean async) throws Exception {
    var calls = new AtomicInteger();
    var observed = new CountDownLatch(3);
    var throwing = api.observe(_ -> {
      calls.incrementAndGet();
      observed.countDown();
      throw new IllegalArgumentException("broken logger");
    });
    server.record("/ok", 200, "fine");
    server.record("/bad", 503, "bad");
    assertThat(send(throwing.get("/ok"), async)).isOk();
    assertThat(send(throwing.get("/bad"), async)).hasErrorInstanceOf(HttpError.Status.class);
    var original = new IllegalStateException("original");
    var request = throwing.get("/ok").successWhen(_ -> { throw original; });
    if (async) {
      assertThatThrownBy(() -> send(request, true)).hasCause(original);
    } else {
      assertThatThrownBy(() -> send(request, false)).isSameAs(original);
    }
    assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void requestValidationAndBodyEncodingDoNotEmitSendEvents() {
    assertThatThrownBy(() -> api.get("/p").send(Person.class))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> api.get("/p").sendAsync(Person.class))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> api.get("/p").header("bad header", "x").send())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.get("/p").send((Class<Person>) null))
        .isInstanceOf(NullPointerException.class);
    assertThat(api.codec(new FakeCodec()).post("/p").tryJson("unsupported")).isError();
    api.get("/p").toHttpRequest();
    assertThat(events).isEmpty();
  }

  // Gate the server until sendAsync returns, so a blocking decoder cannot run on the
  // registering test thread even when local HTTP responses are exceptionally fast.
  private CountDownLatch gatedResponse(String path, String body) {
    var respond = new CountDownLatch(1);
    server.on(path, exchange -> {
      try {
        respond.await();
        TestServer.respond(exchange, 200, body);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    return respond;
  }

  @Test
  void cancellationBeforeHeadersHasNoStatus() throws Exception {
    var respond = gatedResponse("/pending", "done");
    var future = api.get("/pending").sendAsync();
    try {
      assertThat(future.cancel(true)).isTrue();
      assertThat(future).isCancelled();
      var event = event();
      assertThat(event.outcome()).isEqualTo(Outcome.CANCELLED);
      assertThat(event.statusCode()).isEmpty();
    } finally {
      respond.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesFailuresThrownByTheClientBeforeItReturnsAFuture(boolean async) throws Exception {
    var request = api.client(new FailingClient()).get("/p");
    if (async) {
      // The deadline worker invokes the client, so async failures arrive through the future.
      assertThatThrownBy(() -> send(request, true)).isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
    } else {
      assertThatThrownBy(() -> send(request, false)).isInstanceOf(IllegalStateException.class);
    }
    var event = event();
    assertThat(event.outcome()).isEqualTo(Outcome.EXCEPTION);
    assertThat(event.statusCode()).isEmpty();
    assertThat(event.exceptionType()).contains(IllegalStateException.class.getName());
  }

  @Test
  void interruptionDuringAnExchangeProducesOneInterruptedEvent() throws Exception {
    var arrived = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    server.on("/waiting", exchange -> {
      arrived.countDown();
      try {
        release.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    var returned = new CompletableFuture<Result<HttpResponse<Result.Unit>, HttpError>>();
    var interrupted = new AtomicBoolean();
    var caller = Thread.ofVirtual().start(() -> {
      returned.complete(api.get("/waiting").send());
      interrupted.set(Thread.currentThread().isInterrupted());
    });
    try {
      assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
      assertThat(caller.join(Duration.ofSeconds(5))).isTrue();
      assertThat(returned.get(5, TimeUnit.SECONDS)).hasError(HttpError.Interrupted.INSTANCE);
      assertThat(interrupted.get()).isTrue();
      assertThat(event().outcome()).isEqualTo(Outcome.INTERRUPTED);
    } finally {
      release.countDown();
      caller.interrupt();
      caller.join(Duration.ofSeconds(5));
    }
  }

  @Test
  void observerFailureDoesNotReplaceCancellation() throws Exception {
    var respond = gatedResponse("/pending", "done");
    var future = api.observe(event -> {
      events.add(event);
      throw new IllegalStateException("broken logger");
    }).get("/pending").sendAsync();
    try {
      assertThat(future.cancel(true)).isTrue();
      assertThat(future).isCancelled();
      assertThat(event().outcome()).isEqualTo(Outcome.CANCELLED);
    } finally {
      respond.countDown();
    }
  }

  // An injected HttpClient can fail before returning a future, unlike network failures.
  private static final class FailingClient extends HttpClient {
    private final HttpClient delegate = ResultHttpClient.DEFAULT.client();

    @Override
    public Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override
    public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override
    public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override
    public Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
    @Override
    public javax.net.ssl.SSLContext sslContext() { return delegate.sslContext(); }
    @Override
    public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override
    public Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
    @Override
    public Version version() { return delegate.version(); }
    @Override
    public Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new IllegalStateException("client failure");
    }
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new IllegalStateException("client failure");
    }
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
        HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
      throw new IllegalStateException("client failure");
    }
  }

  private JsonCodec codec(Function<String, Result<Object, CodecError>> decode) {
    return new JsonCodec() {
      @Override
      public Result<Object, CodecError> decode(String json, Type type) {
        return decode.apply(json);
      }

      @Override
      public Result<String, CodecError> encode(Object value) {
        return Result.ok("{}");
      }
    };
  }

  @Test
  void includesDecodingTimeAndEmitsNothingUntilDecodingFinishes() throws Exception {
    var respond = gatedResponse("/p", "{}");
    var entered = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    var decodeStart = new AtomicLong();
    var decoded = api.codec(codec(_ -> {
      decodeStart.set(System.nanoTime());
      entered.countDown();
      release.join();
      return Result.error(CodecError.of(new IllegalArgumentException("private decode details")));
    }));
    var future = decoded.get("/p").sendAsync(Person.class);
    respond.countDown();
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(events).isEmpty();
      assertThat(future).isNotDone();
      long minimumDuration = System.nanoTime() - decodeStart.get();
      release.complete(null);
      assertThat(future.get(5, TimeUnit.SECONDS)).hasErrorInstanceOf(HttpError.Decode.class);
      var event = event();
      assertThat(event.outcome()).isEqualTo(Outcome.DECODE);
      assertThat(event.duration().toNanos()).isGreaterThanOrEqualTo(minimumDuration);
      assertThat(event.exceptionType()).contains(IllegalArgumentException.class.getName());
      assertThat(event.toString()).doesNotContain("private");
    } finally {
      release.complete(null);
    }
  }

  @Test
  void cancellationDuringDecodingProducesOneCancellationEvent() throws Exception {
    var respond = gatedResponse("/p", "{}");
    var entered = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    var finished = new CountDownLatch(1);
    var decoded = api.codec(codec(_ -> {
      entered.countDown();
      release.join();
      finished.countDown();
      return Result.ok(new Person("Ada"));
    }));
    var future = decoded.get("/p").sendAsync(Person.class);
    respond.countDown();
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(future.cancel(true)).isTrue();
      assertThat(future).isCancelled();
      var event = event();
      assertThat(event.outcome()).isEqualTo(Outcome.CANCELLED);
      assertThat(event.statusCode()).hasValue(200);
      release.complete(null);
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(future).isCancelled();
      assertThat(events).isEmpty();
    } finally {
      release.complete(null);
    }
  }

  @Test
  void observesThrownCodecExceptions() throws Exception {
    server.record("/p", 200, "{}");
    var failure = new IllegalArgumentException("codec bug");
    var request = api.codec(codec(_ -> { throw failure; })).get("/p");
    assertThatThrownBy(() -> request.send(Person.class)).isSameAs(failure);
    assertThat(event().outcome()).isEqualTo(Outcome.EXCEPTION);
    assertThatThrownBy(() -> request.sendAsync(Person.class).get(5, TimeUnit.SECONDS))
        .hasCause(failure);
    assertThat(event().outcome()).isEqualTo(Outcome.EXCEPTION);
  }

  @Test
  void streamingObservationDoesNotWaitForStreamConsumption() throws Exception {
    var release = new CountDownLatch(1);
    server.on("/stream", exchange -> {
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write('x');
      exchange.getResponseBody().flush();
      try {
        release.await();
        exchange.getResponseBody().write('y');
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    try {
      var result = api.get("/stream").sendAsync(BodyHandlers.ofInputStream()).get(5, TimeUnit.SECONDS);
      try (var body = result.orElseThrow().body()) {
        assertThat(event().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(body.read()).isEqualTo('x');
        release.countDown();
        assertThat(body.read()).isEqualTo('y');
        assertThat(body.read()).isEqualTo(-1);
        assertThat(events).isEmpty();
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void concurrentReuseHasOneIndependentObservationPerSend() throws Exception {
    server.record("/p", 200, "fine");
    var request = api.get("/p").operation("lookup");
    var futures = new ArrayList<CompletableFuture<?>>();
    for (int i = 0; i < 20; i++) {
      futures.add(request.sendAsync());
    }
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    for (int i = 0; i < 20; i++) {
      var event = events.poll(5, TimeUnit.SECONDS);
      assertThat(event).isNotNull();
      assertThat(event.operation()).isEqualTo("lookup");
      assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
      assertThat(event.statusCode()).hasValue(200);
    }
    assertThat(events).isEmpty();
  }
}
