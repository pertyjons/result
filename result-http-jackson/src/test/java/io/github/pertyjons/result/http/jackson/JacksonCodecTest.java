package io.github.pertyjons.result.http.jackson;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.http.CodecError;
import io.github.pertyjons.result.http.HttpError;
import io.github.pertyjons.result.http.ResultHttpClient;
import io.github.pertyjons.result.http.TypeRef;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class JacksonCodecTest {

  record Person(String name, int age) {}

  record Snake(String firstName) {}

  @Nested
  class Codec {

    private final JacksonCodec codec = new JacksonCodec();

    @Test
    void decodesAllPrimitiveValueTypesToTheirBoxedValues() {
      assertThat(codec.decode("true", boolean.class)).hasValue(true);
      assertThat(codec.decode("42", byte.class)).hasValue((byte) 42);
      assertThat(codec.decode("42", short.class)).hasValue((short) 42);
      assertThat(codec.decode("42", int.class)).hasValue(42);
      assertThat(codec.decode("42", long.class)).hasValue(42L);
      assertThat(codec.decode("1.5", float.class)).hasValue(1.5f);
      assertThat(codec.decode("1.5", double.class)).hasValue(1.5d);
      assertThat(codec.decode("\"a\"", char.class)).hasValue('a');
      assertThat(codec.decode("42", Integer.class)).hasValue(42);
    }

    @Test
    void decodesRecordFromClass() {
      Result<Person, CodecError> person =
          codec.decode("{\"name\":\"Ada\",\"age\":36}", Person.class);
      assertThat(person).hasValue(new Person("Ada", 36));
    }

    @Test
    void decodesGenericTypeFromTypeRef() {
      Result<List<Person>, CodecError> people =
          codec.decode("[{\"name\":\"Ada\",\"age\":36}]", new TypeRef<List<Person>>() {});
      assertThat(people).hasValue(List.of(new Person("Ada", 36)));
    }

    @Test
    void decodesJsonNullToError() {
      assertThat(codec.decode("null", Person.class))
          .hasError(CodecError.of("JSON codec produced null"));
    }

    @Test
    void encodesRecord() {
      assertThat(codec.encode(new Person("Ada", 36))).hasValue("{\"name\":\"Ada\",\"age\":36}");
    }

    @Test
    void malformedJsonIsErrorWithJacksonsExceptionAsCause() {
      var error = codec.decode("{", Person.class).toOptionalError().orElseThrow();
      assertThat(error.cause()).get().isInstanceOf(JacksonException.class);
      assertThat(error.message()).isNotBlank();
    }

    @Test
    void wrapsAConfiguredMapper() {
      var mapper =
          JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
      var custom = JacksonCodec.of(mapper);
      assertThat(custom.mapper()).isSameAs(mapper);
      assertThat(custom.encode(new Snake("Ada"))).hasValue("{\"first_name\":\"Ada\"}");
      assertThat(custom.decode("{\"first_name\":\"Ada\"}", Snake.class)).hasValue(new Snake("Ada"));
    }
  }

  @Nested
  class Discovery {

    @Test
    void isRegisteredThroughServiceLoader() {
      assertThat(ResultHttpClient.DEFAULT.codec()).get().isInstanceOf(JacksonCodec.class);
      assertThat(ResultHttpClient.create().codec()).get().isInstanceOf(JacksonCodec.class);
    }
  }

  @Nested
  class EndToEnd {

    private HttpServer server;
    private ResultHttpClient api;

    @BeforeEach
    void start() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/people",
          exchange -> {
            byte[] body;
            if (exchange.getRequestMethod().equals("POST")) {
              // echo the posted object back with an id
              var posted =
                  new JacksonCodec()
                      .decode(
                          new String(exchange.getRequestBody().readAllBytes(), UTF_8), Map.class)
                      .orElseThrow();
              body =
                  ("{\"name\":\"" + ((Map<?, ?>) posted).get("name") + "\",\"age\":1}")
                      .getBytes(UTF_8);
            } else {
              body =
                  "[{\"name\":\"Ada\",\"age\":36},{\"name\":\"Alan\",\"age\":41}]".getBytes(UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      api = ResultHttpClient.DEFAULT.baseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
      server.stop(0);
    }

    @Test
    void decodesPrimitiveHttpResponsesSynchronouslyAndAsynchronously() throws Exception {
      server.createContext("/number", exchange -> {
        byte[] body = "42".getBytes(UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      assertThat(api.get("/number").send(int.class)).hasValue(42);
      assertThat(api.get("/number").sendAsync(int.class).get(5, TimeUnit.SECONDS)).hasValue(42);
    }

    @Test
    void getsAndDecodesWithoutConfiguration() {
      assertThat(api.get("/people").send(new TypeRef<List<Person>>() {}))
          .hasValue(List.of(new Person("Ada", 36), new Person("Alan", 41)));
    }

    @Test
    void postsJsonAndDecodesTheAnswer() {
      assertThat(api.post("/people").json(new Person("Grace", 0)).send(Person.class))
          .hasValue(new Person("Grace", 1));
    }

    @Test
    void wrongShapeIsDecodeError() {
      assertThat(api.get("/people").send(Person.class)).hasErrorInstanceOf(HttpError.Decode.class);
    }
  }
}
