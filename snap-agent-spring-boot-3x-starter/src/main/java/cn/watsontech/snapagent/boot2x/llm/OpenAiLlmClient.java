package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolDef;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} implementation for OpenAI-compatible chat completion APIs.
 *
 * <p>Uses OkHttp to POST to {@code {base-url}/v1/chat/completions} with SSE streaming.
 * Parses the OpenAI streaming chunk protocol and dispatches to {@link LlmEventSink}.</p>
 *
 * <p>Compatible with OpenAI, Azure OpenAI, and OpenAI-compatible gateways
 * (e.g. SF llm-model-hub, vLLM, Ollama).</p>
 *
 * <p>Extends {@link AbstractStreamingLlmClient} for shared infrastructure
 * (proxy setup, call tracking, cancellation, SSE loop skeleton, listModels).</p>
 */
public class OpenAiLlmClient extends AbstractStreamingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");

    /** Constructor with optional Bearer token auth and HTTP proxy. */
    public OpenAiLlmClient(String baseUrl, String apiKey, String authToken,
                           String proxyUrl, int timeoutSeconds) {
        super(baseUrl, apiKey, authToken, proxyUrl, timeoutSeconds, null, null);
    }

    /** Convenience constructor (no proxy, no bearer token). */
    public OpenAiLlmClient(String baseUrl, String apiKey, int timeoutSeconds) {
        this(baseUrl, apiKey, null, null, timeoutSeconds);
    }

    /** Testable constructor — allows injecting a custom OkHttpClient. */
    protected OpenAiLlmClient(String baseUrl, String apiKey, OkHttpClient httpClient) {
        super(baseUrl, apiKey, null, null, 0, null, httpClient);
    }

    // ════════════════════════════════════════════════════════════════
    // Provider-specific hooks
    // ════════════════════════════════════════════════════════════════

    @Override
    protected Request buildHttpRequest(LlmRequest req) throws IOException {
        String json = buildRequestBody(req);
        RequestBody body = RequestBody.create(JSON, json);
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .post(body);
        addAuthHeader(builder);
        return builder.build();
    }

    @Override
    protected void addAuthHeader(Request.Builder builder) {
        if (authToken != null && !authToken.isEmpty()) {
            String headerValue = authToken.toLowerCase().startsWith("bearer ")
                    ? authToken
                    : "Bearer " + authToken;
            builder.header("Authorization", headerValue);
        } else if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    @Override
    protected String getModelsUrl() {
        return baseUrl + "/v1/models";
    }

    @Override
    protected void parseSseResponse(Response response, LlmEventSink events, LlmRequest req) throws IOException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            events.onError("empty response body");
            return;
        }

        BufferedSource source = responseBody.source();
        StringBuilder dataBuilder = new StringBuilder();
        String stopReason = null;

        // Tool call assembly state — indexed by tool_calls array index
        List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<ToolCallAccumulator>();

        while (true) {
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }

            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    // End of stream
                    flushToolCalls(toolCallAccumulators, events);
                    events.onStop(stopReason != null ? stopReason : "end_turn");
                    return;
                }
                if (dataBuilder.length() > 0) {
                    dataBuilder.append("\n");
                }
                dataBuilder.append(data);
            } else if (line.isEmpty()) {
                // End of event — process data
                if (dataBuilder.length() > 0) {
                    stopReason = processSseChunk(dataBuilder.toString(), events, toolCallAccumulators, stopReason);
                    dataBuilder.setLength(0);
                }
            }
        }

        // Process any remaining data
        if (dataBuilder.length() > 0) {
            stopReason = processSseChunk(dataBuilder.toString(), events, toolCallAccumulators, stopReason);
        }

        // Flush any pending tool calls
        flushToolCalls(toolCallAccumulators, events);

        // Ensure onStop is called
        events.onStop(stopReason != null ? stopReason : "end_turn");
    }

    /**
     * OpenAI-compatible APIs may return a non-SSE JSON response when
     * streaming is not available. Parse it as a single completion.
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void handleNonStreamingResponse(Response response, LlmEventSink events) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            events.onError("empty response body");
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(body.string());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                events.onError("no choices in response");
                return;
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message != null) {
                JsonNode content = message.get("content");
                if (content != null && !content.isNull()) {
                    String text = content.asText();
                    if (text != null && !text.isEmpty()) {
                        events.onThought(text);
                    }
                }
                // Handle tool calls in non-streaming response
                JsonNode toolCalls = message.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String id = tc.path("id").asText();
                        String name = tc.path("function").path("name").asText();
                        String argsStr = tc.path("function").path("arguments").asText();
                        Map<String, Object> input = new LinkedHashMap<String, Object>();
                        if (argsStr != null && !argsStr.isEmpty()) {
                            try {
                                input = objectMapper.readValue(argsStr, Map.class);
                            } catch (Exception e) {
                                log.warn("Failed to parse tool arguments: {}", e.getMessage());
                            }
                        }
                        events.onToolUse(id, name, input);
                    }
                }
            }
            String finishReason = choice.path("finish_reason").asText("stop");
            String mappedReason = "stop".equals(finishReason) ? "end_turn"
                    : "tool_calls".equals(finishReason) ? "tool_use"
                    : "length".equals(finishReason) ? "max_tokens"
                    : finishReason;
            events.onStop(mappedReason);
        } catch (Exception e) {
            log.error("Failed to parse non-streaming response: {}", e.getMessage());
            events.onError("Failed to parse response: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Request body building
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String buildRequestBody(LlmRequest req) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", req.getModel());
        body.put("max_tokens", req.getMaxTokens());
        body.put("stream", req.isStreaming());

        // Messages — system prompt becomes a system message
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            Map<String, Object> sysMsg = new LinkedHashMap<String, Object>();
            sysMsg.put("role", "system");
            sysMsg.put("content", req.getSystemPrompt());
            messages.add(sysMsg);
        }

        // Track pending tool results to emit as individual "tool" role messages
        for (Message msg : req.getMessages()) {
            String role = msg.getRole();
            if ("tool".equals(role)) {
                // OpenAI: tool result is a message with role "tool" + tool_call_id
                Map<String, Object> toolMsg = new LinkedHashMap<String, Object>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", msg.getToolUseId());
                toolMsg.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(toolMsg);
            } else if ("assistant".equals(role) && msg.hasToolUses()) {
                // Assistant message with tool_calls
                Map<String, Object> asstMsg = new LinkedHashMap<String, Object>();
                asstMsg.put("role", "assistant");
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    asstMsg.put("content", msg.getContent());
                } else {
                    asstMsg.put("content", "");
                }
                List<Map<String, Object>> toolCalls = new ArrayList<Map<String, Object>>();
                for (ToolUseBlock tu : msg.getToolUses()) {
                    Map<String, Object> tc = new LinkedHashMap<String, Object>();
                    tc.put("id", tu.getId());
                    tc.put("type", "function");
                    Map<String, Object> fn = new LinkedHashMap<String, Object>();
                    fn.put("name", tu.getName());
                    fn.put("arguments", objectMapper.writeValueAsString(tu.getInput()));
                    tc.put("function", fn);
                    toolCalls.add(tc);
                }
                asstMsg.put("tool_calls", toolCalls);
                messages.add(asstMsg);
            } else {
                // Plain user/assistant message
                Map<String, Object> plainMsg = new LinkedHashMap<String, Object>();
                plainMsg.put("role", role);
                plainMsg.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(plainMsg);
            }
        }
        body.put("messages", messages);

        // Tools — OpenAI format: array of {type: "function", function: {name, description, parameters}}
        if (!req.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
            for (ToolDef tool : req.getTools()) {
                Map<String, Object> toolMap = new LinkedHashMap<String, Object>();
                toolMap.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<String, Object>();
                fn.put("name", tool.getName());
                fn.put("description", tool.getDescription());
                fn.put("parameters", objectMapper.readTree(tool.getInputSchema()));
                toolMap.put("function", fn);
                tools.add(toolMap);
            }
            body.put("tools", tools);
        }

        return objectMapper.writeValueAsString(body);
    }

    // ════════════════════════════════════════════════════════════════
    // SSE chunk processing
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String processSseChunk(String data, LlmEventSink events,
                                    List<ToolCallAccumulator> accumulators,
                                    String currentStopReason) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return currentStopReason;
            }

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null) {
                // Text content
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    String text = contentNode.asText();
                    if (text != null && !text.isEmpty()) {
                        events.onThought(text);
                    }
                }

                // Tool calls (streaming)
                JsonNode toolCallsNode = delta.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        int index = tcNode.path("index").asInt(0);
                        // Ensure accumulator list is large enough
                        while (accumulators.size() <= index) {
                            accumulators.add(new ToolCallAccumulator());
                        }
                        ToolCallAccumulator acc = accumulators.get(index);

                        JsonNode idNode = tcNode.get("id");
                        if (idNode != null && !idNode.isNull()) {
                            acc.id = idNode.asText();
                        }
                        JsonNode fnNode = tcNode.path("function");
                        JsonNode nameNode = fnNode.get("name");
                        if (nameNode != null && !nameNode.isNull()) {
                            acc.name = nameNode.asText();
                        }
                        JsonNode argsNode = fnNode.get("arguments");
                        if (argsNode != null && !argsNode.isNull()) {
                            acc.arguments.append(argsNode.asText());
                        }
                    }
                }
            }

            // Check finish_reason
            JsonNode finishReason = choice.get("finish_reason");
            if (finishReason != null && !finishReason.isNull()) {
                String reason = finishReason.asText();
                // Map OpenAI stop reasons to Anthropic-compatible ones
                if ("stop".equals(reason)) {
                    return "end_turn";
                } else if ("tool_calls".equals(reason)) {
                    return "tool_use";
                } else if ("length".equals(reason)) {
                    return "max_tokens";
                }
                return reason;
            }
        } catch (Exception e) {
            log.warn("Failed to parse SSE chunk: {}", e.getMessage());
        }
        return currentStopReason;
    }

    private void flushToolCalls(List<ToolCallAccumulator> accumulators, LlmEventSink events) {
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id != null && acc.name != null) {
                Map<String, Object> input = new LinkedHashMap<String, Object>();
                String argsJson = acc.arguments.toString();
                if (!argsJson.isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = objectMapper.readValue(argsJson, Map.class);
                        input = parsed;
                    } catch (Exception e) {
                        log.warn("Failed to parse tool call arguments '{}': {}", argsJson, e.getMessage());
                        input = new LinkedHashMap<String, Object>();
                    }
                }
                events.onToolUse(acc.id, acc.name, input);
            }
        }
    }

    /** Accumulator for streaming tool call assembly. */
    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
