package cn.watsontech.snapagent.core.graph.advisor;

import cn.watsontech.snapagent.core.graph.GraphState;

/**
 * Cross-cutting concern as graph node decorator.
 * Ordered by getOrder() — lower runs first.
 */
public interface Advisor {
    int getOrder();
    String getName();
    GraphState beforeNode(String nodeName, GraphState state, Object ctx);
    GraphState afterNode(String nodeName, GraphState state, Object ctx);
}
