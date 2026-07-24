package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.patrol.BugfixSuggestion;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for issue closure REST endpoints in {@link SnapAgentController}.
 *
 * <p>Covers POST /runs/{taskId}/solution, POST /runs/{taskId}/issue,
 * GET /issues, GET /issues/{id}, POST /issues/{id}/verify,
 * POST /issues/{id}/close, GET /issues/recent-runs.
 * TDD spec: 08-patrol-alert UC-R10..R17.</p>
 */
class IssueEndpointTest {

    private MockMvc mockMvc;
    private IssueClosureService issueClosureService;
    private TaskStore taskStore;
    private TemplateBugfixSuggester bugfixSuggester;

    @BeforeEach
    void setUp() {
        issueClosureService = mock(IssueClosureService.class);
        taskStore = mock(TaskStore.class);
        bugfixSuggester = mock(TemplateBugfixSuggester.class);
        SecurityGateway securityGateway = mock(SecurityGateway.class);
        when(securityGateway.currentUserId()).thenReturn("test-user");
        when(securityGateway.hasPermission(anyString())).thenReturn(true);

        SnapAgentProperties props = new SnapAgentProperties();
        props.setEnabled(true);

        SnapAgentController controller = new SnapAgentController(
                null, null, taskStore, null, props, securityGateway,
                mock(RateLimiter.class), null, null, null, null, null,
                null, null, bugfixSuggester, issueClosureService, null, null, null, null, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private IssueClosure sampleIssue(String issueId, IssueStatus status) {
        return new IssueClosure(issueId, "EXT-" + issueId, "task-1",
                null, "test-user", "why QPS drop?",
                "NPE at line 87", null, null,
                status, null, null, null,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    // ── POST /runs/{taskId}/solution ──────────────────────────────

    @Test
    @DisplayName("POST /runs/{taskId}/solution returns 200 with issue DTO")
    void shouldProposeSolution() throws Exception {
        IssueClosure issue = sampleIssue("iss-1", IssueStatus.SOLUTION_PROPOSED);
        when(issueClosureService.proposeSolution("task-1")).thenReturn(issue);

        mockMvc.perform(post("/snap-agent/runs/task-1/solution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-1"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("SOLUTION_PROPOSED"));
    }

    @Test
    @DisplayName("POST /runs/{taskId}/solution returns 404 when task not found")
    void shouldReturn404WhenTaskNotFoundForSolution() throws Exception {
        when(issueClosureService.proposeSolution("unknown")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/runs/unknown/solution"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /runs/{taskId}/solution returns 503 when issue closure disabled")
    void shouldReturn503WhenIssueClosureDisabled() throws Exception {
        // Fresh controller with null issueClosureService
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn("test-user");
        when(sg.hasPermission(anyString())).thenReturn(true);
        SnapAgentController disabledController = new SnapAgentController(
                null, null, taskStore, null, new SnapAgentProperties(), sg,
                mock(RateLimiter.class), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(disabledController).build();

        disabledMvc.perform(post("/snap-agent/runs/task-1/solution"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ISSUE_CLOSURE_DISABLED"));
    }

    // ── POST /runs/{taskId}/issue ─────────────────────────────────

    @Test
    @DisplayName("POST /runs/{taskId}/issue returns 200 with created issue")
    void shouldCreateExternalIssue() throws Exception {
        IssueClosure issue = sampleIssue("iss-2", IssueStatus.ISSUE_CREATED);
        when(issueClosureService.createExternalIssue("task-1", "opt-A")).thenReturn(issue);

        mockMvc.perform(post("/snap-agent/runs/task-1/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selected_solution\":\"opt-A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-2"))
                .andExpect(jsonPath("$.status").value("ISSUE_CREATED"));
    }

    @Test
    @DisplayName("POST /runs/{taskId}/issue returns 404 when no issue closure found")
    void shouldReturn404WhenNoIssueClosureFound() throws Exception {
        when(issueClosureService.createExternalIssue(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(post("/snap-agent/runs/unknown/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selected_solution\":\"opt-A\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ISSUE_NOT_FOUND"));
    }

    // ── GET /issues ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /issues returns all issues")
    void shouldListAllIssues() throws Exception {
        when(issueClosureService.listIssues()).thenReturn(Arrays.asList(
                sampleIssue("iss-1", IssueStatus.SOLUTION_PROPOSED),
                sampleIssue("iss-2", IssueStatus.CLOSED)));

        mockMvc.perform(get("/snap-agent/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(2))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("GET /issues?status=CLOSED filters by status")
    void shouldFilterIssuesByStatus() throws Exception {
        when(issueClosureService.listIssues()).thenReturn(Arrays.asList(
                sampleIssue("iss-1", IssueStatus.SOLUTION_PROPOSED),
                sampleIssue("iss-2", IssueStatus.CLOSED)));

        mockMvc.perform(get("/snap-agent/issues?status=CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].issueId").value("iss-2"));
    }

    @Test
    @DisplayName("GET /issues?status=INVALID returns 400")
    void shouldReturn400ForInvalidStatus() throws Exception {
        mockMvc.perform(get("/snap-agent/issues?status=INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS"));
    }

    // ── GET /issues/{issueId} ─────────────────────────────────────

    @Test
    @DisplayName("GET /issues/{id} returns issue detail")
    void shouldGetIssueDetail() throws Exception {
        when(issueClosureService.loadIssue("iss-1")).thenReturn(
                sampleIssue("iss-1", IssueStatus.VERIFIED));

        mockMvc.perform(get("/snap-agent/issues/iss-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-1"))
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    @DisplayName("GET /issues/{id} returns 404 when not found")
    void shouldReturn404WhenIssueNotFound() throws Exception {
        when(issueClosureService.loadIssue("unknown")).thenReturn(null);

        mockMvc.perform(get("/snap-agent/issues/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ISSUE_NOT_FOUND"));
    }

    // ── POST /issues/{issueId}/verify ─────────────────────────────

    @Test
    @DisplayName("POST /issues/{id}/verify returns verified issue")
    void shouldVerifyIssue() throws Exception {
        when(issueClosureService.verify("iss-1")).thenReturn(
                sampleIssue("iss-1", IssueStatus.VERIFIED));

        mockMvc.perform(post("/snap-agent/issues/iss-1/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    @DisplayName("POST /issues/{id}/verify returns 404 when not found")
    void shouldReturn404WhenVerifyNotFound() throws Exception {
        when(issueClosureService.verify("unknown")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/issues/unknown/verify"))
                .andExpect(status().isNotFound());
    }

    // ── POST /issues/{issueId}/close ───────────────────────────────

    @Test
    @DisplayName("POST /issues/{id}/close returns closed issue")
    void shouldCloseIssue() throws Exception {
        when(issueClosureService.close("iss-1")).thenReturn(
                sampleIssue("iss-1", IssueStatus.CLOSED));

        mockMvc.perform(post("/snap-agent/issues/iss-1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("POST /issues/{id}/close returns 404 when not found")
    void shouldReturn404WhenCloseNotFound() throws Exception {
        when(issueClosureService.close("unknown")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/issues/unknown/close"))
                .andExpect(status().isNotFound());
    }

    // ── POST /runs/{id}/bugfix-suggestion ─────────────────────────

    @Test
    @DisplayName("POST /runs/{id}/bugfix-suggestion returns 200 with suggestion")
    void shouldGenerateBugfixSuggestion() throws Exception {
        AgentTask task = new AgentTask("task-1", "test-user", "health-patrol",
                new HashMap<String, String>(), null);
        task.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-1")).thenReturn(task);

        BugfixSuggestion suggestion = new BugfixSuggestion("task-1",
                "NPE at line 87 in UserService.java",
                Arrays.asList("UserService.java", "UserController.java"),
                "Add null check before calling user.getName()",
                BugfixSuggestion.CONFIDENCE_HIGH,
                Arrays.asList("abc1234", "def5678"));
        when(bugfixSuggester.suggest(eq("task-1"), any(List.class))).thenReturn(suggestion);

        mockMvc.perform(post("/snap-agent/runs/task-1/bugfix-suggestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.rootCause").value("NPE at line 87 in UserService.java"))
                .andExpect(jsonPath("$.suggestion").value("Add null check before calling user.getName()"))
                .andExpect(jsonPath("$.confidence").value("HIGH"))
                .andExpect(jsonPath("$.affectedFiles[0]").value("UserService.java"))
                .andExpect(jsonPath("$.commitRefs[0]").value("abc1234"));
    }

    @Test
    @DisplayName("POST /runs/{id}/bugfix-suggestion returns 404 when task not found")
    void shouldReturn404WhenTaskNotFoundForBugfixSuggestion() throws Exception {
        when(taskStore.get("unknown")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/runs/unknown/bugfix-suggestion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("task not found"));
    }

    // ── GET /issues/recent-runs ───────────────────────────────────

    @Test
    @DisplayName("GET /issues/recent-runs returns combined runs with issue status")
    void shouldListRecentRunWithIssues() throws Exception {
        AgentTask succeededTask = new AgentTask("task-1", "test-user", "health-patrol",
                new HashMap<String, String>(), null);
        succeededTask.setStatus(TaskStatus.SUCCEEDED);

        AgentTask failedTask = new AgentTask("task-2", "test-user", "code-review",
                new HashMap<String, String>(), null);
        failedTask.setStatus(TaskStatus.FAILED);

        when(taskStore.query(eq("test-user"), isNull(), isNull(), anyInt(), eq(0)))
                .thenReturn(Arrays.asList(succeededTask, failedTask));
        when(issueClosureService.listIssues()).thenReturn(Collections.singletonList(
                sampleIssue("iss-1", IssueStatus.SOLUTION_PROPOSED)));

        mockMvc.perform(get("/snap-agent/issues/recent-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.runs[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.runs[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runs[0].done").value(true))
                .andExpect(jsonPath("$.runs[1].taskId").value("task-2"))
                .andExpect(jsonPath("$.runs[1].status").value("FAILED"))
                .andExpect(jsonPath("$.runs[1].done").value(false));
    }

    @Test
    @DisplayName("GET /issues/recent-runs includes orphan issues without tasks")
    void shouldIncludeOrphanIssuesInRecentRuns() throws Exception {
        when(taskStore.query(eq("test-user"), isNull(), isNull(), anyInt(), eq(0)))
                .thenReturn(Collections.<AgentTask>emptyList());
        when(issueClosureService.listIssues()).thenReturn(Collections.singletonList(
                sampleIssue("iss-orphan", IssueStatus.VERIFIED)));

        mockMvc.perform(get("/snap-agent/issues/recent-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.runs[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.runs[0].issue.issueId").value("iss-orphan"));
    }

    // ── E2E-4: Full issue closure lifecycle ───────────────────────

    @Test
    @DisplayName("E2E-4: Full issue closure lifecycle (propose → create → verify → close)")
    void shouldCompleteFullIssueClosureLifecycle() throws Exception {
        // Step 1: Propose solution
        IssueClosure proposed = sampleIssue("iss-e2e", IssueStatus.SOLUTION_PROPOSED);
        when(issueClosureService.proposeSolution("task-e2e")).thenReturn(proposed);

        mockMvc.perform(post("/snap-agent/runs/task-e2e/solution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-e2e"))
                .andExpect(jsonPath("$.status").value("SOLUTION_PROPOSED"));

        // Step 2: Create external issue
        IssueClosure created = sampleIssue("iss-e2e", IssueStatus.ISSUE_CREATED);
        when(issueClosureService.createExternalIssue("task-e2e", "opt-A")).thenReturn(created);

        mockMvc.perform(post("/snap-agent/runs/task-e2e/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selected_solution\":\"opt-A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-e2e"))
                .andExpect(jsonPath("$.status").value("ISSUE_CREATED"));

        // Step 3: Verify fix
        IssueClosure verified = sampleIssue("iss-e2e", IssueStatus.VERIFIED);
        when(issueClosureService.verify("iss-e2e")).thenReturn(verified);

        mockMvc.perform(post("/snap-agent/issues/iss-e2e/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-e2e"))
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        // Step 4: Close issue
        IssueClosure closed = sampleIssue("iss-e2e", IssueStatus.CLOSED);
        when(issueClosureService.close("iss-e2e")).thenReturn(closed);

        mockMvc.perform(post("/snap-agent/issues/iss-e2e/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("iss-e2e"))
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // Verify all four service methods were invoked in order
        verify(issueClosureService).proposeSolution("task-e2e");
        verify(issueClosureService).createExternalIssue("task-e2e", "opt-A");
        verify(issueClosureService).verify("iss-e2e");
        verify(issueClosureService).close("iss-e2e");
    }
}
