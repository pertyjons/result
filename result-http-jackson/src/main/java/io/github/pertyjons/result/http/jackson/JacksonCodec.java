package io.github.pertyjons.result.http.jackson;

import static java.util.Objects.requireNonNull;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.http.CodecError;
import io.github.pertyjons.result.http.JsonCodec;
import java.lang.reflect.Type;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link JsonCodec} backed by a Jackson 3 {@link ObjectMapper}. The no-argument constructor, used
 * by {@link java.util.ServiceLoader}, builds a default {@link JsonMapper}; {@link
 * #of(ObjectMapper)} wraps a mapper the application has already configured:
 *
 * <pre>{@code
 * ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
 * ResultHttpClient api = ResultHttpClient.DEFAULT.codec(JacksonCodec.of(mapper));
 * }</pre>
 */
public final class JacksonCodec implements JsonCodec {

  private final ObjectMapper mapper;

  /** Creates a codec over a default {@link JsonMapper}. Used by {@link java.util.ServiceLoader}. */
  public JacksonCodec() {
    this(JsonMapper.builder().build());
  }

  private JacksonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Creates a codec over an existing mapper.
   *
   * @param mapper the mapper to use, must not be null
   * @return a new codec
   */
  public static JacksonCodec of(ObjectMapper mapper) {
    return new JacksonCodec(requireNonNull(mapper, "mapper must not be null"));
  }

  /**
   * The underlying mapper.
   *
   * @return the mapper, never null
   */
  public ObjectMapper mapper() {
    return mapper;
  }

  @Override
  public Result<Object, CodecError> decode(String json, Type type) {
    return JsonCodec.attempt(() -> mapper.readValue(json, mapper.constructType(type)));
  }

  @Override
  public Result<String, CodecError> encode(Object value) {
    return JsonCodec.attempt(() -> mapper.writeValueAsString(value));
  }
}
