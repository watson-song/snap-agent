package cn.watsontech.snapagent.core.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StepResult}.
 */
class StepResultTest {

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        StepResult result = new StepResult(
                "health-check", "task-123", "SUCCEEDED", "all green");

        assertThat(result.getStepName()).isEqualTo("health-check");
        assertThat(result.getTaskId()).isEqualTo("task-123");
        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getReport()).isEqualTo("all green");
    }

    @Test
    void shouldAllowNullTaskIdForSkippedSteps() {
        StepResult result = new StepResult("skipped-step", null, null, null);

        assertThat(result.getStepName()).isEqualTo("skipped-step");
        assertThat(result.getTaskId()).isNull();
        assertThat(result.getStatus()).isNull();
        assertThat(result.getReport()).isNull();
    }

    @Test
    void shouldAllowNullReportWithStatusForFailedSkippedStep() {
        // A failed-then-skipped step has status FAILED but no report
        StepResult result = new StepResult("failed-step", null, "FAILED", null);

        assertThat(result.getTaskId()).isNull();
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getReport()).isNull();
    }

    @Test
    void shouldAllowEmptyReportAndStatus() {
        StepResult result = new StepResult("step-x", "", "", "");

        assertThat(result.getTaskId()).isEqualTo("");
        assertThat(result.getStatus()).isEqualTo("");
        assertThat(result.getReport()).isEqualTo("");
    }

    @Test
    void shouldHaveMeaningfulToString() {
        StepResult withReport = new StepResult(
                "health-check", "task-1", "SUCCEEDED", "all green report");
        StepResult skipped = new StepResult("skipped", null, null, null);

        String str = withReport.toString();
        assertThat(str).contains("StepResult");
        assertThat(str).contains("health-check");
        assertThat(str).contains("task-1");
        assertThat(str).contains("SUCCEEDED");
        // Report length is summarized, not the raw content
        assertThat(str).contains("chars");

        String skipStr = skipped.toString();
        assertThat(skipStr).contains("skipped");
        assertThat(skipStr).contains("null");
    }

    @Test
    void shouldReportReportLengthInToStringWhenReportPresent() {
        StepResult result = new StepResult(
                "step", "task-1", "SUCCEEDED", "abc");
        // Report has 3 chars
        assertThat(result.toString()).contains("3 chars");
    }
}
