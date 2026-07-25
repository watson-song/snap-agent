package cn.watsontech.snapagent.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 不可变图状态。每次 with()/nextTurn() 返回新实例。
 * 多线程并发访问安全。
 */
public class GraphState {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> values;
    private final String threadId;
    private final String checkpointId;
    private final int turn;

    private GraphState(Map<String, Object> values, String threadId, String checkpointId, int turn) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
        this.threadId = threadId;
        this.checkpointId = checkpointId;
        this.turn = turn;
    }

    public static GraphState empty(String threadId) {
        return new GraphState(new HashMap<>(), threadId, null, 0);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) values.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        return (T) values.getOrDefault(key, defaultValue);
    }

    public GraphState with(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(this.values);
        copy.put(key, value);
        return new GraphState(copy, this.threadId, this.checkpointId, this.turn);
    }

    public GraphState nextTurn() {
        return new GraphState(this.values, this.threadId, this.checkpointId, this.turn + 1);
    }

    public int getTurn() {
        return turn;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public GraphState withCheckpointId(String checkpointId) {
        return new GraphState(this.values, this.threadId, checkpointId, this.turn);
    }

    public byte[] serialize() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("values", new HashMap<>(this.values));
            data.put("threadId", this.threadId);
            data.put("checkpointId", this.checkpointId);
            data.put("turn", this.turn);
            return MAPPER.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new RuntimeException("GraphState serialize failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static GraphState deserialize(byte[] data) {
        try {
            Map<String, Object> map = MAPPER.readValue(data, Map.class);
            Map<String, Object> values = (Map<String, Object>) map.getOrDefault("values", new HashMap<>());
            String threadId = (String) map.get("threadId");
            String checkpointId = (String) map.get("checkpointId");
            int turn = (Integer) map.getOrDefault("turn", 0);
            return new GraphState(values, threadId, checkpointId, turn);
        } catch (Exception e) {
            throw new RuntimeException("GraphState deserialize failed", e);
        }
    }
}
