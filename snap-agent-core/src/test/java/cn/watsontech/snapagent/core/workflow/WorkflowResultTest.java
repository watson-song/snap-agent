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

    @Test
    void successFactoryShouldCreateSuccessfulResult() {
        Map<String, String> stepResults = new HashMap<>();
        stepResults.put("health-check", "all green");
        stepResults.put("db-check", "pool ok");

        WorkflowResult result = WorkflowResult.success(
                "full-diagnose", stepResults, 1500L);

        assertThat(result.getWorkflowName()).isEqualTo("full-diagnose");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFailedStep()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getStepResults()).containsEntry("health-check", "all green");
        assertThat(result.getStepResults()).containsEntry("db-check", "pool ok");
        assertThat(result.getStepResults()).hasSize(2);
        assertThat(result.getDurationMs()).isEqualTo(1500L);
    }

    @Test
    void failureFactoryShouldCreateFailedResult() {
        Map<String, String> stepResults = new HashMap<>();
        stepResults.put("health-check", "error detected");

        WorkflowResult result = WorkflowResult.failure(
                "full-diagnose", "find-root-cause",
                "skill execution timed out", stepResults, 3000L);

        assertThat(result.getWorkflowName()).isEqualTo("full-diagnose");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailedStep()).isEqualTo("find-root-cause");
        assertThat(result.getErrorMessage()).isEqualTo("skill execution timed out");
        assertThat(result.getStepResults()).containsEntry("health-check", "error detected");
        assertThat(result.getDurationMs()).isEqualTo(3000L);
    }

    @Test
    void shouldDefensivelyCopyStepResultsOnConstruction() {
        Map<String, String> original = new HashMap<>();
        original.put("step1", "result1");

        WorkflowResult result = WorkflowResult.success("wf", original, 100L);

        // Mutate the original map — result's copy should be unaffected
        original.put("step1", "hacked");
        original.put("step2", "new");

        assertThat(result.getStepResults().get("step1")).isEqualTo("result1");
        assertThat(result.getStepResults()).doesNotContainKey("step2");
        assertThat(result.getStepResults()).hasSize(1);
    }

    @Test
    void shouldReturnUnmodifiableStepResultsFromGetter() {
        Map<String, String> stepResults = new HashMap<>();
        stepResults.put("step1", "result1");

        WorkflowResult result = WorkflowResult.success("wf", stepResults, 100L);

        assertThatThrownBy(() -> result.getStepResults().put("hack", "x"))
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
    void shouldHaveMeaningfulToString() {
        Map<String, String> stepResults = new HashMap<>();
        stepResults.put("health-check", "ok");

        WorkflowResult ok = WorkflowResult.success("full-diagnose", stepResults, 1000L);
        WorkflowResult fail = WorkflowResult.failure(
                "full-diagnose", "step-x", "boom", stepResults, 2000L);

        String okStr = ok.toString();
        assertThat(okStr).contains("WorkflowResult");
        assertThat(okStr).contains("full-diagnose");
        assertThat(okStr).contains("success=true");

        String failStr = fail.toString();
        assertThat(failStr).contains("success=false");
        assertThat(failStr).contains("step-x");
        assertThat(failStr).contains("boom");
    }
}
