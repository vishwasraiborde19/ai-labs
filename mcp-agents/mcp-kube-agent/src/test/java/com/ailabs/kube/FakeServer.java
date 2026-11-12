package com.ailabs.kube;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Tiny HTTP stub: route "METHOD /path" to a handler, record every request. */
public final class FakeServer implements AutoCloseable {

    public record Req(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    public record Res(int status, String contentType, String body, Map<String, String> headers) {
        public static Res json(String body) {
            return new Res(200, "application/json", body, Map.of());
        }
    }

    private final HttpServer server;
    public final List<Req> requests = new CopyOnWriteArrayList<>();

    public FakeServer(Map<String, Function<Req, Res>> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> handle(ex, routes));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex, Map<String, Function<Req, Res>> routes) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                ex.getRequestHeaders(), body);
        requests.add(req);
        Function<Req, Res> h = routes.get(req.method() + " " + req.path());
        Res res = h != null ? h.apply(req) : new Res(404, "text/plain", "not found", Map.of());
        byte[] out = res.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", res.contentType());
        res.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(res.status(), out.length == 0 ? -1 : out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
