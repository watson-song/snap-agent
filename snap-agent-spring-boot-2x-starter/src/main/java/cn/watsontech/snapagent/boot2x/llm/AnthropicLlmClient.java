package cn.watsontech.snapagent.boot2x.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolDef;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link LlmClient} implementation for the Anthropic Messages API (streaming).
 *
 * <p>Uses OkHttp to POST to {@code {base-url}/v1/messages} with SSE streaming.
 * Parses the Anthropic streaming event protocol and dispatches to
 * {@link LlmEventSink} (design doc 05 §2).</p>
 */
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final MediaType SSE = MediaType.parse("text/event-stream");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String baseUrl;
    private final String apiKey;
    private final String authToken;
    private final String defaultModel;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<String, Call>();
    private final ThreadLocal<String> currentTaskId = new ThreadLocal<String>();

    public AnthropicLlmClient(String baseUrl, String apiKey, int timeoutSeconds) {
        this(baseUrl, apiKey, null, null, timeoutSeconds);
    }

    /** Constructor with optional Bearer token auth and HTTP proxy (for proxy gateways like cc-switch). */
    public AnthropicLlmClient(String baseUrl, String apiKey, String authToken, String proxyUrl, int timeoutSeconds) {
        this(baseUrl, apiKey, authToken, proxyUrl, timeoutSeconds, null);
    }

    /** Constructor with default model name (used when LlmRequest.model is null). */
    public AnthropicLlmClient(String baseUrl, String apiKey, String authToken, String proxyUrl,
                              int timeoutSeconds, String defaultModel) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.authToken = authToken;
        this.defaultModel = defaultModel;
        this.objectMapper = new ObjectMapper();
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

    /** Testable constructor — allows injecting a custom OkHttpClient. */
    protected AnthropicLlmClient(String baseUrl, String apiKey, OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.authToken = null;
        this.defaultModel = null;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
    }

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
                // Verify response is SSE before parsing
                String contentType = response.header("content-type");
                if (contentType == null || !contentType.contains("text/event-stream")) {
                    events.onError("Unexpected content-type: " + contentType);
                    return;
                }
                parseSseStream(response, events, req);
            }
        } catch (IOException e) {
            log.error("LLM streaming failed: {}", e.getMessage());
            events.onError("LLM streaming failed: " + e.getMessage());
        }
    }

    /** Testable seam — registers the Call for cancellation tracking, then executes.
     *  Override in tests to inject canned responses (note: overrides skip registration). */
    protected Response executeCall(Request request) throws IOException {
        Call call = httpClient.newCall(request);
        String tid = currentTaskId.get();
        if (tid != null) {
            activeCalls.put(tid, call);
        }
        return call.execute();
        // NOT removed here — stream() finally cleans up after SSE consumption
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
                .url(baseUrl + "/v1/models")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .get();
        if (authToken != null && !authToken.isEmpty()) {
            String headerValue = authToken.toLowerCase().startsWith("bearer ")
                    ? authToken
                    : "Bearer " + authToken;
            builder.header("Authorization", headerValue);
        } else if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("x-api-key", apiKey);
        }
        try (Response response = executeCall(builder.build())) {
            if (!response.isSuccessful()) {
                log.warn("listModels returned HTTP {}", response.code());
                return Collections.emptyList();
            }
            ResponseBody body = response.body();
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

    private Request buildHttpRequest(LlmRequest req) throws IOException {
        String json = buildRequestBody(req);
        RequestBody body = RequestBody.create(JSON, json);
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .post(body);
        // Use Bearer auth if authToken is set (for proxy gateways like cc-switch);
        // otherwise fall back to x-api-key (native Anthropic API)
        if (authToken != null && !authToken.isEmpty()) {
            String headerValue = authToken.toLowerCase().startsWith("bearer ")
                    ? authToken
                    : "Bearer " + authToken;
            builder.header("Authorization", headerValue);
        } else {
            builder.header("x-api-key", apiKey);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private String buildRequestBody(LlmRequest req) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String model = req.getModel();
        if (model == null || model.isEmpty()) model = defaultModel;
        body.put("model", model);
        body.put("max_tokens", req.getMaxTokens());
        body.put("stream", req.isStreaming());

        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            body.put("system", req.getSystemPrompt());
        }

        // Messages — consecutive tool messages are merged into a single user
        // message (Anthropic expects all tool_result blocks for one assistant
        // turn in ONE user message, not spread across multiple).
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        List<Message> pendingToolResults = new ArrayList<Message>();

        for (Message msg : req.getMessages()) {
            if ("tool".equals(msg.getRole())) {
                pendingToolResults.add(msg);
            } else {
                if (!pendingToolResults.isEmpty()) {
                    messages.add(buildToolResultMessage(pendingToolResults));
                    pendingToolResults.clear();
                }
                if ("assistant".equals(msg.getRole()) && msg.hasToolUses()) {
                    messages.add(buildAssistantWithToolUse(msg));
                } else {
                    Map<String, Object> messageMap = new LinkedHashMap<String, Object>();
                    messageMap.put("role", msg.getRole());
                    messageMap.put("content", msg.getContent());
                    messages.add(messageMap);
                }
            }
        }
        // Flush trailing tool results
        if (!pendingToolResults.isEmpty()) {
            messages.add(buildToolResultMessage(pendingToolResults));
        }
        body.put("messages", messages);

        // Tools
        if (!req.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
            for (ToolDef tool : req.getTools()) {
                Map<String, Object> toolMap = new LinkedHashMap<String, Object>();
                toolMap.put("name", tool.getName());
                toolMap.put("description", tool.getDescription());
                toolMap.put("input_schema", objectMapper.readTree(tool.getInputSchema()));
                tools.add(toolMap);
            }
            body.put("tools", tools);
        }

        return objectMapper.writeValueAsString(body);
    }

    /** Builds a single user message containing multiple tool_result blocks. */
    private Map<String, Object> buildToolResultMessage(List<Message> toolMsgs) {
        Map<String, Object> messageMap = new LinkedHashMap<String, Object>();
        messageMap.put("role", "user");
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        for (Message tm : toolMsgs) {
            Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
            toolResult.put("type", "tool_result");
            toolResult.put("tool_use_id", tm.getToolUseId());
            toolResult.put("content", tm.getContent());
            content.add(toolResult);
        }
        messageMap.put("content", content);
        return messageMap;
    }

    /** Builds an assistant message with text + tool_use content blocks. */
    private Map<String, Object> buildAssistantWithToolUse(Message msg) {
        Map<String, Object> messageMap = new LinkedHashMap<String, Object>();
        messageMap.put("role", "assistant");
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            Map<String, Object> textBlock = new LinkedHashMap<String, Object>();
            textBlock.put("type", "text");
            textBlock.put("text", msg.getContent());
            content.add(textBlock);
        }
        for (ToolUseBlock tu : msg.getToolUses()) {
            Map<String, Object> toolBlock = new LinkedHashMap<String, Object>();
            toolBlock.put("type", "tool_use");
            toolBlock.put("id", tu.getId());
            toolBlock.put("name", tu.getName());
            toolBlock.put("input", tu.getInput());
            content.add(toolBlock);
        }
        messageMap.put("content", content);
        return messageMap;
    }

    @SuppressWarnings("unchecked")
    private void parseSseStream(Response response, LlmEventSink events, LlmRequest req) throws IOException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            events.onError("empty response body");
            return;
        }

        BufferedSource source = responseBody.source();

        // Parser state
        String eventType = null;
        StringBuilder dataBuilder = new StringBuilder();

        // Content block state (held in a mutable holder to pass to helper)
        SseState state = new SseState();
        // Skip thinking deltas when no tools are requested (e.g. inject mode)
        state.skipThinking = req.getTools() == null || req.getTools().isEmpty();

        String stopReason = null;

        while (true) {
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }

            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (dataBuilder.length() > 0) {
                    dataBuilder.append("\n");
                }
                dataBuilder.append(line.substring(5).trim());
            } else if (line.isEmpty()) {
                // End of event — process it
                processSseEvent(eventType, dataBuilder.toString(), events, state);
                eventType = null;
                dataBuilder.setLength(0);
            }
        }

        // Process any pending event that wasn't terminated by a blank line
        if (eventType != null && dataBuilder.length() > 0) {
            processSseEvent(eventType, dataBuilder.toString(), events, state);
        }

        // Ensure onStop is called if message_stop was never received
        if (!state.stopCalled) {
            if (state.usageReported) {
                events.onUsage(state.inputTokens, state.outputTokens, state.cacheReadTokens);
            }
            events.onStop(state.stopReason != null ? state.stopReason : "end_turn");
        }
    }

    @SuppressWarnings("unchecked")
    private void processSseEvent(String eventType, String data,
                                  LlmEventSink events, SseState state) {
        if (eventType == null || data.isEmpty()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(data);

            if ("message_start".equals(eventType)) {
                // v1.0 cost accounting: extract input/cache-read tokens from message_start
                JsonNode message = node.get("message");
                if (message != null) {
                    JsonNode usage = message.get("usage");
                    if (usage != null) {
                        state.inputTokens = usage.path("input_tokens").asLong(0);
                        state.cacheReadTokens = usage.path("cache_read_input_tokens").asLong(0);
                        state.usageReported = true;
                    }
                }
            } else if ("content_block_start".equals(eventType)) {
                JsonNode block = node.get("content_block");
                if (block != null && "tool_use".equals(
                        block.path("type").asText())) {
                    state.inToolUse = true;
                    state.toolUseId = block.path("id").asText();
                    state.toolUseName = block.path("name").asText();
                }
            } else if ("content_block_delta".equals(eventType)) {
                JsonNode delta = node.get("delta");
                if (delta != null) {
                    String deltaType = delta.path("type").asText();
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText();
                        events.onThought(text);
                    } else if ("thinking_delta".equals(deltaType)) {
                        // Extended thinking — skip when no tools requested (inject mode)
                        if (!state.skipThinking) {
                            String thinking = delta.path("thinking").asText();
                            if (thinking != null && !thinking.isEmpty()) {
                                events.onThought(thinking);
                            }
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        String partial = delta.path("partial_json").asText();
                        state.toolUseJson.append(partial);
                    }
                    // signature_delta and other deltas are ignored
                }
            } else if ("content_block_stop".equals(eventType)) {
                if (state.inToolUse) {
                    try {
                        Map<String, Object> input = objectMapper.readValue(
                                state.toolUseJson.toString(), Map.class);
                        events.onToolUse(state.toolUseId, state.toolUseName, input);
                    } catch (Exception e) {
                        log.warn("Failed to parse tool_use input JSON: {}", e.getMessage());
                        events.onToolUse(state.toolUseId, state.toolUseName,
                                new HashMap<String, Object>());
                    }
                    state.inToolUse = false;
                    state.toolUseId = null;
                    state.toolUseName = null;
                    state.toolUseJson.setLength(0);
                }
            } else if ("message_delta".equals(eventType)) {
                JsonNode delta = node.get("delta");
                if (delta != null && delta.has("stop_reason")) {
                    state.stopReason = delta.get("stop_reason").asText();
                }
                // v1.0 cost accounting: extract output tokens from message_delta usage
                JsonNode usage = node.get("usage");
                if (usage != null) {
                    state.outputTokens = usage.path("output_tokens").asLong(0);
                    state.usageReported = true;
                }
            } else if ("message_stop".equals(eventType)) {
                if (state.usageReported) {
                    events.onUsage(state.inputTokens, state.outputTokens, state.cacheReadTokens);
                }
                events.onStop(state.stopReason != null ? state.stopReason : "end_turn");
                state.stopCalled = true;
            } else if ("error".equals(eventType)) {
                JsonNode error = node.get("error");
                if (error != null && error.has("message")) {
                    events.onError(error.get("message").asText());
                } else {
                    events.onError("unknown LLM error");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse SSE event '{}': {}", eventType, e.getMessage());
        }
    }

    /** Mutable state shared between parseSseStream and processSseEvent. */
    private static class SseState {
        boolean inToolUse = false;
        boolean skipThinking = false;
        String toolUseId = null;
        String toolUseName = null;
        StringBuilder toolUseJson = new StringBuilder();
        String stopReason = null;
        boolean stopCalled = false;
        // v1.0 cost accounting: token usage extracted from message_start / message_delta
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        boolean usageReported = false;
    }
}
