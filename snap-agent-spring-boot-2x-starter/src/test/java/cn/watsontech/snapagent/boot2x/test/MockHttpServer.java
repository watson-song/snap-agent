package cn.watsontech.snapagent.boot2x.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight HTTP mock server for testing HTTP-based clients.
 * Uses Java built-in {@link com.sun.net.httpserver.HttpServer} — no external
 * dependency required.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (MockHttpServer server = new MockHttpServer()) {
 *     server.when("/api/v1/bugs", "POST", 200, "{\"id\":123}")
 *           .start();
 *     ZentaoIssueTracker tracker = createTracker(server.getBaseUrl());
 *     String issueId = tracker.createIssue("title", "desc", null);
 *     assertThat(issueId).isEqualTo("123");
 *     MockHttpServer.RecordedRequest req = server.findRequest("POST", "/api/v1/bugs");
 *     assertThat(req.header("Authorization")).isEqualTo("Token my-token");
 * }
 * }</pre>
 *
 * <p>Route matching: routes are sorted by path-prefix length (descending)
 * so that longer (more specific) prefixes are checked before shorter ones.
 * The first route where {@code requestPath.startsWith(pathPrefix) && method matches}
 * wins. If no route matches, a 404 is returned.</p>
 */
public class MockHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Route> routes = new ArrayList<>();
    private final List<RecordedRequest> recordedRequests =
            Collections.synchronizedList(new ArrayList<>());

    public MockHttpServer() throws IOException {
        this(0);
    }

    public MockHttpServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
    }

    /**
     * Register a canned response for a path prefix and HTTP method.
     *
     * @param pathPrefix   the request path must start with this string
     * @param method       HTTP method (GET, POST, PATCH, PUT, DELETE)
     * @param statusCode   HTTP status code to return
     * @param responseBody JSON body to return (may be empty string)
     */
    public MockHttpServer when(String pathPrefix, String method,
                               int statusCode, String responseBody) {
        routes.add(new Route(pathPrefix, method, statusCode, responseBody));
        // Sort by path length descending so more specific prefixes match first
        routes.sort((a, b) -> Integer.compare(b.pathPrefix.length(), a.pathPrefix.length()));
        return this;
    }

    public void start() {
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] bodyBytes = readAll(exchange.getRequestBody());
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    headers.put(k, v.get(0));
                }
            });

            recordedRequests.add(new RecordedRequest(method, path,
                    exchange.getRequestURI().getQuery(), headers, body));

            Route route = null;
            for (Route r : routes) {
                if (method.equals(r.method) && path.startsWith(r.pathPrefix)) {
                    route = r;
                    break;
                }
            }

            if (route == null) {
                String err = "{\"error\":\"no mock for " + method + " " + path + "\"}";
                byte[] errBytes = err.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, errBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errBytes);
                }
            } else {
                byte[] respBytes = route.responseBody != null
                        && !route.responseBody.isEmpty()
                        ? route.responseBody.getBytes(StandardCharsets.UTF_8)
                        : new byte[0];
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                // For 204 (No Content) or empty body, send -1 as content length
                if (route.statusCode == 204 || respBytes.length == 0) {
                    exchange.sendResponseHeaders(route.statusCode, -1);
                    exchange.getResponseBody().close();
                } else {
                    exchange.sendResponseHeaders(route.statusCode, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                exchange.sendResponseHeaders(500, 0);
            } catch (IOException ignored) {
                // exchange already closed
            }
            exchange.getResponseBody().close();
        }
    }

    /**
     * Returns all recorded requests in order received.
     */
    public List<RecordedRequest> getRecordedRequests() {
        return new ArrayList<>(recordedRequests);
    }

    /**
     * Finds the first request matching the given method whose path contains
     * the given substring.
     */
    public RecordedRequest findRequest(String method, String pathContains) {
        for (RecordedRequest req : getRecordedRequests()) {
            if (method.equals(req.method) && req.path.contains(pathContains)) {
                return req;
            }
        }
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * Represents a single recorded HTTP request received by the mock server.
     */
    public static class RecordedRequest {
        public final String method;
        public final String path;
        public final String query;
        public final Map<String, String> headers;
        public final String body;

        RecordedRequest(String method, String path, String query,
                         Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        /**
         * Case-insensitive header lookup.
         */
        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    private static class Route {
        final String pathPrefix;
        final String method;
        final int statusCode;
        final String responseBody;

        Route(String pathPrefix, String method, int statusCode, String responseBody) {
            this.pathPrefix = pathPrefix;
            this.method = method;
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}
