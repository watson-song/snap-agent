package cn.watsontech.snapagent.core.patrol;

/**
 * Represents a converged alert that deduplicates repeated anomaly events of
 * the same type and source.
 */
public class AlertConvergence {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RESOLVED = "RESOLVED";

    private String id;
    private String fingerprint;
    private String type;
    private String source;
    private String firstMessage;
    private int count;
    private long firstSeen;
    private long lastSeen;
    private String status;
    private String relatedTaskId;

    public AlertConvergence() {
        this.count = 1;
        this.status = STATUS_ACTIVE;
        long now = System.currentTimeMillis();
        this.firstSeen = now;
        this.lastSeen = now;
    }

    public AlertConvergence(String id, String fingerprint, String type, String source,
                            String firstMessage, String relatedTaskId) {
        this.id = id;
        this.fingerprint = fingerprint;
        this.type = type;
        this.source = source;
        this.firstMessage = firstMessage;
        this.count = 1;
        this.status = STATUS_ACTIVE;
        long now = System.currentTimeMillis();
        this.firstSeen = now;
        this.lastSeen = now;
        this.relatedTaskId = relatedTaskId;
    }

    /**
     * Increments the occurrence count and updates {@code lastSeen} to now.
     */
    public void incrementCount() {
        this.count++;
        this.lastSeen = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFirstMessage() { return firstMessage; }
    public void setFirstMessage(String firstMessage) { this.firstMessage = firstMessage; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public long getFirstSeen() { return firstSeen; }
    public void setFirstSeen(long firstSeen) { this.firstSeen = firstSeen; }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRelatedTaskId() { return relatedTaskId; }
    public void setRelatedTaskId(String relatedTaskId) { this.relatedTaskId = relatedTaskId; }

    @Override
    public String toString() {
        return "AlertConvergence{id='" + id + "', type='" + type + "', count=" + count
                + ", status='" + status + "'}";
    }
}
