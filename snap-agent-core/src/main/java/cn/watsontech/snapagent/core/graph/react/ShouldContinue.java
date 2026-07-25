package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.graph.EdgeCondition;
import cn.watsontech.snapagent.core.graph.GraphState;

/**
 * ReAct loop condition: routes based on stop_reason.
 * end_turn → end (END)
 * tool_use → tools
 * max_tokens (no tool_use) → agent (continue generating)
 * error → end (terminate)
 */
public class ShouldContinue implements EdgeCondition {
    @Override
    public String route(GraphState state) {
        String stopReason = state.get("stop_reason");
        if (stopReason == null) return "end";
        switch (stopReason) {
            case "end_turn": return "end";
            case "tool_use": return "tools";
            case "max_tokens": return "agent";
            case "error": return "end";
            default: return "end";
        }
    }
}
