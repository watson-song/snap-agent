package cn.watsontech.snapagent.boot2x.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for HTTP-based {@link cn.watsontech.snapagent.core.issue.IssueTracker}
 * implementations. Provides a minimal JSON HTTP client using
 * {@link HttpURLConnection} + Jackson {@link ObjectMapper}.
 *
 * <p>Subclasses call {@link #jsonRequest} to perform HTTP requests and receive
 * parsed JSON responses. Error handling is centralized: non-2xx responses
 * throw {@link TrackerException} with the response body.</p>
 */
abstract class AbstractHttpIssueTracker {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpIssueTracker.class);

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Performs an HTTP request and returns the parsed JSON response body.
     *
     * @param urlStr  full URL
     * @param method  HTTP method (GET, POST, PATCH, PUT, DELETE)
     * @param headers extra HTTP headers (Authorization, Content-Type, etc.)
     * @param body    request body object (serialised to JSON); may be {@code null}
     * @return parsed JSON response, or {@code null} if the response body is empty
     * @throws TrackerException if the HTTP call fails or returns non-2xx
     */
    protected JsonNode jsonRequest(String urlStr, String method,
                                   Map<String, String> headers, Object body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            // Always send/receive JSON
            conn.setRequestProperty("Accept", "application/json");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                String json = objectMapper.writeValueAsString(body);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            String responseBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code >= 400) {
                throw new TrackerException(type(),
                        "HTTP " + code + " from " + method + " " + urlStr + ": " + responseBody);
            }

            if (responseBody == null || responseBody.isEmpty()) {
                return null;
            }
            return objectMapper.readTree(responseBody);
        } catch (TrackerException e) {
            throw e;
        } catch (Exception e) {
            throw new TrackerException(type(),
                    "Failed " + method + " " + urlStr + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Builds a header map with a single Authorization entry.
     */
    protected static Map<String, String> authHeader(String value) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", value);
        return h;
    }

    /**
     * Builds a header map with Authorization + Content-Type.
     */
    protected static Map<String, String> authJsonHeaders(String authValue) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", authValue);
        return h;
    }

    private String readAll(InputStream is) {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (Exception e) {
            log.warn("Failed reading response stream: {}", e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Returns the tracker type identifier (same as {@link #type()}).
     */
    protected abstract String type();

    /**
     * Thrown when a tracker HTTP call fails or returns a non-2xx status.
     */
    protected static class TrackerException extends RuntimeException {
        private final String trackerType;

        TrackerException(String trackerType, String message) {
            super(message);
            this.trackerType = trackerType;
        }

        TrackerException(String trackerType, String message, Throwable cause) {
            super(message, cause);
            this.trackerType = trackerType;
        }

        public String getTrackerType() {
            return trackerType;
        }
    }
}
