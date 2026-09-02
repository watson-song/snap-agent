package cn.watsontech.snapagent.boot2x.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCheckpointStoreTest {

    private JdbcTemplate jdbc;
    private CheckpointStore store;

    @BeforeEach
    void setUp() {
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(db);
        store = new JdbcCheckpointStore(jdbc);
    }

    @Test
    void shouldSaveAndLoadCheckpoint() {
        GraphState state = GraphState.empty("thread-1").with("key", "val");
        String id = store.save("thread-1", state);
        assertThat(id).isNotBlank();

        GraphState loaded = store.load(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTurn()).isEqualTo(0);
        assertThat(loaded.<String>get("key")).isEqualTo("val");
    }

    @Test
    void shouldReturnNullForUnknownCheckpoint() {
        assertThat(store.load("nonexistent")).isNull();
    }

    @Test
    void shouldListCheckpointsByThreadInDescOrder() throws Exception {
        store.save("thread-1", GraphState.empty("thread-1"));
        Thread.sleep(5); // ensure different created_at timestamps for stable ordering
        store.save("thread-1", GraphState.empty("thread-1").nextTurn());
        store.save("thread-2", GraphState.empty("thread-2"));

        List<CheckpointMetadata> list = store.list("thread-1");
        assertThat(list).hasSize(2);
        // DESC order — most recent first
        assertThat(list.get(0).getTurn()).isEqualTo(1);
        assertThat(list.get(1).getTurn()).isEqualTo(0);
    }

    @Test
    void shouldReturnEmptyListForUnknownThread() {
        assertThat(store.list("unknown-thread")).isEmpty();
    }

    @Test
    void shouldDeleteCheckpointById() {
        String id = store.save("thread-1", GraphState.empty("thread-1"));
        assertThat(store.load(id)).isNotNull();

        store.delete(id);
        assertThat(store.load(id)).isNull();
    }

    @Test
    void shouldDeleteAllCheckpointsByThread() {
        store.save("thread-1", GraphState.empty("thread-1"));
        store.save("thread-1", GraphState.empty("thread-1").nextTurn());
        store.save("thread-2", GraphState.empty("thread-2"));

        store.deleteByThread("thread-1");

        assertThat(store.list("thread-1")).isEmpty();
        assertThat(store.list("thread-2")).hasSize(1);
    }

    @Test
    void shouldPreserveStateData() {
        GraphState original = GraphState.empty("thread-x")
                .with("name", "test-agent")
                .with("count", 42)
                .nextTurn()
                .nextTurn();

        String id = store.save("thread-x", original);
        GraphState loaded = store.load(id);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getThreadId()).isEqualTo("thread-x");
        assertThat(loaded.getTurn()).isEqualTo(2);
        assertThat(loaded.<String>get("name")).isEqualTo("test-agent");
        assertThat(loaded.<Integer>get("count")).isEqualTo(42);
    }

    @Test
    void shouldRejectNullJdbcTemplate() {
        assertThatThrownBy(() -> new JdbcCheckpointStore(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JdbcTemplate");
    }

    @Test
    void checkpointMetadataShouldContainExpectedFields() {
        String id = store.save("thread-meta", GraphState.empty("thread-meta").nextTurn().nextTurn());
        List<CheckpointMetadata> list = store.list("thread-meta");
        assertThat(list).hasSize(1);
        CheckpointMetadata meta = list.get(0);
        assertThat(meta.getCheckpointId()).isEqualTo(id);
        assertThat(meta.getThreadId()).isEqualTo("thread-meta");
        assertThat(meta.getTurn()).isEqualTo(2);
        assertThat(meta.getCreatedAt()).isGreaterThan(0);
    }
}
