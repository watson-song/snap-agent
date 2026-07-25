package cn.watsontech.snapagent.core.graph.hitl;

import java.util.Map;

public class InterruptException extends Exception {
    private final Map<String, Object> checkpointPayload;

    public InterruptException(Map<String, Object> checkpointPayload) {
        super("interrupted");
        this.checkpointPayload = checkpointPayload;
    }

    public Map<String, Object> getCheckpointPayload() {
        return checkpointPayload;
    }
}
