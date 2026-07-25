package cn.watsontech.snapagent.core.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import java.util.List;

public interface CheckpointStore {
    String save(String threadId, GraphState state);
    GraphState load(String checkpointId);
    List<CheckpointMetadata> list(String threadId);
    void delete(String checkpointId);
    void deleteByThread(String threadId);
}
