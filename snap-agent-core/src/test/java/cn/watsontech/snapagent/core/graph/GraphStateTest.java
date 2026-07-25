package cn.watsontech.snapagent.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GraphState 不可变状态容器")
class GraphStateTest {

    @Test
    @DisplayName("with() 返回新实例，原实例不变")
    void withReturnsNewInstance() {
        GraphState state1 = GraphState.empty("thread-1").with("k", "v1");
        GraphState state2 = state1.with("k", "v2");
        assertThat((String) state1.get("k")).isEqualTo("v1");
        assertThat((String) state2.get("k")).isEqualTo("v2");
        assertThat(state1).isNotSameAs(state2);
    }

    @Test
    @DisplayName("get(key, defaultValue) 兜底")
    void getWithDefaultValue() {
        GraphState state = GraphState.empty("thread-1");
        assertThat(state.get("missing", "default")).isEqualTo("default");
        assertThat((String) state.get("missing")).isNull();
    }

    @Test
    @DisplayName("nextTurn 自增 turn，原实例不变")
    void nextTurnIncrements() {
        GraphState state0 = GraphState.empty("thread-1");
        assertThat(state0.getTurn()).isZero();
        GraphState state1 = state0.nextTurn();
        assertThat(state1.getTurn()).isEqualTo(1);
        assertThat(state0.getTurn()).isZero();
    }

    @Test
    @DisplayName("serialize/deserialize 往返一致")
    void serializeDeserializeRoundTrip() {
        GraphState state = GraphState.empty("thread-1")
            .with("k", "v")
            .nextTurn();
        byte[] bytes = state.serialize();
        GraphState loaded = GraphState.deserialize(bytes);
        assertThat((String) loaded.get("k")).isEqualTo("v");
        assertThat(loaded.getThreadId()).isEqualTo("thread-1");
        assertThat(loaded.getTurn()).isEqualTo(1);
    }

    @Test
    @DisplayName("并发 with 不互相干扰")
    void concurrentWithIsSafe() throws Exception {
        GraphState state0 = GraphState.empty("thread-1").with("counter", 0);
        int threads = 10;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        java.util.List<GraphState> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int val = i;
            pool.submit(() -> {
                results.add(state0.with("counter", val));
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();
        assertThat(results).hasSize(threads);
        assertThat((Integer) state0.get("counter")).isEqualTo(0);
        assertThat(results).allSatisfy(s -> assertThat((Integer) s.get("counter")).isNotNull());
    }
}
