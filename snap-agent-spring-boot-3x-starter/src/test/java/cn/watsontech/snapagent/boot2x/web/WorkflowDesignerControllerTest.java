package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.workflow.*;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowDesignerController")
class WorkflowDesignerControllerTest {

    private SkillRegistry skillRegistry;
    private WorkflowDesignerController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        skillRegistry = Mockito.mock(SkillRegistry.class);
        when(skillRegistry.all()).thenReturn(Collections.<SkillMeta>emptyList());
        WorkflowEngine engine = new WorkflowEngine() {
            @Override
            public WorkflowResult execute(WorkflowDefinition workflow, Map<String, String> triggerInputs) {
                Map<String, StepResult> results = new LinkedHashMap<String, StepResult>();
                for (WorkflowStep step : workflow.getSteps()) {
                    results.put(step.getName(), new StepResult(step.getName(), "task-1", "SUCCEEDED", "ok"));
                }
                return WorkflowResult.success(workflow.getName(), results, 100);
            }
            @Override
            public String type() { return "test"; }
        };
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        controller = new WorkflowDesignerController(skillRegistry, loader, engine, tempDir);
    }

    private SkillMeta createTestSkill(String name, String desc) {
        return new SkillMeta(name, desc,
                Collections.<String>emptyList(),
                Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "# Body", SkillAvailability.AVAILABLE, null);
    }

    @Test
    @DisplayName("constructor rejects null SkillRegistry")
    void shouldRejectNullRegistry() {
        assertThatThrownBy(() -> new WorkflowDesignerController(null, null, null, tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SkillRegistry");
    }

    @Test
    @DisplayName("listSkills returns empty for no skills")
    void shouldListEmptySkills() {
        ResponseEntity<Object> response = controller.listSkills();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) response.getBody();
        assertThat(skills).isEmpty();
    }

    @Test
    @DisplayName("listSkills returns available skills")
    void shouldListAvailableSkills() {
        SkillMeta skill = createTestSkill("log-analysis", "Analyze logs");
        when(skillRegistry.all()).thenReturn(Collections.singletonList(skill));

        ResponseEntity<Object> response = controller.listSkills();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) response.getBody();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0)).containsEntry("name", "log-analysis");
    }

    @Test
    @DisplayName("listSkills excludes unavailable skills")
    void shouldExcludeUnavailableSkills() {
        SkillMeta available = createTestSkill("good-skill", "Good");
        SkillMeta unavailable = new SkillMeta("bad-skill", "Bad",
                Collections.<String>emptyList(),
                Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "# Body", SkillAvailability.UNAVAILABLE, "not ready");
        when(skillRegistry.all()).thenReturn(Arrays.asList(available, unavailable));

        ResponseEntity<Object> response = controller.listSkills();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) response.getBody();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0)).containsEntry("name", "good-skill");
    }

    @Test
    @DisplayName("save requires name")
    void shouldRequireName() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "s1");
        step.put("skill", "log-analysis");
        steps.add(step);
        body.put("steps", steps);

        ResponseEntity<Object> response = controller.saveWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("save requires steps")
    void shouldRequireSteps() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "test-wf");

        ResponseEntity<Object> response = controller.saveWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("save and load workflow round-trip")
    void shouldSaveAndLoad() {
        Map<String, Object> body = createWorkflowBody("test-wf", "A test workflow");

        ResponseEntity<Object> saveResp = controller.saveWorkflow(body);
        assertThat(saveResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Object> listResp = controller.listWorkflows();
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workflows = (List<Map<String, Object>>) listResp.getBody();
        assertThat(workflows).hasSize(1);
        assertThat(workflows.get(0)).containsEntry("name", "test-wf");

        ResponseEntity<Object> getResp = controller.getWorkflow("test-wf");
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("delete removes the workflow")
    void shouldDeleteWorkflow() {
        Map<String, Object> body = createWorkflowBody("to-delete", "Will be deleted");
        controller.saveWorkflow(body);

        ResponseEntity<Object> deleteResp = controller.deleteWorkflow("to-delete");
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Object> getResp = controller.getWorkflow("to-delete");
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("delete returns 404 for unknown workflow")
    void shouldReturn404ForDelete() {
        ResponseEntity<Object> response = controller.deleteWorkflow("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("run executes workflow and returns result")
    void shouldRunWorkflow() {
        Map<String, Object> body = createWorkflowBody("run-wf", "Run test");

        ResponseEntity<Object> response = controller.runWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat(result).containsEntry("success", true);
    }

    @Test
    @DisplayName("run requires steps")
    void shouldRequireStepsForRun() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "empty-wf");

        ResponseEntity<Object> response = controller.runWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("run returns 503 when engine not available")
    void shouldReturn503WhenNoEngine() {
        controller = new WorkflowDesignerController(skillRegistry,
                new YamlWorkflowLoader(tempDir), null, tempDir);

        Map<String, Object> body = createWorkflowBody("fail-wf", "No engine");
        ResponseEntity<Object> response = controller.runWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("export returns YAML text")
    void shouldExportYaml() {
        Map<String, Object> body = createWorkflowBody("export-wf", "Export test");

        ResponseEntity<Object> response = controller.exportYaml(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        String yaml = (String) result.get("yaml");
        assertThat(yaml).contains("name: export-wf");
        assertThat(yaml).contains("skill: log-analysis");
        assertThat(yaml).contains("steps:");
    }

    @Test
    @DisplayName("getWorkflow returns 404 for unknown")
    void shouldReturn404ForGet() {
        ResponseEntity<Object> response = controller.getWorkflow("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("listWorkflows returns 503 when loader is null")
    void shouldReturn503ForListWhenNoLoader() {
        controller = new WorkflowDesignerController(skillRegistry, null, null, tempDir);

        ResponseEntity<Object> response = controller.listWorkflows();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("getWorkflow returns 503 when loader is null")
    void shouldReturn503ForGetWhenNoLoader() {
        controller = new WorkflowDesignerController(skillRegistry, null, null, tempDir);

        ResponseEntity<Object> response = controller.getWorkflow("test");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("save returns 503 when workflowsDir is null")
    void shouldReturn503ForSaveWhenNoDir() {
        controller = new WorkflowDesignerController(skillRegistry, null, null, null);

        Map<String, Object> body = createWorkflowBody("test", "test");
        ResponseEntity<Object> response = controller.saveWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("delete returns 503 when workflowsDir is null")
    void shouldReturn503ForDeleteWhenNoDir() {
        controller = new WorkflowDesignerController(skillRegistry, null, null, null);

        ResponseEntity<Object> response = controller.deleteWorkflow("test");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("save validates step requires name and skill")
    void shouldValidateStepFields() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "test-wf");
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "s1");
        // missing "skill"
        steps.add(step);
        body.put("steps", steps);

        ResponseEntity<Object> response = controller.saveWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("export with multi-step workflow including condition and onFailure")
    void shouldExportMultiStepWorkflow() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "complex-wf");
        body.put("description", "Complex workflow");
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();

        Map<String, Object> s1 = new LinkedHashMap<String, Object>();
        s1.put("name", "analyze");
        s1.put("skill", "log-analysis");
        Map<String, String> s1Inputs = new LinkedHashMap<String, String>();
        s1Inputs.put("query", "${trigger.query}");
        s1.put("inputs", s1Inputs);
        steps.add(s1);

        Map<String, Object> s2 = new LinkedHashMap<String, Object>();
        s2.put("name", "alert");
        s2.put("skill", "send-alert");
        s2.put("condition", "${analyze.result != null}");
        s2.put("onFailure", "SKIP");
        Map<String, String> s2Inputs = new LinkedHashMap<String, String>();
        s2Inputs.put("message", "${analyze.result}");
        s2.put("inputs", s2Inputs);
        steps.add(s2);

        body.put("steps", steps);

        ResponseEntity<Object> response = controller.exportYaml(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        String yaml = (String) result.get("yaml");
        assertThat(yaml).contains("name: complex-wf");
        assertThat(yaml).contains("- name: analyze");
        assertThat(yaml).contains("skill: log-analysis");
        assertThat(yaml).contains("- name: alert");
        assertThat(yaml).contains("skill: send-alert");
        assertThat(yaml).contains("condition:");
        assertThat(yaml).contains("onFailure: SKIP");
    }

    @Test
    @DisplayName("export requires name")
    void shouldRequireNameForExport() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "s1");
        step.put("skill", "log-analysis");
        steps.add(step);
        body.put("steps", steps);

        ResponseEntity<Object> response = controller.exportYaml(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("export requires steps")
    void shouldRequireStepsForExport() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "test-wf");

        ResponseEntity<Object> response = controller.exportYaml(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("run passes triggerInputs to engine")
    void shouldPassTriggerInputs() {
        final Map[] capturedTrigger = new Map[1];
        WorkflowEngine capturingEngine = new WorkflowEngine() {
            @Override
            public WorkflowResult execute(WorkflowDefinition workflow, Map<String, String> triggerInputs) {
                capturedTrigger[0] = triggerInputs;
                Map<String, StepResult> results = new LinkedHashMap<String, StepResult>();
                results.put("s1", new StepResult("s1", "t1", "SUCCEEDED", "ok"));
                return WorkflowResult.success(workflow.getName(), results, 50);
            }
            @Override
            public String type() { return "capturing"; }
        };
        controller = new WorkflowDesignerController(skillRegistry, new YamlWorkflowLoader(tempDir),
                capturingEngine, tempDir);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "trigger-wf");
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "s1");
        step.put("skill", "log-analysis");
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("query", "${trigger.query}");
        step.put("inputs", inputs);
        steps.add(step);
        body.put("steps", steps);

        Map<String, String> trigger = new LinkedHashMap<String, String>();
        trigger.put("query", "find errors");
        body.put("triggerInputs", trigger);

        ResponseEntity<Object> response = controller.runWorkflow(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedTrigger[0]).containsEntry("query", "find errors");
    }

    @Test
    @DisplayName("save and load preserves condition and onFailure")
    void shouldPreserveConditionAndOnFailure() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "condition-wf");
        body.put("description", "Workflow with conditions");
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "step1");
        step.put("skill", "log-analysis");
        step.put("condition", "${trigger.query != null}");
        step.put("onFailure", "RETRY");
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("q", "test");
        step.put("inputs", inputs);
        steps.add(step);
        body.put("steps", steps);

        ResponseEntity<Object> saveResp = controller.saveWorkflow(body);
        assertThat(saveResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Object> getResp = controller.getWorkflow("condition-wf");
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> loaded = (Map<String, Object>) getResp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> loadedSteps = (List<Map<String, Object>>) loaded.get("steps");
        assertThat(loadedSteps).hasSize(1);
        assertThat(loadedSteps.get(0)).containsEntry("condition", "${trigger.query != null}");
        assertThat(loadedSteps.get(0)).containsEntry("onFailure", "RETRY");
    }

    @Test
    @DisplayName("export quotes values with special characters")
    void shouldQuoteSpecialChars() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "special: chars");
        body.put("description", "has # and \"quotes\"");
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "s1");
        step.put("skill", "log-analysis");
        steps.add(step);
        body.put("steps", steps);

        ResponseEntity<Object> response = controller.exportYaml(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        String yaml = (String) result.get("yaml");
        // name contains ':' so should be quoted
        assertThat(yaml).contains("\"special: chars\"");
        // description contains '#' and '"' so should be quoted with escaped quotes
        assertThat(yaml).contains("\\\"quotes\\\"");
    }

    private Map<String, Object> createWorkflowBody(String name, String description) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("description", description);
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("name", "analyze-logs");
        step.put("skill", "log-analysis");
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("query", "${trigger.query}");
        step.put("inputs", inputs);
        steps.add(step);
        body.put("steps", steps);
        return body;
    }
}
