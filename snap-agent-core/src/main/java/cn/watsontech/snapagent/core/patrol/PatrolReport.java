package cn.watsontech.snapagent.core.patrol;

/**
 * A single patrol execution report produced after a patrol task runs.
 */
public class PatrolReport {

    private String id;
    private String patrolId;
    private String taskId;
    private String skillName;
    private long triggeredAt;
    private String status;
    private String summary;
    private boolean anomalyDetected;

    public PatrolReport() {
    }

    public PatrolReport(String id, String patrolId, String taskId, String skillName,
                        long triggeredAt, String status, String summary,
                        boolean anomalyDetected) {
        this.id = id;
        this.patrolId = patrolId;
        this.taskId = taskId;
        this.skillName = skillName;
        this.triggeredAt = triggeredAt;
        this.status = status;
        this.summary = summary;
        this.anomalyDetected = anomalyDetected;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPatrolId() { return patrolId; }
    public void setPatrolId(String patrolId) { this.patrolId = patrolId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public long getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(long triggeredAt) { this.triggeredAt = triggeredAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public boolean isAnomalyDetected() { return anomalyDetected; }
    public void setAnomalyDetected(boolean anomalyDetected) {
        this.anomalyDetected = anomalyDetected;
    }

    @Override
    public String toString() {
        return "PatrolReport{id='" + id + "', patrolId='" + patrolId
                + "', status='" + status + "', anomalyDetected=" + anomalyDetected + "}";
    }
}
