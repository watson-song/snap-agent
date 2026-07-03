package com.watsontech.snapagent.boot2x.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.watsontech.snapagent.core.agent.AgentExecutor;
import com.watsontech.snapagent.core.agent.AgentTask;
import com.watsontech.snapagent.core.agent.RateLimiter;
import com.watsontech.snapagent.core.agent.TaskStatus;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.agent.TranscriptEvent;
import com.watsontech.snapagent.core.security.SecurityGateway;
import com.watsontech.snapagent.core.skill.SkillAvailability;
import com.watsontech.snapagent.core.skill.SkillMeta;
import com.watsontech.snapagent.core.skill.InputSpec;
import com.watsontech.snapagent.core.skill.SkillRegistry;
import com.watsontech.snapagent.core.tool.ToolDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link SnapAgentController} using standalone MockMvc.
 *
 * <p>Covers GET /skills, GET /models, POST /skills/refresh, POST /runs,
 * GET /runs/{id}, GET /runs/{id}/transcript (TDD_SPEC §UC-20).</p>
 */
@ExtendWith(MockitoExtension.class)
class SnapAgentControllerTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private AsyncTaskExecutor taskExecutor;
    @Mock private RateLimiter rateLimiter;

    private SnapAgentProperties properties;
    private SnapAgentController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new SnapAgentProperties();
        objectMapper = new ObjectMapper();
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
    }

    @Test
    void shouldReturnSkillsListWhenGetSkills() throws Exception {
        List<InputSpec> inputs = Collections.singletonList(
                new InputSpec("skuCode", "件号", true, "string", null, null));
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), inputs, "body",
                SkillAvailability.AVAILABLE, null);
        when(skillRegistry.all()).thenReturn(Collections.singletonList(skill));

        mockMvc.perform(get("/snap-agent/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].name").value("test-skill"))
                .andExpect(jsonPath("$.skills[0].availability").value("AVAILABLE"));
    }

    @Test
    void shouldReturnModelsWhenGetModels() throws Exception {
        mockMvc.perform(get("/snap-agent/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default").value("claude-sonnet-4-6"))
                .andExpect(jsonPath("$.allowed[0]").value("claude-sonnet-4-6"));
    }

    @Test
    void shouldReturnToolsWhenGetTools() throws Exception {
        when(toolDispatcher.availableToolNames())
                .thenReturn(new java.util.HashSet<String>(Arrays.asList("mysql_query")));

        mockMvc.perform(get("/snap-agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("mysql_query"));
    }

    @Test
    void shouldReturnRefreshResultWhenPostRefresh() throws Exception {
        when(skillRegistry.refresh())
                .thenReturn(new SkillRegistry.RefreshResult(5, 4, 1, 0));

        mockMvc.perform(post("/snap-agent/skills/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.available").value(4))
                .andExpect(jsonPath("$.unavailable").value(1))
                .andExpect(jsonPath("$.invalid").value(0));
    }

    @Test
    void shouldReturn202WhenPostRunsValid() throws Exception {
        List<InputSpec> inputs = Collections.singletonList(
                new InputSpec("skuCode", "件号", true, "string", null, null));
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), inputs, "body",
                SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);
        when(rateLimiter.tryAcquire("user001")).thenReturn(true);
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            // Don't actually run, just simulate acceptance
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "test-skill");
        Map<String, String> inputsMap = new HashMap<String, String>();
        inputsMap.put("skuCode", "A001");
        body.put("inputs", inputsMap);

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.streamUrl").isNotEmpty());
    }

    @Test
    void shouldReturn404WhenSkillNotFound() throws Exception {
        when(skillRegistry.get("notexist")).thenReturn(null);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "notexist");

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SKILL_NOT_FOUND"));
    }

    @Test
    void shouldReturn409WhenSkillUnavailable() throws Exception {
        SkillMeta skill = new SkillMeta("bad-skill", "测试",
                Collections.singletonList("mysql_query"), Collections.<InputSpec>emptyList(),
                "body", SkillAvailability.UNAVAILABLE, "missing tool");
        when(skillRegistry.get("bad-skill")).thenReturn(skill);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "bad-skill");

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SKILL_UNAVAILABLE"));
    }

    @Test
    void shouldReturn400WhenRequiredInputMissing() throws Exception {
        List<InputSpec> inputs = Collections.singletonList(
                new InputSpec("skuCode", "件号", true, "string", null, null));
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), inputs, "body",
                SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "test-skill");
        // Missing skuCode
        body.put("inputs", new HashMap<String, String>());

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    @Test
    void shouldReturn400WhenModelNotAllowed() throws Exception {
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), Collections.<InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "test-skill");
        body.put("model", "gpt-4");

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MODEL_NOT_ALLOWED"));
    }

    @Test
    void shouldReturn429WhenRateLimited() throws Exception {
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), Collections.<InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);
        when(rateLimiter.tryAcquire("user001")).thenReturn(false);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "test-skill");

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    void shouldReturnTaskStatusWhenGetRunById() throws Exception {
        AgentTask task = new AgentTask("sa_123", "user001", "test-skill",
                Collections.<String, String>emptyMap(), "claude-sonnet-4-6");
        task.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("sa_123")).thenReturn(task);

        mockMvc.perform(get("/snap-agent/runs/sa_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("sa_123"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void shouldReturn404WhenTaskNotFound() throws Exception {
        when(taskStore.get("notexist")).thenReturn(null);

        mockMvc.perform(get("/snap-agent/runs/notexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }

    @Test
    void shouldReturnTranscriptWhenGetTranscript() throws Exception {
        AgentTask task = new AgentTask("sa_123", "user001", "test-skill",
                Collections.<String, String>emptyMap(), "claude-sonnet-4-6");
        task.addTranscriptEvent(TranscriptEvent.thought("thinking"));
        when(taskStore.get("sa_123")).thenReturn(task);

        mockMvc.perform(get("/snap-agent/runs/sa_123/transcript"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcript[0].type").value("thought"))
                .andExpect(jsonPath("$.transcript[0].text").value("thinking"));
    }

    @Test
    void shouldReturnSseEmitterWhenStreamRun() throws Exception {
        AgentTask task = new AgentTask("sa_123", "user001", "test-skill",
                Collections.<String, String>emptyMap(), "claude-sonnet-4-6");
        task.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("sa_123")).thenReturn(task);

        // Use direct controller call instead of MockMvc for SSE
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                controller.streamRun("sa_123", null);

        assertThat(emitter).isNotNull();
    }

    @Test
    void shouldReturnErrorEmitterWhenTaskNotFoundForStream() throws Exception {
        when(taskStore.get("notexist")).thenReturn(null);

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                controller.streamRun("notexist", null);

        assertThat(emitter).isNotNull();
    }

    @Test
    void shouldExecuteRunnableWhenCreateRunSubmitted() throws Exception {
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), Collections.<InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);
        when(rateLimiter.tryAcquire("user001")).thenReturn(true);

        // Use a direct executor that runs synchronously
        final java.util.concurrent.atomic.AtomicReference<Runnable> capturedRunnable =
                new java.util.concurrent.atomic.AtomicReference<Runnable>();
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            capturedRunnable.set(r);
            r.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "test-skill");

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());

        // The Runnable was executed, agentExecutor.execute should have been called
        verify(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    @Test
    void shouldReleaseRateLimiterWhenExecutionFails() throws Exception {
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), Collections.<InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);
        when(rateLimiter.tryAcquire("user001")).thenReturn(true);

        // Simulate execution failure
        doThrow(new RuntimeException("execution failed"))
                .when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "test-skill");

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());

        verify(rateLimiter).release("user001");
    }

    // ---- Cross-pod relay delegation ----

    @Test
    void shouldDelegateToRelayWhenTaskNotFoundAndRelayAvailable() {
        com.watsontech.snapagent.boot2x.routing.PeerSseRelay relay =
                org.mockito.Mockito.mock(com.watsontech.snapagent.boot2x.routing.PeerSseRelay.class);
        SnapAgentController relayController = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor, relay);

        when(taskStore.get("notexist")).thenReturn(null);
        // Inline executor so the relay call runs synchronously
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
        when(relay.tryRelay(any(), anyString())).thenReturn(true);

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                relayController.streamRun("notexist", null);
        assertThat(emitter).isNotNull();
        verify(relay).tryRelay(any(), org.mockito.Mockito.eq("notexist"));
    }

    @Test
    void shouldEmitTaskNotFoundWhenRelayReturnsFalse() {
        com.watsontech.snapagent.boot2x.routing.PeerSseRelay relay =
                org.mockito.Mockito.mock(com.watsontech.snapagent.boot2x.routing.PeerSseRelay.class);
        SnapAgentController relayController = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor, relay);

        when(taskStore.get("notexist")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
        when(relay.tryRelay(any(), anyString())).thenReturn(false);

        // Should not throw — relay returned false, error event is sent to emitter
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                relayController.streamRun("notexist", null);
        assertThat(emitter).isNotNull();
        verify(relay).tryRelay(any(), org.mockito.Mockito.eq("notexist"));
    }

    @Test
    void shouldNotCallRelayWhenTaskFoundLocally() {
        com.watsontech.snapagent.boot2x.routing.PeerSseRelay relay =
                org.mockito.Mockito.mock(com.watsontech.snapagent.boot2x.routing.PeerSseRelay.class);
        SnapAgentController relayController = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor, relay);

        AgentTask task = new AgentTask("sa_local", "user001", "test-skill",
                Collections.<String, String>emptyMap(), "claude-sonnet-4-6");
        when(taskStore.get("sa_local")).thenReturn(task);
        // No-op executor — local streaming loop would block on a non-terminal task
        doAnswer(invocation -> null).when(taskExecutor).execute(any(Runnable.class));

        relayController.streamRun("sa_local", null);
        org.mockito.Mockito.verifyNoInteractions(relay);
    }

    // ---- DELETE /skills/{name} tests ----

    @Test
    void shouldReturn404WhenDeleteNonExistentSkill() throws Exception {
        when(skillRegistry.get("nonexistent")).thenReturn(null);

        mockMvc.perform(delete("/snap-agent/skills/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SKILL_NOT_FOUND"));
    }

    @Test
    void shouldReturn403WhenDeleteBuiltinSkill() throws Exception {
        SkillMeta builtin = new SkillMeta("builtin-skill", "desc",
                Collections.singletonList("mysql_query"), Collections.emptyList(), "body",
                SkillAvailability.AVAILABLE, null, "builtin", false);
        when(skillRegistry.get("builtin-skill")).thenReturn(builtin);
        when(skillRegistry.getCustomSkillPath("builtin-skill")).thenReturn(null);

        mockMvc.perform(delete("/snap-agent/skills/builtin-skill"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("BUILTIN_SKILL"));
    }

    @Test
    void shouldDeleteCustomSkillAndReturn200() throws Exception {
        SkillMeta custom = new SkillMeta("custom-skill", "desc",
                Collections.singletonList("mysql_query"), Collections.emptyList(), "body",
                SkillAvailability.AVAILABLE, null, "custom", false);
        when(skillRegistry.get("custom-skill")).thenReturn(custom);

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-skill", ".md");
        java.nio.file.Files.write(tempFile, "content".getBytes());
        when(skillRegistry.getCustomSkillPath("custom-skill")).thenReturn(tempFile);
        when(skillRegistry.refresh())
                .thenReturn(new SkillRegistry.RefreshResult(0, 0, 0, 0));
        when(skillRegistry.isBuiltin("custom-skill")).thenReturn(false);

        mockMvc.perform(delete("/snap-agent/skills/custom-skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value("custom-skill"))
                .andExpect(jsonPath("$.builtinRestored").value(false));

        assertThat(java.nio.file.Files.exists(tempFile)).isFalse();
    }

    @Test
    void shouldReportBuiltinRestoredWhenCustomOverrideDeleted() throws Exception {
        SkillMeta custom = new SkillMeta("shared", "desc",
                Collections.singletonList("mysql_query"), Collections.emptyList(), "body",
                SkillAvailability.AVAILABLE, null, "custom", true);
        when(skillRegistry.get("shared")).thenReturn(custom);

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-skill", ".md");
        java.nio.file.Files.write(tempFile, "content".getBytes());
        when(skillRegistry.getCustomSkillPath("shared")).thenReturn(tempFile);
        when(skillRegistry.refresh())
                .thenReturn(new SkillRegistry.RefreshResult(1, 1, 0, 0));
        when(skillRegistry.isBuiltin("shared")).thenReturn(true);

        mockMvc.perform(delete("/snap-agent/skills/shared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtinRestored").value(true));
    }

    @Test
    void shouldIncludeSourceInSkillDto() throws Exception {
        SkillMeta skill = new SkillMeta("test-skill", "desc",
                Collections.singletonList("mysql_query"), Collections.emptyList(), "body",
                SkillAvailability.AVAILABLE, null, "builtin", false);
        when(skillRegistry.all()).thenReturn(Collections.singletonList(skill));

        mockMvc.perform(get("/snap-agent/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].source").value("builtin"))
                .andExpect(jsonPath("$.skills[0].overridesBuiltin").value(false));
    }
}
