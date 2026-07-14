package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * Callback invoked after a tool has been executed, for audit recording.
 *
 * <p>Defined in the tool package so that {@link ToolContext} can carry it
 * without depending on the agent package. The agent layer provides an
 * implementation that constructs {@code AuditRecord} objects.</p>
 */
public interface AuditCallback {

    /**
     * Called by {@link ToolDispatcher} after a tool provider returns a result.
     *
     * @param toolName the registered name of the tool
     * @param args     the arguments passed to the tool
     * @param result   the result returned by the tool
     */
    void onToolExecuted(String toolName, Map<String, Object> args, ToolResult result);
}
