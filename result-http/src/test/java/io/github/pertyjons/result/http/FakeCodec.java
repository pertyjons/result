package io.github.pertyjons.result.http;

import io.github.pertyjons.result.Result;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately tiny codec so that result-http's own tests carry no JSON dependency. It reads and
 * writes {@link Person} as {@code {"name":"..."}} and {@code List<Person>} as a JSON array of the
 * same, and rejects everything else. Written with {@link Result} throughout: nothing in it throws,
 * and no exception is ever constructed.
 */
final class FakeCodec implements JsonCodec {

  record Person(String name) {}

  private static final Pattern PERSON =
      Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]*)\"\\s*}");
  private static final Pattern BETWEEN_OBJECTS = Pattern.compile("(?<=})\\s*,\\s*(?=\\{)");

  @Override
  public Result<Object, CodecError> decode(String json, Type type) {
    return Result.<String, CodecError>ok(json.strip())
        .filter(text -> !text.equals("null"), _ -> CodecError.of("JSON codec produced null"))
        .flatMap(
            text ->
                switch (type) {
                  case Class<?> c when c == Person.class -> person(text).map(Object.class::cast);
                  case ParameterizedType p when isListOfPerson(p) ->
                      people(text).map(Object.class::cast);
                  default ->
                      Result.error(CodecError.of("FakeCodec cannot decode " + type.getTypeName()));
                });
  }

  private static Result<Person, CodecError> person(String json) {
    return Result.<Matcher, CodecError>ok(PERSON.matcher(json))
        .filter(Matcher::matches, _ -> CodecError.of("not a Person: " + json))
        .map(m -> new Person(m.group(1)));
  }

  private static Result<List<Person>, CodecError> people(String json) {
    String inner = json.substring(1, json.length() - 1).strip();
    List<String> items = inner.isEmpty() ? List.of() : List.of(BETWEEN_OBJECTS.split(inner));
    return Result.traverse(items, FakeCodec::person);
  }

  private static boolean isListOfPerson(ParameterizedType p) {
    return p.getRawType() == List.class && p.getActualTypeArguments()[0] == Person.class;
  }

  @Override
  public Result<String, CodecError> encode(Object value) {
    return switch (value) {
      case Person p -> Result.ok("{\"name\":\"" + p.name() + "\"}");
      default ->
          Result.error(CodecError.of("FakeCodec cannot encode " + value.getClass().getName()));
    };
  }
}
