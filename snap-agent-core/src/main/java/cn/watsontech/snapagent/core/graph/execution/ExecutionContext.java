package cn.watsontech.snapagent.core.graph.execution;

import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;

public interface ExecutionContext {
    LlmClient getLlmClient();
    ToolCallbackRegistry getTools();
    String getTaskId();
    String getUserId();
    void emit(TranscriptEvent event);
    boolean isCancelled();
}
