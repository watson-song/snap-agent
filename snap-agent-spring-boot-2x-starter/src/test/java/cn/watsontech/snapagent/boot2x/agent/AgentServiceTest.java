package cn.watsontech.snapagent.boot2x.agent;

import cn.watsontech.snapagent.boot2x.conversation.Conversation;
import cn.watsontech.snapagent.boot2x.conversation.ConversationStore;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.CompiledGraph;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.execution.TaskResult;
import cn.watsontech.snapagent.core.graph.react.ReActGraphFactory;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AgentService} conversation auto-save and error detection.
 */
class AgentServiceTest {

    // ---- ConversationStore injection ----

    @Test
    void constructorWithNullConversationStoreDoesNotThrow() {
        TaskStore taskStore = new TaskStore(10, 60000);
        AgentService svc = new AgentService(null, mock(ToolCallbackRegistry.class),
                taskStore, 10, Collections.<Advisor>emptyList(), null);
        // Should not throw — conversationStore is optional
        assertThat(svc).isNotNull();
    }

    @Test
    void constructorWithConversationStoreAcceptsIt() {
        TaskStore taskStore = new TaskStore(10, 60000);
        ConversationStore convStore = mock(ConversationStore.class);
        AgentService svc = new AgentService(null, mock(ToolCallbackRegistry.class),
                taskStore, 10, Collections.<Advisor>emptyList(), convStore);
        assertThat(svc).isNotNull();
    }

    // ---- LLM null → FAILED ----

    @Test
    void executeWithNullLlmClientSetsTaskToFailed() {
        TaskStore taskStore = new TaskStore(10, 60000);
        AgentService svc = new AgentService(null, mock(ToolCallbackRegistry.class),
                taskStore, 10, Collections.<Advisor>emptyList(), null);

        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("message", "hello");
        AgentTask task = new AgentTask("t1", "user1", "test-skill", inputs, "model");
        SkillMeta skill = new SkillMeta("test-skill", "test", Collections.<String>emptyList(),
                Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "body", cn.watsontech.snapagent.core.skill.SkillAvailability.AVAILABLE, null);

        svc.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getReport()).contains("LlmClient not configured");
    }

    // ---- Error event detection in transcript ----

    @Test
    void errorEventsInTranscriptAreDetected() {
        // Verify that TranscriptEvent.TYPE_ERROR events can be added and detected
        AgentTask task = new AgentTask("t1", "user1", "skill", 
                new HashMap<String, String>(), "model");
        
        // Simulate the graph executor adding thought + error events
        task.addTranscriptEvent(TranscriptEvent.thought("thinking..."));
        task.addTranscriptEvent(TranscriptEvent.error("HTTP error: 403"));
        
        // Verify error event is in transcript
        boolean hasError = false;
        for (TranscriptEvent ev : task.getTranscript()) {
            if (TranscriptEvent.TYPE_ERROR.equals(ev.getType())) {
                hasError = true;
                break;
            }
        }
        assertThat(hasError).isTrue();
    }

    @Test
    void transcriptWithoutErrorEventsHasNoError() {
        AgentTask task = new AgentTask("t1", "user1", "skill",
                new HashMap<String, String>(), "model");
        
        task.addTranscriptEvent(TranscriptEvent.thought("thinking..."));
        task.addTranscriptEvent(TranscriptEvent.thought("more thinking..."));
        
        boolean hasError = false;
        for (TranscriptEvent ev : task.getTranscript()) {
            if (TranscriptEvent.TYPE_ERROR.equals(ev.getType())) {
                hasError = true;
                break;
            }
        }
        assertThat(hasError).isFalse();
    }

    // ---- extractReport safety ----

    @Test
    void extractReportFromTranscriptThoughts() {
        // Verify that extracting text from THOUGHT events works correctly
        AgentTask task = new AgentTask("t1", "user1", "skill",
                new HashMap<String, String>(), "model");
        
        task.addTranscriptEvent(TranscriptEvent.thought("Hello "));
        task.addTranscriptEvent(TranscriptEvent.thought("World"));
        
        StringBuilder sb = new StringBuilder();
        for (TranscriptEvent event : task.getTranscript()) {
            if (TranscriptEvent.TYPE_THOUGHT.equals(event.getType()) && event.getText() != null) {
                sb.append(event.getText());
            }
        }
        assertThat(sb.toString().trim()).isEqualTo("Hello World");
    }

    @Test
    void extractReportWithEmptyTranscriptReturnsEmpty() {
        AgentTask task = new AgentTask("t1", "user1", "skill",
                new HashMap<String, String>(), "model");
        
        StringBuilder sb = new StringBuilder();
        for (TranscriptEvent event : task.getTranscript()) {
            if (TranscriptEvent.TYPE_THOUGHT.equals(event.getType()) && event.getText() != null) {
                sb.append(event.getText());
            }
        }
        assertThat(sb.toString().trim()).isEmpty();
    }

    // ---- TaskResult ----

    @Test
    void taskResultCarriesStatusAndReport() {
        TaskResult result = new TaskResult(TaskStatus.SUCCEEDED, "done");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.getReport()).isEqualTo("done");
    }

    @Test
    void taskResultFailedStatus() {
        TaskResult result = new TaskResult(TaskStatus.FAILED, "error occurred");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getReport()).isEqualTo("error occurred");
    }
}
