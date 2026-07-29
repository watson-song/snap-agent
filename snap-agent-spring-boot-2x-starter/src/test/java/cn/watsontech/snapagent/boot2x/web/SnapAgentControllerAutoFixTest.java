package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.issue.IssueClosureService;
import cn.watsontech.snapagent.boot2x.security.InMemoryAuditStore;
import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the v1.1 auto-fix workflow controller endpoints:
 * <ul>
 *   <li>POST /runs/{taskId}/auto-fix</li>
 *   <li>POST /issues/{issueId}/auto-fix</li>
 *   <li>POST /snap-agent-internal/vcs/webhook</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SnapAgentControllerAutoFixTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentService agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolCallbackRegistry toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private RateLimiter rateLimiter;
    @Mock private AsyncTaskExecutor taskExecutor;
    @Mock private IssueClosureService issueClosureService;

    private SnapAgentProperties properties;
    private SnapAgentController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new SnapAgentProperties();
        objectMapper = new ObjectMapper();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(100);
        // Use the 16-param constructor that accepts IssueClosureService
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, null, null, null,
                null, null, null, issueClosureService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
    }

    // ---- POST /runs/{taskId}/auto-fix ----

    @Test
    void shouldReturn404WhenAutoFixByTaskNotFound() throws Exception {
        when(issueClosureService.findByTaskId("task-999")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/runs/task-999/auto-fix"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ISSUE_NOT_FOUND"));
    }

    @Test
    void shouldReturnAutoFixResultByTask() throws Exception {
        IssueClosure issue = createIssue("issue-1", "task-1", IssueStatus.FIX_IN_PROGRESS);
        when(issueClosureService.findByTaskId("task-1")).thenReturn(issue);
        when(issueClosureService.autoFix("issue-1")).thenReturn(
                createIssue("issue-1", "task-1", IssueStatus.FIX_SUBMITTED));

        mockMvc.perform(post("/snap-agent/runs/task-1/auto-fix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("issue-1"))
                .andExpect(jsonPath("$.status").value("FIX_SUBMITTED"))
                .andExpect(jsonPath("$.fixPrUrl").exists())
                .andExpect(jsonPath("$.fixPrNumber").exists());
    }

    @Test
    void shouldReturn503WhenAutoFixByTaskFails() throws Exception {
        IssueClosure issue = createIssue("issue-1", "task-1", IssueStatus.FIX_IN_PROGRESS);
        when(issueClosureService.findByTaskId("task-1")).thenReturn(issue);
        when(issueClosureService.autoFix("issue-1")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/runs/task-1/auto-fix"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("AUTO_FIX_FAILED"));
    }

    // ---- POST /issues/{issueId}/auto-fix ----

    @Test
    void shouldReturn404WhenAutoFixIssueNotFound() throws Exception {
        when(issueClosureService.autoFix("issue-999")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/issues/issue-999/auto-fix"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ISSUE_NOT_FOUND"));
    }

    @Test
    void shouldReturnAutoFixResultByIssue() throws Exception {
        when(issueClosureService.autoFix("issue-1")).thenReturn(
                createIssue("issue-1", "task-1", IssueStatus.FIX_SUBMITTED));

        mockMvc.perform(post("/snap-agent/issues/issue-1/auto-fix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("issue-1"))
                .andExpect(jsonPath("$.status").value("FIX_SUBMITTED"));
    }

    // ---- POST /snap-agent-internal/vcs/webhook ----

    @Test
    void shouldReturnIgnoredWhenIssueClosureDisabled() throws Exception {
        // Recreate controller with null issueClosureService
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, null, null, null,
                null, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("object_attributes", new LinkedHashMap<String, Object>());

        mockMvc.perform(post("/snap-agent/snap-agent-internal/vcs/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void shouldReturnIgnoredWhenNoPrNumber() throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("event", "push");

        mockMvc.perform(post("/snap-agent/snap-agent-internal/vcs/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void shouldReturnIgnoredWhenPrNotMerged() throws Exception {
        Map<String, Object> attr = new LinkedHashMap<String, Object>();
        attr.put("iid", 42);
        attr.put("state", "opened");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("object_attributes", attr);

        mockMvc.perform(post("/snap-agent/snap-agent-internal/vcs/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void shouldReturnProcessedWhenPrMerged() throws Exception {
        Map<String, Object> attr = new LinkedHashMap<String, Object>();
        attr.put("iid", 42);
        attr.put("state", "merged");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("object_attributes", attr);

        IssueClosure issue = createIssue("issue-1", "task-1", IssueStatus.VERIFIED);
        when(issueClosureService.onPrMerged("42")).thenReturn(issue);

        mockMvc.perform(post("/snap-agent/snap-agent-internal/vcs/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));
    }

    @Test
    void shouldReturnNoMatchingIssueWhenOnPrMergedReturnsNull() throws Exception {
        Map<String, Object> attr = new LinkedHashMap<String, Object>();
        attr.put("iid", 99);
        attr.put("state", "merged");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("object_attributes", attr);

        when(issueClosureService.onPrMerged("99")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/snap-agent-internal/vcs/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_matching_issue"));
    }

    @Test
    void shouldParseBitbucketWebhookFormat() throws Exception {
        Map<String, Object> pr = new LinkedHashMap<String, Object>();
        pr.put("id", 7);
        pr.put("state", "MERGED");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("pullRequest", pr);

        when(issueClosureService.onPrMerged("7")).thenReturn(
                createIssue("issue-1", "task-1", IssueStatus.VERIFIED));

        mockMvc.perform(post("/snap-agent/snap-agent-internal/vcs/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));
    }

    // ---- Helper ----

    private IssueClosure createIssue(String issueId, String taskId, IssueStatus status) {
        IssueClosure base = new IssueClosure(
                issueId, "EXT-1", null, taskId,
                "conv-1", "user001", "test query", "root cause",
                null, null,
                IssueStatus.FIX_IN_PROGRESS, "commit-abc",
                "https://vcs.example.com/pr/1", "1",
                null, null,
                System.currentTimeMillis(), System.currentTimeMillis()
        );
        return base.withStatus(status, System.currentTimeMillis());
    }
}
