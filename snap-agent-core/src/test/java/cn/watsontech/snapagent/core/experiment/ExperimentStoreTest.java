package cn.watsontech.snapagent.core.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExperimentStore")
class ExperimentStoreTest {

    private ExperimentStore store;

    @BeforeEach
    void setUp() {
        store = new ExperimentStore();
    }

    @Test
    @DisplayName("save and get returns the experiment")
    void shouldSaveAndGet() {
        Experiment exp = createExperiment("exp-1", "Test");
        store.save(exp);
        assertThat(store.get("exp-1")).isSameAs(exp);
    }

    @Test
    @DisplayName("get returns null for unknown ID")
    void shouldReturnNullForUnknown() {
        assertThat(store.get("nonexistent")).isNull();
    }

    @Test
    @DisplayName("get returns null for null ID")
    void shouldReturnNullForNull() {
        assertThat(store.get(null)).isNull();
    }

    @Test
    @DisplayName("save ignores null experiment")
    void shouldIgnoreNull() {
        store.save(null);
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("list returns experiments sorted by createdAt descending")
    void shouldListSorted() {
        Experiment exp1 = createExperiment("exp-1", "First");
        Experiment exp2 = createExperiment("exp-2", "Second");
        store.save(exp1);
        store.save(exp2);

        List<Experiment> list = store.list();
        assertThat(list).hasSize(2);
        // exp2 was created after exp1 (higher createdAt)
        assertThat(list.get(0).getId()).isEqualTo("exp-2");
        assertThat(list.get(1).getId()).isEqualTo("exp-1");
    }

    @Test
    @DisplayName("listByStatus filters correctly")
    void shouldFilterByStatus() {
        Experiment exp1 = createExperiment("exp-1", "Created");
        Experiment exp2 = createExperiment("exp-2", "Done");
        exp2.setStatus(Experiment.Status.COMPLETED);
        store.save(exp1);
        store.save(exp2);

        List<Experiment> created = store.listByStatus(Experiment.Status.CREATED);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getId()).isEqualTo("exp-1");

        List<Experiment> completed = store.listByStatus(Experiment.Status.COMPLETED);
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getId()).isEqualTo("exp-2");
    }

    @Test
    @DisplayName("remove deletes the experiment")
    void shouldRemove() {
        store.save(createExperiment("exp-1", "Test"));
        assertThat(store.size()).isEqualTo(1);
        store.remove("exp-1");
        assertThat(store.size()).isZero();
        assertThat(store.get("exp-1")).isNull();
    }

    @Test
    @DisplayName("remove handles null gracefully")
    void shouldHandleNullRemove() {
        store.remove(null);
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("clear removes all experiments")
    void shouldClearAll() {
        store.save(createExperiment("exp-1", "A"));
        store.save(createExperiment("exp-2", "B"));
        assertThat(store.size()).isEqualTo(2);
        store.clear();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("evicts experiments when over max size")
    void shouldEvictWhenOverMax() {
        ExperimentStore small = new ExperimentStore(2);
        Experiment exp1 = createExperiment("exp-1", "First");
        exp1.setStatus(Experiment.Status.COMPLETED);
        Experiment exp2 = createExperiment("exp-2", "Second");
        exp2.setStatus(Experiment.Status.COMPLETED);
        Experiment exp3 = createExperiment("exp-3", "Third");

        small.save(exp1);
        small.save(exp2);
        // Now at capacity (2)
        small.save(exp3);
        // Should evict at least one to stay at max
        assertThat(small.size()).isLessThanOrEqualTo(2);
        // The newest should always survive
        assertThat(small.get("exp-3")).isNotNull();
    }

    private Experiment createExperiment(String id, String name) {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("query", "test");
        List<Experiment.Variant> variants = new ArrayList<Experiment.Variant>();
        variants.add(new Experiment.Variant("v1", "gpt-4", 0.7, null));
        return new Experiment(id, name, "desc", "test-skill", inputs, variants);
    }
}
