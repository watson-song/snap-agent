package cn.watsontech.snapagent.boot2x.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed {@link CheckpointStore} for cluster deployments.
 *
 * <p>Uses {@link StringRedisTemplate} for maximum compatibility (works with
 * any Redis setup that supports spring-data-redis). Stores the serialized
 * GraphState as base64 in a Redis hash alongside metadata.</p>
 *
 * <p>Key layout:</p>
 * <ul>
 *   <li>{@code snap-agent:ckpt:{checkpointId}} — hash: turn, createdAt, nodeName, stateData (base64)</li>
 *   <li>{@code snap-agent:ckpt-idx:{threadId}} — sorted set of checkpointIds, scored by createdAt</li>
 * </ul>
 */
public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointStore.class);
    private static final String CKPT_PREFIX = "snap-agent:ckpt:";
    private static final String INDEX_PREFIX = "snap-agent:ckpt-idx:";

    private final StringRedisTemplate redis;

    public RedisCheckpointStore(StringRedisTemplate redis) {
        if (redis == null) {
            throw new IllegalArgumentException("StringRedisTemplate must not be null");
        }
        this.redis = redis;
    }

    @Override
    public String save(String threadId, GraphState state) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String stateB64 = Base64.getEncoder().encodeToString(state.serialize());

        Map<String, String> fields = new HashMap<String, String>();
        fields.put("threadId", threadId);
        fields.put("turn", String.valueOf(state.getTurn()));
        fields.put("createdAt", String.valueOf(now));
        fields.put("nodeName", "");
        fields.put("stateData", stateB64);

        redis.opsForHash().putAll(CKPT_PREFIX + id, fields);
        redis.opsForZSet().add(INDEX_PREFIX + threadId, id, now);

        log.debug("Saved checkpoint {} for thread {} in Redis", id, threadId);
        return id;
    }

    @Override
    public GraphState load(String checkpointId) {
        Map<Object, Object> fields = redis.opsForHash().entries(CKPT_PREFIX + checkpointId);
        if (fields.isEmpty()) {
            return null;
        }
        String stateB64 = (String) fields.get("stateData");
        if (stateB64 == null || stateB64.isEmpty()) {
            return null;
        }
        byte[] data = Base64.getDecoder().decode(stateB64);
        return GraphState.deserialize(data);
    }

    @Override
    public List<CheckpointMetadata> list(String threadId) {
        // Get checkpoint IDs from sorted set in reverse order (most recent first)
        Set<String> ids = redis.opsForZSet().reverseRange(INDEX_PREFIX + threadId, 0, -1);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<CheckpointMetadata> result = new ArrayList<CheckpointMetadata>();
        for (String id : ids) {
            Map<Object, Object> fields = redis.opsForHash().entries(CKPT_PREFIX + id);
            if (!fields.isEmpty()) {
                result.add(new CheckpointMetadata(
                        id,
                        threadId,
                        Integer.parseInt(String.valueOf(fields.get("turn"))),
                        Long.parseLong(String.valueOf(fields.get("createdAt"))),
                        (String) fields.get("nodeName")
                ));
            }
        }
        return result;
    }

    @Override
    public void delete(String checkpointId) {
        // Get threadId first to clean up the index
        Map<Object, Object> fields = redis.opsForHash().entries(CKPT_PREFIX + checkpointId);
        if (!fields.isEmpty()) {
            String threadId = (String) fields.get("threadId");
            if (threadId != null) {
                redis.opsForZSet().remove(INDEX_PREFIX + threadId, checkpointId);
            }
        }
        redis.delete(CKPT_PREFIX + checkpointId);
    }

    @Override
    public void deleteByThread(String threadId) {
        Set<String> ids = redis.opsForZSet().range(INDEX_PREFIX + threadId, 0, -1);
        if (ids != null) {
            for (String id : ids) {
                redis.delete(CKPT_PREFIX + id);
            }
        }
        redis.delete(INDEX_PREFIX + threadId);
    }
}
