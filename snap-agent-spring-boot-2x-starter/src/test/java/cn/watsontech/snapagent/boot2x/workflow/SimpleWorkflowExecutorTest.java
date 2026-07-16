package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.Workflow;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.WorkflowStatus;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import cn.watsontech.snapagent.core.workflow.WorkflowStepFailureStrategy;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SimpleWorkflowExecutorTest {

    @Test
    void execute_singleStep_succeeds() {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);

        SkillMeta skill = new SkillMeta("health-check", "desc",
                Collections.singletonList("jdbc_query"), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("health-check")).thenReturn(skill);

        // Simulate execution: set task status to SUCCEEDED
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("All healthy");
            return null;
        }).when(executor).execute(any(AgentTask.class), eq(skill));

        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(executor, skillRegistry);

        WorkflowStep step = new WorkflowStep("check", "health-check",
                Collections.singletonMap("service", "${trigger.service}"),
                null, WorkflowStepFailureStrategy.ABORT);
        Workflow workflow = makeWorkflow("wf-1", "test", Collections.singletonList(step));

        Map<String, String> trigger = Collections.singletonMap("service", "order-service");
        WorkflowResult result = wfExecutor.execute(workflow, "user1", "model-x", trigger);

        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getStepResults()).hasSize(1);
        assertThat(result.getStepResults().get("check").getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getStepResults().get("check").getReport()).isEqualTo("All healthy");
    }

    @Test
    void execute_stepFails_aborts() {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);

        SkillMeta skill = new SkillMeta("health-check", "desc",
                Collections.emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("health-check")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            return null;
        }).when(executor).execute(any(AgentTask.class), eq(skill));

        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(executor, skillRegistry);

        WorkflowStep step = new WorkflowStep("check", "health-check",
                Collections.<String, String>emptyMap(),
                null, WorkflowStepFailureStrategy.ABORT);
        Workflow workflow = makeWorkflow("wf-1", "test", Collections.singletonList(step));

        WorkflowResult result = wfExecutor.execute(workflow, "user1", "model-x", null);

        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.ABORTED);
        assertThat(result.getStepResults().get("check").getStatus()).isEqualTo("FAILED");
    }

    @Test
    void execute_stepFailsWithSkip_continuesToNextStep() {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);

        SkillMeta skill1 = new SkillMeta("skill-a", "desc",
                Collections.emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        SkillMeta skill2 = new SkillMeta("skill-b", "desc",
                Collections.emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("skill-a")).thenReturn(skill1);
        when(skillRegistry.get("skill-b")).thenReturn(skill2);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            return null;
        }).when(executor).execute(any(AgentTask.class), eq(skill1));

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("success");
            return null;
        }).when(executor).execute(any(AgentTask.class), eq(skill2));

        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(executor, skillRegistry);

        WorkflowStep step1 = new WorkflowStep("step1", "skill-a",
                Collections.<String, String>emptyMap(), null, WorkflowStepFailureStrategy.SKIP);
        WorkflowStep step2 = new WorkflowStep("step2", "skill-b",
                Collections.<String, String>emptyMap(), null, WorkflowStepFailureStrategy.ABORT);
        Workflow workflow = makeWorkflow("wf-1", "test", Arrays.asList(step1, step2));

        WorkflowResult result = wfExecutor.execute(workflow, "user1", "model-x", null);

        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getStepResults().get("step1").getStatus()).isEqualTo("FAILED");
        assertThat(result.getStepResults().get("step2").getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void execute_skillNotFound_aborts() {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);
        when(skillRegistry.get("nonexistent")).thenReturn(null);

        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(executor, skillRegistry);

        WorkflowStep step = new WorkflowStep("step1", "nonexistent",
                Collections.<String, String>emptyMap(),
                null, WorkflowStepFailureStrategy.ABORT);
        Workflow workflow = makeWorkflow("wf-1", "test", Collections.singletonList(step));

        WorkflowResult result = wfExecutor.execute(workflow, "user1", "model-x", null);

        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.ABORTED);
        assertThat(result.getStepResults().get("step1").getStatus()).isEqualTo("FAILED");
    }

    @Test
    void execute_conditionNotMet_skipsStep() {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);

        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(executor, skillRegistry);

        WorkflowStep step = new WorkflowStep("conditional", "any-skill",
                Collections.<String, String>emptyMap(),
                "false == 'true'", WorkflowStepFailureStrategy.ABORT);
        Workflow workflow = makeWorkflow("wf-1", "test", Collections.singletonList(step));

        WorkflowResult result = wfExecutor.execute(workflow, "user1", "model-x", null);

        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getStepResults().get("conditional").getStatus()).isEqualTo("SKIPPED");
    }

    @Test
    void resolveRefs_substitutesValues() {
        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(
                mock(AgentExecutor.class), mock(SkillRegistry.class));
        Map<String, String> context = new HashMap<String, String>();
        context.put("trigger.service", "order-service");
        context.put("step1.report", "All good");
        context.put("step1.status", "SUCCEEDED");

        assertThat(wfExecutor.resolveRefs("Service: ${trigger.service}", context))
                .isEqualTo("Service: order-service");
        assertThat(wfExecutor.resolveRefs("Report: ${step1.report}", context))
                .isEqualTo("Report: All good");
        assertThat(wfExecutor.resolveRefs("Status: ${step1.status}", context))
                .isEqualTo("Status: SUCCEEDED");
        assertThat(wfExecutor.resolveRefs("No refs here", context))
                .isEqualTo("No refs here");
        assertThat(wfExecutor.resolveRefs("Missing: ${nonexistent.ref}", context))
                .isEqualTo("Missing: ${nonexistent.ref}");
    }

    @Test
    void evaluateCondition_eqComparison() {
        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(
                mock(AgentExecutor.class), mock(SkillRegistry.class));

        assertThat(wfExecutor.evaluateCondition("SUCCEEDED == 'SUCCEEDED'")).isTrue();
        assertThat(wfExecutor.evaluateCondition("FAILED == 'SUCCEEDED'")).isFalse();
        assertThat(wfExecutor.evaluateCondition("SUCCEEDED != 'FAILED'")).isTrue();
        assertThat(wfExecutor.evaluateCondition("SUCCEEDED != 'SUCCEEDED'")).isFalse();
        assertThat(wfExecutor.evaluateCondition("")).isTrue();
        assertThat(wfExecutor.evaluateCondition(null)).isTrue();
    }

    @Test
    void execute_retry_failsThenRetries() {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);

        SkillMeta skill = new SkillMeta("flaky-skill", "desc",
                Collections.emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("flaky-skill")).thenReturn(skill);

        // First call fails, second succeeds
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            if (task.getStatus() != TaskStatus.SUCCEEDED) {
                task.setStatus(TaskStatus.FAILED);
            }
            return null;
        }).when(executor).execute(any(AgentTask.class), eq(skill));

        SimpleWorkflowExecutor wfExecutor = new SimpleWorkflowExecutor(executor, skillRegistry);

        // Since retry creates a new task and executes again, we need to handle
        // the fact that the first task fails. With RETRY strategy, it retries once.
        // Our mock always sets FAILED (new task starts PENDING → we set FAILED).
        // So both attempts fail, and the workflow should ABORT.
        WorkflowStep step = new WorkflowStep("flaky", "flaky-skill",
                Collections.<String, String>emptyMap(),
                null, WorkflowStepFailureStrategy.RETRY);
        Workflow workflow = makeWorkflow("wf-1", "test", Collections.singletonList(step));

        WorkflowResult result = wfExecutor.execute(workflow, "user1", "model-x", null);

        // Both attempts fail → abort
        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.ABORTED);
        verify(executor, times(2)).execute(any(AgentTask.class), eq(skill));
    }

    private Workflow makeWorkflow(String id, String desc, List<WorkflowStep> steps) {
        return new Workflow() {
            @Override
            public String getId() { return id; }
            @Override
            public String getDescription() { return desc; }
            @Override
            public List<WorkflowStep> getSteps() { return steps; }
        };
    }
}
