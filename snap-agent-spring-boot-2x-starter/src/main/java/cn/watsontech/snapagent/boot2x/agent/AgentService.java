package cn.watsontech.snapagent.boot2x.agent;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.CompiledGraph;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.checkpoint.InMemoryCheckpointStore;
import cn.watsontech.snapagent.core.graph.execution.GraphExecutor;
import cn.watsontech.snapagent.core.graph.execution.TaskResult;
import cn.watsontech.snapagent.core.graph.react.ReActGraphFactory;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 2.x replacement for the 1.x {@code AgentExecutor}. Uses the graph-based
 * runtime ({@link ReActGraphFactory} + {@link GraphExecutor}) to execute
 * agent tasks with the ReAct (Reason→Act→Observe) loop.
 *
 * <p>The graph is built per-execution from the skill definition and task
 * inputs. Advisors (e.g. project context, cost tracking) are injected as
 * cross-cutting concerns via {@link AdvisorNode} wrappers.</p>
 */
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmClient llmClient;
    private final ToolCallbackRegistry tools;
    private final TaskStore taskStore;
    private final int maxTurns;
    private final List<Advisor> advisors;
    private final GraphExecutor graphExecutor;

    public AgentService(LlmClient llmClient, ToolCallbackRegistry tools,
                        TaskStore taskStore, int maxTurns, List<Advisor> advisors) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.taskStore = taskStore;
        this.maxTurns = maxTurns;
        this.advisors = advisors != null ? advisors : Collections.<Advisor>emptyList();
        this.graphExecutor = new GraphExecutor(new InMemoryCheckpointStore(), maxTurns);
    }

    /**
     * Executes an agent task using the 2.x graph runtime.
     *
     * <p>Builds a ReAct graph from the skill definition, creates an execution
     * context, and drives the graph loop via {@link GraphExecutor}. The task
     * status and report are updated in the {@link TaskStore} on completion.</p>
     *
     * @param task  the agent task to execute
     * @param skill the skill metadata defining the agent's behavior
     */
    public void execute(AgentTask task, SkillMeta skill) {
        log.info("AgentService executing task {} with skill '{}'", task.getTaskId(), skill.getName());

        if (llmClient == null) {
            log.error("LlmClient not available; cannot execute task {}", task.getTaskId());
            task.setStatus(TaskStatus.FAILED);
            task.setReport("LlmClient not configured");
            taskStore.update(task);
            return;
        }

        task.setStatus(TaskStatus.RUNNING);
        taskStore.update(task);

        try {
            // Build the ReAct graph from skill + task
            CompiledGraph graph = new ReActGraphFactory().build(skill, task, advisors);

            // Create initial empty state
            GraphState initialState = GraphState.empty(task.getTaskId());

            // Create execution context
            SimpleExecutionContext ctx = new SimpleExecutionContext(
                    task, skill.getName(), llmClient, tools);

            // Execute the graph
            TaskResult result = graphExecutor.execute(graph, initialState, ctx);

            // Map graph TaskResult to AgentTask status
            TaskStatus finalStatus = mapStatus(result.getStatus());
            task.setStatus(finalStatus);
            task.setReport(extractReport(task, result));
            taskStore.update(task);

            log.info("Task {} completed with status {}", task.getTaskId(), finalStatus);

        } catch (RuntimeException e) {
            log.error("Task {} failed with exception", task.getTaskId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setReport("Execution failed: " + e.getMessage());
            taskStore.update(task);
        }
    }

    private TaskStatus mapStatus(cn.watsontech.snapagent.core.agent.TaskStatus graphStatus) {
        // GraphExecutor already returns TaskStatus, but let's be explicit
        return graphStatus;
    }

    /**
     * Extracts the LLM response from the task's transcript thought events.
     * Falls back to {@link TaskResult#getReport()} for non-success statuses
     * where the report contains error details (e.g., "max-turns exceeded").
     */
    private String extractReport(AgentTask task, TaskResult result) {
        if (result.getStatus() != TaskStatus.SUCCEEDED) {
            return result.getReport();
        }
        StringBuilder sb = new StringBuilder();
        for (TranscriptEvent event : task.getTranscript()) {
            if (TranscriptEvent.TYPE_THOUGHT.equals(event.getType()) && event.getText() != null) {
                sb.append(event.getText());
            }
        }
        String report = sb.toString().trim();
        return report.isEmpty() ? result.getReport() : report;
    }
}
