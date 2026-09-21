package io.github.pertyjons.result.http;

import io.github.pertyjons.result.Result;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Converts between JSON text and Java objects on behalf of {@link ResultHttpClient}. The {@code
 * result-http} module ships no implementation and no JSON dependency; add {@code
 * result-http-jackson} to the classpath, or implement this interface for the library of your
 * choice. An implementation overrides only the untyped {@link #decode(String, Type)} and {@link
 * #encode(Object)}; the typed {@link #decode(String, Class)} and {@link #decode(String, TypeRef)}
 * are inherited defaults that callers use. Every method returns a {@link Result} whose error is a
 * {@link CodecError}, and {@link #attempt(Callable)} turns a throwing library call into one, so a
 * Gson implementation is a few lines:
 *
 * <pre>{@code
 * public final class GsonCodec implements JsonCodec {
 *   private final Gson gson = new Gson();
 *
 *   @Override // the one decoding method to implement; the typed overloads are inherited
 *   public Result<Object, CodecError> decode(String json, Type type) {
 *     return JsonCodec.attempt(() -> gson.fromJson(json, type));
 *   }
 *
 *   @Override
 *   public Result<String, CodecError> encode(Object value) {
 *     return JsonCodec.attempt(() -> gson.toJson(value));
 *   }
 * }
 *
 * JsonCodec codec = new GsonCodec();
 * Result<Person, CodecError> one        = codec.decode(json, Person.class);
 * Result<List<Person>, CodecError> many = codec.decode(json, new TypeRef<List<Person>>() {});
 * }</pre>
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader} when {@link
 * ResultHttpClient#create()} runs, so registering the class in {@code
 * META-INF/services/io.github.pertyjons.result.http.JsonCodec} makes it the default. A codec can
 * also be set explicitly with {@link ResultHttpClient#codec(JsonCodec)}, which is the way to use a
 * mapper that the rest of the application has configured.
 *
 * <p>The client turns an {@code Err} from {@link #decode} into {@link HttpError.Decode}. For
 * encoding, {@link ResultHttpClient.NeedsBody#tryJson(Object)} keeps the {@code CodecError} while
 * {@link ResultHttpClient.NeedsBody#json(Object)} converts it to an {@link
 * IllegalArgumentException} for callers that prefer a fluent convenience method. Exceptions appear
 * in one place only: inside {@link #attempt(Callable)}, where a library actually threw one.
 */
public interface JsonCodec {

  /**
   * Parses {@code json} into an instance of {@code type}. This is the single method an
   * implementation provides; callers use the typed {@link #decode(String, Class)} and {@link
   * #decode(String, TypeRef)} instead. {@code type} is either a {@link Class} or a parameterized
   * type captured by a {@link TypeRef}. The JSON literal {@code null} has no {@code Ok}
   * representation and must be reported as an {@code Err}; {@link #attempt(Callable)} does this.
   *
   * @param json the JSON text, never null
   * @param type the target type, never null
   * @return {@code Ok(object)} or {@code Err(error)}
   */
  Result<Object, CodecError> decode(String json, Type type);

  /**
   * Typed form of {@link #decode(String, Type)} for non-generic targets. The decoded object is
   * checked against {@code type}, so an implementation that produces the wrong class yields an
   * {@code Err} rather than a failure later in the chain. Primitive targets such as {@code int.class}
   * are checked against their boxed class; the original target is passed to the decoder.
   *
   * @param <T> the target type
   * @param json the JSON text, never null
   * @param type the target class, never null
   * @return {@code Ok(value)} or {@code Err(error)}
   */
  default <T> Result<T, CodecError> decode(String json, Class<T> type) {
    Class<T> boxedType = boxed(type);
    return decode(json, (Type) type)
        .filter(
            boxedType::isInstance,
            value ->
                CodecError.of(
                    "JSON codec produced "
                        + value.getClass().getName()
                        + ", expected "
                        + type.getName()))
        .map(boxedType::cast);
  }

  // Primitive class literals have the wrapper's generic type, e.g. int.class is Class<Integer>.
  @SuppressWarnings("unchecked")
  private static <T> Class<T> boxed(Class<T> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    return (Class<T>) switch (type.getName()) {
      case "boolean" -> Boolean.class;
      case "byte" -> Byte.class;
      case "short" -> Short.class;
      case "int" -> Integer.class;
      case "long" -> Long.class;
      case "float" -> Float.class;
      case "double" -> Double.class;
      case "char" -> Character.class;
      case "void" -> Void.class;
      default -> throw new AssertionError("Unknown primitive type: " + type);
    };
  }

  /**
   * Typed form of {@link #decode(String, Type)} for generic targets such as {@code List<Person>}.
   * Generic types cannot be checked at runtime, so the result is trusted to match the captured
   * type; only the raw class is verified.
   *
   * @param <T> the target type
   * @param json the JSON text, never null
   * @param type the captured target type, never null
   * @return {@code Ok(value)} or {@code Err(error)}
   */
  @SuppressWarnings("unchecked")
  default <T> Result<T, CodecError> decode(String json, TypeRef<T> type) {
    Optional<Class<?>> rawType = rawClass(type.type());
    return decode(json, type.type())
        .filter(
            value -> rawType.map(raw -> raw.isInstance(value)).orElse(true),
            value ->
                CodecError.of(
                    "JSON codec produced "
                        + value.getClass().getName()
                        + ", expected "
                        + rawType.orElseThrow().getName()))
        .map(value -> (T) value);
  }

  private static Optional<Class<?>> rawClass(Type type) {
    return switch (type) {
      case Class<?> raw -> Optional.of(raw);
      case ParameterizedType parameterized -> rawClass(parameterized.getRawType());
      case GenericArrayType array ->
          rawClass(array.getGenericComponentType())
              .map(component -> Array.newInstance(component, 0).getClass());
      default -> Optional.empty();
    };
  }

  /**
   * Serialises {@code value} to JSON text.
   *
   * @param value the object to serialise, never null
   * @return {@code Ok(json)} or {@code Err(error)}
   */
  Result<String, CodecError> encode(Object value);

  /**
   * Runs a throwing library call and captures the outcome: a thrown {@link Exception} becomes
   * {@code Err(CodecError.of(exception))}, a {@code null} result, which is what libraries return
   * for the JSON literal {@code null}, becomes an {@code Err} with a message and no cause, and
   * anything else becomes {@code Ok(value)}. {@link Error}s are not caught. If the call throws
   * {@link InterruptedException} the thread's interrupt flag is restored.
   *
   * @param <T> the value type
   * @param call the library call; a null result is reported as an Err rather than returned
   * @return the outcome as a Result
   */
  static <T> Result<T, CodecError> attempt(Callable<? extends T> call) {
    T value;
    try {
      value = call.call();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.error(CodecError.of(e));
    } catch (Exception e) {
      return Result.error(CodecError.of(e));
    }
    if (value == null) {
      return Result.error(CodecError.of("JSON codec produced null"));
    }
    return Result.ok(value);
  }
}
