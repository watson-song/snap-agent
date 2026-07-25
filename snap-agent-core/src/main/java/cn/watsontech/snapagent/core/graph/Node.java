package cn.watsontech.snapagent.core.graph;

import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;

public interface Node {
    default String getName() {
        return getClass().getSimpleName();
    }
    GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException;
}
