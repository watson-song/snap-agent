package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.StepResult;
import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.WorkflowStatus;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SimpleWorkflowEngine}.
 */
class SimpleWorkflowEngineTest {

    private AgentExecutor agentExecutor;
    private SkillRegistry skillRegistry;
    private SimpleWorkflowEngine engine;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        skillRegistry = mock(SkillRegistry.class);
        engine = new SimpleWorkflowEngine(agentExecutor, skillRegistry, "system-user");

        // Default: skill registry always returns a non-null skill
        when(skillRegistry.get(any(String.class)))
                .thenReturn(new SkillMeta("test-skill", "desc",
                        Collections.<String>emptyList(),
                        Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                        "body", SkillAvailability.AVAILABLE, null));
    }

    /**
     * Configures the mock AgentExecutor to set the task status to SUCCEEDED
     * and the report to the given result text.
     */
    private void mockExecuteSuccess(final String reportText) {
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport(reportText);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    /**
     * Configures the mock AgentExecutor to set the task status to FAILED.
     */
    private void mockExecuteFailure() {
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    private WorkflowDefinition buildWorkflow(WorkflowStep... steps) {
        return new WorkflowDefinition("test-workflow", "test",
                Arrays.asList(steps));
    }

    private WorkflowStep step(String name, String skill, String condition,
                              Map<String, String> inputs, String onFailure) {
        return new WorkflowStep(name, skill, condition, inputs, onFailure);
    }

    // ---- Execution tests ----

    @Test
    void shouldExecuteStepsSequentially() {
        final int[] callCount = {0};
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("result-" + (++callCount[0]));
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, null),
                step("s2", "skill-b", null, null, null));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getStepResults()).hasSize(2);

        StepResult s1 = result.getStepResults().get("s1");
        assertThat(s1.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(s1.getReport()).isEqualTo("result-1");
        assertThat(s1.getTaskId()).isNotNull();

        StepResult s2 = result.getStepResults().get("s2");
        assertThat(s2.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(s2.getReport()).isEqualTo("result-2");
    }

    @Test
    void shouldSkipStepWhenConditionIsFalse() {
        mockExecuteSuccess("found-error");

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, null),
                step("s2", "skill-b", "${s1.result.contains('timeout')}", null, null));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStepResults()).hasSize(2);

        StepResult s1 = result.getStepResults().get("s1");
        assertThat(s1.getReport()).isEqualTo("found-error");

        // s2 should be skipped — null status and null report
        StepResult s2 = result.getStepResults().get("s2");
        assertThat(s2).isNotNull();
        assertThat(s2.getStatus()).isNull();
        assertThat(s2.getReport()).isNull();
        assertThat(s2.getTaskId()).isNull();
    }

    @Test
    void shouldExecuteStepWhenConditionContainsMatches() {
        mockExecuteSuccess("error detected in system");

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, null),
                step("s2", "skill-b", "${s1.result.contains('error')}", null, null));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        // s2 should execute because result contains "error"
        StepResult s2 = result.getStepResults().get("s2");
        assertThat(s2.getReport()).isEqualTo("error detected in system");
        assertThat(s2.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldStopOnFailureWhenOnFailureIsStop() {
        mockExecuteFailure();

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, WorkflowStep.STOP),
                step("s2", "skill-b", null, null, null));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(result.getFailedStep()).isEqualTo("s1");
        assertThat(result.getErrorMessage()).contains("step execution failed");
    }

    @Test
    void shouldSkipOnFailureWhenOnFailureIsSkip() {
        // First call fails, second succeeds
        final int[] callCount = {0};
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            callCount[0]++;
            if (callCount[0] == 1) {
                task.setStatus(TaskStatus.FAILED);
            } else {
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setReport("recovered");
            }
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, WorkflowStep.SKIP),
                step("s2", "skill-b", null, null, null));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        // s1 was skipped after failure — StepResult present with FAILED status
        StepResult s1 = result.getStepResults().get("s1");
        assertThat(s1).isNotNull();
        assertThat(s1.getStatus()).isEqualTo("FAILED");
        assertThat(s1.getReport()).isNull();

        StepResult s2 = result.getStepResults().get("s2");
        assertThat(s2.getReport()).isEqualTo("recovered");
    }

    @Test
    void shouldRetryOnceOnFailureWhenOnFailureIsRetry() {
        // First call fails, retry succeeds
        final int[] callCount = {0};
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            callCount[0]++;
            if (callCount[0] == 1) {
                task.setStatus(TaskStatus.FAILED);
            } else {
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setReport("retry-success");
            }
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, WorkflowStep.RETRY));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        StepResult s1 = result.getStepResults().get("s1");
        assertThat(s1.getReport()).isEqualTo("retry-success");
        assertThat(s1.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldResolveTriggerInputs() {
        final String[] capturedInputs = new String[1];
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            capturedInputs[0] = task.getInputs().get("service");
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("ok");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("service", "${trigger.service}");

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, inputs, null));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        triggerInputs.put("service", "order-service");
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        assertThat(capturedInputs[0]).isEqualTo("order-service");
    }

    @Test
    void shouldResolveStepResultReferences() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("rootCause", "${s1.result}");

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, null),
                step("s2", "skill-b", null, inputs, null));

        final String[] capturedInputs = new String[1];
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            if (task.getSkillId().equals("skill-a")) {
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setReport("root-cause-found");
            } else {
                capturedInputs[0] = task.getInputs().get("rootCause");
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setReport("analysis-done");
            }
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        assertThat(capturedInputs[0]).isEqualTo("root-cause-found");
    }

    @Test
    void shouldResolveStatusAndTaskIdPlaceholders() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        // Reference both status and taskId of a prior step
        inputs.put("priorStatus", "${s1.status}");
        inputs.put("priorTaskId", "${s1.taskId}");

        WorkflowDefinition wf = buildWorkflow(
                step("s1", "skill-a", null, null, null),
                step("s2", "skill-b", null, inputs, null));

        final String[] capturedStatus = new String[1];
        final String[] capturedTaskId = new String[1];
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            if (task.getSkillId().equals("skill-a")) {
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setReport("done");
            } else {
                capturedStatus[0] = task.getInputs().get("priorStatus");
                capturedTaskId[0] = task.getInputs().get("priorTaskId");
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setReport("analysis");
            }
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        Map<String, String> triggerInputs = new HashMap<String, String>();
        WorkflowResult result = engine.execute(wf, triggerInputs);

        assertThat(result.isSuccess()).isTrue();
        // s1.status == SUCCEEDED and s1.taskId is non-empty
        assertThat(capturedStatus[0]).isEqualTo("SUCCEEDED");
        assertThat(capturedTaskId[0]).isNotEmpty();
    }

    // ---- Condition evaluation tests ----

    @Test
    void shouldEvaluateNotNullCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "some-result"));

        assertThat(engine.evaluateCondition("${s1.result != null}", stepResults)).isTrue();
        assertThat(engine.evaluateCondition("${s2.result != null}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateContainsCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "error in system"));

        assertThat(engine.evaluateCondition("${s1.result.contains('error')}", stepResults)).isTrue();
        assertThat(engine.evaluateCondition("${s1.result.contains('timeout')}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateSizeCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "non-empty"));

        assertThat(engine.evaluateCondition("${s1.result.size > 0}", stepResults)).isTrue();

        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", ""));
        assertThat(engine.evaluateCondition("${s1.result.size > 0}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateStatusEqualsCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "ok"));

        assertThat(engine.evaluateCondition("${s1.status == 'SUCCEEDED'}", stepResults)).isTrue();
        assertThat(engine.evaluateCondition("${s1.status == 'FAILED'}", stepResults)).isFalse();

        // Skipped step with null status — equality should be false
        stepResults.put("s1", new StepResult("s1", null, null, null));
        assertThat(engine.evaluateCondition("${s1.status == 'SUCCEEDED'}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateTruthyStatusCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "ok"));

        assertThat(engine.evaluateCondition("${s1.status}", stepResults)).isTrue();

        // Null status → falsy
        stepResults.put("s1", new StepResult("s1", null, null, null));
        assertThat(engine.evaluateCondition("${s1.status}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateTruthyTaskIdCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "ok"));

        assertThat(engine.evaluateCondition("${s1.taskId}", stepResults)).isTrue();

        // Null taskId → falsy
        stepResults.put("s1", new StepResult("s1", null, null, null));
        assertThat(engine.evaluateCondition("${s1.taskId}", stepResults)).isFalse();
    }

    @Test
    void shouldReturnTrueForNullCondition() {
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        assertThat(engine.evaluateCondition(null, stepResults)).isTrue();
        assertThat(engine.evaluateCondition("", stepResults)).isTrue();
    }

    // ---- Input resolution tests ----

    @Test
    void shouldResolveTriggerInputPlaceholders() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("service", "${trigger.service}");
        inputs.put("env", "${trigger.env}");

        Map<String, String> triggerInputs = new HashMap<String, String>();
        triggerInputs.put("service", "api-gateway");
        triggerInputs.put("env", "prod");

        Map<String, String> resolved = engine.resolveInputs(inputs, triggerInputs,
                new LinkedHashMap<String, StepResult>());

        assertThat(resolved.get("service")).isEqualTo("api-gateway");
        assertThat(resolved.get("env")).isEqualTo("prod");
    }

    @Test
    void shouldResolveStepResultPlaceholders() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("rootCause", "${s1.result}");

        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED",
                "database-connection-pool-exhausted"));

        Map<String, String> resolved = engine.resolveInputs(inputs,
                new HashMap<String, String>(), stepResults);

        assertThat(resolved.get("rootCause")).isEqualTo("database-connection-pool-exhausted");
    }

    @Test
    void shouldResolveStatusAndTaskIdInputPlaceholders() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("priorStatus", "${s1.status}");
        inputs.put("priorTaskId", "${s1.taskId}");

        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-99", "SUCCEEDED", "ok"));

        Map<String, String> resolved = engine.resolveInputs(inputs,
                new HashMap<String, String>(), stepResults);

        assertThat(resolved.get("priorStatus")).isEqualTo("SUCCEEDED");
        assertThat(resolved.get("priorTaskId")).isEqualTo("task-99");
    }

    @Test
    void shouldHandleMixedPlaceholdersInSameValue() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("combined", "${trigger.service}-${s1.result}");

        Map<String, String> triggerInputs = new HashMap<String, String>();
        triggerInputs.put("service", "api");

        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("s1", new StepResult("s1", "task-1", "SUCCEEDED", "error"));

        Map<String, String> resolved = engine.resolveInputs(inputs, triggerInputs, stepResults);

        assertThat(resolved.get("combined")).isEqualTo("api-error");
    }

    @Test
    void shouldHandleMissingStepInConditionGracefully() {
        // Step not present in map — all condition checks should be false
        Map<String, StepResult> empty = new LinkedHashMap<String, StepResult>();

        assertThat(engine.evaluateCondition("${missing.result != null}", empty)).isFalse();
        assertThat(engine.evaluateCondition("${missing.result.contains('x')}", empty)).isFalse();
        assertThat(engine.evaluateCondition("${missing.status == 'SUCCEEDED'}", empty)).isFalse();
        assertThat(engine.evaluateCondition("${missing.status}", empty)).isFalse();
        assertThat(engine.evaluateCondition("${missing.taskId}", empty)).isFalse();
    }
}
