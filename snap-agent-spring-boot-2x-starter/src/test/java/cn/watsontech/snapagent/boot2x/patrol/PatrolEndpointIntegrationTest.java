package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolScheduler;
import cn.watsontech.snapagent.core.patrol.PatrolTask;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

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
 *
 * <p>TDD spec: 08-patrol-alert GAP-9..GAP-13.</p>
 */
@ExtendWith(MockitoExtension.class)
class PatrolEndpointIntegrationTest {

    @Mock private PatrolScheduler patrolScheduler;
    @Mock private AlertConverger alertConverger;
    @Mock private TaskStore taskStore;
    @Mock private SecurityGateway securityGateway;
    @Mock private RateLimiter rateLimiter;
    @Mock private LlmClient llmClient;

    private MockMvc mockMvc;
    private TemplateBugfixSuggester bugfixSuggester;
    private SnapAgentProperties props;

    @BeforeEach
    void setUp() {
        bugfixSuggester = new TemplateBugfixSuggester();
        props = new SnapAgentProperties();
        props.setEnabled(true);

        lenient().when(securityGateway.currentUserId()).thenReturn("test-user");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);

        // Use the 15-arg constructor that accepts patrol/alert/bugfix beans directly
        SnapAgentController controller = new SnapAgentController(
                null, // skillRegistry
                null, // agentExecutor
                taskStore,
                null, // toolDispatcher
                props,
                securityGateway,
                rateLimiter,
                null, // taskExecutor
                null, // peerSseRelay
                llmClient,
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

    // ── GAP-11: GET /alerts with type filter (already covered, verify) ──

    @Test
    @DisplayName("GAP-11: GET /alerts?type=ERROR_SPIKE filters alerts by type")
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

    // ── GAP-9: POST /patrol/infer (content analysis) → 200 ───────

    @Test
    @DisplayName("GAP-9: POST /patrol/infer returns inferred name and keywords")
    void shouldInferPatrolMeta() throws Exception {
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"name\":\"健康巡检\",\"keywords\":\"异常,超时\"}");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        mockMvc.perform(post("/snap-agent/patrol/infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"每天检查服务健康状态\",\"skillName\":\"health-patrol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("健康巡检"))
                .andExpect(jsonPath("$.keywords").value("异常,超时"));
    }

    // ── GAP-10: POST /patrol/infer with missing content → 400 ────

    @Test
    @DisplayName("GAP-10: POST /patrol/infer returns 400 when instruction is missing")
    void shouldReturn400WhenInstructionMissing() throws Exception {
        mockMvc.perform(post("/snap-agent/patrol/infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("instruction is required"));
    }

    // ── GAP-12: POST /alerts/{id}/resolve for non-existent alert → 404 ──

    @Test
    @DisplayName("GAP-12: POST /alerts/{id}/resolve returns 404 for non-existent alert")
    void shouldReturn404WhenResolvingNonExistentAlert() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "alert not found"))
                .when(alertConverger).resolve("nonexistent");

        mockMvc.perform(post("/snap-agent/alerts/nonexistent/resolve"))
                .andExpect(status().isNotFound());
    }

    // ── GAP-13: GET /alerts without authentication → 401 ──────────

    @Test
    @DisplayName("GAP-13a: GET /alerts returns 401 when not authenticated")
    void shouldReturn401WhenAlertsNoAuth() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // ── GAP-13: GET /alerts without permission → 403 ──────────────

    @Test
    @DisplayName("GAP-13b: GET /alerts returns 403 without permission")
    void shouldReturn403WhenAlertsNoPermission() throws Exception {
        when(securityGateway.hasPermission(anyString())).thenReturn(false);

        mockMvc.perform(get("/snap-agent/alerts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}
