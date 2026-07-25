package cn.watsontech.snapagent.core.graph;

/**
 * Thrown when a graph contains an illegal structure, such as a cycle
 * in a DAG (Directed Acyclic Graph) workflow.
 *
 * <p>ReAct graphs allow cycles (agent→tools→agent), but workflow DAGs
 * must be acyclic. Use {@link StateGraph#compileDag()} to enforce
 * acyclic topology.</p>
 */
public class IllegalGraphException extends RuntimeException {
    private final String cyclePath;

    public IllegalGraphException(String message) {
        super(message);
        this.cyclePath = message;
    }

    public IllegalGraphException(String message, Throwable cause) {
        super(message, cause);
        this.cyclePath = message;
    }

    public String getCyclePath() {
        return cyclePath;
    }
}
