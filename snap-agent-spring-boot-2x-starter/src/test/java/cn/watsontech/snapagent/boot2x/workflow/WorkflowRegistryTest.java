package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.workflow.Workflow;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import cn.watsontech.snapagent.core.workflow.WorkflowStepFailureStrategy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRegistryTest {

    @Test
    void get_existingId_returnsWorkflow() {
        List<Map<String, Object>> stepDefs = Arrays.asList(
                stepDef("step1", "health-check", null, "ABORT"),
                stepDef("step2", "code-analysis", "${step1.status} == 'SUCCEEDED'", "SKIP"));
        Workflow wf = WorkflowRegistry.fromConfig("wf-1", "test workflow", stepDefs);

        WorkflowRegistry registry = new WorkflowRegistry(Collections.singletonList(wf));
        assertThat(registry.size()).isEqualTo(1);

        Workflow retrieved = registry.get("wf-1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getDescription()).isEqualTo("test workflow");
        assertThat(retrieved.getSteps()).hasSize(2);
    }

    @Test
    void get_nonExistentId_returnsNull() {
        WorkflowRegistry registry = new WorkflowRegistry(Collections.emptyList());
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void list_returnsAllWorkflows() {
        Workflow wf1 = WorkflowRegistry.fromConfig("wf-1", "first", null);
        Workflow wf2 = WorkflowRegistry.fromConfig("wf-2", "second", null);
        WorkflowRegistry registry = new WorkflowRegistry(Arrays.asList(wf1, wf2));
        assertThat(registry.list()).hasSize(2);
    }

    @Test
    void fromConfig_nullStepDefs_createsEmptySteps() {
        Workflow wf = WorkflowRegistry.fromConfig("wf-1", "test", null);
        assertThat(wf.getSteps()).isEmpty();
    }

    @Test
    void fromConfig_stepsHaveCorrectFields() {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("service", "${trigger.service}");
        Map<String, Object> sd = new HashMap<String, Object>();
        sd.put("name", "step1");
        sd.put("skill", "health-check");
        sd.put("inputs", inputs);
        sd.put("onFailure", "RETRY");
        List<Map<String, Object>> stepDefs = Collections.singletonList(sd);
        Workflow wf = WorkflowRegistry.fromConfig("wf-1", "test", stepDefs);

        WorkflowStep step = wf.getSteps().get(0);
        assertThat(step.getName()).isEqualTo("step1");
        assertThat(step.getSkillId()).isEqualTo("health-check");
        assertThat(step.getInputs().get("service")).isEqualTo("${trigger.service}");
        assertThat(step.getOnFailure()).isEqualTo(WorkflowStepFailureStrategy.RETRY);
    }

    @Test
    void fromConfig_emptyOnFailure_defaultsToAbort() {
        Map<String, Object> sd = new HashMap<String, Object>();
        sd.put("name", "step1");
        sd.put("skill", "health-check");
        List<Map<String, Object>> stepDefs = Collections.singletonList(sd);
        Workflow wf = WorkflowRegistry.fromConfig("wf-1", "test", stepDefs);

        assertThat(wf.getSteps().get(0).getOnFailure()).isEqualTo(WorkflowStepFailureStrategy.ABORT);
    }

    private Map<String, Object> stepDef(String name, String skill,
                                        Object condition, String onFailure) {
        return stepDef(name, skill, null, condition, onFailure);
    }

    private Map<String, Object> stepDef(String name, String skill,
                                        Map<String, String> inputs,
                                        Object condition, String onFailure) {
        Map<String, Object> sd = new HashMap<String, Object>();
        sd.put("name", name);
        sd.put("skill", skill);
        if (inputs != null) sd.put("inputs", inputs);
        if (condition != null) sd.put("condition", condition);
        if (onFailure != null) sd.put("onFailure", onFailure);
        return sd;
    }
}
