package cn.watsontech.snapagent.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowResult}.
 */
class WorkflowResultTest {

    private StepResult stepResult(String stepName, String taskId, String status, String report) {
        return new StepResult(stepName, taskId, status, report);
    }

    @Test
    void successFactoryShouldCreateCompletedResult() {
        Map<String, StepResult> stepResults = new HashMap<>();
        stepResults.put("health-check",
                stepResult("health-check", "task-1", "SUCCEEDED", "all green"));
        stepResults.put("db-check",
                stepResult("db-check", "task-2", "SUCCEEDED", "pool ok"));

        WorkflowResult result = WorkflowResult.success(
                "full-diagnose", stepResults, 1500L);

        assertThat(result.getWorkflowName()).isEqualTo("full-diagnose");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getFailedStep()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getStepResults()).containsKey("health-check");
        assertThat(result.getStepResults().get("health-check").getReport())
                .isEqualTo("all green");
        assertThat(result.getStepResults().get("db-check").getReport())
                .isEqualTo("pool ok");
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getDurationMs()).isEqualTo(1500L);
    }

    @Test
    void failureFactoryShouldCreateFailedResult() {
        Map<String, StepResult> stepResults = new HashMap<>();
        stepResults.put("health-check",
                stepResult("health-check", "task-1", "SUCCEEDED", "error detected"));

        WorkflowResult result = WorkflowResult.failure(
                "full-diagnose", "find-root-cause",
                "skill execution timed out", stepResults, 3000L);

        assertThat(result.getWorkflowName()).isEqualTo("full-diagnose");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(result.getFailedStep()).isEqualTo("find-root-cause");
        assertThat(result.getErrorMessage()).isEqualTo("skill execution timed out");
        assertThat(result.getStepResults()).containsKey("health-check");
        assertThat(result.getStepResults().get("health-check").getReport())
                .isEqualTo("error detected");
        assertThat(result.getDurationMs()).isEqualTo(3000L);
    }

    @Test
    void shouldDefensivelyCopyStepResultsOnConstruction() {
        Map<String, StepResult> original = new HashMap<>();
        original.put("step1", stepResult("step1", "task-1", "SUCCEEDED", "result1"));

        WorkflowResult result = WorkflowResult.success("wf", original, 100L);

        // Mutate the original map — result's copy should be unaffected
        original.put("step1", stepResult("step1", "task-x", "FAILED", "hacked"));
        original.put("step2", stepResult("step2", "task-y", "SUCCEEDED", "new"));

        assertThat(result.getStepResults().get("step1").getReport()).isEqualTo("result1");
        assertThat(result.getStepResults()).doesNotContainKey("step2");
        assertThat(result.getStepResults()).hasSize(1);
    }

    @Test
    void shouldReturnUnmodifiableStepResultsFromGetter() {
        Map<String, StepResult> stepResults = new HashMap<>();
        stepResults.put("step1", stepResult("step1", "task-1", "SUCCEEDED", "result1"));

        WorkflowResult result = WorkflowResult.success("wf", stepResults, 100L);

        assertThatThrownBy(() -> result.getStepResults().put("hack",
                stepResult("hack", "task-h", "SUCCEEDED", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHaveEmptyStepResultsWhenNullPassed() {
        WorkflowResult success = WorkflowResult.success("wf", null, 100L);
        WorkflowResult failure = WorkflowResult.failure("wf", "step1", "err", null, 200L);

        assertThat(success.getStepResults()).isNotNull();
        assertThat(success.getStepResults()).isEmpty();
        assertThat(failure.getStepResults()).isNotNull();
        assertThat(failure.getStepResults()).isEmpty();
    }

    @Test
    void isSuccessShouldBeBackwardCompatible() {
        // success → COMPLETED → isSuccess true
        WorkflowResult ok = WorkflowResult.success("wf", null, 1L);
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        // failure → FAILED → isSuccess false
        WorkflowResult fail = WorkflowResult.failure("wf", "step1", "err", null, 2L);
        assertThat(fail.isSuccess()).isFalse();
        assertThat(fail.getStatus()).isEqualTo(WorkflowStatus.FAILED);
    }

    @Test
    void shouldHaveMeaningfulToString() {
        Map<String, StepResult> stepResults = new HashMap<>();
        stepResults.put("health-check",
                stepResult("health-check", "task-1", "SUCCEEDED", "ok"));

        WorkflowResult ok = WorkflowResult.success("full-diagnose", stepResults, 1000L);
        WorkflowResult fail = WorkflowResult.failure(
                "full-diagnose", "step-x", "boom", stepResults, 2000L);

        String okStr = ok.toString();
        assertThat(okStr).contains("WorkflowResult");
        assertThat(okStr).contains("full-diagnose");
        assertThat(okStr).contains("COMPLETED");
        // Backward-compat: still surfaces the success boolean
        assertThat(okStr).contains("success=true");

        String failStr = fail.toString();
        assertThat(failStr).contains("FAILED");
        assertThat(failStr).contains("success=false");
        assertThat(failStr).contains("step-x");
        assertThat(failStr).contains("boom");
    }
}
