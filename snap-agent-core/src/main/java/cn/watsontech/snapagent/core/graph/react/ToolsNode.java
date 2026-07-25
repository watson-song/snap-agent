package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolsNode executes tool calls from state["tool_use_blocks"].
 * - Executes each tool via ToolCallbackRegistry
 * - Truncates results exceeding maxToolResultChars
 * - Catches tool exceptions and returns error ToolResult (not crash)
 * - Emits tool_call + tool_result SSE events
 */
public class ToolsNode implements Node {
    private static final Logger log = LoggerFactory.getLogger(ToolsNode.class);
    private final int maxToolResultChars;

    public ToolsNode(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    @Override
    public String getName() { return "tools"; }

    @Override
    @SuppressWarnings("unchecked")
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        List<ToolUseBlock> toolUses = state.get("tool_use_blocks");
        if (toolUses == null || toolUses.isEmpty()) {
            return state.with("tool_results", new ArrayList<ToolResult>());
        }

        ToolCallbackRegistry registry = ctx.getTools();
        List<ToolResult> results = new ArrayList<>();

        for (ToolUseBlock toolUse : toolUses) {
            ctx.emit(TranscriptEvent.toolCall(toolUse.getId(), toolUse.getName(), toolUse.getInput()));

            ToolCallback callback = registry.find(toolUse.getName());
            if (callback == null) {
                ToolResult error = ToolResult.error("tool not found: " + toolUse.getName(), 0);
                results.add(error);
                emitToolResult(ctx, toolUse, error);
                continue;
            }

            if (callback.isApprovalRequired()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolName", toolUse.getName());
                payload.put("toolInput", toolUse.getInput());
                throw new InterruptException(payload);
            }

            try {
                ToolResult result = callback.execute(toolUse.getInput(), null);
                if (result.getContent() != null && result.getContent().length() > maxToolResultChars) {
                    String suffix = "...[truncated]";
                    int cutLen = Math.max(0, maxToolResultChars - suffix.length());
                    String truncated = result.getContent().substring(0, cutLen) + suffix;
                    result = new ToolResult(truncated, result.getRowCount(), true, result.getDurationMs(), result.getError());
                }
                results.add(result);
            } catch (RuntimeException e) {
                log.warn("tool {} failed", toolUse.getName(), e);
                ToolResult error = ToolResult.error(e.getMessage(), 0);
                results.add(error);
            }

            emitToolResult(ctx, toolUse, results.get(results.size() - 1));
        }

        return state.with("tool_results", results);
    }

    private void emitToolResult(ExecutionContext ctx, ToolUseBlock toolUse, ToolResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", toolUse.getId());
        data.put("name", toolUse.getName());
        data.put("rowCount", result.getRowCount());
        data.put("truncated", result.isTruncated());
        if (result.getError() != null) {
            data.put("error", result.getError());
        }
        ctx.emit(TranscriptEvent.toolResult(toolUse.getId(), result.getRowCount(), result.isTruncated(), result.getDurationMs()));
    }
}
