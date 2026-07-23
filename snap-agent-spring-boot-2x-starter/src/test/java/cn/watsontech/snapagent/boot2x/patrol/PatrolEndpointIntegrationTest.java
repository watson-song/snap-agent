package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolScheduler;
import cn.watsontech.snapagent.core.patrol.PatrolTask;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for v0.5 patrol, alert, and bugfix endpoints.
 *
 * <p>Uses standalone MockMvc with mocked PatrolScheduler and AlertConverger,
 * and a real TemplateBugfixSuggester to exercise the template-based suggestion logic.</p>
 */
class PatrolEndpointIntegrationTest {

    private MockMvc mockMvc;
    private PatrolScheduler patrolScheduler;
    private AlertConverger alertConverger;
    private TemplateBugfixSuggester bugfixSuggester;
    private TaskStore taskStore;
    private SecurityGateway securityGateway;

    @BeforeEach
    void setUp() {
        patrolScheduler = mock(PatrolScheduler.class);
        alertConverger = mock(AlertConverger.class);
        bugfixSuggester = new TemplateBugfixSuggester();
        taskStore = mock(TaskStore.class);
        securityGateway = mock(SecurityGateway.class);

        when(securityGateway.currentUserId()).thenReturn("test-user");
        when(securityGateway.hasPermission(anyString())).thenReturn(true);

        SnapAgentProperties props = new SnapAgentProperties();
        props.setEnabled(true);

        // Use the 15-arg constructor that accepts patrol/alert/bugfix beans directly
        SnapAgentController controller = new SnapAgentController(
                null, // skillRegistry
                null, // agentExecutor
                taskStore,
                null, // toolDispatcher
                props,
                securityGateway,
                mock(RateLimiter.class),
                null, // taskExecutor
                null, // peerSseRelay
                null, // llmClient
                null, // auditLogger
                null, // conversationStore
                patrolScheduler,
                alertConverger,
                bugfixSuggester
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /patrol/tasks creates a patrol task")
    void shouldCreatePatrolTask() throws Exception {
        // schedule() must set the task id so the response includes a non-null id
        doAnswer(invocation -> {
            PatrolTask task = invocation.getArgument(0);
            task.setId("patrol-001");
            return null;
        }).when(patrolScheduler).schedule(any(PatrolTask.class));

        mockMvc.perform(post("/snap-agent/patrol/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillName\":\"health-patrol\",\"cron\":\"0 */5 * * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("patrol-001"))
                .andExpect(jsonPath("$.skillName").value("health-patrol"))
                .andExpect(jsonPath("$.cron").value("0 */5 * * * *"));
    }

    @Test
    @DisplayName("GET /patrol/tasks returns list of tasks")
    void shouldListPatrolTasks() throws Exception {
        PatrolTask task = new PatrolTask("p1", "health-patrol", "0 */5 * * * *", "test-user", null);
        when(patrolScheduler.listTasks()).thenReturn(Collections.singletonList(task));

        mockMvc.perform(get("/snap-agent/patrol/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].skillName").value("health-patrol"));
    }

    @Test
    @DisplayName("DELETE /patrol/tasks/{id} cancels task")
    void shouldDeletePatrolTask() throws Exception {
        mockMvc.perform(delete("/snap-agent/patrol/tasks/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    @DisplayName("GET /patrol/reports returns paginated reports")
    void shouldListPatrolReports() throws Exception {
        PatrolReport report = new PatrolReport("r1", "p1", "t1", "health-patrol",
                1000L, "SUCCESS", "OK", false);
        when(patrolScheduler.getReports(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(report));
        when(patrolScheduler.countReports(anyString())).thenReturn(1L);

        mockMvc.perform(get("/snap-agent/patrol/reports?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].id").value("r1"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("GET /alerts returns paginated alerts")
    void shouldListAlerts() throws Exception {
        AlertConvergence alert = new AlertConvergence("a1", "fp", "ERROR_SPIKE",
                "svc", "NPE", "task-1");
        when(alertConverger.query(any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(alert));
        when(alertConverger.count(any(), any())).thenReturn(1L);

        mockMvc.perform(get("/snap-agent/alerts?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].id").value("a1"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("POST /alerts/{id}/resolve resolves alert")
    void shouldResolveAlert() throws Exception {
        mockMvc.perform(post("/snap-agent/alerts/a1/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));
    }

    // ── expanded coverage (GAP-5) ─────────────────────────────────

    @Test
    @DisplayName("PATCH /patrol/tasks/{id}/toggle toggles task enabled state")
    void shouldTogglePatrolTask() throws Exception {
        when(patrolScheduler.toggleEnabled("p1")).thenReturn(false);

        mockMvc.perform(patch("/snap-agent/patrol/tasks/p1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p1"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("PATCH /patrol/tasks/{id}/toggle returns 404 for unknown task")
    void shouldReturn404WhenToggleUnknownTask() throws Exception {
        when(patrolScheduler.toggleEnabled("unknown")).thenReturn(null);

        mockMvc.perform(patch("/snap-agent/patrol/tasks/unknown/toggle"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /alerts?type=ERROR_SPIKE filters alerts by type")
    void shouldListAlertsWithTypeFilter() throws Exception {
        AlertConvergence alert = new AlertConvergence(
                "a1", "fp", "ERROR_SPIKE", "svc", "NPE", "task-1");
        when(alertConverger.query(any(), eq("ERROR_SPIKE"), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(alert));
        when(alertConverger.count(any(), eq("ERROR_SPIKE"))).thenReturn(1L);

        mockMvc.perform(get("/snap-agent/alerts?page=0&size=20&type=ERROR_SPIKE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].id").value("a1"))
                .andExpect(jsonPath("$.alerts[0].type").value("ERROR_SPIKE"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("GET /alerts with no type returns all alerts")
    void shouldListAllAlertsWithoutTypeFilter() throws Exception {
        AlertConvergence a1 = new AlertConvergence(
                "a1", "fp1", "ERROR_SPIKE", "svc-a", "msg1", "t1");
        AlertConvergence a2 = new AlertConvergence(
                "a2", "fp2", "DB_DOWN", "svc-b", "msg2", "t2");
        when(alertConverger.query(any(), isNull(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(a1, a2));
        when(alertConverger.count(any(), isNull())).thenReturn(2L);

        mockMvc.perform(get("/snap-agent/alerts?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("GET /patrol/reports returns 404-style empty for unknown user")
    void shouldReturnEmptyReportsForUnknownUser() throws Exception {
        when(patrolScheduler.getReports(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.<PatrolReport>emptyList());
        when(patrolScheduler.countReports(anyString())).thenReturn(0L);

        mockMvc.perform(get("/snap-agent/patrol/reports?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("POST /runs/{id}/bugfix-suggestion returns suggestion from transcript")
    void shouldGenerateBugfixSuggestion() throws Exception {
        // Build a transcript with code_read + git_log → HIGH confidence
        Map<String, Object> codeReadArgs = new LinkedHashMap<>();
        codeReadArgs.put("file_path", "/src/OrderService.java");

        AgentTask task = new AgentTask("task-1", "test-user", "test-skill",
                Collections.<String, String>emptyMap(), "claude-sonnet-4-6");
        task.addTranscriptEvent(TranscriptEvent.toolCall("c1", "code_read", codeReadArgs));
        task.addTranscriptEvent(TranscriptEvent.toolCall("c2", "git_log", codeReadArgs));
        task.addTranscriptEvent(TranscriptEvent.toolResult("c2", 1, false, 100,
                "abc1234 Fix NPE\ndef5678 Refactor", null));
        task.addTranscriptEvent(TranscriptEvent.done("SUCCEEDED",
                "Root cause: null pointer at line 87"));
        when(taskStore.get("task-1")).thenReturn(task);

        mockMvc.perform(post("/snap-agent/runs/task-1/bugfix-suggestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.confidence").value("HIGH"))
                .andExpect(jsonPath("$.affectedFiles[0]").value("/src/OrderService.java"))
                .andExpect(jsonPath("$.commitRefs").isArray())
                .andExpect(jsonPath("$.commitRefs.length()").value(2));
    }

    @Test
    @DisplayName("POST /runs/{id}/bugfix-suggestion returns 404 for unknown task")
    void shouldReturn404ForUnknownTask() throws Exception {
        when(taskStore.get("unknown")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/runs/unknown/bugfix-suggestion"))
                .andExpect(status().isNotFound());
    }
}
