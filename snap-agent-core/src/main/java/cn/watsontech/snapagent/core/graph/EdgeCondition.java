package cn.watsontech.snapagent.core.graph;

@FunctionalInterface
public interface EdgeCondition {
    String route(GraphState state);
}
