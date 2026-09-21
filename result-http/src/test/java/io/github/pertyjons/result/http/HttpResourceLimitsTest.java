package io.github.pertyjons.result.http;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.http.FakeCodec.Person;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Type;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpResourceLimitsTest {
  private TestServer server;
  private ResultHttpClient api;
  private final LinkedBlockingQueue<HttpObservation> events = new LinkedBlockingQueue<>();

  @BeforeEach
  void start() throws Exception {
    server = new TestServer();
    api = ResultHttpClient.DEFAULT.baseUrl(server.url()).observe(events::add);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void defaultsValidationAndClientCopies() {
    assertThat(api.deadline()).isEqualTo(Duration.ofSeconds(30));
    assertThat(api.maxErrorBodyBytes()).isEqualTo(65536);
    var configured = api.deadline(Duration.ofSeconds(2)).maxErrorBodyBytes(4)
        .client(api.client()).baseUrl(server.url()).header("X-Test", "x").withoutHeader("X-Test")
        .timeout(Duration.ofSeconds(1)).successWhen(code -> code == 200)
        .codec(new FakeCodec()).observe(events::add);
    assertThat(configured.deadline()).isEqualTo(Duration.ofSeconds(2));
    assertThat(configured.maxErrorBodyBytes()).isEqualTo(4);
    assertThat(api.maxErrorBodyBytes()).isEqualTo(65536);
    assertThatThrownBy(() -> api.deadline(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.get("/").deadline(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.deadline(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> api.maxErrorBodyBytes(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.get("/").maxErrorBodyBytes(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oversizedErrorBodyBecomesATruncatedStatus(boolean async) throws Exception {
    server.record("/error", 503, "x".repeat(2 * 1024 * 1024));
    var request = api.get("/error").maxErrorBodyBytes(4096);
    var result = async ? request.sendAsync().get(3, TimeUnit.SECONDS) : request.send();
    assertThat(result).hasErrorInstanceOf(HttpError.Status.class);
    var error = (HttpError.Status) result.toOptionalError().orElseThrow();
    assertThat(error.body()).hasSize(4096);
    assertThat(error.bodyTruncated()).isTrue();
    assertThat(error.code()).isEqualTo(503);
    assertThat(events.poll(3, TimeUnit.SECONDS).outcome()).isEqualTo(HttpObservation.Outcome.STATUS);
    assertThat(events).isEmpty();
  }

  @Test
  void requestLimitOverridesClientAndSurvivesOtherRefinements() {
    server.record("/error", 404, "abcdefgh");
    var base = api.maxErrorBodyBytes(3).get("/error");
    var refined = base.maxErrorBodyBytes(5).operation("lookup").header("X-Test", "x")
        .query("q", "x").timeout(Duration.ofSeconds(2)).successWhen(code -> code == 200)
        .deadline(Duration.ofSeconds(3));
    assertThat(((HttpError.Status) refined.send().toOptionalError().orElseThrow()).body()).isEqualTo("abcde");
    assertThat(((HttpError.Status) base.send().toOptionalError().orElseThrow()).body()).isEqualTo("abc");
  }

  @Test
  void acceptedBodiesAreNotTruncated() {
    server.record("/ok", 200, "abcdefgh");
    assertThat(api.maxErrorBodyBytes(1).get("/ok").send(BodyHandlers.ofString()))
        .extractingValue(HttpResponse::body).isEqualTo("abcdefgh");
  }

  @Test
  void preservesCharsetWhenTruncatingErrors() {
    server.on("/latin", exchange -> {
      byte[] bytes = "åäö!".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=iso-8859-1");
      exchange.sendResponseHeaders(400, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    var error = (HttpError.Status) api.get("/latin").maxErrorBodyBytes(3).send()
        .toOptionalError().orElseThrow();
    assertThat(error.body()).isEqualTo("åäö");
    assertThat(error.bodyTruncated()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 503})
  void deadlineCoversStalledBodiesAfterHeaders(int status) throws Exception {
    var release = stalledBody(status);
    try {
      var request = api.get("/stall").timeout(Duration.ofSeconds(5)).deadline(Duration.ofMillis(500));
      assertThat(request.sendAsync(BodyHandlers.ofString()).get(3, TimeUnit.SECONDS))
          .hasErrorInstanceOf(HttpError.Timeout.class);
      var event = events.poll(3, TimeUnit.SECONDS);
      assertThat(event.outcome()).isEqualTo(HttpObservation.Outcome.TIMEOUT);
      assertThat(event.statusCode()).hasValue(status);
      assertThat(events).isEmpty();
    } finally {
      release.countDown();
    }
  }

  @Test
  void synchronousDeadlineAlsoCoversStalledBody() throws Exception {
    var release = stalledBody(200);
    try {
      var future = CompletableFuture.supplyAsync(() -> api.get("/stall")
          .deadline(Duration.ofMillis(500)).send(BodyHandlers.ofString()));
      assertThat(future.get(3, TimeUnit.SECONDS)).hasErrorInstanceOf(HttpError.Timeout.class);
    } finally {
      release.countDown();
    }
  }

  private CountDownLatch stalledBody(int status) {
    var release = new CountDownLatch(1);
    server.on("/stall", exchange -> {
      exchange.sendResponseHeaders(status, 2);
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
    return release;
  }

  @Test
  void deadlineIncludesDecodingEvenIfTheCodecIgnoresInterruption() throws Exception {
    server.record("/json", 200, "{}");
    var release = new CompletableFuture<Void>();
    var entered = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    JsonCodec blocked = new JsonCodec() {
      @Override public Result<Object, CodecError> decode(String json, Type type) {
        entered.countDown();
        release.join(); // deliberately uninterruptible user code
        finished.countDown();
        return Result.ok(new Person("late"));
      }
      @Override public Result<String, CodecError> encode(Object value) { return Result.ok("{}"); }
    };
    var future = api.codec(blocked).get("/json").deadline(Duration.ofMillis(500)).sendAsync(Person.class);
    try {
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(future.get(3, TimeUnit.SECONDS)).hasErrorInstanceOf(HttpError.Timeout.class);
      assertThat(events.poll(3, TimeUnit.SECONDS).outcome()).isEqualTo(HttpObservation.Outcome.TIMEOUT);
      release.complete(null);
      assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(future.join()).hasErrorInstanceOf(HttpError.Timeout.class);
      assertThat(events).isEmpty();
    } finally {
      release.complete(null);
    }
  }

  @Test
  void streamConsumptionIsOutsideTheDeadlineAfterHandoff() throws Exception {
    var release = stalledBody(200);
    try {
      var response = api.get("/stall").deadline(Duration.ofMillis(500))
          .sendAsync(BodyHandlers.ofInputStream()).get(3, TimeUnit.SECONDS).orElseThrow();
      try (var body = response.body()) {
        assertThat(events.poll(3, TimeUnit.SECONDS).outcome()).isEqualTo(HttpObservation.Outcome.SUCCESS);
        // Let the old deadline pass; the handed-off stream must not be cancelled by its timer.
        assertThat(events.poll(700, TimeUnit.MILLISECONDS)).isNull();
        assertThat(body.read()).isEqualTo('x');
        release.countDown();
        assertThat(body.read()).isEqualTo('y');
        assertThat(body.read()).isEqualTo(-1);
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void streamingResponseArrivingAfterCancellationIsClosed() throws Exception {
    server.record("/stream", 200, "body");
    var closed = new CountDownLatch(1);
    ByteArrayInputStream body = new ByteArrayInputStream(new byte[0]) {
      @Override public void close() { closed.countDown(); }
    };
    var response = api.get("/stream").send(BodyHandlers.replacing(body)).orElseThrow();
    var entered = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    var exchange = new DeadlineExchange<HttpResponse<ByteArrayInputStream>>(Duration.ofSeconds(5), () -> {
      entered.countDown();
      release.join();
      return Result.ok(response);
    }, () -> {});
    try {
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(exchange.result().cancel(true)).isTrue();
      release.complete(null);
      assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(exchange.result()).isCancelled();
    } finally {
      release.complete(null);
      body.close();
    }
  }
}
