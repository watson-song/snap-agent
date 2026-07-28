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

    // ════════════════════════════════════════════════════════════════
    // Type-safe StateKey tests
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("StateKey get/with 类型安全 — 无 unchecked cast")
    void stateKeyGetAndWithTypeSafety() {
        StateKey<String> key = StateKey.of("my.key", String.class);
        GraphState state = GraphState.empty("t1").with(key, "hello");
        assertThat(state.get(key)).isEqualTo("hello");
    }

    @Test
    @DisplayName("StateKey 与 String key 互通 — 同一底层 key name")
    void stateKeyInteroperableWithStringKey() {
        StateKey<String> key = StateKey.of("shared.key", String.class);
        GraphState state = GraphState.empty("t1").with(key, "typed-value");
        // String-based get reads the same value
        assertThat((String) state.get("shared.key")).isEqualTo("typed-value");
        // String-based with is readable via StateKey
        state = state.with("shared.key", "string-value");
        assertThat(state.get(key)).isEqualTo("string-value");
    }

    @Test
    @DisplayName("StateKey get(key, defaultValue) 兜底")
    void stateKeyGetWithDefaultValue() {
        StateKey<Integer> key = StateKey.of("absent", Integer.class);
        GraphState state = GraphState.empty("t1");
        assertThat(state.get(key, 42)).isEqualTo(42);
        assertThat(state.get(key)).isNull();
    }

    @Test
    @DisplayName("StateKey with 返回新实例，原实例不变")
    void stateKeyWithReturnsNewInstance() {
        StateKey<String> key = StateKey.of("k", String.class);
        GraphState s1 = GraphState.empty("t1").with(key, "v1");
        GraphState s2 = s1.with(key, "v2");
        assertThat(s1.get(key)).isEqualTo("v1");
        assertThat(s2.get(key)).isEqualTo("v2");
        assertThat(s1).isNotSameAs(s2);
    }

    @Test
    @DisplayName("StateKeys 常量 — 实际 graph 运行键可用")
    void stateKeysConstantsWork() {
        GraphState state = GraphState.empty("t1")
            .with(StateKeys.SYSTEM_PROMPT, "你是诊断 agent")
            .with(StateKeys.USER_MESSAGE, "分析问题")
            .with(StateKeys.STOP_REASON, "end_turn");

        assertThat(state.get(StateKeys.SYSTEM_PROMPT)).isEqualTo("你是诊断 agent");
        assertThat(state.get(StateKeys.USER_MESSAGE)).isEqualTo("分析问题");
        assertThat(state.get(StateKeys.STOP_REASON)).isEqualTo("end_turn");
    }
}
