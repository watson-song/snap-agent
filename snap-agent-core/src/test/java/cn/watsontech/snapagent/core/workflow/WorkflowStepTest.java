package cn.watsontech.snapagent.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowStep}.
 */
class WorkflowStepTest {

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("service", "${trigger.service}");
        inputs.put("timeWindow", "1h");

        WorkflowStep step = new WorkflowStep(
                "find-root-cause", "error-spike-investigation",
                "${health-check.result.contains('error')}",
                inputs, "STOP");

        assertThat(step.getName()).isEqualTo("find-root-cause");
        assertThat(step.getSkill()).isEqualTo("error-spike-investigation");
        assertThat(step.getCondition()).isEqualTo(
                "${health-check.result.contains('error')}");
        assertThat(step.getInputs()).containsEntry("service", "${trigger.service}");
        assertThat(step.getInputs()).containsEntry("timeWindow", "1h");
        assertThat(step.getInputs()).hasSize(2);
        assertThat(step.getOnFailure()).isEqualTo("STOP");
    }

    @Test
    void shouldDefensivelyCopyInputsOnConstruction() {
        Map<String, String> original = new HashMap<>();
        original.put("key", "value");

        WorkflowStep step = new WorkflowStep("s1", "skill", null, original, null);

        // Mutate the original map — step's copy should be unaffected
        original.put("key", "changed");
        original.put("new-key", "new-value");

        assertThat(step.getInputs().get("key")).isEqualTo("value");
        assertThat(step.getInputs()).doesNotContainKey("new-key");
        assertThat(step.getInputs()).hasSize(1);
    }

    @Test
    void shouldReturnUnmodifiableInputsFromGetter() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("key", "value");

        WorkflowStep step = new WorkflowStep("s1", "skill", null, inputs, null);

        assertThatThrownBy(() -> step.getInputs().put("hack", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHaveEmptyInputsWhenNullPassed() {
        WorkflowStep step = new WorkflowStep("s1", "skill", null, null, null);

        assertThat(step.getInputs()).isNotNull();
        assertThat(step.getInputs()).isEmpty();
    }

    @Test
    void shouldDefaultOnFailureToStopWhenNullPassed() {
        WorkflowStep step = new WorkflowStep("s1", "skill", null, null, null);

        assertThat(step.getOnFailure()).isEqualTo(WorkflowStep.STOP);
        assertThat(step.getOnFailure()).isEqualTo("STOP");
    }

    @Test
    void shouldPreserveExplicitOnFailureValues() {
        WorkflowStep stop = new WorkflowStep("s1", "skill", null, null, "STOP");
        WorkflowStep skip = new WorkflowStep("s2", "skill", null, null, "SKIP");
        WorkflowStep retry = new WorkflowStep("s3", "skill", null, null, "RETRY");

        assertThat(stop.getOnFailure()).isEqualTo(WorkflowStep.STOP);
        assertThat(skip.getOnFailure()).isEqualTo(WorkflowStep.SKIP);
        assertThat(retry.getOnFailure()).isEqualTo(WorkflowStep.RETRY);
    }

    @Test
    void shouldExposeStopSkipRetryConstants() {
        assertThat(WorkflowStep.STOP).isEqualTo("STOP");
        assertThat(WorkflowStep.SKIP).isEqualTo("SKIP");
        assertThat(WorkflowStep.RETRY).isEqualTo("RETRY");
    }

    @Test
    void shouldHaveMeaningfulToString() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("service", "order-service");

        WorkflowStep step = new WorkflowStep(
                "health-check", "health-check", null, inputs, "STOP");

        String str = step.toString();
        assertThat(str).contains("WorkflowStep");
        assertThat(str).contains("health-check");
        assertThat(str).contains("STOP");
    }
}
