package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SimpleVerificationRunner}.
 *
 * <p>Verifies the re-run-and-check flow: load the original diagnostic task,
 * resolve its skill, re-execute it with the system user, and compare the
 * resulting {@link TaskStatus} against {@link TaskStatus#SUCCEEDED}.</p>
 */
class SimpleVerificationRunnerTest {

    private AgentExecutor agentExecutor;
    private TaskStore taskStore;
    private SkillRegistry skillRegistry;

    private SimpleVerificationRunner runner;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        taskStore = mock(TaskStore.class);
        skillRegistry = mock(SkillRegistry.class);

        runner = new SimpleVerificationRunner(agentExecutor, taskStore, skillRegistry, "system");
    }

    private IssueClosure issueWithTaskId(String taskId) {
        return new IssueClosure(
                "issue-verify", null, taskId,
                null, "为什么订单服务超时?", "连接池打满",
                null, null,
                IssueStatus.FIX_IN_PROGRESS, null,
                null, null,
                1_000L, 2_000L);
    }

    private SkillMeta skillNamed(String name) {
        return new SkillMeta(name, "desc",
                Collections.<String>emptyList(),
                Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
    }

    // ---- failure cases ----

    @Test
    void shouldReturnFailedResultWhenTaskIdIsNull() {
        IssueClosure issue = issueWithTaskId(null);

        VerificationResult result = runner.verify(issue);

        assertThat(result).isNotNull();
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("task not found");
        assertThat(result.getBeforeStatus()).isNull();
        assertThat(result.getAfterStatus()).isNull();
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    @Test
    void shouldReturnFailedResultWhenTaskNotFoundInTaskStore() {
        when(taskStore.get("task-missing")).thenReturn(null);
        IssueClosure issue = issueWithTaskId("task-missing");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("task not found");
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    @Test
    void shouldReturnFailedResultWhenSkillNotFoundInSkillRegistry() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "order service timeout");
        AgentTask diagTask = new AgentTask("task-001", "user1", "health-check",
                inputs, null);
        diagTask.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-001")).thenReturn(diagTask);
        when(skillRegistry.get("health-check")).thenReturn(null);
        IssueClosure issue = issueWithTaskId("task-001");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("skill not found");
        verify(agentExecutor, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    // ---- success / failure on re-run ----

    @Test
    void shouldReturnPassedResultWhenReRunTaskSucceeds() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "order service timeout");
        AgentTask diagTask = new AgentTask("task-002", "user1", "health-check",
                inputs, null);
        diagTask.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-002")).thenReturn(diagTask);
        SkillMeta skill = skillNamed("health-check");
        when(skillRegistry.get("health-check")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("Verification passed: pool size now adequate");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        IssueClosure issue = issueWithTaskId("task-002");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getSummary()).contains("Verification passed");
        assertThat(result.getBeforeStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getAfterStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldReturnFailedResultWhenReRunTaskDoesNotSucceed() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "still failing");
        AgentTask diagTask = new AgentTask("task-003", "user1", "health-check",
                inputs, null);
        diagTask.setStatus(TaskStatus.FAILED);
        when(taskStore.get("task-003")).thenReturn(diagTask);
        SkillMeta skill = skillNamed("health-check");
        when(skillRegistry.get("health-check")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            task.setReport("Verification failed: still timing out");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        IssueClosure issue = issueWithTaskId("task-003");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getAfterStatus()).isEqualTo("FAILED");
        assertThat(result.getBeforeStatus()).isEqualTo("FAILED");
    }

    @Test
    void shouldSetBeforeStatusAndAfterStatusCorrectly() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "fixed");
        AgentTask diagTask = new AgentTask("task-004", "user1", "health-check",
                inputs, null);
        diagTask.setStatus(TaskStatus.FAILED); // beforeStatus
        when(taskStore.get("task-004")).thenReturn(diagTask);
        SkillMeta skill = skillNamed("health-check");
        when(skillRegistry.get("health-check")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED); // afterStatus
            task.setReport("now passes");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        IssueClosure issue = issueWithTaskId("task-004");

        VerificationResult result = runner.verify(issue);

        assertThat(result.getBeforeStatus()).isEqualTo("FAILED");
        assertThat(result.getAfterStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void shouldReRunSkillWithSameInputsAndSystemUser() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("symptom", "order service timeout");
        AgentTask diagTask = new AgentTask("task-005", "user1", "health-check",
                inputs, null);
        diagTask.setStatus(TaskStatus.SUCCEEDED);
        when(taskStore.get("task-005")).thenReturn(diagTask);
        SkillMeta skill = skillNamed("health-check");
        when(skillRegistry.get("health-check")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("ok");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        runner.verify(issueWithTaskId("task-005"));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentExecutor).execute(taskCaptor.capture(), any(SkillMeta.class));
        AgentTask verifyTask = taskCaptor.getValue();
        assertThat(verifyTask.getSkillId()).isEqualTo("health-check");
        assertThat(verifyTask.getUserId()).isEqualTo("system");
        assertThat(verifyTask.getInputs().get("symptom")).isEqualTo("order service timeout");
    }
}
