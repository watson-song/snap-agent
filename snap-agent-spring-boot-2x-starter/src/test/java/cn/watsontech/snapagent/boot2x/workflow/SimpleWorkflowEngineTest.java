package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
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
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getStepResults().get("s1")).isEqualTo("result-1");
        assertThat(result.getStepResults().get("s2")).isEqualTo("result-2");
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
        assertThat(result.getStepResults().get("s1")).isEqualTo("found-error");
        // s2 should be skipped (null result)
        assertThat(result.getStepResults().get("s2")).isNull();
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
        assertThat(result.getStepResults().get("s2")).isEqualTo("error detected in system");
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
        assertThat(result.getStepResults().get("s1")).isNull();
        assertThat(result.getStepResults().get("s2")).isEqualTo("recovered");
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
        assertThat(result.getStepResults().get("s1")).isEqualTo("retry-success");
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

    // ---- Condition evaluation tests ----

    @Test
    void shouldEvaluateNotNullCondition() {
        Map<String, String> stepResults = new LinkedHashMap<String, String>();
        stepResults.put("s1", "some-result");

        assertThat(engine.evaluateCondition("${s1.result != null}", stepResults)).isTrue();
        assertThat(engine.evaluateCondition("${s2.result != null}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateContainsCondition() {
        Map<String, String> stepResults = new LinkedHashMap<String, String>();
        stepResults.put("s1", "error in system");

        assertThat(engine.evaluateCondition("${s1.result.contains('error')}", stepResults)).isTrue();
        assertThat(engine.evaluateCondition("${s1.result.contains('timeout')}", stepResults)).isFalse();
    }

    @Test
    void shouldEvaluateSizeCondition() {
        Map<String, String> stepResults = new LinkedHashMap<String, String>();
        stepResults.put("s1", "non-empty");

        assertThat(engine.evaluateCondition("${s1.result.size > 0}", stepResults)).isTrue();

        stepResults.put("s1", "");
        assertThat(engine.evaluateCondition("${s1.result.size > 0}", stepResults)).isFalse();
    }

    @Test
    void shouldReturnTrueForNullCondition() {
        Map<String, String> stepResults = new LinkedHashMap<String, String>();
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
                new LinkedHashMap<String, String>());

        assertThat(resolved.get("service")).isEqualTo("api-gateway");
        assertThat(resolved.get("env")).isEqualTo("prod");
    }

    @Test
    void shouldResolveStepResultPlaceholders() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("rootCause", "${s1.result}");

        Map<String, String> stepResults = new LinkedHashMap<String, String>();
        stepResults.put("s1", "database-connection-pool-exhausted");

        Map<String, String> resolved = engine.resolveInputs(inputs,
                new HashMap<String, String>(), stepResults);

        assertThat(resolved.get("rootCause")).isEqualTo("database-connection-pool-exhausted");
    }

    @Test
    void shouldHandleMixedPlaceholdersInSameValue() {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("combined", "${trigger.service}-${s1.result}");

        Map<String, String> triggerInputs = new HashMap<String, String>();
        triggerInputs.put("service", "api");

        Map<String, String> stepResults = new LinkedHashMap<String, String>();
        stepResults.put("s1", "error");

        Map<String, String> resolved = engine.resolveInputs(inputs, triggerInputs, stepResults);

        assertThat(resolved.get("combined")).isEqualTo("api-error");
    }
}
