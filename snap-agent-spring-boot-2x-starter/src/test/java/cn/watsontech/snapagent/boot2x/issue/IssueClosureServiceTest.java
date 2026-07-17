package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggester;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.issue.VerificationRunner;
import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IssueClosureService}.
 */
class IssueClosureServiceTest {

    private AgentExecutor agentExecutor;
    private TaskStore taskStore;
    private SkillRegistry skillRegistry;
    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private KnowledgeBase knowledgeBase;
    private KnowledgeSedimentationExtractor sedimentationExtractor;
    private SolutionSuggester solutionSuggester;
    private VerificationRunner verificationRunner;

    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        taskStore = mock(TaskStore.class);
        skillRegistry = mock(SkillRegistry.class);
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        knowledgeBase = mock(KnowledgeBase.class);
        sedimentationExtractor = new KnowledgeSedimentationExtractor();
        solutionSuggester = null;
        verificationRunner = null;

        // By default the service uses the legacy fallback path (null SPIs).
        service = newIssueClosureService(null, null);
    }

    private IssueClosureService newIssueClosureService(
            SolutionSuggester suggester, VerificationRunner runner) {
        return new IssueClosureService(agentExecutor, taskStore, skillRegistry,
                issueStore, issueTracker, knowledgeBase, sedimentationExtractor,
                suggester, runner, "system");
    }

    private SolutionSuggestion suggestionOf(String... titles) {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        int index = 1;
        for (String title : titles) {
            options.add(new SolutionOption("opt-" + index, title, title, "medium", false));
            index++;
        }
        String recommended = options.isEmpty() ? null : "opt-1";
        return new SolutionSuggestion(options, recommended, null, null);
    }

    private VerificationResult verificationOf(boolean passed, String summary) {
        return new VerificationResult(passed, summary, "FIX_IN_PROGRESS",
                passed ? "SUCCEEDED" : "FAILED", 1_500L);
    }

    // ---- proposeSolution ----

    @Test
    void shouldProposeSolutionAndStoreIssue() {
        // Prepare diagnostic task
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "order service timeout");
        AgentTask diagTask = new AgentTask("task-001", "user1", "health-check",
                inputs, null);
        diagTask.setReport("Root cause: connection pool exhausted");
        diagTask.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-001")).thenReturn(diagTask);

        // Prepare skill
        SkillMeta skill = new SkillMeta("solution-suggest", "desc",
                Collections.<String>emptyList(), Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("solution-suggest")).thenReturn(skill);

        // Mock execute to set a report on the task
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setReport("Solution 1: Increase pool size\nSolution 2: Add retry logic");
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        IssueClosure result = service.proposeSolution("task-001");

        assertThat(result).isNotNull();
        assertThat(result.getTaskId()).isEqualTo("task-001");
        assertThat(result.getRootCause()).isEqualTo("Root cause: connection pool exhausted");
        assertThat(result.getUserQuery()).contains("order service timeout");
        assertThat(result.getStatus()).isEqualTo(IssueStatus.SOLUTION_PROPOSED);
        assertThat(result.getSolution()).isNotNull();
        assertThat(result.getSolution().getOptions()).hasSize(2);
        assertThat(result.getSolution().getOptions().get(0).getTitle()).contains("Increase pool size");
        assertThat(result.getSolution().getRecommendedOptionId()).isEqualTo("opt-1");

        verify(issueStore).save(any(IssueClosure.class));
    }

    @Test
    void shouldReturnNullWhenTaskNotFoundForProposeSolution() {
        when(taskStore.get("nonexistent")).thenReturn(null);

        IssueClosure result = service.proposeSolution("nonexistent");

        assertThat(result).isNull();
        verify(issueStore, never()).save(any(IssueClosure.class));
    }

    @Test
    void shouldReturnNullWhenSolutionSuggestSkillNotFound() {
        AgentTask diagTask = new AgentTask("task-002", "user1", "health-check",
                new HashMap<String, String>(), null);
        diagTask.setReport("some root cause");
        when(taskStore.get("task-002")).thenReturn(diagTask);
        when(skillRegistry.get("solution-suggest")).thenReturn(null);

        IssueClosure result = service.proposeSolution("task-002");

        assertThat(result).isNull();
        verify(issueStore, never()).save(any(IssueClosure.class));
    }

    // ---- createExternalIssue ----

    @Test
    void shouldCreateExternalIssueAndUpdateStatus() {
        IssueClosure existing = new IssueClosure(
                "issue-001", null, "task-100",
                null, "query", "root cause text",
                suggestionOf("solution A", "solution B"), null,
                IssueStatus.SOLUTION_PROPOSED, null,
                null, null,
                1_000L, 2_000L);
        when(issueStore.findByTaskId("task-100")).thenReturn(existing);
        when(issueTracker.createIssue(anyString(), anyString(), nullable(String.class)))
                .thenReturn("EXT-001");

        IssueClosure result = service.createExternalIssue("task-100", "solution A");

        assertThat(result).isNotNull();
        assertThat(result.getExternalIssueId()).isEqualTo("EXT-001");
        assertThat(result.getSelectedSolution()).isEqualTo("solution A");
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);

        verify(issueTracker).createIssue(anyString(), eq("solution A"), nullable(String.class));
        verify(issueStore).save(any(IssueClosure.class));
    }

    @Test
    void shouldReturnNullWhenIssueNotFoundForCreateExternalIssue() {
        when(issueStore.findByTaskId("nonexistent")).thenReturn(null);

        IssueClosure result = service.createExternalIssue("nonexistent", "solution");

        assertThat(result).isNull();
        verify(issueTracker, never()).createIssue(anyString(), anyString(), nullable(String.class));
    }

    @Test
    void shouldHandleNullExternalIssueIdFromNoopTracker() {
        IssueClosure existing = new IssueClosure(
                "issue-002", null, "task-200",
                null, "query", "root cause",
                suggestionOf("sol"), null,
                IssueStatus.SOLUTION_PROPOSED, null,
                null, null,
                1_000L, 2_000L);
        when(issueStore.findByTaskId("task-200")).thenReturn(existing);
        when(issueTracker.createIssue(anyString(), anyString(), nullable(String.class)))
                .thenReturn(null); // NoopIssueTracker returns null

        IssueClosure result = service.createExternalIssue("task-200", "sol");

        assertThat(result).isNotNull();
        assertThat(result.getExternalIssueId()).isNull();
        assertThat(result.getSelectedSolution()).isEqualTo("sol");
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
        verify(issueStore).save(any(IssueClosure.class));
    }

    // ---- verify ----

    @Test
    void shouldVerifyFixAndUpdateIssue() {
        IssueClosure existing = new IssueClosure(
                "issue-003", "EXT-1", "task-300",
                null, "order timeout", "connection pool exhausted",
                suggestionOf("increase pool"), "increase pool",
                IssueStatus.FIX_IN_PROGRESS, null,
                null, null,
                1_000L, 2_000L);
        when(issueStore.load("issue-003")).thenReturn(existing);

        SkillMeta skill = new SkillMeta("verify-fix", "desc",
                Collections.<String>emptyList(), Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("verify-fix")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setReport("Verification passed: pool size now adequate");
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        IssueClosure result = service.verify("issue-003");

        assertThat(result).isNotNull();
        assertThat(result.getVerificationResult()).isNotNull();
        assertThat(result.getVerificationResult().isPassed()).isTrue();
        assertThat(result.getVerificationResult().getSummary()).contains("Verification passed");
        assertThat(result.getStatus()).isEqualTo(IssueStatus.VERIFIED);
        verify(issueStore).save(any(IssueClosure.class));
    }

    @Test
    void shouldReturnNullWhenIssueNotFoundForVerify() {
        when(issueStore.load("nonexistent")).thenReturn(null);

        IssueClosure result = service.verify("nonexistent");

        assertThat(result).isNull();
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    @Test
    void shouldReturnNullWhenVerifyFixSkillNotFound() {
        IssueClosure existing = new IssueClosure(
                "issue-004", null, "task-400",
                null, "query", "root cause",
                suggestionOf("sol"), null,
                IssueStatus.FIX_IN_PROGRESS, null,
                null, null,
                1_000L, 2_000L);
        when(issueStore.load("issue-004")).thenReturn(existing);
        when(skillRegistry.get("verify-fix")).thenReturn(null);

        IssueClosure result = service.verify("issue-004");

        assertThat(result).isNull();
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    // ---- close ----

    @Test
    void shouldCloseIssueAndSedimentKnowledge() {
        IssueClosure existing = new IssueClosure(
                "issue-005", "EXT-5", "task-500",
                null, "order timeout", "pool exhausted",
                suggestionOf("increase pool"), "increase pool",
                IssueStatus.VERIFIED, null,
                verificationOf(true, "verification passed"), null,
                1_000L, 3_000L);
        when(issueStore.load("issue-005")).thenReturn(existing);

        IssueClosure result = service.close("issue-005");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.CLOSED);
        assertThat(result.getKnowledgeEntryId()).isEqualTo("sedimentation:issue-005");
        verify(knowledgeBase).reload();
        verify(issueStore).save(any(IssueClosure.class));
    }

    @Test
    void shouldReturnNullWhenIssueNotFoundForClose() {
        when(issueStore.load("nonexistent")).thenReturn(null);

        IssueClosure result = service.close("nonexistent");

        assertThat(result).isNull();
        verify(issueStore, never()).save(any(IssueClosure.class));
    }

    // ---- null knowledgeBase ----

    @Test
    void shouldCloseWithoutKnowledgeBaseWhenDisabled() {
        // Service with null knowledgeBase
        IssueClosureService serviceNoKb = newIssueClosureService(null, null);

        IssueClosure existing = new IssueClosure(
                "issue-006", null, "task-600",
                null, "query", "root cause",
                suggestionOf("sol"), "sol",
                IssueStatus.VERIFIED, null,
                verificationOf(true, "verified"), null,
                1_000L, 2_000L);
        when(issueStore.load("issue-006")).thenReturn(existing);

        IssueClosure result = serviceNoKb.close("issue-006");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.CLOSED);
        assertThat(result.getKnowledgeEntryId()).isEqualTo("sedimentation:issue-006");
        // Should not throw even though knowledgeBase is null
        verify(issueStore).save(any(IssueClosure.class));
    }

    @Test
    void shouldExtractRootCauseFromTaskReport() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "high latency");
        AgentTask diagTask = new AgentTask("task-007", "user1", "health-check",
                inputs, null);
        diagTask.setReport("Root cause: missing database index");
        diagTask.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-007")).thenReturn(diagTask);

        SkillMeta skill = new SkillMeta("solution-suggest", "desc",
                Collections.<String>emptyList(), Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("solution-suggest")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setReport("Solution 1: Add index");
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        IssueClosure result = service.proposeSolution("task-007");

        assertThat(result).isNotNull();
        assertThat(result.getRootCause()).isEqualTo("Root cause: missing database index");

        // Verify the solution task inputs were populated correctly
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentExecutor).execute(taskCaptor.capture(), any(SkillMeta.class));
        AgentTask executedTask = taskCaptor.getValue();
        assertThat(executedTask.getInputs().get("root_cause")).isEqualTo("Root cause: missing database index");
        assertThat(executedTask.getInputs().get("original_query")).contains("high latency");
        assertThat(executedTask.getInputs().get("task_id")).isEqualTo("task-007");
        assertThat(executedTask.getUserId()).isEqualTo("system");
    }

    // ---- SPI path: SolutionSuggester / VerificationRunner ----

    @Test
    void shouldUseSolutionSuggesterWhenConfigured() {
        SolutionSuggester suggester = mock(SolutionSuggester.class);
        IssueClosureService serviceWithSuggester = newIssueClosureService(suggester, null);

        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "order service timeout");
        AgentTask diagTask = new AgentTask("task-spi", "user1", "health-check",
                inputs, null);
        diagTask.setReport("连接超时, 连接池打满");
        diagTask.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-spi")).thenReturn(diagTask);

        SolutionSuggestion expectedSuggestion = suggestionOf("调整连接池配置", "增加超时时间");
        when(suggester.suggest(any(IssueClosure.class), anyString()))
                .thenReturn(expectedSuggestion);

        IssueClosure result = serviceWithSuggester.proposeSolution("task-spi");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.SOLUTION_PROPOSED);
        assertThat(result.getSolution()).isSameAs(expectedSuggestion);
        assertThat(result.getSolution().getRecommendedOptionId()).isEqualTo("opt-1");
        // AgentExecutor should NOT be invoked when a suggester is configured.
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
        verify(issueStore).save(any(IssueClosure.class));
    }

    @Test
    void shouldUseVerificationRunnerWhenConfigured() {
        VerificationRunner runner = mock(VerificationRunner.class);
        IssueClosureService serviceWithRunner = newIssueClosureService(null, runner);

        IssueClosure existing = new IssueClosure(
                "issue-spi", "EXT-1", "task-spi",
                null, "order timeout", "connection pool exhausted",
                suggestionOf("increase pool"), "increase pool",
                IssueStatus.FIX_IN_PROGRESS, null,
                null, null,
                1_000L, 2_000L);
        when(issueStore.load("issue-spi")).thenReturn(existing);

        VerificationResult expectedResult = verificationOf(true, "re-run succeeded");
        when(runner.verify(any(IssueClosure.class))).thenReturn(expectedResult);

        IssueClosure result = serviceWithRunner.verify("issue-spi");

        assertThat(result).isNotNull();
        assertThat(result.getVerificationResult()).isSameAs(expectedResult);
        assertThat(result.getVerificationResult().isPassed()).isTrue();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.VERIFIED);
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
        verify(issueStore).save(any(IssueClosure.class));
    }
}
