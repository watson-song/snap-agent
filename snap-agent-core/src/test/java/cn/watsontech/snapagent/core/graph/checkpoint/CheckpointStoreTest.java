package cn.watsontech.snapagent.core.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CheckpointStore SPI — InMemoryCheckpointStore")
class CheckpointStoreTest {

    @Test
    @DisplayName("save 返回 checkpointId 并可 load 还原")
    void saveLoadRoundTrip() {
        CheckpointStore store = new InMemoryCheckpointStore();
        GraphState state = GraphState.empty("t-1").with("k", "v").nextTurn();
        String id = store.save("t-1", state);
        assertThat(id).isNotBlank();
        GraphState loaded = store.load(id);
        assertThat((String) loaded.get("k")).isEqualTo("v");
        assertThat(loaded.getTurn()).isEqualTo(1);
    }

    @Test
    @DisplayName("list 按 threadId 返回元数据列表倒序")
    void listByThread() throws InterruptedException {
        CheckpointStore store = new InMemoryCheckpointStore();
        GraphState s1 = GraphState.empty("t-1").with("n", 1);
        String id1 = store.save("t-1", s1);
        Thread.sleep(5);
        GraphState s2 = GraphState.empty("t-1").with("n", 2);
        String id2 = store.save("t-1", s2);
        Thread.sleep(5);
        GraphState s3 = GraphState.empty("t-1").with("n", 3);
        String id3 = store.save("t-1", s3);

        List<CheckpointMetadata> list = store.list("t-1");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).getCreatedAt()).isGreaterThanOrEqualTo(list.get(1).getCreatedAt());
    }

    @Test
    @DisplayName("delete 单个")
    void deleteSingle() {
        CheckpointStore store = new InMemoryCheckpointStore();
        String id1 = store.save("t-1", GraphState.empty("t-1"));
        store.save("t-1", GraphState.empty("t-1"));
        store.save("t-1", GraphState.empty("t-1"));
        store.delete(id1);
        assertThat(store.list("t-1")).hasSize(2);
    }

    @Test
    @DisplayName("deleteByThread 批量")
    void deleteByThread() {
        CheckpointStore store = new InMemoryCheckpointStore();
        store.save("t-1", GraphState.empty("t-1"));
        store.save("t-1", GraphState.empty("t-1"));
        store.save("t-2", GraphState.empty("t-2"));
        store.deleteByThread("t-1");
        assertThat(store.list("t-1")).isEmpty();
        assertThat(store.list("t-2")).hasSize(1);
    }
}
