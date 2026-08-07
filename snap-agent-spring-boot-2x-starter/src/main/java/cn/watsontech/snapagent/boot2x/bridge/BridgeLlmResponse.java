package cn.watsontech.snapagent.boot2x.bridge;

import java.util.List;
import java.util.Map;

/**
 * DTO for LLM response from browser extension.
 */
public class BridgeLlmResponse {

    public String id;
    public String text;
    public List<ToolCall> toolCalls;
    public Usage usage;

    public static class ToolCall {
        public String id;
        public String name;
        public Map<String, Object> input;
    }

    public static class Usage {
        public long inputTokens;
        public long outputTokens;
        public long cacheReadTokens;

        public Usage() {}

        public Usage(long inputTokens, long outputTokens, long cacheReadTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadTokens = cacheReadTokens;
        }
    }
}
