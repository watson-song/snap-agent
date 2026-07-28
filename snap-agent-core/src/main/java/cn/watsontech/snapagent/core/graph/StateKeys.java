package cn.watsontech.snapagent.core.graph;

import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Central registry of all {@link StateKey} constants used across the
 * SnapAgent graph runtime. Replaces raw string keys with compile-time
 * checked, type-safe constants.
 *
 * <p>Each constant documents who writes it and who reads it, so the
 * data flow through {@link GraphState} is traceable without grepping.</p>
 */
public final class StateKeys {

    private StateKeys() {}

    // ════════════════════════════════════════════════════════════════
    // Layer 1 — Instructions (system prompt)
    // ════════════════════════════════════════════════════════════════

    /** The assembled system prompt (guardrail + skill body + output format + LTM blocks).
     *  Writers: EntryNode, LongTermMemoryAdvisor
     *  Readers: AgentNode, LongTermMemoryAdvisor */
    public static final StateKey<String> SYSTEM_PROMPT = StateKey.of("system.prompt", String.class);

    // ════════════════════════════════════════════════════════════════
    // Layer 2 — User Input
    // ════════════════════════════════════════════════════════════════

    /** The user message assembled from task inputs (JSON in &lt;user_inputs&gt; tags).
     *  Writers: EntryNode
     *  Readers: AgentNode, MessageChatMemoryAdvisor */
    public static final StateKey<String> USER_MESSAGE = StateKey.of("user.message", String.class);

    /** The raw user query string (used for sanitization and RAG retrieval).
     *  Writers: SafeGuardAdvisor (modifies)
     *  Readers: SafeGuardAdvisor, RetrievalAugmentationAdvisor */
    public static final StateKey<String> USER_QUERY = StateKey.of("user.query", String.class);

    // ════════════════════════════════════════════════════════════════
    // Layer 3 — Retrieved Facts (RAG)
    // ════════════════════════════════════════════════════════════════

    /** RAG-augmented context string, injected into system prompt as &lt;retrieved_facts&gt;.
     *  Writer: RetrievalAugmentationAdvisor
     *  Reader: AgentNode */
    public static final StateKey<String> RAG_CONTEXT = StateKey.of("rag.context", String.class);

    // ════════════════════════════════════════════════════════════════
    // Layer 5 — Short-term Notes (conversation history)
    // ════════════════════════════════════════════════════════════════

    /** Conversation history messages loaded from ChatMemory.
     *  Writer: MessageChatMemoryAdvisor
     *  Reader: AgentNode */
    @SuppressWarnings("unchecked")
    public static final StateKey<List<Message>> MEMORY_MESSAGES = StateKey.of("memory.messages", (Class) List.class);

    // ════════════════════════════════════════════════════════════════
    // Agent turn outputs
    // ════════════════════════════════════════════════════════════════

    /** LLM stop reason: "end_turn", "tool_use", "max_tokens", "error".
     *  Writer: AgentNode
     *  Readers: ShouldContinue, MessageChatMemoryAdvisor, MicrometerObservationAdvisor */
    public static final StateKey<String> STOP_REASON = StateKey.of("stop_reason", String.class);

    /** The LLM's thought/response text.
     *  Writer: AgentNode
     *  Readers: MessageChatMemoryAdvisor, SafeGuardAdvisor */
    public static final StateKey<String> THOUGHT = StateKey.of("thought", String.class);

    /** Tool use blocks from the LLM response.
     *  Writer: AgentNode
     *  Reader: ToolsNode */
    @SuppressWarnings("unchecked")
    public static final StateKey<List<ToolUseBlock>> TOOL_USE_BLOCKS = StateKey.of("tool_use_blocks", (Class) List.class);

    /** Whether the LLM response was truncated by max_tokens.
     *  Writer: AgentNode */
    public static final StateKey<Boolean> TRUNCATED = StateKey.of("truncated", Boolean.class);

    // ════════════════════════════════════════════════════════════════
    // Tool execution outputs
    // ════════════════════════════════════════════════════════════════

    /** Tool execution results.
     *  Writer: ToolsNode
     *  Readers: MessageChatMemoryAdvisor, AuditAdvisor, MicrometerObservationAdvisor */
    @SuppressWarnings("unchecked")
    public static final StateKey<List<ToolResult>> TOOL_RESULTS = StateKey.of("tool_results", (Class) List.class);

    // ════════════════════════════════════════════════════════════════
    // LLM usage metadata
    // ════════════════════════════════════════════════════════════════

    /** Input token count from LLM response.
     *  Readers: CostBudgetAdvisor, AuditAdvisor, MicrometerObservationAdvisor */
    public static final StateKey<Integer> LLM_INPUT_TOKENS = StateKey.of("llm.input_tokens", Integer.class);

    /** Output token count from LLM response.
     *  Readers: CostBudgetAdvisor, AuditAdvisor, MicrometerObservationAdvisor */
    public static final StateKey<Integer> LLM_OUTPUT_TOKENS = StateKey.of("llm.output_tokens", Integer.class);

    /** Cache read token count from LLM response.
     *  Reader: CostBudgetAdvisor */
    public static final StateKey<Integer> LLM_CACHE_READ_TOKENS = StateKey.of("llm.cache_read_tokens", Integer.class);

    /** Model name used for the LLM call.
     *  Readers: CostBudgetAdvisor, AuditAdvisor */
    public static final StateKey<String> LLM_MODEL = StateKey.of("llm.model", String.class);

    // ════════════════════════════════════════════════════════════════
    // Tool metadata (for audit)
    // ════════════════════════════════════════════════════════════════

    /** Tool name being executed (for audit).
     *  Reader: AuditAdvisor */
    public static final StateKey<String> TOOL_NAME = StateKey.of("tool.name", String.class);

    /** Tool arguments (for audit).
     *  Reader: AuditAdvisor */
    @SuppressWarnings("unchecked")
    public static final StateKey<Map<String, Object>> TOOL_ARGS = StateKey.of("tool.args", (Class) Map.class);

    // ════════════════════════════════════════════════════════════════
    // Identity / routing keys
    // ════════════════════════════════════════════════════════════════

    /** Conversation ID for memory persistence.
     *  Reader: MessageChatMemoryAdvisor */
    public static final StateKey<String> CONVERSATION_ID = StateKey.of("conversation.id", String.class);

    /** User ID for long-term memory.
     *  Reader: LongTermMemoryAdvisor */
    public static final StateKey<String> USER_ID = StateKey.of("user.id", String.class);

    /** Project ID for long-term memory.
     *  Reader: LongTermMemoryAdvisor */
    public static final StateKey<String> PROJECT_ID = StateKey.of("project.id", String.class);

    // ════════════════════════════════════════════════════════════════
    // Internal (advisors)
    // ════════════════════════════════════════════════════════════════

    /** Internal: start time for metrics observation.
     *  Writer/Reader: MicrometerObservationAdvisor */
    public static final StateKey<Long> METRICS_START_TIME = StateKey.of("__metrics_start_time", Long.class);
}
