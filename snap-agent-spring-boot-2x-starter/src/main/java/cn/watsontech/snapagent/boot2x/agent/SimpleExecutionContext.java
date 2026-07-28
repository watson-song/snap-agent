package cn.watsontech.snapagent.boot2x.agent;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple implementation of {@link ExecutionContext} backed by an {@link AgentTask}.
 *
 * <p>Holds the LLM client, tool registry, and task metadata. Tracks cancellation
 * state and forwards {@link TranscriptEvent}s to a callback (if registered).</p>
 */
public class SimpleExecutionContext implements ExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(SimpleExecutionContext.class);

    private final AgentTask task;
    private final String skillName;
    private final LlmClient llmClient;
    private final ToolCallbackRegistry tools;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile boolean cancelled = false;

    public SimpleExecutionContext(AgentTask task, String skillName,
                                  LlmClient llmClient, ToolCallbackRegistry tools) {
        this.task = task;
        this.skillName = skillName;
        this.llmClient = llmClient;
        this.tools = tools;
    }

    @Override
    public LlmClient getLlmClient() { return llmClient; }

    @Override
    public ToolCallbackRegistry getTools() { return tools; }

    @Override
    public String getTaskId() { return task.getTaskId(); }

    @Override
    public String getUserId() { return task.getUserId(); }

    @Override
    public String getSkillName() { return skillName; }

    @Override
    public void emit(TranscriptEvent event) {
        if (event == null) return;
        log.debug("transcript event: type={} task={}", event.getType(), task.getTaskId());
        task.addTranscriptEvent(event);
    }

    @Override
    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    public void cancel() {
        this.cancelled = true;
    }

    public AgentTask getTask() { return task; }
}
