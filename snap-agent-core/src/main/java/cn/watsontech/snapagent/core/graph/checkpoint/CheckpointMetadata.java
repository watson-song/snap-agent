package cn.watsontech.snapagent.core.graph.checkpoint;

public class CheckpointMetadata {
    private final String checkpointId;
    private final String threadId;
    private final int turn;
    private final long createdAt;
    private final String nodeName;

    public CheckpointMetadata(String checkpointId, String threadId, int turn, long createdAt, String nodeName) {
        this.checkpointId = checkpointId;
        this.threadId = threadId;
        this.turn = turn;
        this.createdAt = createdAt;
        this.nodeName = nodeName;
    }

    public String getCheckpointId() { return checkpointId; }
    public String getThreadId() { return threadId; }
    public int getTurn() { return turn; }
    public long getCreatedAt() { return createdAt; }
    public String getNodeName() { return nodeName; }
}
