package cn.watsontech.snapagent.core.graph;

/**
 * Thrown when a workflow YAML definition fails to compile into a StateGraph.
 *
 * <p>Common causes: missing entry point, empty nodes list, edges referencing
 * unknown nodes, invalid conditional edge routing.</p>
 */
public class WorkflowCompileException extends RuntimeException {
    public WorkflowCompileException(String message) {
        super(message);
    }

    public WorkflowCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
