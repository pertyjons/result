package io.github.pertyjons.result.http;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import org.jspecify.annotations.Nullable;

/**
 * Captures a generic type so that a response body can be decoded to, for example, {@code
 * List<Person>}, which a {@link Class} literal cannot express. Create one as an anonymous subclass:
 *
 * <pre>{@code
 * Result<List<Person>, HttpError> people = client.get("/people").send(new TypeRef<List<Person>>() {});
 * }</pre>
 *
 * <p>The subclass must extend {@code TypeRef} directly and supply a type with no unresolved type
 * variables. Named direct subclasses with a concrete type are also supported. Indirect inheritance
 * and type variables are rejected at construction instead of risking an incorrectly typed result.
 *
 * @param <T> the captured type
 */
public abstract class TypeRef<T> {

  private final Type type;

  /**
   * Captures the type argument given to the anonymous subclass.
   *
   * @throws IllegalArgumentException if the subclass does not extend {@code TypeRef} directly with
   *     a type argument, or the captured type contains an unresolved type variable
   */
  protected TypeRef() {
    if (!(getClass().getGenericSuperclass() instanceof ParameterizedType parameterized)
        || parameterized.getRawType() != TypeRef.class) {
      throw new IllegalArgumentException(
          "Subclass must directly extend TypeRef with a type argument, e.g. new TypeRef<List<Person>>() {}");
    }
    this.type = parameterized.getActualTypeArguments()[0];
    requireConcrete(type);
  }

  private static void requireConcrete(Type type) {
    switch (type) {
      case TypeVariable<?> variable -> throw new IllegalArgumentException(
          "TypeRef must not contain an unresolved type variable: " + variable.getTypeName());
      case ParameterizedType parameterized -> {
        Type owner = parameterized.getOwnerType();
        if (owner != null) {
          requireConcrete(owner);
        }
        for (Type argument : parameterized.getActualTypeArguments()) {
          requireConcrete(argument);
        }
      }
      case GenericArrayType array -> requireConcrete(array.getGenericComponentType());
      case WildcardType wildcard -> {
        for (Type bound : wildcard.getUpperBounds()) {
          requireConcrete(bound);
        }
        for (Type bound : wildcard.getLowerBounds()) {
          requireConcrete(bound);
        }
      }
      default -> { }
    }
  }

  /**
   * The captured type, for example {@code java.util.List<Person>}.
   *
   * @return the type, never null
   */
  public final Type type() {
    return type;
  }

  @Override
  public final boolean equals(@Nullable Object other) {
    return other instanceof TypeRef<?> that && type.equals(that.type);
  }

  @Override
  public final int hashCode() {
    return type.hashCode();
  }

  @Override
  public final String toString() {
    return "TypeRef<" + type.getTypeName() + ">";
  }
}
