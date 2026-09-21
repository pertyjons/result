package io.github.pertyjons.result.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeRefTest {
  static class ListRef<T> extends TypeRef<List<T>> {}

  static class StringListRef extends TypeRef<List<String>> {}

  @Test
  void rejectsIndirectGenericSubclassInsteadOfCapturingTheWrongType() {
    assertThatThrownBy(() -> new ListRef<String>() {})
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("directly");
  }

  @Test
  void rejectsIndirectConcreteSubclass() {
    assertThatThrownBy(() -> new StringListRef() {})
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("directly");
  }

  @Test
  void acceptsNamedDirectSubclassWithConcreteType() {
    assertThat(new StringListRef()).isEqualTo(new TypeRef<List<String>>() {});
  }

  @Test
  void preservesNestedGenericArrayAndWildcardTypes() {
    var ref = new TypeRef<Map<String, List<? extends Number>[]>>() {};
    assertThat(ref.type().getTypeName())
        .isEqualTo("java.util.Map<java.lang.String, java.util.List<? extends java.lang.Number>[]>");
    assertThat(ref).isEqualTo(new TypeRef<Map<String, List<? extends Number>[]>>() {});
  }

  @Test
  <T> void rejectsUnresolvedTypeVariables() {
    assertThatThrownBy(() -> new TypeRef<T>() {})
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type variable");
    assertThatThrownBy(() -> new TypeRef<List<T>>() {})
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type variable");
    assertThatThrownBy(() -> new TypeRef<T[]>() {})
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type variable");
    assertThatThrownBy(() -> new TypeRef<List<? extends T>>() {})
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type variable");
  }
}
