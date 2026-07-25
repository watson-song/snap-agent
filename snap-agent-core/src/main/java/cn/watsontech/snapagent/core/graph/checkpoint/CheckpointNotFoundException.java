package cn.watsontech.snapagent.core.graph.checkpoint;

/**
 * Thrown when attempting to resume from a checkpoint that does not exist
 * in the {@link CheckpointStore}.
 */
public class CheckpointNotFoundException extends RuntimeException {
    private final String checkpointId;

    public CheckpointNotFoundException(String checkpointId) {
        super("checkpoint not found: " + checkpointId);
        this.checkpointId = checkpointId;
    }

    public String getCheckpointId() {
        return checkpointId;
    }
}
