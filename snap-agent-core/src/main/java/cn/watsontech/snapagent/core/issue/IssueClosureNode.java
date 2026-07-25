package cn.watsontech.snapagent.core.issue;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional terminal graph node that closes an issue after agent end_turn.
 *
 * <p>When enabled ({@code snap-agent.patrol.issue-closure-node=true}), the
 * graph routes {@code end_turn → issue_closure → END}. This node calls
 * {@link IssueClosureHandler#close(String)} to complete the issue lifecycle
 * (close + knowledge sedimentation).</p>
 *
 * <p>Exceptions from the handler are caught and logged — the graph success
 * is never blocked by issue closure failures.</p>
 */
public class IssueClosureNode implements Node {

    private static final Logger log = LoggerFactory.getLogger(IssueClosureNode.class);

    public static final String NODE_NAME = "issue_closure";

    private final IssueClosureHandler handler;

    /**
     * @param handler the closure handler, or null for no-op
     */
    public IssueClosureNode(IssueClosureHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getName() {
        return NODE_NAME;
    }

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        if (handler == null) {
            return state;
        }
        String taskId = ctx.getTaskId();
        if (taskId == null || taskId.isEmpty()) {
            return state;
        }
        try {
            handler.close(taskId);
        } catch (RuntimeException e) {
            log.warn("IssueClosureNode handler failed for task {}: {}", taskId, e.getMessage());
        }
        return state;
    }
}
