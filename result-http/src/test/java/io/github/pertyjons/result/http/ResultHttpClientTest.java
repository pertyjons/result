package io.github.pertyjons.result.http;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.Result.Unit;
import io.github.pertyjons.result.http.FakeCodec.Person;
import io.github.pertyjons.result.http.HttpError.Decode;
import io.github.pertyjons.result.http.HttpError.Interrupted;
import io.github.pertyjons.result.http.HttpError.Status;
import io.github.pertyjons.result.http.HttpError.Timeout;
import io.github.pertyjons.result.http.HttpError.Transport;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResultHttpClientTest {

  private TestServer server;
  private ResultHttpClient api;

  @BeforeEach
  void start() throws Exception {
    server = new TestServer();
    api = ResultHttpClient.DEFAULT.baseUrl(server.url());
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Nested
  class Configuration {

    @Test
    void defaultHasDocumentedSettings() {
      var d = ResultHttpClient.DEFAULT;
      assertThat(d.baseUrl()).isEmpty();
      assertThat(d.headers()).isEmpty();
      assertThat(d.timeout()).isEqualTo(Duration.ofSeconds(10));
      assertThat(d.codec()).isEmpty(); // no JsonCodec is registered on result-http's own classpath
    }

    @Test
    void derivingDoesNotChangeTheOriginal() {
      var base = ResultHttpClient.DEFAULT.header("Accept", "application/json");
      var derived =
          base.header("Accept", "text/csv").timeout(Duration.ofSeconds(1)).withoutHeader("Accept");

      assertThat(base.headers()).containsExactly(java.util.Map.entry("Accept", "application/json"));
      assertThat(base.timeout()).isEqualTo(Duration.ofSeconds(10));
      assertThat(derived.headers()).isEmpty();
      assertThat(derived.timeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void headerNamesAreCaseInsensitiveAndReplace() {
      var c = ResultHttpClient.DEFAULT.header("accept", "a").header("ACCEPT", "b");
      assertThat(c.headers()).hasSize(1).containsValue("b");
      assertThat(c.headers().get("Accept")).isEqualTo("b");
    }

    @Test
    void createWithExistingClientKeepsIt() {
      var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      assertThat(ResultHttpClient.create(jdk).client()).isSameAs(jdk);
      assertThat(ResultHttpClient.DEFAULT.client(jdk).client()).isSameAs(jdk);
    }

    @Test
    void createWithConfigurerBuildsAClient() {
      var c = ResultHttpClient.create(b -> b.connectTimeout(Duration.ofSeconds(3)));
      assertThat(c.client().connectTimeout()).contains(Duration.ofSeconds(3));
    }

    @Test
    void codecCanBeSetExplicitly() {
      var codec = new FakeCodec();
      assertThat(ResultHttpClient.DEFAULT.codec(codec).codec()).contains(codec);
    }

    @Test
    void baseUrlMustBeAbsolute() {
      assertThatThrownBy(() -> ResultHttpClient.DEFAULT.baseUrl("/api"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("absolute");
    }

    @Test
    void baseUrlMustNotContainQueryOrFragment() {
      assertThatThrownBy(() -> ResultHttpClient.DEFAULT.baseUrl("https://example.com/api?token=x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("query");
      assertThatThrownBy(() -> ResultHttpClient.DEFAULT.baseUrl("https://example.com/api#section"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fragment");
    }

    @Test
    void timeoutMustBePositiveOnClientAndRequest() {
      assertThatThrownBy(() -> ResultHttpClient.DEFAULT.timeout(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
      assertThatThrownBy(() -> ResultHttpClient.DEFAULT.timeout(Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> api.get("/x").timeout(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }

    @Test
    void headersMapIsUnmodifiable() {
      var headers = ResultHttpClient.DEFAULT.header("A", "1").headers();
      assertThatThrownBy(() -> headers.put("B", "2"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class UrlBuilding {

    @Test
    void fillsPathTemplateWithEncodedArguments() {
      server.record("/users", 200, "");
      api.get("/users/{id}/files/{name}", 42, "a b/c").send();
      assertThat(server.last.get().path()).isEqualTo("/users/42/files/a%20b%2Fc");
    }

    @Test
    void joinsBaseAndPathRegardlessOfSlashes() {
      server.record("/v1", 200, "");
      var slashBase = ResultHttpClient.DEFAULT.baseUrl(server.url() + "/v1/");
      var bareBase = ResultHttpClient.DEFAULT.baseUrl(server.url() + "/v1");

      slashBase.get("/users").send();
      assertThat(server.last.get().path()).isEqualTo("/v1/users");
      bareBase.get("users").send();
      assertThat(server.last.get().path()).isEqualTo("/v1/users");
      bareBase.get("").send();
      assertThat(server.last.get().path()).isEqualTo("/v1");
    }

    @Test
    void absoluteUrlIgnoresBase() {
      server.record("/direct", 200, "");
      ResultHttpClient.DEFAULT
          .baseUrl("https://example.invalid")
          .get(server.url() + "/direct")
          .send();
      assertThat(server.last.get().path()).isEqualTo("/direct");
    }

    @Test
    void absoluteUrlWorksWithoutBase() {
      server.record("/direct", 200, "ok");
      assertThat(
              ResultHttpClient.DEFAULT.get(server.url() + "/direct").send(BodyHandlers.ofString()))
          .extractingValue(HttpResponse::body)
          .isEqualTo("ok");
    }

    @Test
    void relativePathWithoutBaseIsAProgrammingError() {
      assertThatThrownBy(() -> ResultHttpClient.DEFAULT.get("/users"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("baseUrl");
    }

    @Test
    void placeholderCountMustMatchArguments() {
      assertThatThrownBy(() -> api.get("/users/{id}"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("more placeholders");
      assertThatThrownBy(() -> api.get("/users", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fewer placeholders");
    }

    @Test
    void queryParametersAreEncodedAndRepeatable() {
      server.record("/search", 200, "");
      api.get("/search").query("q", "a&b c").query("tag", 1).query("tag", 2).send();
      assertThat(server.last.get().query()).isEqualTo("q=a%26b+c&tag=1&tag=2");
    }

    @Test
    void queryAppendsToExistingQueryString() {
      server.record("/search", 200, "");
      api.get("/search?x=1").query("y", 2).send();
      assertThat(server.last.get().query()).isEqualTo("x=1&y=2");
    }

    @Test
    void queryIsInsertedBeforeFragment() {
      var uri =
          ResultHttpClient.DEFAULT
              .get("https://example.com/search?x=1#results")
              .query("y", 2)
              .toHttpRequest()
              .uri();

      assertThat(uri.toString()).isEqualTo("https://example.com/search?x=1&y=2#results");
      assertThat(uri.getRawQuery()).isEqualTo("x=1&y=2");
      assertThat(uri.getRawFragment()).isEqualTo("results");
    }

    @Test
    void toHttpRequestExposesTheBuiltRequest() {
      var request =
          api.get("/users/{id}", 7).query("expand", "roles").header("X-Probe", "p").toHttpRequest();
      assertThat(request.uri().toString()).isEqualTo(server.url() + "/users/7?expand=roles");
      assertThat(request.method()).isEqualTo("GET");
      assertThat(request.headers().firstValue("X-Probe")).contains("p");
      assertThat(request.timeout()).contains(Duration.ofSeconds(10));
    }
  }

  @Nested
  class Headers {

    @Test
    void requestHeaderOverridesClientHeader() {
      server.record("/h", 200, "");
      api.header("X-Probe", "client").get("/h").header("x-probe", "request").send();
      assertThat(server.last.get().header()).isEqualTo("request");
    }

    @Test
    void clientHeaderIsSentWhenNotOverridden() {
      server.record("/h", 200, "");
      api.header("X-Probe", "client").get("/h").send();
      assertThat(server.last.get().header()).isEqualTo("client");
    }

    @Test
    void textBodySetsContentType() {
      server.record("/h", 200, "");
      api.post("/h").body("hello", "text/plain").send();
      var seen = server.last.get();
      assertThat(seen.method()).isEqualTo("POST");
      assertThat(seen.contentType()).isEqualTo("text/plain");
      assertThat(seen.body()).isEqualTo("hello");
    }

    @Test
    void publisherBodySetsNoContentType() {
      server.record("/h", 200, "");
      api.put("/h").body(BodyPublishers.ofString("raw")).send();
      var seen = server.last.get();
      assertThat(seen.method()).isEqualTo("PUT");
      assertThat(seen.contentType()).isEqualTo("null");
      assertThat(seen.body()).isEqualTo("raw");
    }
  }

  @Nested
  class Sending {

    @Test
    void acceptedStatusIsOkWithResponse() {
      server.record("/ok", 200, "body");
      var result = api.get("/ok").send(BodyHandlers.ofString());
      assertThat(result).isOk().extractingValue(HttpResponse::body).isEqualTo("body");
      assertThat(result.orElseThrow().statusCode()).isEqualTo(200);
    }

    @Test
    void rejectedStatusIsStatusErrorWithWholeResponse() {
      server.record("/missing", 404, "no such thing");
      var result = api.get("/missing").send(BodyHandlers.ofString());
      assertThat(result).hasErrorInstanceOf(Status.class);
      var status = (Status) result.toOptionalError().orElseThrow();
      assertThat(status.code()).isEqualTo(404);
      assertThat(status.uri().getPath()).isEqualTo("/missing");
      assertThat(status.response().body()).isEqualTo("no such thing");
    }

    @Test
    void errorBodyIsReadAsTextWhateverHandlerWasAsked() {
      server.record("/missing", 404, "gone");
      var discarded = api.get("/missing").send();
      assertThat(((Status) discarded.toOptionalError().orElseThrow()).body()).isEqualTo("gone");

      var bytes = api.get("/missing").send(BodyHandlers.ofByteArray());
      assertThat(((Status) bytes.toOptionalError().orElseThrow()).body()).isEqualTo("gone");

      var stream = api.get("/missing").send(BodyHandlers.ofInputStream());
      assertThat(((Status) stream.toOptionalError().orElseThrow()).body()).isEqualTo("gone");
    }

    @Test
    void withResponseExposesCodeUriHeadersAndBody() {
      server.record("/missing", 404, "gone");
      var error = api.get("/missing").query("q", 1).send().toOptionalError().orElseThrow();
      assertThat(error).isInstanceOf(HttpError.WithResponse.class);
      var with = (HttpError.WithResponse) error;
      assertThat(with.code()).isEqualTo(404);
      assertThat(with.uri().toString()).isEqualTo(server.url() + "/missing?q=1");
      assertThat(with.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
      assertThat(with.body()).isEqualTo("gone");
      assertThat(with.response().request().method()).isEqualTo("GET");
    }

    @Test
    void withResponseCoversStatusAndDecodeButNotNetworkErrors() {
      server.record("/missing", 404, "gone");
      server.record("/bad", 200, "<html>");
      var json = api.codec(new FakeCodec());

      assertThat(describe(json.get("/missing").send(Person.class))).isEqualTo("404: gone");
      assertThat(describe(json.get("/bad").send(Person.class))).isEqualTo("200: <html>");
      assertThat(describe(ResultHttpClient.DEFAULT.get("http://127.0.0.1:1/").send()))
          .isEqualTo("no response");
    }

    private static String describe(Result<?, HttpError> result) {
      return switch (result) {
        case Result.Ok<?, HttpError> ok -> "ok";
        case Result.Err<?, HttpError>(HttpError.WithResponse r) -> r.code() + ": " + r.body();
        case Result.Err<?, HttpError> err -> "no response";
      };
    }

    @Test
    void acceptedResponseKeepsCallersBodyAndMetadata() {
      server.record("/ok", 201, "created");
      var response = api.post("/ok").noBody().send(BodyHandlers.ofByteArray()).orElseThrow();
      assertThat(response.statusCode()).isEqualTo(201);
      assertThat(response.body()).isEqualTo("created".getBytes());
      assertThat(response.uri().getPath()).isEqualTo("/ok");
      assertThat(response.request().method()).isEqualTo("POST");
      assertThat(response.headers().firstValue("Content-Type")).isPresent();
      assertThat(response.version()).isNotNull();
      assertThat(response.sslSession()).isEmpty();
      assertThat(response.previousResponse()).isEmpty();
      assertThat(response.toString()).isEqualTo("(POST " + server.url() + "/ok) 201");

      var discarded = api.get("/ok").send().orElseThrow();
      assertThat(discarded.body()).isEqualTo(Unit.INSTANCE);
      assertThat(discarded.statusCode()).isEqualTo(201);
    }

    @Test
    void errorBodyCharsetComesFromContentType() {
      server.on(
          "/latin1",
          exchange -> {
            byte[] bytes = "räksmörgås".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=ISO-8859-1");
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      var error = (Status) api.get("/latin1").send().toOptionalError().orElseThrow();
      assertThat(error.body()).isEqualTo("räksmörgås");
    }

    @Test
    void successWhenCanBeOverriddenPerRequest() {
      server.record("/missing", 404, "");
      assertThat(api.get("/missing").successWhen(s -> s == 404).send()).isOk();
      assertThat(api.get("/missing").send()).hasErrorInstanceOf(Status.class);
    }

    @Test
    void successWhenCanBeSetOnClient() {
      server.record("/teapot", 418, "");
      assertThat(api.successWhen(s -> s == 418).get("/teapot").send()).isOk();
    }

    @Test
    void deleteHeadAndCustomMethods() {
      server.record("/r", 204, "");
      assertThat(api.delete("/r").send()).isOk();
      assertThat(server.last.get().method()).isEqualTo("DELETE");
      assertThat(api.head("/r").send()).isOk();
      assertThat(server.last.get().method()).isEqualTo("HEAD");
      assertThat(api.method("OPTIONS", "/r").noBody().send()).isOk();
      assertThat(server.last.get().method()).isEqualTo("OPTIONS");
      assertThat(api.patch("/r").noBody().send()).isOk();
      assertThat(server.last.get().method()).isEqualTo("PATCH");
    }

    @Test
    void timeoutBecomesTimeoutError() {
      server.on(
          "/slow",
          exchange -> {
            try {
              Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
            TestServer.respond(exchange, 200, "");
          });
      var result = api.get("/slow").timeout(Duration.ofMillis(100)).send();
      assertThat(result).hasErrorInstanceOf(Timeout.class);
    }

    @Test
    void connectionRefusedBecomesTransportError() {
      var result = ResultHttpClient.DEFAULT.get("http://127.0.0.1:1/").send();
      assertThat(result).hasErrorInstanceOf(Transport.class);
      var transport = (Transport) result.toOptionalError().orElseThrow();
      assertThat(transport.cause()).isInstanceOf(ConnectException.class);
    }

    @Test
    void interruptionBecomesInterruptedErrorAndRestoresFlag() {
      server.on(
          "/slow",
          exchange -> {
            try {
              Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
            TestServer.respond(exchange, 200, "");
          });
      Thread.currentThread().interrupt();
      Result<HttpResponse<Unit>, HttpError> result;
      try {
        result = api.get("/slow").send();
      } finally {
        assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
      }
      assertThat(result).hasError(Interrupted.INSTANCE);
    }

    @Test
    void interruptionWhileWaitingReturnsPromptlyAndRestoresFlag() throws Exception {
      var arrived = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      server.on("/waiting", exchange -> {
        arrived.countDown();
        try {
          release.await();
          TestServer.respond(exchange, 200, "done");
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        } finally {
          exchange.close();
        }
      });
      var outcome = new AtomicReference<Result<HttpResponse<Unit>, HttpError>>();
      var interruptRestored = new AtomicBoolean();
      var caller = Thread.ofVirtual().start(() -> {
        outcome.set(api.get("/waiting").send());
        interruptRestored.set(Thread.currentThread().isInterrupted());
      });
      try {
        assertThat(arrived.await(5, TimeUnit.SECONDS)).as("request reached server").isTrue();
        caller.interrupt();
        assertThat(caller.join(Duration.ofSeconds(5))).as("interrupted caller finished").isTrue();
        assertThat(outcome.get()).hasError(Interrupted.INSTANCE);
        assertThat(interruptRestored.get()).isTrue();
      } finally {
        release.countDown();
        caller.interrupt();
        caller.join(Duration.ofSeconds(5));
      }
    }
  }

  @Nested
  class Json {

    private ResultHttpClient json;

    @BeforeEach
    void codec() {
      json = api.codec(new FakeCodec());
    }

    @Test
    void decodesBodyToClass() {
      server.record("/p", 200, "{\"name\":\"Ada\"}");
      assertThat(json.get("/p").send(Person.class)).hasValue(new Person("Ada"));
    }

    @Test
    void decodesBodyToTypeRef() {
      server.record("/p", 200, "[{\"name\":\"Ada\"}, {\"name\":\"Alan\"}]");
      assertThat(json.get("/p").send(new TypeRef<List<Person>>() {}))
          .hasValue(List.of(new Person("Ada"), new Person("Alan")));
    }

    @Test
    void statusIsCheckedBeforeDecoding() {
      server.record("/p", 500, "{\"error\":\"boom\"}");
      var result = json.get("/p").send(Person.class);
      assertThat(result).hasErrorInstanceOf(Status.class);
      assertThat(((Status) result.toOptionalError().orElseThrow()).response().body())
          .isEqualTo("{\"error\":\"boom\"}");
    }

    @Test
    void undecodableBodyIsDecodeErrorWithRawResponse() {
      server.record("/p", 200, "<html>");
      var result = json.get("/p").send(Person.class);
      assertThat(result).hasErrorInstanceOf(Decode.class);
      var decode = (Decode) result.toOptionalError().orElseThrow();
      assertThat(decode.error().message()).isEqualTo("not a Person: <html>");
      assertThat(decode.error().cause()).isEmpty();
      assertThat(decode.response().body()).isEqualTo("<html>");
    }

    @Test
    void jsonNullIsDecodeError() {
      server.record("/p", 200, "null");
      var result = json.get("/p").send(Person.class);
      assertThat(result).hasErrorInstanceOf(Decode.class);
      assertThat(((Decode) result.toOptionalError().orElseThrow()).error())
          .isEqualTo(CodecError.of("JSON codec produced null"));
    }

    @Test
    void typedDecodeChecksTheRuntimeClass() {
      JsonCodec lying =
          new JsonCodec() {
            @Override
            public Result<Object, CodecError> decode(String json, java.lang.reflect.Type type) {
              return Result.ok("not a person");
            }

            @Override
            public Result<String, CodecError> encode(Object value) {
              return Result.ok("");
            }
          };
      assertThat(lying.decode("{}", Person.class))
          .hasError(
              CodecError.of(
                  "JSON codec produced java.lang.String, expected " + Person.class.getName()));
      assertThat(lying.decode("{}", String.class)).hasValue("not a person");
      assertThat(lying.decode("42", int.class))
          .hasError(CodecError.of("JSON codec produced java.lang.String, expected int"));

      server.record("/p", 200, "{}");
      assertThat(api.codec(lying).get("/p").send(Person.class)).hasErrorInstanceOf(Decode.class);
    }

    @Test
    void genericDecodeChecksTheRuntimeRawClass() {
      JsonCodec lying =
          new JsonCodec() {
            @Override
            public Result<Object, CodecError> decode(String json, java.lang.reflect.Type type) {
              return Result.ok("not a list");
            }

            @Override
            public Result<String, CodecError> encode(Object value) {
              return Result.ok("");
            }
          };

      assertThat(lying.decode("[]", new TypeRef<List<Person>>() {}))
          .hasError(CodecError.of("JSON codec produced java.lang.String, expected java.util.List"));
    }

    @Test
    void attemptCapturesExceptionsNullAndValues() {
      assertThat(JsonCodec.attempt(() -> "x")).hasValue("x");
      assertThat(JsonCodec.attempt(() -> null)).hasError(CodecError.of("JSON codec produced null"));
      var boom = new IllegalStateException("boom");
      var error =
          JsonCodec.attempt(
                  () -> {
                    throw boom;
                  })
              .toOptionalError()
              .orElseThrow();
      assertThat(error.message()).isEqualTo("boom");
      assertThat(error.cause()).containsSame(boom);
      assertThat(error.toString()).isEqualTo("boom (java.lang.IllegalStateException)");
      assertThat(CodecError.of(new IllegalStateException()).message())
          .isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void unencodableBodyCarriesTheCodecMessageAndCause() {
      assertThatThrownBy(() -> json.post("/p").json("not a person"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Could not encode java.lang.String: FakeCodec cannot encode java.lang.String")
          .hasNoCause();
    }

    @Test
    void jsonBodyIsEncodedAndTyped() {
      server.record("/p", 201, "");
      assertThat(json.post("/p").json(new Person("Ada")).send()).isOk();
      var seen = server.last.get();
      assertThat(seen.contentType()).isEqualTo("application/json");
      assertThat(seen.body()).isEqualTo("{\"name\":\"Ada\"}");
    }

    @Test
    void tryJsonReturnsEncodedRequest() {
      server.record("/p", 201, "");

      var request = json.post("/p").tryJson(new Person("Ada"));

      assertThat(request).isOk();
      assertThat(request.orElseThrow().send()).isOk();
      var seen = server.last.get();
      assertThat(seen.contentType()).isEqualTo("application/json");
      assertThat(seen.body()).isEqualTo("{\"name\":\"Ada\"}");
    }

    @Test
    void tryJsonReturnsCodecErrorWithoutSending() {
      var request = json.post("/p").tryJson("not a person");

      assertThat(request).hasError(CodecError.of("FakeCodec cannot encode java.lang.String"));
      assertThat(server.last.get()).as("nothing was sent").isNull();
    }

    @Test
    void unencodableBodyIsAProgrammingError() {
      assertThatThrownBy(() -> json.post("/p").json("not a person"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Could not encode");
    }

    @Test
    void missingCodecIsAProgrammingErrorBeforeSending() {
      assertThatThrownBy(() -> api.get("/p").send(Person.class))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("result-http-jackson");
      assertThatThrownBy(() -> api.post("/p").json(new Person("x")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(server.last.get()).as("nothing was sent").isNull();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void typeRefRequiresATypeArgument() {
      assertThatThrownBy(() -> new TypeRef() {}).isInstanceOf(IllegalArgumentException.class);
      assertThat(new TypeRef<List<Person>>() {}).isEqualTo(new TypeRef<List<Person>>() {});
      assertThat(new TypeRef<List<Person>>() {}.toString())
          .isEqualTo("TypeRef<java.util.List<" + Person.class.getName() + ">>");
    }
  }

  @Nested
  class CallbackFailures {

    @BeforeEach
    void response() {
      server.record("/callback", 200, "body");
    }

    @Test
    void predicateFailureRemainsAProgrammingErrorInBothModes() {
      var failure = new IllegalStateException("predicate bug");
      var request = api.get("/callback").successWhen(_ -> { throw failure; });
      assertThatThrownBy(request::send).isSameAs(failure);
      assertThatThrownBy(() -> request.sendAsync().get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class).hasCause(failure);
    }

    @Test
    void handlerFailureRemainsAProgrammingErrorInBothModes() {
      var failure = new IllegalArgumentException("handler bug");
      BodyHandler<String> handler = _ -> { throw failure; };
      var request = api.get("/callback");
      assertThatThrownBy(() -> request.send(handler)).isSameAs(failure);
      assertThatThrownBy(() -> request.sendAsync(handler).get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class).hasCause(failure);
    }

    @Test
    void bodyMappingFailureRemainsAProgrammingErrorInBothModes() {
      var failure = new IllegalArgumentException("body mapping bug");
      BodyHandler<String> handler = info -> BodySubscribers.mapping(
          BodyHandlers.ofString().apply(info), _ -> { throw failure; });
      var request = api.get("/callback");
      assertThatThrownBy(() -> request.send(handler)).isSameAs(failure);
      assertThatThrownBy(() -> request.sendAsync(handler).get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class).hasCause(failure);
    }

    @Test
    void callbackErrorsAreNotTurnedIntoTransportErrors() {
      var failure = new AssertionError("callback bug");
      var request = api.get("/callback").successWhen(_ -> { throw failure; });
      assertThatThrownBy(request::send).isSameAs(failure);
      assertThatThrownBy(() -> request.sendAsync().get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class).hasCause(failure);
    }
  }

  @Nested
  class Async {

    @Test
    void completesNormallyWithOk() throws Exception {
      server.record("/a", 200, "async");
      var result = api.get("/a").sendAsync(BodyHandlers.ofString()).get(5, TimeUnit.SECONDS);
      assertThat(result).extractingValue(HttpResponse::body).isEqualTo("async");
    }

    @Test
    void completesNormallyWithStatusErrorAndItsBody() throws Exception {
      server.record("/a", 503, "later");
      var result = api.get("/a").sendAsync().get(5, TimeUnit.SECONDS);
      assertThat(result).hasErrorInstanceOf(Status.class);
      assertThat(((Status) result.toOptionalError().orElseThrow()).body()).isEqualTo("later");
    }

    @Test
    void completesNormallyWithTransportError() throws Exception {
      var result =
          ResultHttpClient.DEFAULT.get("http://127.0.0.1:1/").sendAsync().get(5, TimeUnit.SECONDS);
      assertThat(result).hasErrorInstanceOf(Transport.class);
    }

    @Test
    void completesNormallyWithTimeout() throws Exception {
      server.on(
          "/slow",
          exchange -> {
            try {
              Thread.sleep(2_000);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
            TestServer.respond(exchange, 200, "");
          });
      var result =
          api.get("/slow").timeout(Duration.ofMillis(100)).sendAsync().get(5, TimeUnit.SECONDS);
      assertThat(result).hasErrorInstanceOf(Timeout.class);
    }

    @Test
    void decodesAsynchronously() throws Exception {
      server.record("/p", 200, "[{\"name\":\"Ada\"}]");
      var json = api.codec(new FakeCodec());
      assertThat(json.get("/p").sendAsync(Person.class).get(5, TimeUnit.SECONDS))
          .hasErrorInstanceOf(Decode.class);
      assertThat(json.get("/p").sendAsync(new TypeRef<List<Person>>() {}).get(5, TimeUnit.SECONDS))
          .hasValue(List.of(new Person("Ada")));
    }
  }
}
