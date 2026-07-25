package cn.watsontech.snapagent.core.graph.execution;

import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.*;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

            String nextNode = null;
            for (EdgeTarget edge : edges) {
                if (edge.getCondition() != null) {
                    String routeKey = edge.getCondition().route(currentState);
                    if (edge.getLabel() != null && edge.getLabel().equals(routeKey)) {
                        nextNode = edge.getNodeName();
                        break;
                    }
                } else {
                    nextNode = edge.getNodeName();
                    break;
                }
            }

            if (nextNode == null) {
                return new TaskResult(TaskStatus.SUCCEEDED, "completed");
            }

            currentNode = nextNode;
        }
    }

    public TaskResult resume(CompiledGraph graph, String checkpointId, ExecutionContext ctx) {
        GraphState state = checkpointStore.load(checkpointId);
        if (state == null) {
            throw new IllegalStateException("checkpoint not found: " + checkpointId);
        }
        return execute(graph, state, ctx);
    }

    private void saveCheckpointSafe(GraphState state, String nodeName, ExecutionContext ctx) {
        try {
            String checkpointId = checkpointStore.save(ctx.getTaskId(), state);
            log.debug("checkpoint saved: {} at node {}", checkpointId, nodeName);
        } catch (RuntimeException e) {
            log.warn("checkpoint save failed", e);
        }
    }
}
