package cn.watsontech.snapagent.core.graph;

/**
 * Represents a routing target from a node.
 * For simple edges: nodeName + no label.
 * For conditional edges: nodeName + label (the routing key).
 */
public class EdgeTarget {
    private final String nodeName;
    private final String label;

    public EdgeTarget(String nodeName) {
        this(nodeName, null);
    }

    public EdgeTarget(String nodeName, String label) {
        this.nodeName = nodeName;
        this.label = label;
    }

    public String getNodeName() { return nodeName; }
    public String getLabel() { return label; }
}
