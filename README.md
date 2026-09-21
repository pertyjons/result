# Result

A Rust-inspired `Result<T, E>` type for Java 25: a sealed sum type that is either `Ok(value)` or `Err(error)`, with a fluent API for transforming, combining and unwrapping explicitly modelled outcomes. Ships with a companion AssertJ module for concise, readable tests and a fluent HTTP client over `java.net.http` whose exchanges return a `Result`.

```java
Result<User, ApiError> user = client.get("/users/42").flatMap(this::parseUser);

switch (user) {
  case Result.Ok(var u)  -> render(u);
  case Result.Err(var e) -> report(e);
}
```

## Table of contents

- [Why Result?](#why-result)
- [Getting started](#getting-started)
- [Core concepts](#core-concepts)
- [API reference](#api-reference)
  - [Creating a Result](#creating-a-result)
    - [ok](#okvalue) · [ok()](#ok-unit) · [ok(value, Class)](#okvalue-errorclass) · [error](#errorerror) · [error(error, Class)](#errorerror-valueclass)
    - [ensure](#ensurecondition-error) · [ofNullable](#ofnullablevalue-error) · [ofOptional](#ofoptionaloptional-errorsupplier) · [ofCallable](#ofcallablecallable)
  - [Transforming](#transforming)
    - [map](#mapf) · [mapError](#maperrorf) · [mapBoth](#mapbothonok-onerror) · [flatMap](#flatmapf) · [recover](#recoverf) · [filter](#filterpredicate-errorfunction) · [swap](#swap)
  - [Side effects](#side-effects)
    - [onOk](#onokconsumer) · [onError](#onerrorconsumer) · [match](#matchonok-onerror)
  - [Unwrapping](#unwrapping)
    - [fold](#foldonok-onerror) · [orElseThrow](#orelsethrow) · [orElseThrow(fn)](#orelsethrowerrortoexception) · [orElse](#orelsedefaultvalue) · [orElseGet](#orelsegetfallback)
    - [toOptional / toOptionalError](#tooptional--tooptionalerror) · [stream](#stream) · [isOk / isError](#isok--iserror)
  - [Combining](#combining)
    - [map2](#map2ra-rb-f) · [map3](#map3ra-rb-rc-f) · [map2All](#map2allra-rb-f) · [map3All](#map3allra-rb-rc-f)
  - [Collections](#collections)
    - [sequence](#sequenceresults) · [traverse](#traverseitems-f) · [traverseIndexed](#traverseindexeditems-f) · [sequenceAll](#sequenceallresults) · [traverseAll](#traverseallitems-f) · [partition](#partitionresults)
  - [Stream collectors](#stream-collectors)
    - [toResult](#toresult) · [toResultAll](#toresultall) · [toPartitioned](#topartitioned)
  - [Supporting types](#supporting-types)
    - [Unit](#unit) · [Partitioned](#partitioned) · [ResultException](#resultexception) · [TriFunction](#trifunction)
- [Type inference and variance](#type-inference-and-variance)
- [Asynchronous composition with AsyncResult](#asynchronous-composition-with-asyncresult)
- [Testing effectively with result-assertj](#testing-effectively-with-result-assertj)
- [HTTP with result-http](#http-with-result-http)
  - [Four phases](#four-phases) · [Configuring a client](#configuring-a-client) · [Building a request](#building-a-request) · [Sending](#sending) · [Resource limits](#resource-limits) · [HttpError](#httperror) · [JSON and JsonCodec](#json-and-jsoncodec) · [Errors versus bugs](#errors-versus-bugs)
- [Logging](#logging)
- [Worked examples](#worked-examples)
- [Building](#building)
- [Design notes](#design-notes)
- [License](#license)

## Why Result?

Java commonly signals failure with exceptions, `null` or `Optional`. Exceptions make failure control flow implicit, while `null` and `Optional` cannot explain why an operation failed.

- **Errors are values, not control flow.** An `Err` is an ordinary object you can log, map, collect and pattern-match. Representing an expected failure as `Err` does not unwind the stack or require `try` nesting and checked-exception signatures throughout a pipeline.
- **The modelled error type is part of the signature.** `Result<Config, ConfigError>` tells the caller which expected failures the operation represents. With a sealed error type the compiler enforces that every case is handled in an exhaustive `switch`. As elsewhere in Java, unchecked exceptions and errors may still escape.
- **Failure is difficult to mistake for a value.** Unlike a `null` return, a `Result` cannot be dereferenced as though it were the success value. To reach that value you must map it, fold it, pattern-match it or explicitly unwrap it. Java still allows callers to discard any return value, including a `Result`.
- **Composition is linear.** A chain of `flatMap` calls reads top to bottom and stops at the first failure. Independent results combine with `map2`/`map3`, their accumulating counterparts collect every error, and lists of results collapse with `sequence`, `traverse` or a stream collector.
- **Both "first error wins" and "collect every error" are first class.** Validation wants all violations; a pipeline wants to stop early. The API has explicit variants for each.
- **Exception boundaries are explicit.** `ofCallable` turns a throwing call into a value at the edge of your code, and `orElseThrow` turns it back into an exception where a caller genuinely cannot continue.
- **Null-free variants by construction.** Neither variant accepts `null`, and the package is `@NullMarked` (JSpecify). `Unit` covers the "success with no value" case.

The core module depends only on the JDK and the JSpecify annotations.

## Getting started

**Requirements:** Java 25 or later. Building from source needs no local JDK 25: the build uses a Gradle toolchain and downloads one if needed.

The project is a Gradle multi-module build with four artifacts under group `io.github.pertyjons`:

| Module | Artifact | Purpose |
|---|---|---|
| [`result`](result) | `io.github.pertyjons:result` | The `Result` type and its operations. Depends on `org.jspecify:jspecify` only. |
| [`result-assertj`](result-assertj) | `io.github.pertyjons:result-assertj` | AssertJ assertions for `Result`; normally added to the test configuration. |
| [`result-http`](result-http) | `io.github.pertyjons:result-http` | Fluent, `Result`-returning adapter over the JDK `HttpClient`. Depends on `result` only. |
| [`result-http-jackson`](result-http-jackson) | `io.github.pertyjons:result-http-jackson` | Jackson 3 `JsonCodec` for `result-http`, auto-discovered when on the classpath. |

Add the modules you need (Gradle Kotlin DSL shown; Maven coordinates are the same):

```kotlin
dependencies {
    implementation("io.github.pertyjons:result:0.1.0")
    implementation("io.github.pertyjons:result-http:0.1.0")          // optional: HTTP client
    implementation("io.github.pertyjons:result-http-jackson:0.1.0")  // optional: JSON via Jackson 3
    testImplementation("io.github.pertyjons:result-assertj:0.1.0")
}
```

```xml
<dependency>
  <groupId>io.github.pertyjons</groupId>
  <artifactId>result</artifactId>
  <version>0.1.0</version>
</dependency>
```

Each jar declares an `Automatic-Module-Name` matching its base package (`io.github.pertyjons.result`, `io.github.pertyjons.result.assertj`, `io.github.pertyjons.result.http`, `io.github.pertyjons.result.http.jackson`), so the modules can be required from a `module-info.java`.

The current version lives in [`gradle.properties`](gradle.properties).

A first end-to-end example:

```java
import io.github.pertyjons.result.Result;
import java.util.Map;

sealed interface ParseError {
  record Missing(String key) implements ParseError {}
  record NotANumber(String key, String raw) implements ParseError {}
}

Result<Integer, ParseError> port(Map<String, String> env) {
  return Result.ofNullable(env.get("PORT"), () -> new ParseError.Missing("PORT"), ParseError.class)
      .flatMap(raw -> Result.ofCallable(
          () -> Integer.parseInt(raw),
          _ -> new ParseError.NotANumber("PORT", raw)))
      .filter(p -> p > 0 && p < 65_536, p -> new ParseError.NotANumber("PORT", p.toString()));
}
```

The trailing `ParseError.class` makes the whole chain a `Result<…, ParseError>` rather than a `Result<…, ParseError.Missing>`, so the later steps can fail with another case. See [Type inference and variance](#type-inference-and-variance).

## Core concepts

**Two variants.** `Result<T, E>` is a sealed interface with exactly two record implementations, [`Ok`](result/src/main/java/io/github/pertyjons/result/Result.java#L55) and [`Err`](result/src/main/java/io/github/pertyjons/result/Result.java#L70). Because they are records you get `equals`, `hashCode`, `toString` and deconstruction patterns for free:

```java
switch (result) {
  case Result.Ok(var value)  -> ...
  case Result.Err(var error) -> ...
}
```

The error variant is named `Err`, not `Error`, so importing it never shadows `java.lang.Error`.

**No null in, no null out.** `Result.ok(null)` and `Result.error(null)` throw `NullPointerException`, and functions passed to transformation methods must not return `null`, so neither variant can contain a null payload. No accessor on `Result` returns `null` either: an absent side is an empty `Optional` from `toOptional()`/`toOptionalError()`, and after pattern matching the payload is the record component, `Ok.value()` or `Err.error()`. The one place null is accepted is `ofNullable`, which exists to turn a null from a legacy API into an `Err` at the boundary.

**Unit for "nothing".** Operations that succeed without producing a value return `Result<Unit, E>` via `Result.ok()`. See [Unit](#unit).

**Immutability.** A `Result` never changes variant or payload reference after construction, so the container is safe to share between threads. The payload is stored as-is, neither copied nor frozen, so a mutable value such as an `ArrayList` stays mutable through the reference the caller kept. Payload immutability is the caller's responsibility. `Partitioned` and every list produced by the collection operations are immutable copies.

**Pass-through is free.** `map` on an `Err`, `mapError` on an `Ok` and similar pass-through cases return the same instance re-typed, without allocating.

## API reference

Each entry links to the source. All examples assume `import io.github.pertyjons.result.Result;` and, where a nested type such as `Unit` is used by its simple name, `import io.github.pertyjons.result.Result.*;`. Exhaustive behavioural tests live in [`ResultTest`](result/src/test/java/io/github/pertyjons/result/ResultTest.java), [`ResultApiTest`](result/src/test/java/io/github/pertyjons/result/ResultApiTest.java) and [`ResultCollectorsTest`](result/src/test/java/io/github/pertyjons/result/ResultCollectorsTest.java).

### Creating a Result

#### `ok(value)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L551). Wraps a non-null value in `Ok`.

```java
Result<String, String> r = Result.ok("hello"); // Ok("hello")
```

#### `ok()` (Unit)

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L568). Returns `Ok(Unit.INSTANCE)` for operations with no meaningful value.

```java
Result<Unit, IoError> write(Path p) {
  ...
  return Result.ok();
}
```

#### `ok(value, errorClass)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L589). Same as `ok(value)` but takes a `Class<E>` witness so `var` and generic arguments infer `E` without the `Result.<T, E>ok(...)` syntax. Works for non-generic error types only; for `List<String>` and the like use the explicit form.

```java
var r = Result.ok("hello", IoError.class); // Result<String, IoError>
```

`ensure`, `ofNullable`, `ofOptional` and `ofCallable` accept the same `Class<E>` witness as their last argument. See [Type inference and variance](#type-inference-and-variance).

#### `error(error)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L607). Wraps a non-null error in `Err`.

```java
Result<String, String> r = Result.error("oops"); // Err("oops")
```

#### `error(error, valueClass)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L628). Mirror of `ok(value, Class)`: a `Class<T>` witness fixes the success type.

```java
var r = Result.error(new IoError("disk full"), String.class); // Result<String, IoError>
```

#### `ensure(condition, error)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L648). `Ok(Unit)` if the condition holds, otherwise `Err(error)`. Similar to Rust's `ensure!` and Scala's `Either.cond`. A [lazy overload](result/src/main/java/io/github/pertyjons/result/Result.java#L668) takes a `Supplier<E>` so the error object is only built on failure. An [overload with an error-type witness](result/src/main/java/io/github/pertyjons/result/Result.java#L691) fixes `E` when the supplier builds one case of a sealed hierarchy.

```java
Result.ensure(age >= 18, "Must be 18 or older")
    .flatMap(_ -> createAccount(name));

Result.ensure(file.exists(), () -> new IoError("Not found: " + path))
    .flatMap(_ -> readFile(path));

Result.ensure(age >= 18, () -> new ValidationError.TooYoung(age), ValidationError.class)
    .flatMap(_ -> register(name)); // Result<Unit, ValidationError>
```

Used for preconditions in the [`OrderLifecycle`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L631) example and for validation in [`ValidatingInput`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L173).

#### `ofNullable(value, error)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L712). Lifts a possibly-null value: `Ok(value)` if non-null, else `Err(error)`. The natural bridge from `Map.get`, `System.getenv` and other null-returning APIs. A [lazy overload](result/src/main/java/io/github/pertyjons/result/Result.java#L731) takes a `Supplier<E>`, and [another](result/src/main/java/io/github/pertyjons/result/Result.java#L754) adds an error-type witness.

```java
Result.ofNullable(map.get(key), "missing " + key);
Result.ofNullable(System.getenv(name), () -> new ConfigError("unset: " + name));
Result.ofNullable(env.get(key), () -> new ConfigError.Missing(key), ConfigError.class);
```

#### `ofOptional(optional, errorSupplier)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L774). Converts an `Optional`, supplying the error for the empty case. An [overload](result/src/main/java/io/github/pertyjons/result/Result.java#L798) adds an error-type witness.

```java
Result.ofOptional(repo.findById(id), () -> new NotFound(id));
Result.ofOptional(repo.findById(id), () -> new ApiError.NotFound(id), ApiError.class);
```

See the repository lookup in [`BankTransfers`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L306).

#### `ofCallable(callable)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L819). Runs a throwing operation and captures any thrown `Exception` as the error. `java.lang.Error` is not caught. If the callable throws `InterruptedException` the thread's interrupt flag is restored. An [overload](result/src/main/java/io/github/pertyjons/result/Result.java#L840) takes an error mapper so the exception becomes a domain error immediately, and [a third](result/src/main/java/io/github/pertyjons/result/Result.java#L875) adds an error-type witness to that.

```java
Result.ofCallable(() -> Integer.parseInt("42"));   // Ok(42)
Result.ofCallable(() -> Integer.parseInt("nope")); // Err(NumberFormatException)

Result.ofCallable(() -> readFile(path), e -> new IoError(e.getMessage()));
Result.ofCallable(() -> Integer.parseInt(raw), _ -> new ParseError.NotANumber(raw), ParseError.class);
```

The [`FileIo`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L457) example shows checked I/O exceptions becoming values and being matched by type.

### Transforming

These methods apply a function to the inner value without unwrapping. The untouched variant passes through unchanged.

#### `map(f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L157). Transforms the success value; errors pass through.

```java
Result.ok(5).map(n -> n * 2);                          // Ok(10)
Result.<Integer, String>error("fail").map(n -> n * 2); // Err("fail")
```

#### `mapError(f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L177). Transforms the error value; successes pass through. Typical for translating a low-level error into a domain error or adding context.

```java
Result.<String, Integer>error(404).mapError(e -> "HTTP " + e); // Err("HTTP 404")
```

#### `mapBoth(onOk, onError)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L198). Transforms both variants in one call.

```java
result.mapBoth(String::length, IoError::message); // Result<Integer, String>
```

#### `flatMap(f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L224). Chains a fallible operation on the success value (monadic bind). The first `Err` in a chain short-circuits everything after it.

```java
Result.ok(5).flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive"));  // Ok(5)
Result.ok(-1).flatMap(n -> n > 0 ? Result.ok(n) : Result.error("must be positive")); // Err(...)
```

The [`CallingAnApi`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L730) example chains two dependent HTTP calls and verifies that the second request is never sent when the first fails.

#### `recover(f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L255). Attempts to turn an error into a new `Result`. The recovery function may return `Ok` or another `Err`, and that `Err` may have a different error type. An existing `Ok` passes through unchanged because it contains no error value.

Recover selected errors while preserving the original error type:

```java
find(id).map(Account::balance)
    .recover(e -> switch (e) {
      case UnknownAccount _ -> Result.ok(0L);   // treat as zero
      case Frozen _, InsufficientFunds _ -> Result.error(e); // still an error
    });
```

Or replace one failure domain with another. If `readCache` returns `Result<User, CacheError>` and `downloadUser` returns `Result<User, NetworkError>`, the recovered result has only `NetworkError` as its possible error:

```java
Result<User, NetworkError> user =
    readCache(id).recover(_ -> downloadUser(id));
```

The original `CacheError` is consumed by the recovery function. For partial recovery that may keep the old error or produce a new one, use a common error supertype for the resulting `Result`.

#### `filter(predicate, errorFunction)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L277). Converts an `Ok` to `Err` when the predicate fails. The error function receives the rejected value.

```java
Result.ok(10).filter(n -> n > 5, n -> "too small: " + n); // Ok(10)
Result.ok(3).filter(n -> n > 5, n -> "too small: " + n);  // Err("too small: 3")
```

#### `swap()`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L296). Exchanges the variants: `Ok(v)` becomes `Err(v)` and vice versa. Useful when an error is the interesting case, for example when asserting that an operation must fail.

### Side effects

#### `onOk(consumer)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L316). Runs the consumer if `Ok`, then returns `this`. For logging, metrics and audit trails inside a chain.

#### `onError(consumer)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L334). Runs the consumer if `Err`, then returns `this`.

```java
transfer(from, to, amount)
    .onOk(_ -> audit.add("ok"))
    .onError(e -> audit.add("failed: " + e));
```

#### `match(onOk, onError)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L354). Runs exactly one of two consumers. The `void` counterpart of `fold`, analogous to `Optional.ifPresentOrElse`.

```java
result.match(value -> response.ok(value), error -> response.fail(error));
```

### Unwrapping

These methods leave the `Result` wrapper. Prefer pattern matching or `fold` over unwrapping.

#### `fold(onOk, onError)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L380). Collapses both variants into a single value.

```java
String message = result.fold(v -> "Got: " + v, e -> "Failed: " + e);
```

The [`BankTransfers`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L306) example uses `fold` with an exhaustive `switch` to map every error to exactly one HTTP response.

#### `orElseThrow()`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L400). Returns the value or throws [`ResultException`](#resultexception) carrying the error. If the error is itself a `Throwable` it becomes the cause, so stack traces survive.

#### `orElseThrow(errorToException)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L422). Returns the value or throws an exception built from the error. Checked exceptions are supported: the method declares `throws X`, exactly like `Optional.orElseThrow`.

```java
Result.error("bad").orElseThrow(e -> new IOException("Got: " + e)); // throws IOException
```

#### `orElse(defaultValue)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L443). Returns the value or a fixed default.

#### `orElseGet(fallback)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L461). Returns the value or computes a fallback from the error.

```java
Result.error("fail").orElseGet(e -> "recovered from: " + e); // "recovered from: fail"
```

#### `toOptional()` / `toOptionalError()`

[toOptional](result/src/main/java/io/github/pertyjons/result/Result.java#L478), [toOptionalError](result/src/main/java/io/github/pertyjons/result/Result.java#L496). Convert to `Optional` of the value or of the error, discarding the other side. `toOptionalError` corresponds to Rust's `Result::err`.

#### `stream()`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L509). A stream of zero or one elements, like `Optional.stream`. Enables keeping only successes in a pipeline:

```java
var receipts = jobs.stream().map(this::run).flatMap(Result::stream).toList();
```

#### `isOk()` / `isError()`

[isOk](result/src/main/java/io/github/pertyjons/result/Result.java#L523), [isError](result/src/main/java/io/github/pertyjons/result/Result.java#L532). Boolean variant checks, for loop conditions and the like.

### Combining

#### `map2(ra, rb, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L924). Combines two independent results with a `BiFunction`. Short-circuits on the first error in argument order.

```java
Result.map2(Result.ok(1), Result.ok(2), Integer::sum);         // Ok(3)
Result.map2(Result.ok(1), Result.error("fail"), Integer::sum); // Err("fail")
```

#### `map3(ra, rb, rc, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L951). Combines three results with a [`TriFunction`](#trifunction). Ideal for building a record from independently validated parts:

```java
Result.map3(
    require(env, "HOST"),
    requireInt(env, "PORT"),
    requireInt(env, "TIMEOUT_MS").map(Duration::ofMillis),
    ServerConfig::new);
```

See [`ReadingConfiguration`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L43).

#### `map2All(ra, rb, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L983).

Combines two independent results, collecting both errors in argument order. The returned error list is immutable, and the combining function runs only when both inputs are `Ok`.

```java
Result.map2All(
    validateName(input),
    validateAge(input),
    Person::new); // Result<Person, List<ValidationError>>
```

#### `map3All(ra, rb, rc, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1024).

The three-input accumulating variant. Unlike `sequenceAll`, its successful inputs may have different types:

```java
Result<ServerConfig, List<ConfigError>> config = Result.map3All(
    require(env, "HOST"),
    requireInt(env, "PORT"),
    requireDuration(env, "TIMEOUT"),
    ServerConfig::new);
```

If several inputs are `Err`, every error is returned in argument order. See the accumulating configuration example in [`ReadingConfiguration`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L43).

### Collections

Two families: **short-circuiting** (`sequence`, `traverse`, `traverseIndexed`) stop at the first error, and **accumulating** (`sequenceAll`, `traverseAll`, `partition`) process every element. All returned lists are immutable.

#### `sequence(results)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1124). Turns a collection of results into `Ok(List<T>)` or the first `Err`. Analogous to Rust's `collect::<Result<Vec<T>, E>>()`.

```java
Result.sequence(List.of(Result.ok(1), Result.ok(2)));               // Ok([1, 2])
Result.sequence(List.of(Result.ok(1), Result.error("boom"), ...));  // Err("boom")
```

#### `traverse(items, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1159). Maps each element through a fallible function and collects. Equivalent to `sequence(items.stream().map(f).toList())` but stops calling `f` after the first `Err`, which matters when `f` is expensive or has side effects.

```java
Result.traverse(List.of("1", "2", "3"), s -> Result.ofCallable(() -> Integer.parseInt(s)));
// Ok([1, 2, 3])
```

#### `traverseIndexed(items, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1192). Like `traverse` but the function also receives the zero-based index, so errors can say where they happened.

```java
Result.traverseIndexed(lines, (i, line) -> parse(line)
    .mapError(e -> "line %d: %s".formatted(i + 1, e)));
```

See [`ParsingLines`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L235).

#### `sequenceAll(results)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1291). Like `sequence` but accumulates every error: `Ok(List<T>)` if all succeeded, otherwise `Err(List<E>)`.

```java
Result.sequenceAll(List.of(Result.ok(1), Result.error("a"), Result.ok(3), Result.error("b")));
// Err(["a", "b"])
```

The tool for form validation, where the user wants all violations at once. See [`ValidatingInput`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L173).

#### `traverseAll(items, f)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1316). Like `traverse` but calls `f` for every element and accumulates every error. The tool for imports where every bad line should be reported. See the lenient parser in [`ParsingLines`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L235).

#### `partition(results)`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1261). Separates results into values and errors, both kept, in a [`Partitioned`](#partitioned). Nothing short-circuits.

```java
var p = Result.partition(results);
p.values(); p.errors(); p.allOk(); p.allErrors();
```

### Stream collectors

`Collector` equivalents of the collection operations, for use with `Stream.collect`. The stream itself always runs to completion; use `traverse` on a collection when you need to stop calling the mapping function early. Implementation lives in [`ResultCollectors`](result/src/main/java/io/github/pertyjons/result/ResultCollectors.java) and supports parallel streams while preserving encounter order.

#### `toResult()`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1066). Collector form of `sequence`.

```java
lines.stream().map(this::parse).collect(Result.toResult()); // Result<List<Row>, ParseError>
```

#### `toResultAll()`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1084). Collector form of `sequenceAll`.

#### `toPartitioned()`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1101). Collector form of `partition`.

```java
var p = jobs.stream().map(this::run).collect(Result.toPartitioned());
log.info("{} succeeded, {} failed", p.values().size(), p.errors().size());
```

All three are exercised side by side in [`BatchJobs`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L551).

### Supporting types

#### `Unit`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L82). A record with a single shared `Unit.INSTANCE`, used as the success type when there is no value to return. All `Unit` values are equal.

#### `Partitioned`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L1218). `record Partitioned<T, E>(List<T> values, List<E> errors)` with `allOk()` and `allErrors()`. The canonical constructor copies both lists, so they are immutable and null-free however constructed.

#### `ResultException`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L92). Unchecked exception thrown by `orElseThrow()`. `error()` returns the original error value; if that value is a `Throwable` it is also the cause. Serializable only if the error value is.

#### `TriFunction`

[Source](result/src/main/java/io/github/pertyjons/result/Result.java#L894). A three-argument functional interface used by `map3`.

## Type inference and variance

Combination and collection methods accept covariant inputs such as `Result<? extends T, ? extends E>`, while callback parameters use `? super` / `? extends` wildcards where appropriate. In practice this means a `Result<Circle, IoError>` can participate in an operation producing `Result<Shape, Exception>`, a `flatMap` lambda may return a subtype of the declared error, and `recover` may replace the original error type entirely. `Result` itself remains invariant, like Java generic types generally. [`ResultApiTest.Variance`](result/src/test/java/io/github/pertyjons/result/ResultApiTest.java#L29) documents the supported cases.

Inference has three rough edges, each with a cure:

1. **Starting a chain with one case of a sealed error hierarchy.** `ensure`, `ofNullable`, `ofOptional` and `ofCallable` infer `E` from the supplier or mapper, so `() -> new ParseError.Missing(key)` makes `E` the record `Missing`, not `ParseError`. A later `flatMap` or `filter` that fails with another case then does not compile. Pass the parent type as the last argument:

   ```java
   Result.ofNullable(env.get("PORT"), () -> new ParseError.Missing("PORT"), ParseError.class)
       .flatMap(this::parsePort); // parsePort may fail with any ParseError
   ```

   The compiler checks that the supplier produces a `ParseError`. The witness goes last, as on `ok(value, Class)` and `error(error, Class)`, and like them it only works for non-generic error types. Inside `flatMap`, and in a `return` statement, the target type usually fixes `E`, so no witness is needed there. Inside `recover`, the target type instead fixes the new error type produced by the recovery. [`ResultApiTest.ErrorClassWitness`](result/src/test/java/io/github/pertyjons/result/ResultApiTest.java#L400) covers all four methods.
2. **Building an error directly into a sealed hierarchy.** `Result.error(new NotFound(id))` infers `E = NotFound`, not the sealed parent, and `error(error, Class)` does not help here because its witness fixes the *success* type. Assign to a declared type or write `Result.<T, TransferError>error(...)`. The end of [`BankTransfers`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L306) shows this.
3. **`var` with a factory.** `var r = Result.ok("x")` leaves `E` as `Object`. Use `Result.ok("x", IoError.class)` or a declared type.

An alternative to the witness for cases 1 and 2 is to give the sealed type static factory methods that return the parent, such as `static ParseError missing(String key) { return new Missing(key); }`. Every call site then infers the parent without further help.

## Asynchronous composition with AsyncResult

[`AsyncResult<T, E>`](result/src/main/java/io/github/pertyjons/result/AsyncResult.java) is included in the core `result` artifact. It wraps a `CompletionStage<Result<T, E>>` and never blocks. Use `transformResult` for the existing synchronous Result API and `flatMap` for dependent asynchronous operations. It is a separate type, not a subtype of `Result` or an implementation of `Future`.

| Method | Purpose | Returns |
|---|---|---|
| `from(stage)` | Wrap an existing stage; does not start or reschedule work | `AsyncResult<T, E>` |
| `completed(result)` | Wrap an already available result | `AsyncResult<T, E>` |
| `transformResult(fn)` | Apply ordinary Result operations to either variant; may change both types | `AsyncResult<U, F>` |
| `flatMap(fn)` | Run an asynchronous operation for `Ok`; pass `Err` through | `AsyncResult<U, E>` |
| `recover(fn)` | Run asynchronous recovery for `Err`; may change the error type | `AsyncResult<T, F>` |
| `fold(onOk, onError)` | Handle either variant to produce a plain value | `CompletionStage<R>` |
| `stage()` | Access the stage for standard Java composition | `CompletionStage<Result<T, E>>` |

For example, with a configured `ResultHttpClient API` and a JSON codec, fetch a user and then the user's latest order:

```java
import io.github.pertyjons.result.AsyncResult;
import java.util.concurrent.CompletionStage;

record User(int latestOrderId) {}
record Order(int total) {}

CompletionStage<String> message =
    AsyncResult.from(API.get("/users/{id}", 42).sendAsync(User.class))
        .flatMap(user -> AsyncResult.from(
            API.get("/orders/{id}", user.latestOrderId()).sendAsync(Order.class)))
        .transformResult(result -> result
            .map(Order::total)
            .onOk(System.out::println)
            .onError(System.err::println))
        .fold(total -> "Total: " + total, error -> "Failed: " + error);
```

If the user request returns `Err`, the order request is never sent. `transformResult` still runs on that `Err`, so `onError` can observe it. Use `.stage()` instead of `.fold(...)` when the caller needs the final `Result`.

Recovery can replace a cache error with a download outcome:

```java
// readCacheAsync: CompletionStage<Result<User, CacheError>>
// downloadUserAsync: CompletionStage<Result<User, NetworkError>>
AsyncResult<User, NetworkError> user = AsyncResult.from(readCacheAsync(id))
    .recover(_ -> AsyncResult.from(downloadUserAsync(id)));
```

**Expected errors and exceptional completion are separate.** `recover` handles only `Err`. An exception thrown by a callback, a failed source stage, or a null callback result completes the derived stage exceptionally; subsequent Result callbacks are skipped. Use standard stage operations such as `handle` or `exceptionally` to handle this channel explicitly. `fold` also requires a non-null output. Null method arguments are rejected immediately, while a source stage that completes with null is rejected through exceptional completion.

**Execution and cancellation.** No executor is chosen internally. Callbacks use non-async stage continuations and may run on the completing thread or immediately on the registering thread. Schedule blocking or expensive work explicitly in your operations. Cancellation follows the supplied stage implementation: cancelling a derived future does not guarantee cancellation of the source, a fallback, or an HTTP request. Keep the original operation's cancellation handle when you need that control. `stage()` exposes the composed stage, not a defensive copy or an upstream cancellation handle.

[`AsyncResultExampleTest`](result/src/test/java/io/github/pertyjons/result/AsyncResultExampleTest.java) demonstrates dependent requests, synchronous transformations and cache-to-download recovery using controlled futures without network access or sleeps. [`AsyncResultTest`](result/src/test/java/io/github/pertyjons/result/AsyncResultTest.java) covers composition, error propagation, null handling and cancellation of the source.

## Testing effectively with result-assertj

The [`result-assertj`](result-assertj) module provides [`ResultAssert`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java), a custom AssertJ assertion. Its point is that a test should state the expected outcome in one expression and get a precise failure message, instead of the unwrap-then-assert pattern:

```java
// Without result-assertj: three lines, and a failure says "expected true but was false"
assertThat(result.isOk()).isTrue();
var value = result.toOptional().orElseThrow();
assertThat(value).isEqualTo("hello");

// With result-assertj: one line, and a failure says
// "Expected Result to be Ok but was Error<oops>" or
// "Expected Result to contain value <hello> but was <world>"
assertThat(result).hasValue("hello");
```

### Setup

```kotlin
testImplementation("io.github.pertyjons:result-assertj:0.1.0")
```

Import the static factory alongside AssertJ's. The two `assertThat` overloads coexist because the parameter types differ:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
```

### Assertion catalogue

Every method first asserts the variant, so a wrong variant produces a message naming the actual content rather than a `ClassCastException` or a null.

| Method | Asserts | Failure message shape |
|---|---|---|
| [`isOk()`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L134) | variant is `Ok` | `Expected Result to be Ok but was Error<...>` |
| [`isError()`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L153) | variant is `Err` | `Expected Result to be Error but was Ok<...>` |
| [`hasValue(expected)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L176) | `Ok` and value equals | `Expected Result to contain value <a> but was <b>` |
| [`hasError(expected)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L197) | `Err` and error equals | `Expected Result to contain error <a> but was <b>` |
| [`hasValueSatisfying(Consumer)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L219) | `Ok`, then runs nested assertions on the value | whatever the nested assertion reports |
| [`hasErrorSatisfying(Consumer)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L236) | `Err`, then nested assertions on the error | as above |
| [`hasValueSatisfying(Condition)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L259) | `Ok` and value matches a reusable `Condition` | `Expected Result value to satisfy [desc] but <v> did not` |
| [`hasErrorSatisfying(Condition)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L283) | `Err` and error matches a `Condition` | `Expected Result error to satisfy [desc] but <e> did not` |
| [`hasValueMatching(Predicate, desc)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L309) | `Ok` and predicate holds | `Expected Result value to match [desc] but was <v>` |
| [`hasErrorMatching(Predicate, desc)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L332) | `Err` and error matches | `Expected Result error to match [desc] but was <e>` |
| [`hasValueInstanceOf(Class)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L354) | `Ok` and value is an instance | `Expected Result value to be instance of <X> but was <Y>` |
| [`hasErrorInstanceOf(Class)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L376) | `Err` and error is an instance | `Expected Result error to be instance of <X> but was <Y>` |

**Transformations that stay in `ResultAssert`.** These let you assert on a derived result without leaving the fluent chain. Errors pass through exactly as in the core API.

| Method | Effect |
|---|---|
| [`map(fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L415) | assert on `actual.map(fn)` |
| [`flatMap(fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L440) | assert on `actual.flatMap(fn)` |
| [`map2(other, fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L462) | assert on `Result.map2(actual, other, fn)` |
| [`map3(b, c, fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L486) | assert on `Result.map3(actual, b, c, fn)` |
| [`map2All(other, fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L504) | assert on `Result.map2All(actual, other, fn)` and accumulate errors |
| [`map3All(b, c, fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L522) | assert on `Result.map3All(actual, b, c, fn)` and accumulate errors |
| [`mapError(fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L551) | assert on `actual.mapError(fn)` |

**Extractions that hand over to standard AssertJ.** These assert the variant, then return an `ObjectAssert` so the full AssertJ vocabulary (`asString()`, `isInstanceOf`, `extracting`, `satisfies`, ...) is available.

| Method | Returns |
|---|---|
| [`extractingValue()`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L573) | `ObjectAssert<T>` on the value |
| [`extractingValue(fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L596) | `ObjectAssert<U>` on `fn(value)` |
| [`extractingError()`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L614) | `ObjectAssert<E>` on the error |
| [`extractingError(fn)`](result-assertj/src/main/java/io/github/pertyjons/result/assertj/ResultAssert.java#L634) | `ObjectAssert<U>` on `fn(error)` |

Every one of these is covered in [`ResultAssertTest`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertTest.java), one nested class per method, including the exact failure messages.

### Patterns that keep tests short and failures clear

**Assert the whole record.** Records have value equality, so compare the entire expected object rather than field by field. One line, and the failure prints both records:

```java
assertThat(parse(env)).hasValue(new ServerConfig("api.internal", 8443, Duration.ofMillis(2500)));
assertThat(bank.transfer("A", "B", 250)).hasError(new InsufficientFunds("A", 100, 250));
```

**Assert the error type when the details are incidental.** For sealed hierarchies, `hasErrorInstanceOf` states the case being tested without repeating every field:

```java
assertThat(bank.transfer("A", "B", 1)).hasErrorInstanceOf(TransferError.Frozen.class);
```

**Dig into a field with `extractingError(fn)` or `extractingValue(fn)`.** Cast inside the mapper when the error type is sealed:

```java
assertThat(client.get("/orders/3").flatMap(this::parseOrder))
    .hasErrorInstanceOf(ApiError.MalformedBody.class)
    .extractingError(e -> ((ApiError.MalformedBody) e).reason())
    .isEqualTo("not a number: lots");
```

**Chain variant, value and type checks.** Each step fails with its own message:

```java
assertThat(result)
    .isOk()
    .hasValueInstanceOf(String.class)
    .hasValue("hello");
```

**Use `map` when the interesting property is derived.** Rather than extracting and losing the `Result` vocabulary:

```java
assertThat(result).map(Payload::toUtf8String).hasValueMatching(s -> s.contains("sign-on"), "contains sign-on");
```

**Reuse `Condition`s for domain rules.** A `Condition` carries its own description, composes with `allOf`/`anyOf`/`not`, and reads well in the failure message:

```java
Condition<String> timeout = new Condition<>(s -> s.contains("timeout"), "a timeout message");
assertThat(result).hasErrorSatisfying(timeout);
```

**Name the result under test with `as(...)`.** When several results are checked in one test, a description says which one failed. The description, a custom representation and an overriding error message all survive `map`, `flatMap`, `map2`, `map3`, `map2All`, `map3All`, `mapError` and every `extracting...` call, so put `as(...)` once at the front of the chain:

```java
assertThat(lookup("42")).as("user lookup").map(User::name).hasValue("Alice");
// on failure: [user lookup] Expected Result to contain value <Alice> but was <Bob>
```

This is guaranteed by [`ResultAssertTest.PreservesAssertionState`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertTest.java#L734).

**Test side effects and short-circuiting together.** A fake collaborator that records calls lets one test verify both the outcome and that nothing unnecessary happened:

```java
assertThat(latestOrderFor(client, "42")).hasErrorInstanceOf(ApiError.Unauthorized.class);
assertThat(client.requests).containsExactly("/users/42"); // second call never made
```

**Assert accumulated errors as a list.** With `sequenceAll`/`traverseAll`/`toResultAll` the error is a `List<E>`, so `hasError(List.of(...))` checks count, order and content at once:

```java
assertThat(jobs.stream().map(this::run).collect(Result.toResultAll()))
    .hasError(List.of("job b failed", "job d failed"));
```

**Null is handled.** `assertThat((Result<T, E>) null).isOk()` fails with AssertJ's standard `isNotNull` message rather than a `NullPointerException`.

## HTTP with result-http

[`result-http`](result-http) wraps `java.net.http.HttpClient` in a fluent, immutable API where every exchange ends in a `Result<..., HttpError>`. It adds no dependency beyond `result` itself. The entry point is [`ResultHttpClient`](result-http/src/main/java/io/github/pertyjons/result/http/ResultHttpClient.java); failures are the sealed [`HttpError`](result-http/src/main/java/io/github/pertyjons/result/http/HttpError.java).

```java
import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.Result.Err;
import io.github.pertyjons.result.Result.Ok;
import io.github.pertyjons.result.http.ResultHttpClient;
import io.github.pertyjons.result.http.HttpError;
import java.time.Duration;

static final ResultHttpClient API = ResultHttpClient.DEFAULT
    .baseUrl("https://api.example.com")
    .header("Accept", "application/json")
    .timeout(Duration.ofSeconds(5));

Result<Person, HttpError> person = API.get("/people/{id}", 42).send(Person.class);

String outcome = switch (person) {
  case Ok(var p)                          -> "hello " + p.name();
  case Err(HttpError.Status s)            -> "server said " + s.code() + ": " + s.body();
  case Err(HttpError.Timeout t)           -> "too slow";
  case Err(HttpError.Transport t)         -> "network: " + t.cause().getMessage();
  case Err(HttpError.Interrupted i)       -> "interrupted";
  case Err(HttpError.Decode d)            -> "unreadable: " + d.error().message();
};
```

### Four phases

The API is used in four phases, and each phase only exposes the methods that make sense in it, so IDE completion leads you through the chain and the compiler rejects a wrong order. Requests without a body skip the second phase.

| Phase | Type | What you can do |
|---|---|---|
| Configure | `ResultHttpClient` | `baseUrl`, `header`, `withoutHeader`, `timeout`, `deadline`, `maxErrorBodyBytes`, `successWhen`, `codec`, `client`, `observe`; then `get`/`delete`/`head`/`post`/`put`/`patch`/`method` |
| Choose body | `NeedsBody` (after `post`, `put`, `patch`, `method`) | `body(BodyPublisher)`, `body(text, contentType)`, `json(value)`, `tryJson(value)`, `noBody()` — nothing else |
| Refine and send | `Request` | `query`, `header`, `timeout`, `deadline`, `maxErrorBodyBytes`, `successWhen`, `operation`, `toHttpRequest`; then `send(...)` or `sendAsync(...)` |
| Handle | `Result<..., HttpError>` | the ordinary `Result` API: `map`, `flatMap`, `mapError`, `fold`, pattern matching |

`send` and `sendAsync` are the only request methods with side effects. Everything before them is pure data collection, which makes request building trivial to test with `toHttpRequest()` and no network.

### Configuring a client

`ResultHttpClient.DEFAULT` is a shared, immutable instance over `HttpClient.newHttpClient()` with no base URL, no headers, a 10 second response-header timeout, a 30 second total deadline, a 64 KiB error-body limit and 2xx counted as success. Every configuration method returns a new instance, so "build on" and "override" are the same operation and a shared constant can be refined per team without locking:

```java
static final ResultHttpClient API = ResultHttpClient.DEFAULT
    .baseUrl("https://api.example.com")
    .header("Accept", "application/json");

static final ResultHttpClient REPORTS = API                // inherits base URL and Accept
    .timeout(Duration.ofSeconds(60))
    .deadline(Duration.ofSeconds(90))
    .header("X-Tenant", "reports");
```

Header names are case-insensitive and a later `header` replaces an earlier one with the same name. There is deliberately no mutable global: an application defines its own `static final` base as above.

Settings the JDK client owns — connect timeout, redirects, proxy, SSL context, executor, HTTP version — are configured on an `HttpClient` and passed in. Nothing is configured in two places.

```java
HttpClient jdk = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

ResultHttpClient a = ResultHttpClient.create(jdk);                                       // wrap an existing client
ResultHttpClient b = ResultHttpClient.create(builder -> builder.connectTimeout(Duration.ofSeconds(2))); // configure inline
ResultHttpClient c = API.client(mtlsClient);                                             // swap the client, keep the rest
```

### Building a request

Paths are resolved against the base URL; slashes at the seam are normalised. An absolute URL ignores the base. `{name}` placeholders are filled positionally and URL-encoded, and query parameters may be repeated:

```java
API.get("/users/{id}/files/{name}", 42, "a b")   // → /users/42/files/a%20b
   .query("tag", "x").query("tag", "y")          // → ?tag=x&tag=y
   .header("Accept", "text/csv")                 // overrides the client's Accept for this call only
   .successWhen(status -> status == 404 || status / 100 == 2)
   .send(BodyHandlers.ofString());
```

`post`, `put`, `patch` and `method` return `NeedsBody`, whose only methods choose the body, so the body cannot be forgotten and headers cannot be set before it is decided:

```java
API.post("/users").json(new Person("Ada")).send();                    // application/json via the codec
Result<Request, CodecError> encoded = API.post("/users").tryJson(person); // keep encoding failure as Err
API.put("/users/{id}", 42).body(csv, "text/csv").send();              // UTF-8 text with Content-Type
API.patch("/users/{id}", 42).body(BodyPublishers.ofFile(path)).send(); // raw publisher, no Content-Type
API.method("OPTIONS", "/users").noBody().send();                       // explicit no body
```

### Sending

| Method | Returns | Notes |
|---|---|---|
| `send(BodyHandler<T>)` | `Result<HttpResponse<T>, HttpError>` | Any JDK body handler. |
| `send()` | `Result<HttpResponse<Unit>, HttpError>` | Body ignored and reported as `Unit`; for `HEAD`, `DELETE` and writes. |
| `send(Class<T>)` | `Result<T, HttpError>` | Body decoded by the `JsonCodec`. |
| `send(TypeRef<T>)` | `Result<T, HttpError>` | Same, for generic types such as `List<Person>`. |
| `sendAsync(...)` | `CompletableFuture<Result<..., HttpError>>` | Same four overloads. The future completes normally with an `Err` for every `HttpError`; cancellation and programming errors still complete it exceptionally. |

The success predicate is evaluated as soon as the status line and headers arrive, before the body is read. An accepted response is read with the handler you asked for. A rejected response is read as text up to `maxErrorBodyBytes`, whatever handler you asked for and even with `send()`. `Status.bodyTruncated()` reports whether only a prefix was retained. Decoding happens after that check, so a JSON error message from the server becomes `Err(Status)` with its raw text rather than a decoding failure against the wrong type.

### Resource limits

Three independent limits protect exchanges:

| Setting | Default | Covers |
|---|---|---|
| `timeout(Duration)` | 10 seconds | Waiting for response headers, using the JDK request timeout. |
| `deadline(Duration)` | 30 seconds | The complete exchange: response headers, body handling and JSON decoding. |
| `maxErrorBodyBytes(int)` | 65536 bytes (64 KiB) | The retained prefix of a response rejected by `successWhen`. |

Configure them on the client or override them on an individual request:

```java
var api = ResultHttpClient.DEFAULT
    .baseUrl("https://api.example.com")
    .timeout(Duration.ofSeconds(3))
    .deadline(Duration.ofSeconds(10))
    .maxErrorBodyBytes(16 * 1024);

var response = api.get("/reports")
    .deadline(Duration.ofSeconds(60))
    .maxErrorBodyBytes(4 * 1024)
    .send();
```

Changing the header timeout does not change the total deadline. Both durations must be positive. The body limit accepts zero to retain no error text, and rejects negative values. A body exactly at the byte limit is complete; one exceeding it produces `Err(Status)` with `bodyTruncated() == true`. The subscriber cancels further reading on overflow instead of draining an arbitrarily large response. Response headers remain the server's original headers, so `Content-Length` can exceed the retained prefix. The original Content-Type charset is respected; a multibyte character cut at the limit may decode to a replacement character.

The error-body limit applies to rejected HTTP statuses. Accepted bodies, including raw JSON retained in `HttpError.Decode`, follow the selected body handler and are not size-limited by this setting.

A total deadline produces `Err(Timeout)` and attempts to stop in-flight work. Each exchange runs on a virtual thread; interruption cancels the underlying JDK future while it is waiting for HTTP, and interrupts decoding if decoding has started. A custom codec that ignores interruption can keep running, but its late result cannot replace the timeout. Async cancellation also interrupts the exchange. Request construction and JSON request-body encoding happen before the exchange and are outside its deadline.

For streaming handlers such as `BodyHandlers.ofInputStream()`, the deadline ends when the response becomes available to the caller. Subsequent consumption has no library deadline: the caller must manage its read limits and close the stream. The completion observer reports one final outcome, including total-deadline timeouts.

### HttpError

| Variant | When | Carries |
|---|---|---|
| `Transport(IOException cause)` | DNS failure, connection refused or reset, TLS failure, stream error | the exception |
| `Timeout(HttpTimeoutException cause)` | response-header timeout, total deadline, or the JDK client's connect timeout | the exception |
| `Interrupted()` | the calling thread was interrupted; the flag is restored | nothing |
| `Status(HttpResponse<String> response, boolean bodyTruncated)` | the status was not accepted by `successWhen` | response metadata, bounded text body and truncation flag |
| `Decode(CodecError error, HttpResponse<String> response)` | the codec could not convert the body | the codec's message, the library's exception if there was one, and the response with its raw text |

`Status` and `Decode` implement `HttpError.WithResponse`, which adds `code()`, `uri()`, `headers()` and `body()` on top of `response()`. The three network errors do not, since there is no response to show. This lets one branch handle everything the server actually answered:

```java
String message = switch (error) {
  case HttpError.WithResponse r -> r.code() + " from " + r.uri() + ": " + r.body();
  case HttpError.Transport t    -> "network: " + t.cause().getMessage();
  case HttpError.Timeout t      -> "timed out";
  case HttpError.Interrupted i  -> "interrupted";
};
```

Because `body()` is text and the codec is at hand, a typed API error is one `mapError` away:

```java
Result<Person, ApiError> person = API.get("/people/{id}", 42).send(Person.class)
    .mapError(e -> e instanceof HttpError.WithResponse r
        ? codec.decode(r.body(), ApiError.class).orElse(new ApiError.Unknown(r.code()))
        : new ApiError.Unavailable());
```

`HttpError` is sealed and callers cannot add variants; map it into your own domain error with `mapError` at the boundary as above.

### JSON and JsonCodec

`result-http` ships no JSON library. It defines [`JsonCodec`](result-http/src/main/java/io/github/pertyjons/result/http/JsonCodec.java), an interface with one decoding method to implement, `Result<Object, CodecError> decode(String, Type)`, plus typed default overloads `decode(String, Class<T>)` and `decode(String, TypeRef<T>)` that return `Result<T, CodecError>` and are what the client calls, and discovers an implementation through `ServiceLoader` when a client is created. Adding [`result-http-jackson`](result-http-jackson) to the classpath registers a Jackson 3 codec, so `ResultHttpClient.DEFAULT` decodes JSON with no configuration. To use a mapper the rest of the application has configured:

```java
ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
ResultHttpClient api = ResultHttpClient.DEFAULT.codec(JacksonCodec.of(mapper));
```

Any other library is a few lines against the same interface. An implementation overrides only the untyped `decode(String, Type)` and `encode`; the typed `decode(String, Class<T>)` and `decode(String, TypeRef<T>)` are inherited defaults. The error type is `CodecError`, a record of a message and an optional cause: a codec that spots a problem itself reports a message, and never constructs an exception just to carry it. `JsonCodec.attempt` turns a throwing library call into a `Result`, keeping the thrown exception as the cause and reporting a `null` result (the JSON literal `null`) as an error with a message. Gson, for example:

```java
public final class GsonCodec implements JsonCodec {
  private final Gson gson = new Gson();

  @Override  // the one decoding method to implement; the typed overloads are inherited
  public Result<Object, CodecError> decode(String json, Type type) {
    return JsonCodec.attempt(() -> gson.fromJson(json, type));
  }

  @Override
  public Result<String, CodecError> encode(Object value) {
    return JsonCodec.attempt(() -> gson.toJson(value));
  }
}

JsonCodec codec = new GsonCodec();
Result<Person, CodecError> one        = codec.decode(json, Person.class);                  // inherited, checked against the class
Result<List<Person>, CodecError> many = codec.decode(json, new TypeRef<List<Person>>() {}); // inherited
```

Register it in `META-INF/services/io.github.pertyjons.result.http.JsonCodec` to make it the default, or pass it to `codec(...)`.

Primitive targets such as `int.class` are supported: the codec receives the requested target and the result is checked against its boxed class (`Integer` for `int`).

`TypeRef` subclasses must extend `TypeRef` directly and supply a type without unresolved type variables. Both `new TypeRef<List<Person>>() {}` and a named `class People extends TypeRef<List<Person>> {}` work. Indirect inheritance and captures such as `new TypeRef<List<T>>() {}` inside a generic method throw `IllegalArgumentException` at construction.

### Errors versus bugs

Only conditions that depend on the network or the server become an `HttpError`. Things the programmer controls throw, in the same way `Result` rejects `null`:

Exceptions from `successWhen`, body handlers and body mappings propagate from `send()` and complete `sendAsync()` exceptionally. Synchronous sends wait for the deadline-controlled exchange; interruption attempts to cancel it and restores the interrupt flag. The first terminal outcome wins when interruption races a completed response or deadline.

| Situation | Outcome |
|---|---|
| Relative path with no base URL, placeholder count mismatch, non-absolute base URL, zero or negative timeout | `IllegalArgumentException` |
| `send(Class)`, `send(TypeRef)` or `json(...)` with no codec configured | `IllegalStateException`, thrown before anything is sent |
| The codec cannot serialise the object given to `json(...)` | `IllegalArgumentException` |
| The codec cannot serialise the object given to `tryJson(...)` | `Err(CodecError)` |
| The codec cannot parse a response body | `Err(Decode)` |

The module's own tests run against the JDK's built-in `HttpServer` and a fake codec, so `result-http` is verified without any JSON dependency; [`ResultHttpClientTest`](result-http/src/test/java/io/github/pertyjons/result/http/ResultHttpClientTest.java) covers every `HttpError` variant, URL building, header precedence, the `NeedsBody` flow and the async path.

## Logging

The library is silent by default and has no logging dependency. Your application chooses the logger, levels and destination. Use `Result.onOk` / `onError` to log application outcomes, and `ResultHttpClient.observe(...)` to log HTTP completions centrally.

### Log HTTP requests

Register an observer on your application's base client. This example uses the JDK logger and needs no extra dependency:

```java
import io.github.pertyjons.result.http.ResultHttpClient;
import static java.lang.System.Logger.Level.DEBUG;

System.Logger logger = System.getLogger("myapp.http");

var api = ResultHttpClient.DEFAULT
    .baseUrl("https://api.example.com")
    .observe(event -> {
      if (logger.isLoggable(DEBUG)) {
        String status = event.statusCode().isPresent()
            ? Integer.toString(event.statusCode().getAsInt()) : "-";
        logger.log(DEBUG,
            "HTTP operation={0} method={1} host={2} status={3} durationMs={4} outcome={5}",
            event.operation(), event.method(), event.host(), status,
            event.duration().toMillis(), event.outcome());
      }
    });

api.get("/users/{id}", 42)
    .operation("get-user")
    .send();
```

Configure the application's logging backend to enable DEBUG when these entries are wanted. The observer receives an immutable [`HttpObservation`](result-http/src/main/java/io/github/pertyjons/result/http/HttpObservation.java):

| Field | Meaning |
|---|---|
| `method()` | HTTP method, e.g. `GET`. |
| `operation()` | Explicit `.operation("get-user")` label; defaults to the HTTP method. |
| `host()` | Original destination host, excluding credentials and port. |
| `duration()` | Monotonic elapsed time as a `Duration`, including body handling and JSON decoding, excluding the observer. |
| `statusCode()` | `OptionalInt`: populated when response headers arrived, even if handling or decoding later failed. |
| `outcome()` | `SUCCESS`, `STATUS`, `TRANSPORT`, `TIMEOUT`, `INTERRUPTED`, `DECODE`, `CANCELLED` or `EXCEPTION`. |
| `exceptionType()` | Optional exception class name; no message, stack trace or exception object. |

The status predicate determines success, so an explicitly accepted 404 can have outcome `SUCCESS`. JSON decoding must also succeed: a 200 response with undecodable JSON has outcome `DECODE`. Programming exceptions have outcome `EXCEPTION` and still propagate through the normal synchronous or asynchronous error channel.

If your application already uses SLF4J, pass its logger directly through the callback instead. For example, with `logger` being your application's SLF4J logger:

```java
var loggedApi = api.observe(event -> logger.debug(
    "HTTP operation={} outcome={} durationMs={}",
    event.operation(), event.outcome(), event.duration().toMillis()));
```

`observe` returns a new client and replaces its previous observer. All other client settings are preserved, and later configuration calls keep the observer. To send the event to multiple destinations, call both from one callback. Each concurrent send has its own event, including when the same immutable request is reused.

### Completion and sensitive data

- Exactly one event is delivered for each send that passes argument, codec and request validation. All eight `send` / `sendAsync` overloads are covered. Building a request, encoding its body and failures before a send can start produce no event.
- A typed JSON send is observed after decoding finishes. For streaming handlers such as `BodyHandlers.ofInputStream()`, the event describes when the response becomes available; it does not measure subsequent stream consumption or report failures during that consumption.
- Cancelling the future returned by `sendAsync` produces `CANCELLED`. This describes local cancellation, not confirmation that the server stopped processing. Cancelling a separately derived future is not guaranteed to cancel the original send.
- Synchronous observers run before the method returns. Asynchronous observers can run on a completion or cancellation thread, or immediately on the registering thread. Awaiting the result does not guarantee that its observer has finished. Keep callbacks fast and thread-safe; thread-local context such as MDC is not propagated automatically.
- A `RuntimeException` from the observer is ignored and does not change the HTTP result, original exception or cancellation state. This is a diagnostic hook, not a durable audit mechanism.
- Events exclude URL paths, queries, fragments, headers, request/response bodies and exception messages. Operation names are passed through unchanged: use a fixed label such as `get-user` or `GET /users/{id}`, without identifiers or credentials.

### Log application outcomes

`Result` itself does not automatically log `Err`: an error may be expected or recovered later. Choose the level at the point where your application decides the final outcome. For example, using a JDK logger:

```java
api.get("/users/{id}", 42)
    .operation("get-user")
    .send()
    .onError(error -> logger.log(System.Logger.Level.WARNING,
        "User lookup failed ({0})", error.getClass().getSimpleName()));
```

The HTTP observer can remain at DEBUG while the application logs an unrecovered operation failure once at its chosen level. `onError` handles returned `Err` values; exceptional completion of an asynchronous operation remains a separate channel. The HTTP observer covers both channels.

## Worked examples

[`ResultAssertExamplesTest`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java) contains eight self-contained scenarios. Each nested class has the "production" code at the top and the tests below it, using only the JDK, JUnit and AssertJ. Read them in order; they grow from a single `flatMap` chain to sealed error hierarchies, accumulation, collectors and exception boundaries.

| # | Scenario | Demonstrates |
|---|---|---|
| 1 | [`ReadingConfiguration`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L43) | `ofNullable`, `filter`, `flatMap`, `map3` vs. `map3All`, a sealed error type, pattern matching to a user-facing message |
| 2 | [`ValidatingInput`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L173) | `ensure` + `sequenceAll` to report every violation; `toPartitioned` for bulk import |
| 3 | [`ParsingLines`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L235) | `traverseIndexed` for line-numbered errors; strict vs. lenient parsing |
| 4 | [`BankTransfers`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L306) | service layer with `ofOptional`, `filter`, `map2`, `onOk`/`onError` audit, selective `recover`, `fold` to HTTP responses |
| 5 | [`FileIo`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L457) | `ofCallable` at an exception boundary, matching on exception type, `orElseThrow` with cause |
| 6 | [`BatchJobs`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L551) | `toResult` vs. `toResultAll` vs. `toPartitioned`, `Result::stream`, a generic retry helper |
| 7 | [`OrderLifecycle`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L631) | `Unit`-returning commands, `ensure` preconditions, a small state machine |
| 8 | [`CallingAnApi`](result-assertj/src/test/java/io/github/pertyjons/result/assertj/ResultAssertExamplesTest.java#L730) | HTTP status codes as a sealed error type, only 200 parsed into a record, dependent calls that short-circuit, exhaustive retry decision |

## Building

```text
./gradlew build                      # compile, test, javadoc, sources and javadoc jars
./gradlew test                       # tests only
./gradlew test javadoc --rerun-tasks # rerun verification without Gradle's up-to-date reuse
```

The build compiles with `-Xlint:all`, generates Javadoc for every module and runs the JUnit 6 suite. Every production `compileJava` task also runs NullAway through Error Prone in JSpecify mode (`OnlyNullMarked`), with nullness violations treated as build errors. Tests deliberately exercising null rejection are excluded from this analysis. These analysis tools are build-time dependencies and are not published as library dependencies. Gradle's configuration cache is enabled in [`gradle.properties`](gradle.properties). Dependency versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### Releasing

Publishing uses the [Vanniktech Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/) against the Sonatype Central Portal. Shared POM metadata lives in the root [`build.gradle.kts`](build.gradle.kts). Each module supplies its own `description`.

1. Set `version` in [`gradle.properties`](gradle.properties) to the release version (no `-SNAPSHOT`) and add an entry to [`CHANGELOG.md`](CHANGELOG.md).
2. Create a GitHub release with tag `v<version>` (for example `v0.1.0`). The [`publish`](.github/workflows/publish.yml) workflow builds, signs and releases all four modules to Maven Central.
3. Bump `version` to the next `-SNAPSHOT`.

Before any publish step, [release validation](.github/scripts/validate_release.py) checks that the checked-out `gradle.properties` has a non-snapshot release version and the tag is exactly `v<version>`. Manual workflow dispatch must also target a matching tag; branch dispatch is rejected. A shared concurrency group prevents overlapping publications and does not cancel a running release. The build workflow tests the validator; run those tests locally with `python3 -m unittest discover -s .github/scripts -p 'test_*.py'`.

The workflow needs these repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), `SIGNING_KEY` (an ASCII-armoured PGP private key) and `SIGNING_KEY_PASSWORD`. `./gradlew publishToMavenLocal` works without any of them. Signing is skipped when no key is configured.

## Design notes

- **Why a sealed interface with records, not a class hierarchy?** Records give value semantics and deconstruction patterns, and sealing gives exhaustive `switch`. The result is a type you can match on directly, which is often clearer than any method on the API.
- **Why reject null?** Allowing `Ok(null)` would reintroduce exactly the ambiguity `Result` exists to remove. `Unit` covers the legitimate "no value" case, and `ofNullable` covers the boundary with null-returning APIs.
- **Why both short-circuiting and accumulating collection operations?** They answer different questions. "Can I proceed?" wants the first blocker as cheaply as possible. "What is wrong with this form?" wants everything. Naming them separately (`sequence` vs. `sequenceAll`) keeps the choice visible at the call site.
- **Why `Err` rather than `Error`?** So `import io.github.pertyjons.result.Result.Err` never shadows `java.lang.Error`, and so a `case Err(var e)` pattern reads unambiguously.
- **Why do pass-through cases return `this`?** `map` on an `Err` cannot change anything, so re-typing the phantom parameter and returning the same instance avoids allocation. The same technique is used by `Optional.flatMap`. [`ResultApiTest.PassThroughIdentity`](result/src/test/java/io/github/pertyjons/result/ResultApiTest.java#L188) pins this down.
- **Why is the payload not copied?** Copying arbitrary generic values is not possible in general, and forcing a copy strategy on callers would be worse than documenting the contract. The container is immutable; the payload is whatever you put in.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
