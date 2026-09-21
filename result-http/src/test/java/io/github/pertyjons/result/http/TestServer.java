package io.github.pertyjons.result.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** A JDK HttpServer bound to an ephemeral port, plus helpers to record and answer requests. */
final class TestServer implements AutoCloseable {

  /** What the server saw for the last request on a recording context. */
  record Received(
      String method, String path, String query, String header, String contentType, String body) {}

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  final AtomicReference<Received> last = new AtomicReference<>();

  TestServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void on(String path, HttpHandler handler) {
    server.createContext(path, handler);
  }

  /** Records the request (echoing the {@code X-Probe} header) and answers with the given body. */
  void record(String path, int status, String body) {
    on(
        path,
        exchange -> {
          var uri = exchange.getRequestURI();
          last.set(
              new Received(
                  exchange.getRequestMethod(),
                  uri.getRawPath(),
                  uri.getRawQuery() == null ? "" : uri.getRawQuery(),
                  String.valueOf(exchange.getRequestHeaders().getFirst("X-Probe")),
                  String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                  new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
          respond(exchange, status, body);
        });
  }

  static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    // HEAD must not send a body; -1 means "no body" to the JDK server.
    long length =
        exchange.getRequestMethod().equals("HEAD") ? -1 : (bytes.length == 0 ? -1 : bytes.length);
    exchange.sendResponseHeaders(status, length);
    if (length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
