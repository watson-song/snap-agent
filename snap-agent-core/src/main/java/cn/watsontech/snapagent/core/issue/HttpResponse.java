package cn.watsontech.snapagent.core.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Immutable HTTP response DTO returned by {@link HttpExecutor}.
 *
 * <p>Carries the raw response body as a String and provides lazy JSON
 * parsing via {@link #getJsonBody(ObjectMapper)}.</p>
 */
public class HttpResponse {

    private final int statusCode;
    private final String body;
    private final Map<String, String> headers;

    public HttpResponse(int statusCode, String body) {
        this(statusCode, body, null);
    }

    public HttpResponse(int statusCode, String body, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Parse the response body as JSON.
     *
     * @param mapper Jackson ObjectMapper instance
     * @return parsed JSON, or {@code null} if the body is empty
     * @throws RuntimeException if JSON parsing fails
     */
    public JsonNode getJsonBody(ObjectMapper mapper) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON response: " + e.getMessage(), e);
        }
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }

    public boolean isError() {
        return statusCode >= 400;
    }
}
