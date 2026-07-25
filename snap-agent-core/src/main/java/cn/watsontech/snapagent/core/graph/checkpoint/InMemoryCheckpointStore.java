package cn.watsontech.snapagent.core.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryCheckpointStore implements CheckpointStore {
    private final ConcurrentHashMap<String, GraphState> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CheckpointMetadata> metadata = new ConcurrentHashMap<>();

    @Override
    public String save(String threadId, GraphState state) {
        String id = UUID.randomUUID().toString();
        checkpoints.put(id, state);
        metadata.put(id, new CheckpointMetadata(id, threadId, state.getTurn(), System.currentTimeMillis(), null));
        return id;
    }

    @Override
    public GraphState load(String checkpointId) {
        return checkpoints.get(checkpointId);
    }

    @Override
    public List<CheckpointMetadata> list(String threadId) {
        return metadata.values().stream()
            .filter(m -> m.getThreadId().equals(threadId))
            .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(String checkpointId) {
        checkpoints.remove(checkpointId);
        metadata.remove(checkpointId);
    }

    @Override
    public void deleteByThread(String threadId) {
        metadata.values().stream()
            .filter(m -> m.getThreadId().equals(threadId))
            .map(CheckpointMetadata::getCheckpointId)
            .forEach(id -> { checkpoints.remove(id); metadata.remove(id); });
    }
}
