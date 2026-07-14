package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * SPI for tool providers. Implementations register a unique {@link #name()}
 * and a JSON {@link #schema()} (Anthropic tool format) and execute tool calls.
 *
 * <p>Concrete implementations (JdbcQueryToolProvider, RedisReadToolProvider,
 * McpToolProvider) live in the starter module.</p>
 */
public interface ToolProvider {

    /** Unique tool name referenced by skill frontmatter {@code tools} field. */
    String name();

    /** JSON Schema string injected into the LLM tools definition. */
    String schema();

    /**
     * Execute the tool with the given arguments and context.
     *
     * @param args arguments parsed from the LLM tool_use block
     * @param ctx  request-scoped context (userId, audit)
     * @return immutable result; never {@code null}
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
