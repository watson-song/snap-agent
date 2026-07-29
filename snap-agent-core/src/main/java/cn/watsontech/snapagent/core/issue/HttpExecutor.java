package cn.watsontech.snapagent.core.issue;

import java.util.Map;

/**
 * SPI for HTTP execution, used by {@code AbstractHttpIssueTracker} and
 * {@code AbstractHttpVcsClient} to delegate the actual HTTP call.
 *
 * <p>Default implementation {@code DirectHttpExecutor} encapsulates the
 * existing {@link java.net.HttpURLConnection} logic. When the browser
 * bridge is enabled, a {@code BridgeHttpExecutor} replaces it to route
 * requests through the user's browser via SSE.</p>
 *
 * <p>Implementations should throw {@link RuntimeException} on connection
 * failure or non-2xx HTTP responses. The calling code ({@code jsonRequest})
 * wraps the exception in the appropriate tracker-specific exception type.</p>
 */
public interface HttpExecutor {

    /**
     * Execute an HTTP request and return the response.
     *
     * @param url     full URL
     * @param method  HTTP method (GET, POST, PATCH, PUT, DELETE)
     * @param headers HTTP headers (Authorization, Content-Type, etc.); may be {@code null}
     * @param body    request body object (serialised to JSON by the executor); may be {@code null}
     * @return HTTP response containing status code and body
     * @throws RuntimeException if the HTTP call fails or returns non-2xx
     */
    HttpResponse execute(String url, String method,
                         Map<String, String> headers, Object body);
}
