package cn.watsontech.snapagent.boot2x.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SqliteCheckpointStore")
class SqliteCheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("save/load 往返")
    void saveLoadRoundTrip() {
        String dbPath = tempDir.resolve("test.db").toString();
        SqliteCheckpointStore store = new SqliteCheckpointStore(dbPath);
        store.init();

        GraphState state = GraphState.empty("t-1").with("k", "v").nextTurn();
        String id = store.save("t-1", state);
        GraphState loaded = store.load(id);
        assertThat((String) loaded.get("k")).isEqualTo("v");
        assertThat(loaded.getTurn()).isEqualTo(1);
        assertThat(loaded.getThreadId()).isEqualTo("t-1");
    }

    @Test
    @DisplayName("list 按 threadId 返回倒序")
    void listByThread() throws InterruptedException {
        SqliteCheckpointStore store = new SqliteCheckpointStore(tempDir.resolve("test.db").toString());
        store.init();

        store.save("t-1", GraphState.empty("t-1").with("n", 1));
        Thread.sleep(10);
        store.save("t-1", GraphState.empty("t-1").with("n", 2));
        Thread.sleep(10);
        store.save("t-1", GraphState.empty("t-1").with("n", 3));

        List<CheckpointMetadata> list = store.list("t-1");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).getCreatedAt()).isGreaterThanOrEqualTo(list.get(2).getCreatedAt());
    }

    @Test
    @DisplayName("delete 单个 + deleteByThread 批量")
    void deleteOperations() {
        SqliteCheckpointStore store = new SqliteCheckpointStore(tempDir.resolve("test.db").toString());
        store.init();

        String id1 = store.save("t-1", GraphState.empty("t-1"));
        store.save("t-1", GraphState.empty("t-1"));
        store.save("t-2", GraphState.empty("t-2"));

        store.delete(id1);
        assertThat(store.list("t-1")).hasSize(1);

        store.deleteByThread("t-1");
        assertThat(store.list("t-1")).isEmpty();
        assertThat(store.list("t-2")).hasSize(1);
    }
}
