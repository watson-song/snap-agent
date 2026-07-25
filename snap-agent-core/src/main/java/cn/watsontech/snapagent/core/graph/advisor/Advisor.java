package cn.watsontech.snapagent.core.graph.advisor;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;

/**
 * Cross-cutting concern as graph node decorator.
 * Ordered by getOrder() — lower runs first.
 */
public interface Advisor {
    int getOrder();
    String getName();
    GraphState beforeNode(String nodeName, GraphState state, Object ctx) throws InterruptException;
    GraphState afterNode(String nodeName, GraphState state, Object ctx) throws InterruptException;
}
