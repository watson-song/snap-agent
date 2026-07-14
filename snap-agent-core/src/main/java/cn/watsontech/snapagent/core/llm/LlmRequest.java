package cn.watsontech.snapagent.core.llm;

import java.util.Collections;
import java.util.List;

/**
 * Request payload for {@link LlmClient#stream}.
 */
public final class LlmRequest {

    private final String systemPrompt;
    private final List<Message> messages;
    private final List<ToolDef> tools;
    private final String model;
    private final int maxTokens;
    private final boolean streaming;

    public LlmRequest(String systemPrompt, List<Message> messages, List<ToolDef> tools,
                      String model, int maxTokens, boolean streaming) {
        this.systemPrompt = systemPrompt;
        this.messages = messages == null ? Collections.<Message>emptyList() : messages;
        this.tools = tools == null ? Collections.<ToolDef>emptyList() : tools;
        this.model = model;
        this.maxTokens = maxTokens;
        this.streaming = streaming;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public List<ToolDef> getTools() {
        return tools;
    }

    public String getModel() {
        return model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public boolean isStreaming() {
        return streaming;
    }
}
