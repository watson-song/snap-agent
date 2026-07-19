package cn.watsontech.snapagent.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowStatus}.
 */
class WorkflowStatusTest {

    @Test
    void shouldExposeAllExpectedEnumValues() {
        List<WorkflowStatus> values = Arrays.asList(WorkflowStatus.values());

        assertThat(values).contains(WorkflowStatus.RUNNING);
        assertThat(values).contains(WorkflowStatus.COMPLETED);
        assertThat(values).contains(WorkflowStatus.ABORTED);
        assertThat(values).contains(WorkflowStatus.FAILED);
        assertThat(values).hasSize(4);
    }

    @Test
    void shouldResolveByValueOf() {
        assertThat(WorkflowStatus.valueOf("RUNNING")).isSameAs(WorkflowStatus.RUNNING);
        assertThat(WorkflowStatus.valueOf("COMPLETED")).isSameAs(WorkflowStatus.COMPLETED);
        assertThat(WorkflowStatus.valueOf("ABORTED")).isSameAs(WorkflowStatus.ABORTED);
        assertThat(WorkflowStatus.valueOf("FAILED")).isSameAs(WorkflowStatus.FAILED);
    }

    @Test
    void shouldHaveDistinctEnumConstants() {
        assertThat(WorkflowStatus.RUNNING).isNotSameAs(WorkflowStatus.COMPLETED);
        assertThat(WorkflowStatus.COMPLETED).isNotSameAs(WorkflowStatus.ABORTED);
        assertThat(WorkflowStatus.ABORTED).isNotSameAs(WorkflowStatus.FAILED);
        assertThat(WorkflowStatus.FAILED).isNotSameAs(WorkflowStatus.RUNNING);
    }
}
