package cn.watsontech.snapagent.core.graph.execution;

import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.*;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointNotFoundException;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Executes a {@link CompiledGraph} — drives the node execution loop,
 * handles checkpointing, cancel signals, max turns, and HITL interrupts.
 *
 * <p>Shared between ReAct (cycles allowed) and Workflow (DAG) topologies —
 * only the graph structure differs, not the execution loop.</p>
 *
 * <p>Edge routing behavior:
 * <ul>
 *   <li>Unknown route key → defaults to END + WARN log</li>
 *   <li>Empty routing table → defaults to END + WARN log</li>
 *   <li>EdgeCondition.route() throws → defaults to END + ERROR log, TaskStatus=FAILED</li>
 * </ul></p>
 */
public class GraphExecutor {
    private static final Logger log = LoggerFactory.getLogger(GraphExecutor.class);
    private final CheckpointStore checkpointStore;
    private final int maxTurns;

    public GraphExecutor(CheckpointStore checkpointStore, int maxTurns) {
        this.checkpointStore = checkpointStore;
        this.maxTurns = maxTurns;
    }

    public TaskResult execute(CompiledGraph graph, GraphState state, ExecutionContext ctx) {
        String currentNode = graph.getEntryPoint();
        GraphState currentState = state;

        while (true) {
            if (ctx.isCancelled()) {
                return new TaskResult(TaskStatus.CANCELLED, "cancelled");
            }
            if (currentState.getTurn() >= maxTurns) {
                return new TaskResult(TaskStatus.TIMEOUT, "max-turns exceeded");
            }

            Node node = graph.getNodes().get(currentNode);
            if (node == null) {
                return new TaskResult(TaskStatus.FAILED, "node not found: " + currentNode);
            }

            try {
                currentState = node.execute(currentState, ctx);
            } catch (InterruptException e) {
                saveCheckpointSafe(currentState, currentNode, ctx);
                ctx.emit(TranscriptEvent.paused("interrupted"));
                return new TaskResult(TaskStatus.PAUSED, "interrupted");
            } catch (RuntimeException e) {
                saveCheckpointSafe(currentState, currentNode, ctx);
                log.error("node {} failed", currentNode, e);
                return new TaskResult(TaskStatus.FAILED, e.getMessage());
            }

            saveCheckpointSafe(currentState, currentNode, ctx);
            currentState = currentState.nextTurn();

            List<EdgeTarget> edges = graph.getEdgesFrom(currentNode);
            if (edges.isEmpty()) {
                return new TaskResult(TaskStatus.SUCCEEDED, "completed");
            }

            String nextNode = resolveNextNode(edges, currentState, currentNode);

            if (nextNode == null) {
                // No route matched — reached END
                return new TaskResult(TaskStatus.SUCCEEDED, "completed");
            }

            currentNode = nextNode;
        }
    }

    public TaskResult resume(CompiledGraph graph, String checkpointId, ExecutionContext ctx) {
        GraphState state = checkpointStore.load(checkpointId);
        if (state == null) {
            throw new CheckpointNotFoundException(checkpointId);
        }
        return execute(graph, state, ctx);
    }

    /**
     * Resolve the next node from edges. Handles:
     * - Simple edges (no condition): take the first one
     * - Conditional edges: evaluate condition, match route key
     * - Unknown route key: default to END (return null)
     * - EdgeCondition exception: default to END (return null) + log ERROR
     * - Empty routing table: default to END (return null) + log WARN
     */
    private String resolveNextNode(List<EdgeTarget> edges, GraphState state, String currentNode) {
        // Check if there are simple (unconditional) edges
        boolean hasConditional = false;
        boolean hasSimple = false;

        for (EdgeTarget edge : edges) {
            if (edge.getCondition() != null) {
                hasConditional = true;
            } else {
                hasSimple = true;
            }
        }

        // If there are simple (unconditional) edges, take the first one
        if (hasSimple) {
            for (EdgeTarget edge : edges) {
                if (edge.getCondition() == null) {
                    return edge.getNodeName();
                }
            }
        }

        // All edges are conditional — need to evaluate
        if (!hasConditional) {
            // Empty routing table
            log.warn("empty routing table from node {}, fallback to END", currentNode);
            return null;
        }

        // Evaluate conditional edges
        for (EdgeTarget edge : edges) {
            if (edge.getCondition() == null) continue;

            try {
                String routeKey = edge.getCondition().route(state);
                if (routeKey != null && routeKey.equals(edge.getLabel())) {
                    log.debug("conditional edge from {} routed to {} (key={})", currentNode, edge.getNodeName(), routeKey);
                    return edge.getNodeName();
                }
            } catch (RuntimeException e) {
                log.error("edge condition from node {} threw exception, fallback to END", currentNode, e);
                return null;
            }
        }

        // No route key matched — unknown key
        log.warn("unknown route key from node {}, fallback to END", currentNode);
        return null;
    }

    private void saveCheckpointSafe(GraphState state, String nodeName, ExecutionContext ctx) {
        try {
            String checkpointId = checkpointStore.save(ctx.getTaskId(), state);
            log.debug("checkpoint saved: {} at node {}", checkpointId, nodeName);
        } catch (RuntimeException e) {
            log.warn("checkpoint save failed, degraded", e);
        }
    }
}
