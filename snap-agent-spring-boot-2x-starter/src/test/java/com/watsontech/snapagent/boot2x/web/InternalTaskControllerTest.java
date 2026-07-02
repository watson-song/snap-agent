package com.watsontech.snapagent.boot2x.web;

import com.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import com.watsontech.snapagent.core.agent.AgentTask;
import com.watsontech.snapagent.core.agent.TaskStatus;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.agent.TranscriptEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalTaskControllerTest {

    private TaskStore taskStore;
    @Mock private AsyncTaskExecutor taskExecutor;
    private InternalTaskController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskStore = new TaskStore();
        controller = new InternalTaskController(taskStore, "secret-token", taskExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        // Inline execution so the streaming loop runs during the request.
        lenient().doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }

    private AgentTask seedTask(String id, String userId, TaskStatus status) {
        Map<String, String> inputs = new HashMap<String, String>();
        AgentTask task = new AgentTask(id, userId, "diag", inputs, "claude-sonnet-4-6");
        task.setStatus(status);
        taskStore.save(task);
        return task;
    }

    // ---- probe endpoint ----

    @Test
    void shouldReturn200WhenTaskExistsLocally() throws Exception {
        seedTask("task-1", "user-1", TaskStatus.RUNNING);
        mockMvc.perform(get("/snap-agent-internal/tasks/task-1/probe")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "secret-token"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"taskId\":\"task-1\",\"status\":\"RUNNING\",\"ownerPod\":true}"));
    }

    @Test
    void shouldReturn404WhenTaskNotFound() throws Exception {
        mockMvc.perform(get("/snap-agent-internal/tasks/missing/probe")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "secret-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn401WhenTokenMissing() throws Exception {
        seedTask("task-1", "user-1", TaskStatus.RUNNING);
        mockMvc.perform(get("/snap-agent-internal/tasks/task-1/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401WhenTokenWrong() throws Exception {
        seedTask("task-1", "user-1", TaskStatus.RUNNING);
        mockMvc.perform(get("/snap-agent-internal/tasks/task-1/probe")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401WhenNoTokenConfigured() throws Exception {
        controller = new InternalTaskController(taskStore, "", taskExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        seedTask("task-1", "user-1", TaskStatus.RUNNING);
        mockMvc.perform(get("/snap-agent-internal/tasks/task-1/probe")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "anything"))
                .andExpect(status().isUnauthorized());
    }

    // ---- stream endpoint ----

    @Test
    void shouldStreamTranscriptEventsForOwnedTask() throws Exception {
        AgentTask task = seedTask("task-1", "user-1", TaskStatus.SUCCEEDED);
        task.addTranscriptEvent(TranscriptEvent.thought("analyzing"));
        MvcResult result = mockMvc.perform(get("/snap-agent-internal/tasks/task-1/stream")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "secret-token"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("analyzing");
        assertThat(body).contains("event:done");
    }

    @Test
    void shouldSendErrorEventWhenTaskNotFoundOnStream() throws Exception {
        MvcResult result = mockMvc.perform(get("/snap-agent-internal/tasks/missing/stream")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "secret-token"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:error");
        assertThat(body).contains("TASK_NOT_FOUND");
    }

    @Test
    void shouldSendErrorEventWhenTokenMissingOnStream() throws Exception {
        seedTask("task-1", "user-1", TaskStatus.RUNNING);
        MvcResult result = mockMvc.perform(get("/snap-agent-internal/tasks/task-1/stream"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:error");
        assertThat(body).contains("UNAUTHORIZED");
    }

    @Test
    void shouldReturnNonNullEmitterForRunningTask() {
        // Non-terminal task — streaming loop would block; use direct call so the
        // inline executor does NOT run the loop (we only assert the emitter).
        seedTask("task-2", "user-1", TaskStatus.RUNNING);
        // Override executor to no-op for this case
        lenient().doAnswer(invocation -> null).when(taskExecutor).execute(any(Runnable.class));
        SseEmitter emitter = controller.stream("task-2",
                "secret-token");
        assertThat(emitter).isNotNull();
    }

    @Test
    void shouldNotEnforceUserOwnershipOnInternalStream() throws Exception {
        // The internal endpoint trusts the originating pod's ownership check.
        seedTask("task-1", "another-user", TaskStatus.SUCCEEDED);
        MvcResult result = mockMvc.perform(get("/snap-agent-internal/tasks/task-1/stream")
                        .header(PeerSseRelay.INTERNAL_TOKEN_HEADER, "secret-token"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("FORBIDDEN");
        assertThat(body).contains("event:done");
    }
}
