package cn.watsontech.snapagent.core.graph;

/**
 * Represents a routing target from a node.
 * For simple edges: nodeName + no label + no condition.
 * For conditional edges: nodeName + label (routing key) + condition.
 */
public class EdgeTarget {
    private final String nodeName;
    private final String label;
    private final EdgeCondition condition;

    public EdgeTarget(String nodeName) {
        this(nodeName, null, null);
    }

    public EdgeTarget(String nodeName, String label) {
        this(nodeName, label, null);
    }

    public EdgeTarget(String nodeName, String label, EdgeCondition condition) {
        this.nodeName = nodeName;
        this.label = label;
        this.condition = condition;
    }

    public String getNodeName() { return nodeName; }
    public String getLabel() { return label; }
    public EdgeCondition getCondition() { return condition; }
}
