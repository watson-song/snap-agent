package cn.watsontech.snapagent.boot2x.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    /**
     * Performs an HTTP POST and returns the response body as a string.
     *
     * <p>Used by push channels (e.g. webhook delivery of alert notifications).
     * Mirrors {@link #httpGet} pattern: protected so tests can override.</p>
     *
     * @param url              full URL
     * @param headers          custom request headers (may be {@code null} or empty)
     * @param body             request body (UTF-8); {@code null} or empty means no body
     * @param connectTimeoutMs connect timeout in milliseconds
     * @param readTimeoutMs    read timeout in milliseconds
     * @return response body string
     * @throws IOException network error or non-2xx response
     */
    protected String httpPost(String url, Map<String, String> headers, String body,
                              int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (body != null && !body.isEmpty()) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = readAll(is);
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + respBody);
            }
            return respBody;
        } finally {
            conn.disconnect();
        }
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
