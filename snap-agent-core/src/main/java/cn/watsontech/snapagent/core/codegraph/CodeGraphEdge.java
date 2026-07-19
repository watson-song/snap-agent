package cn.watsontech.snapagent.core.codegraph;

/**
 * A directed edge in the code graph representing a semantic relationship
 * between two {@link CodeGraphNode} instances.
 */
public class CodeGraphEdge {

    public enum EdgeType {
        CALLS,        // method A calls method B
        IMPLEMENTS,   // class A implements interface B
        EXTENDS,      // class A extends class B
        DEPENDS_ON,   // class A depends on class B (field type / method param)
        OVERRIDES,    // method A overrides method B
        REFERENCES    // method A references field B
    }

    private final String fromId;
    private final String toId;
    private final EdgeType type;
    private final String context;

    public CodeGraphEdge(String fromId, String toId, EdgeType type, String context) {
        this.fromId = fromId;
        this.toId = toId;
        this.type = type;
        this.context = context;
    }

    public String getFromId() { return fromId; }
    public String getToId() { return toId; }
    public EdgeType getType() { return type; }
    public String getContext() { return context; }

    @Override
    public String toString() {
        return fromId + " --" + type + "--> " + toId
                + (context != null ? " (" + context + ")" : "");
    }
}
