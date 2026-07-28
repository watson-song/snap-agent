package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Base class for OkHttp-based streaming LLM clients. Captures the shared
 * infrastructure: proxy setup, call tracking, cancellation, SSE loop
 * skeleton, and listModels parsing.
 *
 * <p>Subclasses implement the provider-specific hooks:</p>
 * <ul>
 *   <li>{@link #buildHttpRequest} — URL, headers, request body</li>
 *   <li>{@link #parseSseResponse} — SSE event parsing</li>
 *   <li>{@link #addAuthHeader} — auth header style</li>
 *   <li>{@link #getModelsUrl} — models endpoint</li>
 * </ul>
 */
public abstract class AbstractStreamingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractStreamingLlmClient.class);

    protected final String baseUrl;
    protected final String apiKey;
    protected final String authToken;
    protected final String defaultModel;
    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;

    final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<String, Call>();
    final ThreadLocal<String> currentTaskId = new ThreadLocal<String>();

    /**
     * Construct with full configuration including proxy and default model.
     *
     * @param baseUrl       API base URL
     * @param apiKey        API key (may be null if authToken is used)
     * @param authToken     Bearer token (may be null)
     * @param proxyUrl      HTTP proxy URL (may be null)
     * @param timeoutSeconds connect + read timeout
     * @param defaultModel  model name used when LlmRequest.model is null
     * @param httpClient    injected OkHttpClient (for tests); null to build one
     */
    protected AbstractStreamingLlmClient(String baseUrl, String apiKey, String authToken,
                                         String proxyUrl, int timeoutSeconds,
                                         String defaultModel, OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.authToken = authToken;
        this.defaultModel = defaultModel;
        this.objectMapper = new ObjectMapper();
        if (httpClient != null) {
            this.httpClient = httpClient;
        } else {
            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS);
            if (proxyUrl != null && !proxyUrl.isEmpty()) {
                try {
                    URL url = new URL(proxyUrl);
                    int port = url.getPort() > 0 ? url.getPort() : 80;
                    clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(url.getHost(), port)));
                    log.info("LLM client using HTTP proxy: {}", proxyUrl);
                } catch (Exception e) {
                    log.warn("Invalid proxy-url '{}', ignoring: {}", proxyUrl, e.getMessage());
                }
            }
            this.httpClient = clientBuilder.build();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Shared LlmClient implementation
    // ════════════════════════════════════════════════════════════════

    @Override
    public void stream(LlmRequest req, LlmEventSink events, String taskId) {
        currentTaskId.set(taskId);
        try {
            streamInternal(req, events);
        } finally {
            if (taskId != null) {
                activeCalls.remove(taskId);
            }
            currentTaskId.remove();
        }
    }

    private void streamInternal(LlmRequest req, LlmEventSink events) {
        try {
            Request httpRequest = buildHttpRequest(req);
            try (Response response = executeCall(httpRequest)) {
                if (!response.isSuccessful()) {
                    events.onError("HTTP error: " + response.code());
                    return;
                }
                String contentType = response.header("content-type");
                if (contentType == null || !contentType.contains("text/event-stream")) {
                    handleNonStreamingResponse(response, events);
                    return;
                }
                parseSseResponse(response, events, req);
            }
        } catch (IOException e) {
            log.error("LLM streaming failed: {}", e.getMessage());
            events.onError("LLM streaming failed: " + e.getMessage());
        }
    }

    /**
     * Registers the Call for cancellation tracking, then executes.
     * Override in tests to inject canned responses.
     */
    protected Response executeCall(Request request) throws IOException {
        Call call = httpClient.newCall(request);
        String tid = currentTaskId.get();
        if (tid != null) {
            activeCalls.put(tid, call);
        }
        return call.execute();
    }

    @Override
    public void cancel(String taskId) {
        if (taskId == null) return;
        Call call = activeCalls.get(taskId);
        if (call != null) {
            log.info("Cancelling LLM call for task {}", taskId);
            call.cancel();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listModels() {
        Request.Builder builder = new Request.Builder()
                .url(getModelsUrl())
                .header("content-type", "application/json")
                .get();
        addAuthHeader(builder);
        try (Response response = executeCall(builder.build())) {
            if (!response.isSuccessful()) {
                log.warn("listModels returned HTTP {}", response.code());
                return Collections.emptyList();
            }
            okhttp3.ResponseBody body = response.body();
            if (body == null) return Collections.emptyList();
            JsonNode root = objectMapper.readTree(body.string());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return Collections.emptyList();
            List<String> models = new ArrayList<String>();
            for (JsonNode node : data) {
                String id = node.path("id").asText();
                if (id != null && !id.isEmpty()) {
                    models.add(id);
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("listModels failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Provider-specific hooks
    // ════════════════════════════════════════════════════════════════

    /**
     * Build the provider-specific HTTP request (URL, headers, body).
     */
    protected abstract Request buildHttpRequest(LlmRequest req) throws IOException;

    /**
     * Parse the SSE response stream and dispatch events.
     */
    protected abstract void parseSseResponse(Response response, LlmEventSink events, LlmRequest req) throws IOException;

    /**
     * Handle a non-SSE response (some providers return JSON when streaming is not available).
     * Default: error. Override if the provider supports non-streaming fallback.
     */
    protected void handleNonStreamingResponse(Response response, LlmEventSink events) throws IOException {
        String contentType = response.header("content-type");
        events.onError("Unexpected content-type: " + contentType);
    }

    /**
     * Add provider-specific auth headers to a request builder.
     */
    protected abstract void addAuthHeader(Request.Builder builder);

    /**
     * @return the models endpoint URL.
     */
    protected abstract String getModelsUrl();
}
