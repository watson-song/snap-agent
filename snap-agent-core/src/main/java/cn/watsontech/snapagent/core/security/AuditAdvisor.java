package cn.watsontech.snapagent.core.security;

import cn.watsontech.snapagent.core.agent.AuditRecord;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Advisor (order=400) that records LLM and tool invocations to AuditStore.
 *
 * <p>After agent node: records LLM call with model, token counts.</p>
 * <p>After tools node: records tool name, args, result, truncated flag.</p>
 * <p>If AuditStore.saveRecord throws, logs WARN and does not interrupt.</p>
 */
public class AuditAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(AuditAdvisor.class);

    private final AuditStore auditStore;

    public AuditAdvisor(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    @Override
    public int getOrder() { return 400; }

    @Override
    public String getName() { return "audit"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        return state;
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctxObj) {
        if (auditStore == null) {
            return state;
        }

        ExecutionContext ctx = (ExecutionContext) ctxObj;

        try {
            AuditRecord record;
            if ("tools".equals(nodeName)) {
                record = buildToolRecord(state, ctx, nodeName);
            } else {
                record = buildLlmRecord(state, ctx, nodeName);
            }
            auditStore.saveRecord(record);
        } catch (RuntimeException e) {
            log.warn("AuditStore.saveRecord failed, audit record lost", e);
        }

        return state;
    }

    private AuditRecord buildLlmRecord(GraphState state, ExecutionContext ctx, String nodeName) {
        Integer inputTokens = state.get(StateKeys.LLM_INPUT_TOKENS);
        Integer outputTokens = state.get(StateKeys.LLM_OUTPUT_TOKENS);
        String model = state.get(StateKeys.LLM_MODEL);

        return new AuditRecord(
                ctx.getTaskId(),
                ctx.getUserId(),
                nodeName,
                model,
                inputTokens != null ? inputTokens : 0,
                outputTokens != null ? outputTokens : 0,
                null,
                Collections.<String, Object>emptyMap(),
                null,
                0,
                false,
                System.currentTimeMillis(),
                0
        );
    }

    @SuppressWarnings("unchecked")
    private AuditRecord buildToolRecord(GraphState state, ExecutionContext ctx, String nodeName) {
        java.util.List<cn.watsontech.snapagent.core.tool.ToolResult> results = state.get(StateKeys.TOOL_RESULTS);
        String toolName = state.get(StateKeys.TOOL_NAME);
        java.util.Map<String, Object> args = state.get(StateKeys.TOOL_ARGS);

        int rowCount = 0;
        boolean truncated = false;
        String resultContent = null;

        if (results != null && !results.isEmpty()) {
            cn.watsontech.snapagent.core.tool.ToolResult first = results.get(0);
            rowCount = first.getRowCount();
            truncated = first.isTruncated();
            resultContent = first.getContent();
        }

        return new AuditRecord(
                ctx.getTaskId(),
                ctx.getUserId(),
                nodeName,
                null,
                0,
                0,
                toolName,
                args != null ? args : Collections.<String, Object>emptyMap(),
                resultContent,
                rowCount,
                truncated,
                System.currentTimeMillis(),
                0
        );
    }
}
