package cn.watsontech.snapagent.core.cost;

/**
 * Immutable record of a single LLM cost event.
 */
public class CostRecord {

    private final String userId;
    private final String skillName;
    private final String taskId;
    private final String model;
    private final long inputTokens;
    private final long outputTokens;
    private final long cacheReadTokens;
    private final double cost;
    private final long timestamp;

    public CostRecord(String userId, String skillName, String taskId, String model,
                      long inputTokens, long outputTokens, long cacheReadTokens,
                      double cost, long timestamp) {
        this.userId = userId;
        this.skillName = skillName;
        this.taskId = taskId;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cost = cost;
        this.timestamp = timestamp;
    }

    public String getUserId() { return userId; }
    public String getSkillName() { return skillName; }
    public String getTaskId() { return taskId; }
    public String getModel() { return model; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public double getCost() { return cost; }
    public long getTimestamp() { return timestamp; }
}
