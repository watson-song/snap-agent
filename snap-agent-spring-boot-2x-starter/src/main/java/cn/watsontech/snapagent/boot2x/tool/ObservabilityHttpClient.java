package cn.watsontech.snapagent.boot2x.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Lightweight HTTP client for observability back-ends (Prometheus / Loki / Jaeger / Nacos).
 *
 * <p>Built on top of the JDK {@link HttpURLConnection}, it introduces no extra
 * dependencies. All methods are {@code protected} so tests can subclass and
 * override to return mock responses without a mock framework.</p>
 *
 * <p>See design doc §3.1 for the contract.</p>
 */
public class ObservabilityHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Performs an HTTP GET and returns the response body as a string.
     *
     * @param url              full URL (already URL-encoded, including query params)
     * @param headers          custom request headers (may be {@code null} or empty)
     * @param connectTimeoutMs connect timeout in milliseconds
     * @param readTimeoutMs    read timeout in milliseconds
     * @return response body string
     * @throws IOException network error or non-2xx response
     */
    protected String httpGet(String url, Map<String, String> headers,
                             int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    /** Parses a JSON string into a Jackson {@link JsonNode}. */
    protected JsonNode parseJson(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    /** Reads an {@link InputStream} fully into a UTF-8 string. */
    private String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
