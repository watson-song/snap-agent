package cn.watsontech.snapagent.core.llm;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable representation of a single {@code tool_use} content block emitted by
 * the LLM in an assistant turn.
 *
 * <p>Carried by {@link Message#assistant(String, java.util.List)} so that the
 * full assistant turn (text + tool_use blocks) can be replayed to the LLM on the
 * next turn. Without this, a subsequent {@code tool_result} would have no
 * matching {@code tool_use} and the provider API would reject the request.</p>
 */
public final class ToolUseBlock {

    private final String id;
    private final String name;
    private final Map<String, Object> input;

    public ToolUseBlock(String id, String name, Map<String, Object> input) {
        this.id = id;
        this.name = name;
        this.input = input == null ? Collections.<String, Object>emptyMap() : input;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    @Override
    public String toString() {
        return "ToolUseBlock{id='" + id + "', name='" + name + "'}";
    }
}
