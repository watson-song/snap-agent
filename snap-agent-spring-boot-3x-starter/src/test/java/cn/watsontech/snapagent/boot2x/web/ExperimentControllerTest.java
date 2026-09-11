package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.core.experiment.Experiment;
import cn.watsontech.snapagent.core.experiment.ExperimentRunner;
import cn.watsontech.snapagent.core.experiment.ExperimentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExperimentController")
class ExperimentControllerTest {

    private ExperimentStore store;
    private ExperimentController controller;

    @BeforeEach
    void setUp() {
        store = new ExperimentStore();
        ExperimentRunner runner = new ExperimentRunner() {
            @Override
            public void run(Experiment experiment) {
                // no-op for tests
            }
            @Override
            public boolean isRunning(String experimentId) {
                return false;
            }
        };
        controller = new ExperimentController(store, runner);
    }

    @Test
    @DisplayName("listExperiments returns 200 with empty list")
    void shouldListEmpty() {
        ResponseEntity<Object> response = controller.listExperiments(null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("experiments");
        assertThat(body).containsEntry("total", 0);
    }

    @Test
    @DisplayName("createExperiment returns 201 with experiment details")
    void shouldCreateExperiment() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "Model Comparison");
        body.put("skillId", "log-analysis");
        body.put("description", "Compare models");
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("query", "test");
        body.put("inputs", inputs);

        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        Map<String, Object> v1 = new LinkedHashMap<String, Object>();
        v1.put("name", "gpt4");
        v1.put("model", "gpt-4");
        v1.put("temperature", 0.7);
        variants.add(v1);
        Map<String, Object> v2 = new LinkedHashMap<String, Object>();
        v2.put("name", "haiku");
        v2.put("model", "claude-3-haiku");
        v2.put("temperature", 0.5);
        variants.add(v2);
        body.put("variants", variants);

        ResponseEntity<Object> response = controller.createExperiment(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat(result).containsKey("id");
        assertThat(result).containsEntry("name", "Model Comparison");
        assertThat(result).containsEntry("skillId", "log-analysis");
        assertThat(result).containsEntry("status", "CREATED");
    }

    @Test
    @DisplayName("createExperiment returns 400 when name is missing")
    void shouldRejectMissingName() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", "log-analysis");
        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        Map<String, Object> v1 = new LinkedHashMap<String, Object>();
        v1.put("name", "v1");
        v1.put("model", "gpt-4");
        variants.add(v1);
        body.put("variants", variants);

        ResponseEntity<Object> response = controller.createExperiment(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("createExperiment returns 400 when skillId is missing")
    void shouldRejectMissingSkill() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "Test");
        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        Map<String, Object> v1 = new LinkedHashMap<String, Object>();
        v1.put("name", "v1");
        variants.add(v1);
        body.put("variants", variants);

        ResponseEntity<Object> response = controller.createExperiment(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("createExperiment returns 400 when variants is empty")
    void shouldRejectEmptyVariants() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "Test");
        body.put("skillId", "log-analysis");
        body.put("variants", Collections.emptyList());

        ResponseEntity<Object> response = controller.createExperiment(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("getExperiment returns 404 for unknown ID")
    void shouldReturn404ForUnknown() {
        ResponseEntity<Object> response = controller.getExperiment("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("getExperiment returns experiment after creation")
    void shouldReturnCreatedExperiment() {
        // Create first
        Map<String, Object> body = createMinimalBody();
        ResponseEntity<Object> createResp = controller.createExperiment(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) createResp.getBody();
        String id = (String) created.get("id");

        // Get
        ResponseEntity<Object> response = controller.getExperiment(id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat(result).containsEntry("id", id);
        assertThat(result).containsKey("variants");
    }

    @Test
    @DisplayName("deleteExperiment removes the experiment")
    void shouldDeleteExperiment() {
        Map<String, Object> body = createMinimalBody();
        ResponseEntity<Object> createResp = controller.createExperiment(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) createResp.getBody();
        String id = (String) created.get("id");

        ResponseEntity<Object> response = controller.deleteExperiment(id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(store.get(id)).isNull();
    }

    @Test
    @DisplayName("deleteExperiment returns 404 for unknown ID")
    void shouldReturn404ForDeleteUnknown() {
        ResponseEntity<Object> response = controller.deleteExperiment("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("listExperiments with status filter works")
    void shouldFilterByStatus() {
        Map<String, Object> body = createMinimalBody();
        controller.createExperiment(body);

        ResponseEntity<Object> response = controller.listExperiments("CREATED");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat((Integer) result.get("total")).isEqualTo(1);
    }

    @Test
    @DisplayName("listExperiments returns 400 for invalid status")
    void shouldRejectInvalidStatus() {
        ResponseEntity<Object> response = controller.listExperiments("INVALID");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("compareVariants returns 404 for unknown experiment")
    void shouldReturn404ForCompareUnknown() {
        ResponseEntity<Object> response = controller.compareVariants("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("compareVariants returns variant comparison")
    void shouldCompareVariants() {
        Map<String, Object> body = createMinimalBody();
        ResponseEntity<Object> createResp = controller.createExperiment(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) createResp.getBody();
        String id = (String) created.get("id");

        ResponseEntity<Object> response = controller.compareVariants(id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat(result).containsEntry("experimentId", id);
        assertThat(result).containsKey("variants");
    }

    @Test
    @DisplayName("runExperiment returns 409 when already running")
    void shouldRejectRunWhenAlreadyRunning() {
        Experiment exp = createAndSaveExperiment();
        exp.setStatus(Experiment.Status.RUNNING);

        ResponseEntity<Object> response = controller.runExperiment(exp.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "ALREADY_RUNNING");
    }

    @Test
    @DisplayName("runExperiment returns 409 when already completed")
    void shouldRejectRunWhenAlreadyCompleted() {
        Experiment exp = createAndSaveExperiment();
        exp.setStatus(Experiment.Status.COMPLETED);

        ResponseEntity<Object> response = controller.runExperiment(exp.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "ALREADY_COMPLETED");
    }

    @Test
    @DisplayName("runExperiment returns 404 for unknown experiment")
    void shouldReturn404ForRunUnknown() {
        ResponseEntity<Object> response = controller.runExperiment("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("runExperiment returns 503 when runner is null")
    void shouldReturn503WhenNoRunner() {
        controller = new ExperimentController(store, null);
        Experiment exp = createAndSaveExperiment();

        ResponseEntity<Object> response = controller.runExperiment(exp.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "NO_RUNNER");
    }

    @Test
    @DisplayName("runExperiment returns 200 and starts execution")
    void shouldStartExperimentRun() {
        Experiment exp = createAndSaveExperiment();

        ResponseEntity<Object> response = controller.runExperiment(exp.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("status", "RUNNING");
        assertThat(body).containsEntry("message", "Experiment started");
    }

    @Test
    @DisplayName("deleteExperiment returns 409 when still running")
    void shouldRejectDeleteWhenRunning() {
        Experiment exp = createAndSaveExperiment();
        exp.setStatus(Experiment.Status.RUNNING);

        ResponseEntity<Object> response = controller.deleteExperiment(exp.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "STILL_RUNNING");
        // Verify it was NOT deleted
        assertThat(store.get(exp.getId())).isNotNull();
    }

    @Test
    @DisplayName("compareVariants identifies best variant by cost")
    void shouldIdentifyBestVariant() {
        Experiment exp = createAndSaveExperiment();
        // Set results: v1 cheap, v2 expensive
        exp.getVariants().get(0).setResult(
                new Experiment.VariantResult(100, 50, 30, 0.001, "output1", 2, null));
        exp.getVariants().get(1).setResult(
                new Experiment.VariantResult(200, 100, 60, 0.05, "output2", 3, null));
        exp.setStatus(Experiment.Status.COMPLETED);

        ResponseEntity<Object> response = controller.compareVariants(exp.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat(result).containsEntry("bestVariant", "v1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variants = (List<Map<String, Object>>) result.get("variants");
        assertThat(variants).hasSize(2);
        assertThat(variants.get(0)).containsEntry("success", true);
        assertThat(variants.get(0)).containsEntry("durationMs", 100L);
    }

    @Test
    @DisplayName("compareVariants shows pending when no result")
    void shouldShowPendingWhenNoResult() {
        Experiment exp = createAndSaveExperiment();
        // No results set yet

        ResponseEntity<Object> response = controller.compareVariants(exp.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertThat(result).containsEntry("bestVariant", null); // null → no best yet
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variants = (List<Map<String, Object>>) result.get("variants");
        assertThat(variants.get(0)).containsEntry("pending", true);
    }

    @Test
    @DisplayName("compareVariants skips failed variants for best selection")
    void shouldSkipFailedVariantsForBest() {
        Experiment exp = createAndSaveExperiment();
        exp.getVariants().get(0).setResult(
                new Experiment.VariantResult(100, 50, 30, 0.001, null, 2, "error"));
        exp.getVariants().get(1).setResult(
                new Experiment.VariantResult(200, 100, 60, 0.05, "ok", 3, null));

        ResponseEntity<Object> response = controller.compareVariants(exp.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody();
        // v1 failed (has error), v2 succeeded → v2 is best
        assertThat(result).containsEntry("bestVariant", "v2");
    }

    @Test
    @DisplayName("createExperiment with null inputs defaults to empty map")
    void shouldDefaultInputsToEmpty() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "No Inputs");
        body.put("skillId", "test-skill");
        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        Map<String, Object> v1 = new LinkedHashMap<String, Object>();
        v1.put("name", "v1");
        v1.put("model", "gpt-4");
        variants.add(v1);
        body.put("variants", variants);
        // No "inputs" key

        ResponseEntity<Object> response = controller.createExperiment(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("constructor rejects null store")
    void shouldRejectNullStore() {
        assertThatThrownBy(() -> new ExperimentController(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ExperimentStore");
    }

    private Experiment createAndSaveExperiment() {
        Map<String, Object> body = createMinimalBody();
        ResponseEntity<Object> createResp = controller.createExperiment(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) createResp.getBody();
        String id = (String) created.get("id");
        return store.get(id);
    }

    private Map<String, Object> createMinimalBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "Test Experiment");
        body.put("skillId", "test-skill");
        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        Map<String, Object> v1 = new LinkedHashMap<String, Object>();
        v1.put("name", "v1");
        v1.put("model", "gpt-4");
        variants.add(v1);
        Map<String, Object> v2 = new LinkedHashMap<String, Object>();
        v2.put("name", "v2");
        v2.put("model", "gpt-3.5");
        variants.add(v2);
        body.put("variants", variants);
        return body;
    }
}
