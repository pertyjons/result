package io.github.pertyjons.result.assertj;

import static io.github.pertyjons.result.assertj.ResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pertyjons.result.Result;
import io.github.pertyjons.result.Result.Unit;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scenario-based examples of {@link Result} in realistic code, asserted with {@link ResultAssert}.
 * Each nested class is a self-contained mini-domain: the "production" code lives at the top of the
 * class, the tests below it. Only the JDK, JUnit and AssertJ are used.
 *
 * <p>Read in order — the scenarios grow from a single {@code flatMap} chain to sealed error
 * hierarchies, error accumulation, stream collectors, exception boundaries and an HTTP client whose
 * status codes become a sealed error type.
 */
class ResultAssertExamplesTest {

  // ==========================================================================================
  // 1. Reading configuration — ofNullable, filter, flatMap, map3/map3All and a sealed error type.
  //    Typical for parsing environment variables or a Properties file at startup.
  // ==========================================================================================

  @Nested
  class ReadingConfiguration {

    sealed interface ConfigError {
      record Missing(String key) implements ConfigError {}

      record Invalid(String key, String value, String reason) implements ConfigError {}
    }

    record ServerConfig(String host, int port, Duration timeout) {}

    /** A required, non-blank string setting. */
    Result<String, ConfigError> require(Map<String, String> env, String key) {
      return Result.ofNullable(env.get(key), () -> new ConfigError.Missing(key), ConfigError.class)
          .filter(v -> !v.isBlank(), v -> new ConfigError.Invalid(key, v, "must not be blank"));
    }

    /** A required integer setting. Exceptions from parseInt become domain errors. */
    Result<Integer, ConfigError> requireInt(Map<String, String> env, String key) {
      return require(env, key)
          .flatMap(
              v ->
                  Result.ofCallable(
                      () -> Integer.parseInt(v.trim()),
                      _ -> new ConfigError.Invalid(key, v, "not an integer")));
    }

    /** Combines three independent settings; the first failure wins. */
    Result<ServerConfig, ConfigError> parse(Map<String, String> env) {
      return Result.map3(
          require(env, "HOST"),
          requireInt(env, "PORT")
              .filter(
                  p -> p > 0 && p < 65_536,
                  p -> new ConfigError.Invalid("PORT", p.toString(), "out of range")),
          requireInt(env, "TIMEOUT_MS").map(Duration::ofMillis),
          ServerConfig::new);
    }

    /** Combines the same independent settings but reports every failure in argument order. */
    Result<ServerConfig, List<ConfigError>> parseAll(Map<String, String> env) {
      return Result.map3All(
          require(env, "HOST"),
          requireInt(env, "PORT")
              .filter(
                  p -> p > 0 && p < 65_536,
                  p -> new ConfigError.Invalid("PORT", p.toString(), "out of range")),
          requireInt(env, "TIMEOUT_MS").map(Duration::ofMillis),
          ServerConfig::new);
    }

    @Test
    void parsesACompleteEnvironment() {
      var env = Map.of("HOST", "api.internal", "PORT", "8443", "TIMEOUT_MS", "2500");

      assertThat(parse(env))
          .isOk()
          .hasValue(new ServerConfig("api.internal", 8443, Duration.ofMillis(2500)))
          .extractingValue(ServerConfig::timeout)
          .isEqualTo(Duration.ofSeconds(2).plusMillis(500));
    }

    @Test
    void reportsTheMissingKey() {
      var env = Map.of("HOST", "api.internal", "TIMEOUT_MS", "2500");

      assertThat(parse(env)).hasError(new ConfigError.Missing("PORT"));
    }

    @Test
    void reportsWhyAValueIsInvalid() {
      var env = Map.of("HOST", "api.internal", "PORT", "eighty", "TIMEOUT_MS", "2500");

      assertThat(parse(env))
          .hasErrorInstanceOf(ConfigError.Invalid.class)
          .hasErrorSatisfying(
              e -> {
                var invalid = (ConfigError.Invalid) e;
                assertThat(invalid.key()).isEqualTo("PORT");
                assertThat(invalid.reason()).isEqualTo("not an integer");
              });
    }

    @Test
    void firstErrorWinsWhenSeveralSettingsAreBroken() {
      var env = Map.of("HOST", "  ", "PORT", "0");

      // HOST is checked first by map3, so its error is the one reported.
      assertThat(parse(env))
          .hasErrorMatching(
              e -> e instanceof ConfigError.Invalid i && i.key().equals("HOST"),
              "an Invalid error for HOST");
    }

    @Test
    void accumulatingVariantReportsEveryBrokenSetting() {
      var env = Map.of("HOST", "  ", "PORT", "0");

      assertThat(parseAll(env))
          .hasError(
              List.of(
                  new ConfigError.Invalid("HOST", "  ", "must not be blank"),
                  new ConfigError.Invalid("PORT", "0", "out of range"),
                  new ConfigError.Missing("TIMEOUT_MS")));
    }

    @Test
    void userFacingMessageViaPatternMatching() {
      Result<ServerConfig, ConfigError> result =
          parse(Map.of("HOST", "h", "PORT", "-1", "TIMEOUT_MS", "1"));

      var message =
          result.fold(
              cfg -> "Listening on " + cfg.host() + ":" + cfg.port(),
              err ->
                  switch (err) {
                    case ConfigError.Missing(var key) -> "Missing setting " + key;
                    case ConfigError.Invalid(var key, var value, var reason) ->
                        "Setting %s=%s is invalid: %s".formatted(key, value, reason);
                  });

      assertThat(message).isEqualTo("Setting PORT=-1 is invalid: out of range");
    }
  }

  // ==========================================================================================
  // 2. Validating user input — ensure + sequenceAll to report *every* violation at once,
  //    and partition for a bulk import. Typical for form handling and CSV imports.
  // ==========================================================================================

  @Nested
  class ValidatingInput {

    record Violation(String field, String message) {}

    record Registration(String email, String password, int age) {}

    Result<Registration, List<Violation>> validate(String email, String password, int age) {
      var checks =
          List.of(
              Result.ensure(email.contains("@"), new Violation("email", "must contain @")),
              Result.ensure(
                  password.length() >= 12, new Violation("password", "at least 12 chars")),
              Result.ensure(age >= 18, new Violation("age", "must be 18 or older")));
      return Result.sequenceAll(checks).map(_ -> new Registration(email, password, age));
    }

    @Test
    void acceptsValidInput() {
      assertThat(validate("ada@example.com", "correct-horse-battery", 36))
          .hasValueSatisfying(r -> assertThat(r.email()).isEqualTo("ada@example.com"));
    }

    @Test
    void reportsEveryViolationNotJustTheFirst() {
      assertThat(validate("not-an-email", "short", 17))
          .isError()
          .hasErrorSatisfying(
              violations ->
                  assertThat(violations)
                      .extracting(Violation::field)
                      .containsExactly("email", "password", "age"));
    }

    @Test
    void bulkImportSeparatesGoodRowsFromBadOnes() {
      record Row(String email, String password, int age) {}
      var rows =
          List.of(
              new Row("ada@example.com", "correct-horse-battery", 36),
              new Row("bad", "x", 1),
              new Row("bob@example.com", "another-long-secret", 41));

      var imported =
          rows.stream()
              .map(r -> validate(r.email(), r.password(), r.age()))
              .collect(Result.toPartitioned());

      assertThat(imported.values())
          .extracting(Registration::email)
          .containsExactly("ada@example.com", "bob@example.com");
      assertThat(imported.errors()).hasSize(1);
      assertThat(imported.errors().getFirst()).hasSize(3);
      assertThat(imported.allOk()).isFalse();
    }
  }

  // ==========================================================================================
  // 3. Parsing a text file line by line — traverseIndexed to prefix errors with the line
  //    number, traverseAll to report every bad line. Typical for fixed-format imports.
  // ==========================================================================================

  @Nested
  class ParsingLines {

    record Transaction(String account, long amountOre) {}

    Result<Transaction, String> parseLine(String line) {
      var fields = line.split(";");
      return Result.ensure(fields.length == 2, "expected 2 fields but got " + fields.length)
          .flatMap(
              _ ->
                  Result.ofCallable(
                      () -> Long.parseLong(fields[1].trim()),
                      _ -> "amount is not a number: '" + fields[1].trim() + "'"))
          .map(amount -> new Transaction(fields[0].trim(), amount));
    }

    /** Stops at the first bad line, which is what you want when later lines depend on it. */
    Result<List<Transaction>, String> parseStrict(List<String> lines) {
      return Result.traverseIndexed(
          lines, (i, line) -> parseLine(line).mapError(e -> "line %d: %s".formatted(i + 1, e)));
    }

    /** Reports every bad line, which is what you want for a user fixing a file by hand. */
    Result<List<Transaction>, List<String>> parseLenient(List<String> lines) {
      var numbered = new ArrayList<Result<Transaction, String>>();
      for (int i = 0; i < lines.size(); i++) {
        var lineNo = i + 1;
        numbered.add(parseLine(lines.get(i)).mapError(e -> "line %d: %s".formatted(lineNo, e)));
      }
      return Result.sequenceAll(numbered);
    }

    @Test
    void parsesAWellFormedFile() {
      var lines = List.of("SE01; 12500", "SE02; -300");

      assertThat(parseStrict(lines))
          .hasValue(List.of(new Transaction("SE01", 12_500), new Transaction("SE02", -300)));
    }

    @Test
    void strictParsingPointsAtTheFirstBadLine() {
      var lines = List.of("SE01; 12500", "SE02; twelve", "garbage");

      assertThat(parseStrict(lines)).hasError("line 2: amount is not a number: 'twelve'");
    }

    @Test
    void lenientParsingListsEveryBadLine() {
      var lines = List.of("SE01; 12500", "SE02; twelve", "garbage");

      assertThat(parseLenient(lines))
          .hasError(
              List.of(
                  "line 2: amount is not a number: 'twelve'",
                  "line 3: expected 2 fields but got 1"));
    }

    @Test
    void emptyFileIsAnEmptyList() {
      assertThat(parseStrict(List.of())).hasValue(List.of());
      assertThat(parseLenient(List.of())).hasValue(List.of());
    }
  }

  // ==========================================================================================
  // 4. A service layer with a sealed error hierarchy — ofOptional for repository lookups,
  //    filter for business rules, map2 to combine two lookups, onOk/onError for auditing,
  //    recover for a fallback, and exhaustive switch to map errors to HTTP-style responses.
  // ==========================================================================================

  @Nested
  class BankTransfers {

    sealed interface TransferError {
      record UnknownAccount(String id) implements TransferError {}

      record Frozen(String id) implements TransferError {}

      record InsufficientFunds(String id, long balance, long requested) implements TransferError {}
    }

    record Account(String id, long balance, boolean frozen) {}

    record Response(int status, String body) {}

    /** In-memory "repository" plus the service methods that operate on it. */
    class Bank {
      final Map<String, Account> accounts = new HashMap<>();
      final List<String> audit = new ArrayList<>();

      Bank(Account... initial) {
        for (var a : initial) {
          accounts.put(a.id(), a);
        }
      }

      Result<Account, TransferError> find(String id) {
        return Result.ofOptional(
            Optional.ofNullable(accounts.get(id)), () -> new TransferError.UnknownAccount(id));
      }

      Result<Account, TransferError> debit(Account account, long amount) {
        return Result.ok(account, TransferError.class)
            .filter(a -> !a.frozen(), a -> new TransferError.Frozen(a.id()))
            .filter(
                a -> a.balance() >= amount,
                a -> new TransferError.InsufficientFunds(a.id(), a.balance(), amount))
            .map(a -> new Account(a.id(), a.balance() - amount, false));
      }

      Result<Unit, TransferError> transfer(String from, String to, long amount) {
        return Result.map2(
                find(from).flatMap(a -> debit(a, amount)),
                find(to).map(b -> new Account(b.id(), b.balance() + amount, b.frozen())),
                (debited, credited) -> {
                  accounts.put(debited.id(), debited);
                  accounts.put(credited.id(), credited);
                  return Unit.INSTANCE;
                })
            .onOk(_ -> audit.add("transfer %s -> %s %d: ok".formatted(from, to, amount)))
            .onError(e -> audit.add("transfer %s -> %s %d: %s".formatted(from, to, amount, e)));
      }

      /** Unknown accounts read as zero; other errors are real and propagate. */
      Result<Long, TransferError> balanceOrZero(String id) {
        return find(id)
            .map(Account::balance)
            .recover(
                e ->
                    switch (e) {
                      case TransferError.UnknownAccount _ -> Result.ok(0L);
                      case TransferError.Frozen _, TransferError.InsufficientFunds _ ->
                          Result.error(e);
                    });
      }
    }

    /** The edge of the system: every error maps to exactly one response. */
    Response toResponse(Result<Unit, TransferError> result) {
      return result.fold(
          _ -> new Response(204, ""),
          error ->
              switch (error) {
                case TransferError.UnknownAccount(var id) -> new Response(404, "no account " + id);
                case TransferError.Frozen(var id) ->
                    new Response(403, "account " + id + " is frozen");
                case TransferError.InsufficientFunds(var id, var balance, var requested) ->
                    new Response(409, "%s has %d, requested %d".formatted(id, balance, requested));
              });
    }

    @Test
    void movesMoneyBetweenAccounts() {
      var bank = new Bank(new Account("A", 1000, false), new Account("B", 0, false));

      assertThat(bank.transfer("A", "B", 250)).hasValue(Unit.INSTANCE);
      assertThat(bank.accounts.get("A").balance()).isEqualTo(750);
      assertThat(bank.accounts.get("B").balance()).isEqualTo(250);
      assertThat(bank.audit).containsExactly("transfer A -> B 250: ok");
    }

    @Test
    void refusesToOverdrawAndLeavesBalancesUntouched() {
      var bank = new Bank(new Account("A", 100, false), new Account("B", 0, false));

      assertThat(bank.transfer("A", "B", 250))
          .hasError(new TransferError.InsufficientFunds("A", 100, 250));
      assertThat(bank.accounts.get("A").balance()).isEqualTo(100);
      assertThat(bank.accounts.get("B").balance()).isZero();
      assertThat(bank.audit.getFirst()).startsWith("transfer A -> B 250: InsufficientFunds");
    }

    @Test
    void frozenIsCheckedBeforeBalance() {
      var bank = new Bank(new Account("A", 0, true), new Account("B", 0, false));

      assertThat(bank.transfer("A", "B", 1)).hasErrorInstanceOf(TransferError.Frozen.class);
    }

    @Test
    void unknownDestinationIsReportedEvenThoughSourceIsFine() {
      var bank = new Bank(new Account("A", 1000, false));

      assertThat(bank.transfer("A", "nope", 1)).hasError(new TransferError.UnknownAccount("nope"));
      assertThat(bank.accounts.get("A").balance()).isEqualTo(1000);
    }

    @Test
    void recoverTurnsOnlyTheChosenErrorIntoADefault() {
      var bank = new Bank(new Account("A", 42, false));

      assertThat(bank.balanceOrZero("A")).hasValue(42L);
      assertThat(bank.balanceOrZero("ghost")).hasValue(0L);
    }

    @Test
    void everyErrorHasExactlyOneHttpResponse() {
      var bank = new Bank(new Account("A", 100, false), new Account("F", 0, true));

      assertThat(toResponse(bank.transfer("A", "F", 10))).isEqualTo(new Response(204, ""));
      assertThat(toResponse(bank.transfer("F", "A", 10)))
          .isEqualTo(new Response(403, "account F is frozen"));
      assertThat(toResponse(bank.transfer("A", "A", 500)))
          .isEqualTo(new Response(409, "A has 90, requested 500"));
      assertThat(toResponse(bank.transfer("Z", "A", 1)))
          .isEqualTo(new Response(404, "no account Z"));

      // Building an error directly: the Class witness fixes T, but E is still inferred from the
      // argument — here UnknownAccount, not TransferError — so a sealed hierarchy needs the
      // declared type (or Result.<Unit, TransferError>error(...)) to widen E.
      Result<Unit, TransferError> direct = Result.error(new TransferError.UnknownAccount("Q"));
      assertThat(toResponse(direct)).isEqualTo(new Response(404, "no account Q"));
    }
  }

  // ==========================================================================================
  // 5. Exception boundaries — ofCallable turns checked I/O exceptions into values, the
  //    exception type stays available for matching, and orElseThrow restores an exception
  //    (with cause) at the point where the caller really cannot continue.
  // ==========================================================================================

  @Nested
  class FileIo {

    @TempDir Path dir;

    Result<List<String>, Exception> readLines(Path file) {
      return Result.ofCallable(() -> Files.readAllLines(file));
    }

    Result<Unit, Exception> writeLines(Path file, List<String> lines) {
      return Result.ofCallable(
          () -> {
            Files.write(file, lines);
            return Unit.INSTANCE;
          });
    }

    /** Copy while normalising line endings; both I/O steps stay in Result-land. */
    Result<Integer, Exception> copyTrimmed(Path from, Path to) {
      return readLines(from)
          .map(lines -> lines.stream().map(String::strip).toList())
          .flatMap(lines -> writeLines(to, lines).map(_ -> lines.size()));
    }

    @Test
    void roundTripsThroughTheFileSystem() throws IOException {
      var src = dir.resolve("in.txt");
      var dst = dir.resolve("out.txt");
      Files.writeString(src, "  alpha \nbeta\t\n");

      assertThat(copyTrimmed(src, dst)).hasValue(2);
      assertThat(Files.readAllLines(dst)).containsExactly("alpha", "beta");
    }

    @Test
    void missingFileIsAValueNotAnException() {
      var result = readLines(dir.resolve("does-not-exist.txt"));

      assertThat(result)
          .hasErrorInstanceOf(NoSuchFileException.class)
          .extractingError(Exception::getMessage)
          .asString()
          .endsWith("does-not-exist.txt");
    }

    @Test
    void writeFailureShortCircuitsTheChain() throws IOException {
      var src = dir.resolve("in.txt");
      Files.writeString(src, "x\n");
      var unwritable = dir.resolve("missing-dir").resolve("out.txt");

      assertThat(copyTrimmed(src, unwritable)).hasErrorInstanceOf(NoSuchFileException.class);
    }

    @Test
    void orElseThrowKeepsTheOriginalExceptionAsCause() {
      var result = readLines(dir.resolve("nope.txt"));

      assertThatThrownBy(result::orElseThrow)
          .isInstanceOf(Result.ResultException.class)
          .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void orElseThrowCanRethrowAsACheckedDomainException() {
      class ConfigLoadException extends Exception {
        @Serial private static final long serialVersionUID = 1L;

        ConfigLoadException(String message, Throwable cause) {
          super(message, cause);
        }
      }
      var result = readLines(dir.resolve("app.conf"));

      assertThatThrownBy(
              () -> result.orElseThrow(e -> new ConfigLoadException("cannot load app.conf", e)))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessage("cannot load app.conf")
          .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void fallbackToDefaultsWhenTheFileIsOptional() {
      var lines = readLines(dir.resolve("optional.conf")).orElse(List.of("default=true"));

      assertThat(lines).containsExactly("default=true");
    }
  }

  // ==========================================================================================
  // 6. Batch processing — stream collectors for all-or-nothing vs. best-effort semantics,
  //    stream() to keep successes, and a small retry helper built on isError().
  // ==========================================================================================

  @Nested
  class BatchJobs {

    record Job(String id, boolean failing) {}

    record Receipt(String jobId) {}

    Result<Receipt, String> run(Job job) {
      return Result.ensure(!job.failing(), "job " + job.id() + " failed")
          .map(_ -> new Receipt(job.id()));
    }

    /** Re-runs the operation until it succeeds or the attempts are exhausted. */
    <T, E> Result<T, E> retry(int attempts, Supplier<Result<T, E>> operation) {
      var result = operation.get();
      for (int attempt = 2; attempt <= attempts && result.isError(); attempt++) {
        result = operation.get();
      }
      return result;
    }

    List<Job> jobs =
        List.of(new Job("a", false), new Job("b", true), new Job("c", false), new Job("d", true));

    @Test
    void allOrNothingStopsAtTheFirstFailure() {
      var result = jobs.stream().map(this::run).collect(Result.toResult());

      assertThat(result).hasError("job b failed");
    }

    @Test
    void bestEffortReportsEveryFailure() {
      var result = jobs.stream().map(this::run).collect(Result.toResultAll());

      assertThat(result).hasError(List.of("job b failed", "job d failed"));
    }

    @Test
    void keepOnlyTheSuccessfulReceipts() {
      var receipts = jobs.stream().map(this::run).flatMap(Result::stream).toList();

      assertThat(receipts).extracting(Receipt::jobId).containsExactly("a", "c");
    }

    @Test
    void summariseWithPartition() {
      var summary = jobs.stream().map(this::run).collect(Result.toPartitioned());

      assertThat(summary.values()).hasSize(2);
      assertThat(summary.errors()).hasSize(2);
      assertThat("%d ok, %d failed".formatted(summary.values().size(), summary.errors().size()))
          .isEqualTo("2 ok, 2 failed");
    }

    @Test
    void retrySucceedsOnceTheFlakyOperationDoes() {
      var calls = new AtomicInteger();
      Supplier<Result<String, String>> flaky =
          () -> calls.incrementAndGet() < 3 ? Result.error("timeout") : Result.ok("connected");

      assertThat(retry(5, flaky)).hasValue("connected");
      assertThat(calls).hasValue(3);
    }

    @Test
    void retryGivesUpWithTheLastError() {
      var calls = new AtomicInteger();
      Supplier<Result<String, String>> alwaysFails =
          () -> Result.error("attempt " + calls.incrementAndGet() + " timed out");

      assertThat(retry(3, alwaysFails)).hasError("attempt 3 timed out");
    }
  }

  // ==========================================================================================
  // 7. A small state machine — Unit-returning commands, ensure for preconditions, chained
  //    transitions, and match() to dispatch side effects without unwrapping.
  // ==========================================================================================

  @Nested
  class OrderLifecycle {

    enum Status {
      NEW,
      PAID,
      SHIPPED,
      CANCELLED
    }

    record Order(String id, Status status, List<String> items) {
      Order with(Status next) {
        return new Order(id, next, items);
      }
    }

    Result<Order, String> pay(Order order) {
      return Result.ensure(
              order.status() == Status.NEW, "cannot pay an order that is " + order.status())
          .flatMap(_ -> Result.ensure(!order.items().isEmpty(), "cannot pay for an empty order"))
          .map(_ -> order.with(Status.PAID));
    }

    Result<Order, String> ship(Order order) {
      return Result.ensure(
              order.status() == Status.PAID, "cannot ship an order that is " + order.status())
          .map(_ -> order.with(Status.SHIPPED));
    }

    Result<Order, String> cancel(Order order) {
      return Result.ensure(order.status() != Status.SHIPPED, "cannot cancel a shipped order")
          .map(_ -> order.with(Status.CANCELLED));
    }

    @Test
    void happyPathChainsTransitions() {
      var order = new Order("o-1", Status.NEW, List.of("book"));

      assertThat(pay(order).flatMap(this::ship))
          .extractingValue(Order::status)
          .isEqualTo(Status.SHIPPED);
    }

    @Test
    void illegalTransitionExplainsTheCurrentState() {
      var order = new Order("o-1", Status.NEW, List.of("book"));

      assertThat(ship(order)).hasError("cannot ship an order that is NEW");
      assertThat(pay(order).flatMap(this::ship).flatMap(this::cancel))
          .hasError("cannot cancel a shipped order");
    }

    @Test
    void preconditionsAreCheckedInOrder() {
      var empty = new Order("o-2", Status.NEW, List.of());

      assertThat(pay(empty)).hasError("cannot pay for an empty order");
      assertThat(pay(empty.with(Status.PAID))).hasError("cannot pay an order that is PAID");
    }

    @Test
    void matchDispatchesSideEffectsWithoutUnwrapping() {
      var notifications = new ArrayList<String>();
      var order = new Order("o-3", Status.NEW, List.of("book"));

      pay(order)
          .match(
              paid -> notifications.add("receipt for " + paid.id()),
              error -> notifications.add("payment failed: " + error));
      cancel(order.with(Status.SHIPPED))
          .match(
              cancelled -> notifications.add("cancelled " + cancelled.id()),
              error -> notifications.add("cancel failed: " + error));

      assertThat(notifications)
          .containsExactly("receipt for o-3", "cancel failed: cannot cancel a shipped order");
    }

    @Test
    void mapBothAdaptsToAnotherLayerInOneStep() {
      record Event(String type, String detail) {}
      var order = new Order("o-4", Status.NEW, List.of("book"));

      Result<Event, Event> event =
          ship(order)
              .mapBoth(
                  shipped -> new Event("OrderShipped", shipped.id()),
                  error -> new Event("ShipmentRejected", error));

      assertThat(event).hasError(new Event("ShipmentRejected", "cannot ship an order that is NEW"));
    }
  }

  // ==========================================================================================
  // 8. Calling an HTTP API — every status code becomes one variant of a sealed error type,
  //    only 200 is parsed into a record, and a dependent second call short-circuits on the
  //    first failure so no request is sent that cannot be used.
  // ==========================================================================================

  @Nested
  class CallingAnApi {

    sealed interface ApiError {
      record BadRequest(String detail) implements ApiError {}

      record Unauthorized() implements ApiError {}

      record NotFound(String path) implements ApiError {}

      record ServerError(int status) implements ApiError {}

      record MalformedBody(String reason) implements ApiError {}
    }

    record HttpResponse(int status, String body) {}

    record User(String id, String name) {}

    record Order(String id, String userId, long totalOre) {}

    /** A fake transport: paths map to canned responses, anything else is a 404. */
    class ApiClient {
      final Map<String, HttpResponse> canned = new HashMap<>();
      final List<String> requests = new ArrayList<>();

      ApiClient on(String path, int status, String body) {
        canned.put(path, new HttpResponse(status, body));
        return this;
      }

      /** The single place where status codes are interpreted. Only 200 carries a usable body. */
      Result<String, ApiError> get(String path) {
        requests.add(path);
        var response = canned.getOrDefault(path, new HttpResponse(404, ""));
        return switch (response.status()) {
          case 200 -> Result.ok(response.body());
          case 400 -> Result.error(new ApiError.BadRequest(response.body()));
          case 401 -> Result.error(new ApiError.Unauthorized());
          case 404 -> Result.error(new ApiError.NotFound(path));
          default -> Result.error(new ApiError.ServerError(response.status()));
        };
      }
    }

    /** Body format is {@code key=value;key=value}. Anything else is a malformed body. */
    Result<Map<String, String>, ApiError> fields(String body) {
      var map = new HashMap<String, String>();
      for (var pair : body.split(";")) {
        var kv = pair.split("=", 2);
        if (kv.length != 2 || kv[0].isBlank()) {
          return Result.error(new ApiError.MalformedBody("bad pair '" + pair + "'"));
        }
        map.put(kv[0].strip(), kv[1].strip());
      }
      return Result.ok(map);
    }

    Result<String, ApiError> field(Map<String, String> fields, String key) {
      return Result.ofNullable(fields.get(key), () -> new ApiError.MalformedBody("missing " + key));
    }

    Result<Long, ApiError> number(String raw) {
      return Result.ofCallable(
          () -> Long.parseLong(raw), _ -> new ApiError.MalformedBody("not a number: " + raw));
    }

    Result<User, ApiError> parseUser(String body) {
      return fields(body).flatMap(f -> Result.map2(field(f, "id"), field(f, "name"), User::new));
    }

    Result<Order, ApiError> parseOrder(String body) {
      return fields(body)
          .flatMap(
              f ->
                  Result.map3(
                      field(f, "id"),
                      field(f, "userId"),
                      field(f, "total").flatMap(this::number),
                      Order::new));
    }

    /** Two dependent calls: the order is only requested once the user lookup has succeeded. */
    Result<Order, ApiError> latestOrderFor(ApiClient client, String userId) {
      return client
          .get("/users/" + userId)
          .flatMap(this::parseUser)
          .flatMap(user -> client.get("/users/" + user.id() + "/orders/latest"))
          .flatMap(this::parseOrder);
    }

    /** Only transient server-side failures are worth retrying; the switch must be exhaustive. */
    boolean retryable(Result<?, ApiError> result) {
      return result.fold(
          _ -> false,
          error ->
              switch (error) {
                case ApiError.ServerError(var status) -> status >= 500;
                case ApiError.BadRequest _,
                    ApiError.Unauthorized _,
                    ApiError.NotFound _,
                    ApiError.MalformedBody _ ->
                    false;
              });
    }

    @Test
    void okResponseIsParsedIntoARecord() {
      var client = new ApiClient().on("/users/42", 200, "id=42;name=Alice");

      assertThat(client.get("/users/42").flatMap(this::parseUser))
          .hasValue(new User("42", "Alice"))
          .extractingValue(User::name)
          .isEqualTo("Alice");
    }

    @Test
    void eachStatusCodeHasItsOwnErrorVariant() {
      var client =
          new ApiClient()
              .on("/bad", 400, "id is required")
              .on("/private", 401, "")
              .on("/flaky", 500, "")
              .on("/down", 503, "");

      assertThat(client.get("/bad")).hasError(new ApiError.BadRequest("id is required"));
      assertThat(client.get("/private")).hasErrorInstanceOf(ApiError.Unauthorized.class);
      assertThat(client.get("/missing")).hasError(new ApiError.NotFound("/missing"));
      assertThat(client.get("/flaky")).hasError(new ApiError.ServerError(500));
      assertThat(client.get("/down")).hasError(new ApiError.ServerError(503));
    }

    @Test
    void okStatusWithABrokenBodyIsStillAnError() {
      var client =
          new ApiClient()
              .on("/users/1", 200, "id=1;name")
              .on("/users/2", 200, "id=2")
              .on("/orders/3", 200, "id=3;userId=2;total=lots");

      assertThat(client.get("/users/1").flatMap(this::parseUser))
          .hasError(new ApiError.MalformedBody("bad pair 'name'"));
      assertThat(client.get("/users/2").flatMap(this::parseUser))
          .hasError(new ApiError.MalformedBody("missing name"));
      assertThat(client.get("/orders/3").flatMap(this::parseOrder))
          .hasErrorInstanceOf(ApiError.MalformedBody.class)
          .extractingError(e -> ((ApiError.MalformedBody) e).reason())
          .isEqualTo("not a number: lots");
    }

    @Test
    void dependentCallsSucceedEndToEnd() {
      var client =
          new ApiClient()
              .on("/users/42", 200, "id=42;name=Alice")
              .on("/users/42/orders/latest", 200, "id=o-9;userId=42;total=12500");

      assertThat(latestOrderFor(client, "42")).hasValue(new Order("o-9", "42", 12_500));
      assertThat(client.requests).containsExactly("/users/42", "/users/42/orders/latest");
    }

    @Test
    void dependentCallsStopAtTheFirstFailure() {
      var client =
          new ApiClient()
              .on("/users/42", 401, "")
              .on("/users/42/orders/latest", 200, "id=o-9;userId=42;total=12500");

      assertThat(latestOrderFor(client, "42")).hasErrorInstanceOf(ApiError.Unauthorized.class);
      assertThat(client.requests).containsExactly("/users/42");
    }

    @Test
    void unparsableUserNeverTriggersTheOrderCall() {
      var client = new ApiClient().on("/users/42", 200, "garbage");

      assertThat(latestOrderFor(client, "42")).hasErrorInstanceOf(ApiError.MalformedBody.class);
      assertThat(client.requests).containsExactly("/users/42");
    }

    @Test
    void onlyServerErrorsAreRetryable() {
      var client = new ApiClient().on("/ok", 200, "").on("/bad", 400, "").on("/down", 503, "");

      assertThat(retryable(client.get("/ok"))).isFalse();
      assertThat(retryable(client.get("/bad"))).isFalse();
      assertThat(retryable(client.get("/missing"))).isFalse();
      assertThat(retryable(client.get("/down"))).isTrue();
    }
  }
}
