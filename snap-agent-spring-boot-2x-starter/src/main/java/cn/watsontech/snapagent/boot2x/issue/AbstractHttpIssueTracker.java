package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.HttpExecutor;
import cn.watsontech.snapagent.core.issue.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for HTTP-based {@link cn.watsontech.snapagent.core.issue.IssueTracker}
 * implementations. Provides a minimal JSON HTTP client using
 * {@link HttpExecutor} + Jackson {@link ObjectMapper}.
 *
 * <p>Subclasses call {@link #jsonRequest} to perform HTTP requests and receive
 * parsed JSON responses. Error handling is centralized: non-2xx responses
 * throw {@link TrackerException} with the response body.</p>
 *
 * <p>The HTTP execution is delegated to {@link #httpExecutor}, which defaults
 * to {@link DirectHttpExecutor} (using {@link java.net.HttpURLConnection}).
 * When the browser bridge is enabled, a {@code BridgeHttpExecutor} is injected
 * via {@code BeanPostProcessor} to route requests through the user's browser.</p>
 */
abstract class AbstractHttpIssueTracker {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpIssueTracker.class);

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * HTTP executor used for all outbound requests. Defaults to
     * {@link DirectHttpExecutor} (direct {@link java.net.HttpURLConnection}).
     * Replaced by {@code BridgeHttpExecutor} when the bridge is enabled.
     */
    protected HttpExecutor httpExecutor = new DirectHttpExecutor();

    /**
     * Performs an HTTP request and returns the parsed JSON response body.
     *
     * <p>Delegates the actual HTTP call to {@link #httpExecutor} and handles
     * error wrapping and JSON parsing. When {@code bridge.enabled=false}
     * (the default), {@code httpExecutor} is {@link DirectHttpExecutor} and
     * behavior is identical to the pre-bridge implementation.</p>
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
        try {
            HttpResponse resp = httpExecutor.execute(urlStr, method, headers, body);
            return resp.getJsonBody(objectMapper);
        } catch (RuntimeException e) {
            throw new TrackerException(type(),
                    e.getMessage(), e.getCause());
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
