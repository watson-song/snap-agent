package cn.watsontech.snapagent.boot2x.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.HashOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCheckpointStoreTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private ZSetOperations<String, String> zsetOps;
    private CheckpointStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        store = new RedisCheckpointStore(redis);
    }

    @Test
    void shouldSaveCheckpointToRedis() {
        GraphState state = GraphState.empty("thread-1").with("key", "value");

        String id = store.save("thread-1", state);

        assertThat(id).isNotBlank();
        verify(hashOps).putAll(eq("snap-agent:ckpt:" + id), org.mockito.ArgumentMatchers.<Map<String, String>>any());
        verify(zsetOps).add(eq("snap-agent:ckpt-idx:thread-1"), eq(id), anyDouble());
    }

    @Test
    void shouldLoadCheckpointFromRedis() {
        GraphState original = GraphState.empty("thread-1").with("name", "test");
        String stateB64 = Base64.getEncoder().encodeToString(original.serialize());

        String id = "test-checkpoint-id";
        Map<Object, Object> fields = new HashMap<Object, Object>();
        fields.put("threadId", "thread-1");
        fields.put("turn", "0");
        fields.put("createdAt", "1234567890");
        fields.put("nodeName", "");
        fields.put("stateData", stateB64);

        when(hashOps.entries("snap-agent:ckpt:" + id)).thenReturn(fields);

        GraphState loaded = store.load(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.<String>get("name")).isEqualTo("test");
    }

    @Test
    void shouldReturnNullForUnknownCheckpoint() {
        when(hashOps.entries("snap-agent:ckpt:unknown")).thenReturn(Collections.emptyMap());
        assertThat(store.load("unknown")).isNull();
    }

    @Test
    void shouldListCheckpointsByThread() {
        Set<String> ids = new HashSet<String>(Arrays.asList("cp-1", "cp-2"));
        when(zsetOps.reverseRange("snap-agent:ckpt-idx:thread-1", 0, -1)).thenReturn(ids);

        Map<Object, Object> fields1 = new HashMap<Object, Object>();
        fields1.put("threadId", "thread-1");
        fields1.put("turn", "0");
        fields1.put("createdAt", "1000");
        fields1.put("nodeName", "");
        fields1.put("stateData", Base64.getEncoder().encodeToString(GraphState.empty("thread-1").serialize()));

        Map<Object, Object> fields2 = new HashMap<Object, Object>();
        fields2.put("threadId", "thread-1");
        fields2.put("turn", "1");
        fields2.put("createdAt", "2000");
        fields2.put("nodeName", "");
        fields2.put("stateData", Base64.getEncoder().encodeToString(GraphState.empty("thread-1").serialize()));

        when(hashOps.entries("snap-agent:ckpt:cp-1")).thenReturn(fields1);
        when(hashOps.entries("snap-agent:ckpt:cp-2")).thenReturn(fields2);

        List<CheckpointMetadata> list = store.list("thread-1");
        assertThat(list).hasSize(2);
    }

    @Test
    void shouldReturnEmptyListForUnknownThread() {
        when(zsetOps.reverseRange("snap-agent:ckpt-idx:unknown", 0, -1)).thenReturn(null);
        assertThat(store.list("unknown")).isEmpty();
    }

    @Test
    void shouldDeleteCheckpointAndCleanIndex() {
        Map<Object, Object> fields = new HashMap<Object, Object>();
        fields.put("threadId", "thread-1");
        when(hashOps.entries("snap-agent:ckpt:cp-1")).thenReturn(fields);

        store.delete("cp-1");

        verify(zsetOps).remove("snap-agent:ckpt-idx:thread-1", "cp-1");
        verify(redis).delete("snap-agent:ckpt:cp-1");
    }

    @Test
    void shouldDeleteAllCheckpointsByThread() {
        Set<String> ids = new HashSet<String>(Arrays.asList("cp-1", "cp-2"));
        when(zsetOps.range("snap-agent:ckpt-idx:thread-1", 0, -1)).thenReturn(ids);

        store.deleteByThread("thread-1");

        verify(redis).delete("snap-agent:ckpt:cp-1");
        verify(redis).delete("snap-agent:ckpt:cp-2");
        verify(redis).delete("snap-agent:ckpt-idx:thread-1");
    }

    @Test
    void shouldRejectNullRedisTemplate() {
        assertThatThrownBy(() -> new RedisCheckpointStore(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StringRedisTemplate");
    }
}
