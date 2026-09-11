package cn.watsontech.snapagent.core.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Experiment model")
class ExperimentTest {

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("stores all fields")
        void shouldStoreFields() {
            Map<String, String> inputs = new LinkedHashMap<String, String>();
            inputs.put("q", "v");
            List<Experiment.Variant> variants = new ArrayList<Experiment.Variant>();
            variants.add(new Experiment.Variant("v1", "gpt-4", 0.7, "prompt"));

            Experiment exp = new Experiment("id-1", "name", "desc", "skill-1", inputs, variants);

            assertThat(exp.getId()).isEqualTo("id-1");
            assertThat(exp.getName()).isEqualTo("name");
            assertThat(exp.getDescription()).isEqualTo("desc");
            assertThat(exp.getSkillId()).isEqualTo("skill-1");
            assertThat(exp.getInputs()).containsEntry("q", "v");
            assertThat(exp.getVariants()).hasSize(1);
            assertThat(exp.getStatus()).isEqualTo(Experiment.Status.CREATED);
            assertThat(exp.getCreatedAt()).isGreaterThan(0);
            assertThat(exp.getCompletedAt()).isZero();
        }

        @Test
        @DisplayName("null inputs become empty unmodifiable map")
        void shouldHandleNullInputs() {
            Experiment exp = new Experiment("id", "n", "d", "s", null, null);
            assertThat(exp.getInputs()).isEmpty();
            assertThat(exp.getVariants()).isEmpty();
        }

        @Test
        @DisplayName("inputs are defensively copied")
        void shouldDefensivelyCopyInputs() {
            Map<String, String> inputs = new LinkedHashMap<String, String>();
            inputs.put("a", "b");
            Experiment exp = new Experiment("id", "n", "d", "s", inputs, null);

            inputs.put("c", "d"); // mutate original
            assertThat(exp.getInputs()).doesNotContainKey("c");
        }

        @Test
        @DisplayName("inputs are unmodifiable")
        void shouldReturnUnmodifiableInputs() {
            Map<String, String> inputs = new LinkedHashMap<String, String>();
            inputs.put("a", "b");
            Experiment exp = new Experiment("id", "n", "d", "s", inputs, null);

            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> exp.getInputs().put("x", "y"));
        }
    }

    @Nested
    @DisplayName("status transitions")
    class StatusTransitions {

        @Test
        @DisplayName("setStatus to COMPLETED records completedAt")
        void shouldRecordCompletedAt() {
            Experiment exp = new Experiment("id", "n", "d", "s", null, null);
            assertThat(exp.getCompletedAt()).isZero();

            exp.setStatus(Experiment.Status.COMPLETED);

            assertThat(exp.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
            assertThat(exp.getCompletedAt()).isGreaterThan(0);
        }

        @Test
        @DisplayName("setStatus to FAILED records completedAt")
        void shouldRecordCompletedAtOnFailure() {
            Experiment exp = new Experiment("id", "n", "d", "s", null, null);
            exp.setStatus(Experiment.Status.FAILED);
            assertThat(exp.getCompletedAt()).isGreaterThan(0);
        }

        @Test
        @DisplayName("setStatus to RUNNING does not set completedAt")
        void shouldNotSetCompletedAtForRunning() {
            Experiment exp = new Experiment("id", "n", "d", "s", null, null);
            exp.setStatus(Experiment.Status.RUNNING);
            assertThat(exp.getCompletedAt()).isZero();
        }

        @Test
        @DisplayName("can transition through multiple states")
        void shouldAllowMultipleTransitions() {
            Experiment exp = new Experiment("id", "n", "d", "s", null, null);
            assertThat(exp.getStatus()).isEqualTo(Experiment.Status.CREATED);

            exp.setStatus(Experiment.Status.RUNNING);
            assertThat(exp.getStatus()).isEqualTo(Experiment.Status.RUNNING);

            exp.setStatus(Experiment.Status.COMPLETED);
            assertThat(exp.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
        }
    }

    @Nested
    @DisplayName("Variant")
    class VariantTest {

        @Test
        @DisplayName("stores all properties")
        void shouldStoreProperties() {
            Experiment.Variant v = new Experiment.Variant("v1", "gpt-4", 0.7, "custom prompt");
            assertThat(v.getName()).isEqualTo("v1");
            assertThat(v.getModel()).isEqualTo("gpt-4");
            assertThat(v.getTemperature()).isEqualTo(0.7);
            assertThat(v.getSystemPromptOverride()).isEqualTo("custom prompt");
            assertThat(v.getResult()).isNull();
        }

        @Test
        @DisplayName("allows null optional fields")
        void shouldAllowNulls() {
            Experiment.Variant v = new Experiment.Variant("v1", null, null, null);
            assertThat(v.getModel()).isNull();
            assertThat(v.getTemperature()).isNull();
            assertThat(v.getSystemPromptOverride()).isNull();
        }

        @Test
        @DisplayName("setResult stores result")
        void shouldSetResult() {
            Experiment.Variant v = new Experiment.Variant("v1", "gpt-4", 0.7, null);
            Experiment.VariantResult r = new Experiment.VariantResult(100, 50, 30, 0.001, "output", 2, null);
            v.setResult(r);
            assertThat(v.getResult()).isSameAs(r);
        }
    }

    @Nested
    @DisplayName("VariantResult")
    class VariantResultTest {

        @Test
        @DisplayName("success when error is null")
        void shouldBeSuccessWhenNoError() {
            Experiment.VariantResult r = new Experiment.VariantResult(100, 50, 30, 0.001, "output", 2, null);
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getError()).isNull();
            assertThat(r.getOutput()).isEqualTo("output");
        }

        @Test
        @DisplayName("failure when error is set")
        void shouldBeFailureWhenError() {
            Experiment.VariantResult r = new Experiment.VariantResult(100, 0, 0, 0.0, null, 0, "timeout");
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getError()).isEqualTo("timeout");
        }

        @Test
        @DisplayName("stores all metrics")
        void shouldStoreMetrics() {
            Experiment.VariantResult r = new Experiment.VariantResult(500, 200, 150, 0.005, "result", 3, null);
            assertThat(r.getDurationMs()).isEqualTo(500);
            assertThat(r.getInputTokens()).isEqualTo(200);
            assertThat(r.getOutputTokens()).isEqualTo(150);
            assertThat(r.getCost()).isEqualTo(0.005);
            assertThat(r.getIterationCount()).isEqualTo(3);
        }
    }
}
