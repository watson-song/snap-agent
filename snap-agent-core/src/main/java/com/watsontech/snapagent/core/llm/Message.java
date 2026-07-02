package com.watsontech.snapagent.core.llm;

import java.util.Collections;
import java.util.List;

/**
 * A chat message in the LLM conversation.
 *
 * <p>Roles: {@code system}, {@code user}, {@code assistant}, {@code tool}.
 * For tool-result messages, {@link #toolUseId} carries the corresponding
 * tool_use id and {@link #content} carries the serialized result.</p>
 *
 * <p>For assistant turns that contain tool_use blocks, {@link #toolUses}
 * carries the full block list so the next request can replay the assistant
 * turn with both text and tool_use content blocks (required by provider APIs
 * to match subsequent {@code tool_result} blocks).</p>
 */
public final class Message {

    private final String role;
    private final String content;
    private final String toolUseId;
    private final List<ToolUseBlock> toolUses;

    public Message(String role, String content, String toolUseId) {
        this(role, content, toolUseId, null);
    }

    /**
     * Full constructor.
     *
     * @param role      message role
     * @param content   text content (may be {@code null} for tool-only turns)
     * @param toolUseId tool_use id (only for role {@code "tool"})
     * @param toolUses  tool_use blocks emitted in this assistant turn (only for
     *                  role {@code "assistant"}; {@code null} or empty for text-only turns)
     */
    public Message(String role, String content, String toolUseId, List<ToolUseBlock> toolUses) {
        this.role = role;
        this.content = content;
        this.toolUseId = toolUseId;
        this.toolUses = toolUses == null || toolUses.isEmpty()
                ? Collections.<ToolUseBlock>emptyList()
                : Collections.<ToolUseBlock>unmodifiableList(toolUses);
    }

    public static Message system(String text) {
        return new Message("system", text, null, null);
    }

    public static Message user(String text) {
        return new Message("user", text, null, null);
    }

    /** Text-only assistant turn (no tool_use blocks). */
    public static Message assistant(String text) {
        return new Message("assistant", text, null, null);
    }

    /**
     * Assistant turn that may contain tool_use blocks.
     *
     * @param text     the assistant's text (thoughts); may be empty
     * @param toolUses tool_use blocks emitted in this turn; may be {@code null}
     */
    public static Message assistant(String text, List<ToolUseBlock> toolUses) {
        return new Message("assistant", text, null, toolUses);
    }

    public static Message toolResult(String toolUseId, String content) {
        return new Message("tool", content, toolUseId, null);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    /** Tool_use blocks for assistant turns; empty for non-assistant or text-only turns. */
    public List<ToolUseBlock> getToolUses() {
        return toolUses;
    }

    /** True when this assistant message carries tool_use blocks. */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    @Override
    public String toString() {
        return "Message{role='" + role + "', content='" + content
                + "', toolUseId='" + toolUseId + "', toolUses=" + toolUses.size() + '}';
    }
}
