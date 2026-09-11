package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.core.experiment.Experiment;
import cn.watsontech.snapagent.core.experiment.ExperimentRunner;
import cn.watsontech.snapagent.core.experiment.ExperimentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for A/B experiment management.
 *
 * <p>Provides endpoints to create, list, run, and compare experiments.
 * Each experiment tests the same skill + inputs against multiple model
 * configurations (variants) and records metrics for comparison.</p>
 */
@RestController
@RequestMapping("${snap-agent.base-path:/snap-agent}")
public class ExperimentController {

    private static final Logger log = LoggerFactory.getLogger(ExperimentController.class);

    private final ExperimentStore experimentStore;
    private final ExperimentRunner experimentRunner;

    public ExperimentController(ExperimentStore experimentStore, ExperimentRunner experimentRunner) {
        if (experimentStore == null) {
            throw new IllegalArgumentException("ExperimentStore must not be null");
        }
        this.experimentStore = experimentStore;
        this.experimentRunner = experimentRunner;
    }

    /**
     * GET /experiments — list all experiments.
     */
    @GetMapping("/experiments")
    public ResponseEntity<Object> listExperiments(
            @RequestParam(required = false) String status) {

        List<Experiment> experiments;
        if (status != null && !status.isEmpty()) {
            try {
                Experiment.Status s = Experiment.Status.valueOf(status.toUpperCase());
                experiments = experimentStore.listByStatus(s);
            } catch (IllegalArgumentException e) {
                return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                        "Unknown status: " + status + ". Valid: CREATED, RUNNING, COMPLETED, FAILED");
            }
        } else {
            experiments = experimentStore.list();
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Experiment exp : experiments) {
            result.add(toSummaryMap(exp));
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("experiments", result);
        response.put("total", result.size());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /experiments/{id} — get a single experiment with full variant details.
     */
    @GetMapping("/experiments/{id}")
    public ResponseEntity<Object> getExperiment(@PathVariable String id) {
        Experiment exp = experimentStore.get(id);
        if (exp == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "EXPERIMENT_NOT_FOUND",
                    "Experiment '" + id + "' not found");
        }
        return ResponseEntity.ok(toDetailMap(exp));
    }

    /**
     * POST /experiments — create a new experiment.
     */
    @PostMapping("/experiments")
    public ResponseEntity<Object> createExperiment(@RequestBody Map<String, Object> body) {
        String name = strVal(body, "name");
        if (name == null || name.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_NAME",
                    "Experiment name is required");
        }

        String skillId = strVal(body, "skillId");
        if (skillId == null || skillId.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_SKILL",
                    "skillId is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, String> inputs = (Map<String, String>) body.get("inputs");
        if (inputs == null) {
            inputs = Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variantDefs =
                (List<Map<String, Object>>) body.get("variants");
        if (variantDefs == null || variantDefs.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "MISSING_VARIANTS",
                    "At least one variant is required");
        }

        List<Experiment.Variant> variants = new ArrayList<Experiment.Variant>();
        for (Map<String, Object> vd : variantDefs) {
            String vName = strVal(vd, "name");
            String vModel = strVal(vd, "model");
            Double vTemp = vd.get("temperature") instanceof Number
                    ? ((Number) vd.get("temperature")).doubleValue() : null;
            String vPrompt = strVal(vd, "systemPromptOverride");
            variants.add(new Experiment.Variant(vName, vModel, vTemp, vPrompt));
        }

        String id = "exp-" + UUID.randomUUID().toString().substring(0, 8);
        String description = strVal(body, "description");

        Experiment experiment = new Experiment(id, name, description, skillId, inputs, variants);
        experimentStore.save(experiment);

        log.info("Created experiment '{}' with {} variants", id, variants.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetailMap(experiment));
    }

    /**
     * POST /experiments/{id}/run — execute all variants.
     */
    @PostMapping("/experiments/{id}/run")
    public ResponseEntity<Object> runExperiment(@PathVariable String id) {
        Experiment exp = experimentStore.get(id);
        if (exp == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "EXPERIMENT_NOT_FOUND",
                    "Experiment '" + id + "' not found");
        }
        if (exp.getStatus() == Experiment.Status.RUNNING) {
            return errorResponse(HttpStatus.CONFLICT, "ALREADY_RUNNING",
                    "Experiment '" + id + "' is already running");
        }
        if (exp.getStatus() == Experiment.Status.COMPLETED) {
            return errorResponse(HttpStatus.CONFLICT, "ALREADY_COMPLETED",
                    "Experiment '" + id + "' has already completed. Create a new experiment.");
        }

        if (experimentRunner == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "NO_RUNNER",
                    "ExperimentRunner not configured");
        }

        // Run asynchronously in a background thread
        final Experiment experiment = exp;
        Thread runner = new Thread(new Runnable() {
            @Override
            public void run() {
                experimentRunner.run(experiment);
            }
        }, "experiment-runner-" + id);
        runner.setDaemon(true);
        runner.start();

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("status", "RUNNING");
        result.put("message", "Experiment started");
        return ResponseEntity.ok(result);
    }

    /**
     * DELETE /experiments/{id} — delete an experiment.
     */
    @DeleteMapping("/experiments/{id}")
    public ResponseEntity<Object> deleteExperiment(@PathVariable String id) {
        Experiment exp = experimentStore.get(id);
        if (exp == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "EXPERIMENT_NOT_FOUND",
                    "Experiment '" + id + "' not found");
        }
        if (exp.getStatus() == Experiment.Status.RUNNING) {
            return errorResponse(HttpStatus.CONFLICT, "STILL_RUNNING",
                    "Cannot delete a running experiment");
        }
        experimentStore.remove(id);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("deleted", true);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /experiments/{id}/compare — comparison summary of variant results.
     */
    @GetMapping("/experiments/{id}/compare")
    public ResponseEntity<Object> compareVariants(@PathVariable String id) {
        Experiment exp = experimentStore.get(id);
        if (exp == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "EXPERIMENT_NOT_FOUND",
                    "Experiment '" + id + "' not found");
        }

        List<Map<String, Object>> comparisons = new ArrayList<Map<String, Object>>();
        for (Experiment.Variant v : exp.getVariants()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", v.getName());
            m.put("model", v.getModel());
            m.put("temperature", v.getTemperature());
            if (v.getResult() != null) {
                Experiment.VariantResult r = v.getResult();
                m.put("success", r.isSuccess());
                m.put("durationMs", r.getDurationMs());
                m.put("inputTokens", r.getInputTokens());
                m.put("outputTokens", r.getOutputTokens());
                m.put("cost", r.getCost());
                m.put("iterationCount", r.getIterationCount());
                m.put("error", r.getError());
            } else {
                m.put("success", false);
                m.put("pending", true);
            }
            comparisons.add(m);
        }

        // Find the best variant (lowest cost among successful ones)
        String bestVariant = null;
        double bestCost = Double.MAX_VALUE;
        for (Experiment.Variant v : exp.getVariants()) {
            if (v.getResult() != null && v.getResult().isSuccess()
                    && v.getResult().getCost() < bestCost) {
                bestCost = v.getResult().getCost();
                bestVariant = v.getName();
            }
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("experimentId", id);
        response.put("status", exp.getStatus().name());
        response.put("variants", comparisons);
        response.put("bestVariant", bestVariant);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toSummaryMap(Experiment exp) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", exp.getId());
        m.put("name", exp.getName());
        m.put("description", exp.getDescription());
        m.put("skillId", exp.getSkillId());
        m.put("status", exp.getStatus().name());
        m.put("variantCount", exp.getVariants().size());
        m.put("createdAt", exp.getCreatedAt());
        m.put("completedAt", exp.getCompletedAt());
        return m;
    }

    private Map<String, Object> toDetailMap(Experiment exp) {
        Map<String, Object> m = toSummaryMap(exp);
        m.put("inputs", exp.getInputs());

        List<Map<String, Object>> variantList = new ArrayList<Map<String, Object>>();
        for (Experiment.Variant v : exp.getVariants()) {
            Map<String, Object> vm = new LinkedHashMap<String, Object>();
            vm.put("name", v.getName());
            vm.put("model", v.getModel());
            vm.put("temperature", v.getTemperature());
            vm.put("systemPromptOverride", v.getSystemPromptOverride());
            if (v.getResult() != null) {
                Experiment.VariantResult r = v.getResult();
                Map<String, Object> rm = new LinkedHashMap<String, Object>();
                rm.put("success", r.isSuccess());
                rm.put("durationMs", r.getDurationMs());
                rm.put("inputTokens", r.getInputTokens());
                rm.put("outputTokens", r.getOutputTokens());
                rm.put("cost", r.getCost());
                rm.put("output", r.getOutput());
                rm.put("iterationCount", r.getIterationCount());
                rm.put("error", r.getError());
                vm.put("result", rm);
            }
            variantList.add(vm);
        }
        m.put("variants", variantList);
        return m;
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
