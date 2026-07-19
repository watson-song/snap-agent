package cn.watsontech.snapagent.core.cost;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 每次 LLM 调用的成本记录。
 *
 * <p>Immutable value object: all fields are fixed at construction time.
 * The {@code id} field is auto-generated when a {@code null} id is passed
 * to the constructor, using the format
 * {@code "cost_" + System.currentTimeMillis() + "_" + random8}.</p>
 */
public final class CostRecord {

    private final String id;
    private final String userId;
    private final String skillName;
    private final String taskId;
    private final String model;
    private final long inputTokens;
    private final long outputTokens;
    private final long cacheReadTokens;
    private final BigDecimal cost;
    private final long timestamp;

    /**
     * Full-argument constructor.
     *
     * <p>When {@code id} is {@code null}, an identifier is auto-generated as
     * {@code "cost_" + System.currentTimeMillis() + "_" +
     * UUID.randomUUID().toString().substring(0, 8)}. This lets callers
     * create new records without pre-computing an id, while still allowing
     * storage layers to reconstruct records with their original id.</p>
     *
     * @param id              记录 ID (可空, 空=自动生成)
     * @param userId          用户 ID
     * @param skillName       Skill 名
     * @param taskId          关联任务 ID (可空)
     * @param model           模型名 (e.g. "claude-sonnet-4-20250514")
     * @param inputTokens     输入 token 数
     * @param outputTokens    输出 token 数
     * @param cacheReadTokens 缓存命中 token 数 (折价)
     * @param cost            计算费用
     * @param timestamp       记录时间 (epoch millis)
     */
    public CostRecord(String id, String userId, String skillName, String taskId,
                      String model, long inputTokens, long outputTokens,
                      long cacheReadTokens, BigDecimal cost, long timestamp) {
        this.id = id != null ? id : ("cost_" + System.currentTimeMillis()
                + "_" + UUID.randomUUID().toString().substring(0, 8));
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

    /** 记录 ID。 */
    public String getId() {
        return id;
    }

    /** 用户 ID。 */
    public String getUserId() {
        return userId;
    }

    /** Skill 名。 */
    public String getSkillName() {
        return skillName;
    }

    /** 关联任务 ID (可空)。 */
    public String getTaskId() {
        return taskId;
    }

    /** 模型名 (e.g. "claude-sonnet-4-20250514")。 */
    public String getModel() {
        return model;
    }

    /** 输入 token 数。 */
    public long getInputTokens() {
        return inputTokens;
    }

    /** 输出 token 数。 */
    public long getOutputTokens() {
        return outputTokens;
    }

    /** 缓存命中 token 数 (折价)。 */
    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    /** 计算费用。 */
    public BigDecimal getCost() {
        return cost;
    }

    /** 记录时间 (epoch millis)。 */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "CostRecord{id='" + id + "', userId='" + userId
                + "', skillName='" + skillName + "', taskId='" + taskId
                + "', model='" + model + "', inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens
                + ", cacheReadTokens=" + cacheReadTokens
                + ", cost=" + cost + ", timestamp=" + timestamp + "}";
    }
}
