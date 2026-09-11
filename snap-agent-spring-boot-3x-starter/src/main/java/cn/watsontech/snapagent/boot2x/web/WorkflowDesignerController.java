package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.boot2x.workflow.WorkflowEngine;
import cn.watsontech.snapagent.boot2x.workflow.WorkflowResult;
import cn.watsontech.snapagent.boot2x.workflow.WorkflowStep;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.core.skill.InputSpec;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * REST endpoints for the Visual Workflow Designer.
 *
 * <p>Provides endpoints to list available skills (for the skill palette),
 * save/load workflow definitions as YAML, and run ad-hoc workflows directly
 * from the designer without saving first.</p>
 */
@RestController
@RequestMapping("${snap-agent.base-path:/snap-agent}")
public class WorkflowDesignerController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDesignerController.class);

    private final SkillRegistry skillRegistry;
    private final YamlWorkflowLoader workflowLoader;
    private final WorkflowEngine workflowEngine;
    private final Path workflowsDir;

    public WorkflowDesignerController(SkillRegistry skillRegistry,
                                       YamlWorkflowLoader workflowLoader,
                                       WorkflowEngine workflowEngine,
                                       Path workflowsDir) {
        if (skillRegistry == null) {
            throw new IllegalArgumentException("SkillRegistry must not be null");
        }
        this.skillRegistry = skillRegistry;
        this.workflowLoader = workflowLoader;
        this.workflowEngine = workflowEngine;
        this.workflowsDir = workflowsDir;
    }

    /**
     * GET /workflow-designer/skills — list available skills for the palette.
     */
    @GetMapping("/workflow-designer/skills")
    public ResponseEntity<Object> listSkills() {
        List<Map<String, Object>> skills = new ArrayList<Map<String, Object>>();
        for (SkillMeta skill : skillRegistry.all()) {
            if (skill.getAvailability() != SkillAvailability.AVAILABLE) {
                continue;
            }
            Map<String, Object> dto = new LinkedHashMap<String, Object>();
            dto.put("name", skill.getName());
            dto.put("description", skill.getDescription());
            List<Map<String, Object>> inputs = new ArrayList<Map<String, Object>>();
            for (InputSpec spec : skill.getInputs()) {
                Map<String, Object> inputDto = new LinkedHashMap<String, Object>();
                inputDto.put("key", spec.getKey());
                inputDto.put("label", spec.getLabel());
                inputDto.put("required", spec.isRequired());
                inputDto.put("type", spec.getType());
                inputs.add(inputDto);
            }
            dto.put("inputs", inputs);
            skills.add(dto);
        }
        Collections.sort(skills, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return ((String) a.get("name")).compareTo((String) b.get("name"));
            }
        });
        return ResponseEntity.ok(skills);
    }

    /**
     * GET /workflow-designer/workflows — list saved workflows.
     */
    @GetMapping("/workflow-designer/workflows")
    public ResponseEntity<Object> listWorkflows() {
        if (workflowLoader == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOWS_DISABLED",
                    "workflow engine not enabled");
        }
        List<WorkflowDefinition> workflows = workflowLoader.loadAll();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (WorkflowDefinition wf : workflows) {
            Map<String, Object> dto = new LinkedHashMap<String, Object>();
            dto.put("name", wf.getName());
            dto.put("description", wf.getDescription());
            List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
            for (WorkflowStep step : wf.getSteps()) {
                steps.add(stepToDto(step));
            }
            dto.put("steps", steps);
            result.add(dto);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /workflow-designer/workflows/{name} — get workflow for editing.
     */
    @GetMapping("/workflow-designer/workflows/{name}")
    public ResponseEntity<Object> getWorkflow(@PathVariable String name) {
        if (workflowLoader == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOWS_DISABLED",
                    "workflow engine not enabled");
        }
        WorkflowDefinition wf = workflowLoader.load(name);
        if (wf == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND",
                    "workflow not found: " + name);
        }
        Map<String, Object> dto = new LinkedHashMap<String, Object>();
        dto.put("name", wf.getName());
        dto.put("description", wf.getDescription());
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        for (WorkflowStep step : wf.getSteps()) {
            steps.add(stepToDto(step));
        }
        dto.put("steps", steps);
        return ResponseEntity.ok(dto);
    }

    /**
     * DELETE /workflow-designer/workflows/{name} — delete a saved workflow.
     */
    @DeleteMapping("/workflow-designer/workflows/{name}")
    public ResponseEntity<Object> deleteWorkflow(@PathVariable String name) {
        if (workflowsDir == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOWS_DISABLED",
                    "workflow engine not enabled");
        }
        Path file = workflowsDir.resolve(sanitizeFilename(name) + ".yml");
        if (!Files.exists(file)) {
            return errorResponse(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND",
                    "workflow not found: " + name);
        }
        try {
            Files.delete(file);
            log.info("Deleted workflow file: {}", file);
        } catch (IOException e) {
            log.error("Failed to delete workflow file {}: {}", file, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DELETE_FAILED",
                    "failed to delete workflow: " + e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("deleted", true);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /workflow-designer/save — save workflow as YAML.
     */
    @PostMapping("/workflow-designer/save")
    public ResponseEntity<Object> saveWorkflow(@RequestBody Map<String, Object> body) {
        if (workflowsDir == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOWS_DISABLED",
                    "workflow engine not enabled");
        }

        String name = strVal(body, "name");
        if (name == null || name.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_NAME",
                    "workflow name is required");
        }

        String description = strVal(body, "description");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) body.get("steps");
        if (stepDefs == null || stepDefs.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_STEPS",
                    "at least one step is required");
        }

        // Build YAML content
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(yamlQuote(name)).append("\n");
        if (description != null && !description.isEmpty()) {
            yaml.append("description: ").append(yamlQuote(description)).append("\n");
        }
        yaml.append("steps:\n");
        for (Map<String, Object> stepDef : stepDefs) {
            String stepName = strVal(stepDef, "name");
            String skill = strVal(stepDef, "skill");
            if (stepName == null || stepName.isEmpty() || skill == null || skill.isEmpty()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_STEP",
                        "each step requires 'name' and 'skill' fields");
            }
            yaml.append("  - name: ").append(yamlQuote(stepName)).append("\n");
            yaml.append("    skill: ").append(yamlQuote(skill)).append("\n");

            String condition = strVal(stepDef, "condition");
            if (condition != null && !condition.isEmpty()) {
                yaml.append("    condition: ").append(yamlQuote(condition)).append("\n");
            }

            String onFailure = strVal(stepDef, "onFailure");
            if (onFailure != null && !onFailure.isEmpty()) {
                yaml.append("    onFailure: ").append(yamlQuote(onFailure)).append("\n");
            }

            @SuppressWarnings("unchecked")
            Map<String, String> inputs = (Map<String, String>) stepDef.get("inputs");
            if (inputs != null && !inputs.isEmpty()) {
                yaml.append("    inputs:\n");
                for (Map.Entry<String, String> entry : inputs.entrySet()) {
                    yaml.append("      ").append(entry.getKey()).append(": ")
                            .append(yamlQuote(entry.getValue())).append("\n");
                }
            }
        }

        // Write to file
        String filename = sanitizeFilename(name) + ".yml";
        Path file = workflowsDir.resolve(filename);
        try {
            Files.write(file, yaml.toString().getBytes(StandardCharsets.UTF_8));
            log.info("Saved workflow '{}' to {}", name, file);
        } catch (IOException e) {
            log.error("Failed to save workflow to {}: {}", file, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SAVE_FAILED",
                    "failed to save workflow: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("file", filename);
        result.put("stepCount", stepDefs.size());
        result.put("saved", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * POST /workflow-designer/run — run ad-hoc workflow from JSON.
     */
    @PostMapping("/workflow-designer/run")
    public ResponseEntity<Object> runWorkflow(@RequestBody Map<String, Object> body) {
        if (workflowEngine == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOWS_DISABLED",
                    "workflow engine not enabled");
        }

        String name = strVal(body, "name");
        if (name == null || name.isEmpty()) {
            name = "ad-hoc-designer";
        }

        String description = strVal(body, "description");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) body.get("steps");
        if (stepDefs == null || stepDefs.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_STEPS",
                    "at least one step is required");
        }

        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        for (Map<String, Object> stepDef : stepDefs) {
            String stepName = strVal(stepDef, "name");
            String skill = strVal(stepDef, "skill");
            if (stepName == null || skill == null) continue;

            String condition = strVal(stepDef, "condition");
            String onFailure = strVal(stepDef, "onFailure");
            @SuppressWarnings("unchecked")
            Map<String, String> inputs = (Map<String, String>) stepDef.get("inputs");
            steps.add(new WorkflowStep(stepName, skill, condition, inputs, onFailure));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> triggerInputs = (Map<String, String>) body.get("triggerInputs");
        if (triggerInputs == null) {
            triggerInputs = new HashMap<String, String>();
        }

        WorkflowDefinition workflow = new WorkflowDefinition(name, description, steps);
        WorkflowResult result = workflowEngine.execute(workflow, triggerInputs);

        return ResponseEntity.ok(toResultDto(result));
    }

    /**
     * POST /workflow-designer/export — export workflow as YAML text.
     */
    @PostMapping("/workflow-designer/export")
    public ResponseEntity<Object> exportYaml(@RequestBody Map<String, Object> body) {
        String name = strVal(body, "name");
        if (name == null || name.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_NAME",
                    "workflow name is required");
        }

        String description = strVal(body, "description");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) body.get("steps");
        if (stepDefs == null || stepDefs.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_STEPS",
                    "at least one step is required");
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(yamlQuote(name)).append("\n");
        if (description != null && !description.isEmpty()) {
            yaml.append("description: ").append(yamlQuote(description)).append("\n");
        }
        yaml.append("steps:\n");
        for (Map<String, Object> stepDef : stepDefs) {
            String stepName = strVal(stepDef, "name");
            String skill = strVal(stepDef, "skill");
            if (stepName == null || skill == null) continue;
            yaml.append("  - name: ").append(yamlQuote(stepName)).append("\n");
            yaml.append("    skill: ").append(yamlQuote(skill)).append("\n");
            String condition = strVal(stepDef, "condition");
            if (condition != null && !condition.isEmpty()) {
                yaml.append("    condition: ").append(yamlQuote(condition)).append("\n");
            }
            String onFailure = strVal(stepDef, "onFailure");
            if (onFailure != null && !onFailure.isEmpty()) {
                yaml.append("    onFailure: ").append(yamlQuote(onFailure)).append("\n");
            }
            @SuppressWarnings("unchecked")
            Map<String, String> inputs = (Map<String, String>) stepDef.get("inputs");
            if (inputs != null && !inputs.isEmpty()) {
                yaml.append("    inputs:\n");
                for (Map.Entry<String, String> entry : inputs.entrySet()) {
                    yaml.append("      ").append(entry.getKey()).append(": ")
                            .append(yamlQuote(entry.getValue())).append("\n");
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("yaml", yaml.toString());
        return ResponseEntity.ok(result);
    }

    // ---- Helper methods ----

    private Map<String, Object> stepToDto(WorkflowStep step) {
        Map<String, Object> dto = new LinkedHashMap<String, Object>();
        dto.put("name", step.getName());
        dto.put("skill", step.getSkill());
        dto.put("condition", step.getCondition());
        dto.put("onFailure", step.getOnFailure());
        dto.put("inputs", step.getInputs());
        return dto;
    }

    private Map<String, Object> toResultDto(WorkflowResult result) {
        Map<String, Object> dto = new LinkedHashMap<String, Object>();
        dto.put("workflowName", result.getWorkflowName());
        dto.put("success", result.isSuccess());
        dto.put("status", result.getStatus() != null ? result.getStatus().name() : null);
        dto.put("failedStep", result.getFailedStep());
        dto.put("errorMessage", result.getErrorMessage());
        dto.put("durationMs", result.getDurationMs());
        Map<String, Object> stepResults = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, cn.watsontech.snapagent.boot2x.workflow.StepResult> entry
                : result.getStepResults().entrySet()) {
            cn.watsontech.snapagent.boot2x.workflow.StepResult sr = entry.getValue();
            if (sr != null) {
                Map<String, Object> srDto = new LinkedHashMap<String, Object>();
                srDto.put("stepName", sr.getStepName());
                srDto.put("status", sr.getStatus());
                srDto.put("report", sr.getReport());
                stepResults.put(entry.getKey(), srDto);
            }
        }
        dto.put("stepResults", stepResults);
        return dto;
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase();
    }

    private String yamlQuote(String value) {
        if (value == null) return "\"\"";
        if (value.contains("'") || value.contains("\"") || value.contains(":")
                || value.contains("#") || value.contains("\n")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    private ResponseEntity<Object> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body((Object) body);
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
