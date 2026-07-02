package com.watsontech.snapagent.core.agent;

import com.watsontech.snapagent.core.llm.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runtime state of a single agent diagnostic run.
 *
 * <p>Thread-safe: {@code status} and {@code updatedAt} are volatile and updated
 * atomically via a synchronized setter; the transcript and audit lists are
 * synchronized with bounded capacity.</p>
 */
public class AgentTask {

    private static final int DEFAULT_TRANSCRIPT_LIMIT = 500;
    private static final int DEFAULT_AUDIT_LIMIT = 1000;

    private final String taskId;
    private final String userId;
    private final String skillId;
    private final Map<String, String> inputs;
    private final String model;
    private final long createdAt;
    private final List<Message> history;

    private volatile TaskStatus status;
    private final List<TranscriptEvent> transcript;
    private final List<AuditRecord> auditRecords;
    private volatile String report;
    private volatile long updatedAt;

    private final int transcriptLimit;
    private final int auditLimit;

    /** Streaming queue for real-time SSE push (token-by-token). */
    private final LinkedBlockingQueue<TranscriptEvent> streamQueue =
            new LinkedBlockingQueue<TranscriptEvent>(2000);

    public AgentTask(String taskId, String userId, String skillId,
                     Map<String, String> inputs, String model) {
        this(taskId, userId, skillId, inputs, model, null);
    }

    public AgentTask(String taskId, String userId, String skillId,
                     Map<String, String> inputs, String model,
                     List<Message> history) {
        this(taskId, userId, skillId, inputs, model,
                DEFAULT_TRANSCRIPT_LIMIT, DEFAULT_AUDIT_LIMIT, history);
    }

    public AgentTask(String taskId, String userId, String skillId,
                     Map<String, String> inputs, String model,
                     int transcriptLimit, int auditLimit) {
        this(taskId, userId, skillId, inputs, model,
                transcriptLimit, auditLimit, null);
    }

    public AgentTask(String taskId, String userId, String skillId,
                     Map<String, String> inputs, String model,
                     int transcriptLimit, int auditLimit,
                     List<Message> history) {
        this.taskId = taskId;
        this.userId = userId;
        this.skillId = skillId;
        this.inputs = inputs == null ? new ConcurrentHashMap<String, String>()
                                     : new ConcurrentHashMap<String, String>(inputs);
        this.model = model;
        this.createdAt = System.currentTimeMillis();
        this.history = history != null ? new ArrayList<Message>(history) : null;
        this.status = TaskStatus.PENDING;
        this.transcript = Collections.synchronizedList(new ArrayList<TranscriptEvent>());
        this.auditRecords = Collections.synchronizedList(new ArrayList<AuditRecord>());
        this.report = null;
        this.updatedAt = this.createdAt;
        this.transcriptLimit = transcriptLimit;
        this.auditLimit = auditLimit;
    }

    /** Creates a new task with a generated id (format: sa_{timestamp}_{random12}). */
    public static AgentTask create(String userId, String skillId,
                                   Map<String, String> inputs, String model) {
        String id = "sa_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new AgentTask(id, userId, skillId, inputs, model);
    }

    public static AgentTask create(String userId, String skillId,
                                   Map<String, String> inputs, String model,
                                   List<Message> history) {
        String id = "sa_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new AgentTask(id, userId, skillId, inputs, model, history);
    }

    public void addTranscriptEvent(TranscriptEvent event) {
        synchronized (transcript) {
            if (transcript.size() < transcriptLimit) {
                transcript.add(event);
            }
        }
        this.updatedAt = System.currentTimeMillis();
        streamQueue.offer(event);
    }

    /**
     * Polls the streaming queue for real-time events (token-by-token streaming).
     * Returns null if no event is available within the timeout.
     */
    public TranscriptEvent pollStreamEvent(long timeoutMs) {
        try {
            return streamQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Drains all pending stream events without blocking. */
    public List<TranscriptEvent> drainStreamEvents() {
        List<TranscriptEvent> events = new ArrayList<TranscriptEvent>();
        streamQueue.drainTo(events);
        return events;
    }

    public List<TranscriptEvent> getTranscript() {
        synchronized (transcript) {
            return new ArrayList<TranscriptEvent>(transcript);
        }
    }

    public void addAuditRecord(AuditRecord record) {
        synchronized (auditRecords) {
            if (auditRecords.size() < auditLimit) {
                auditRecords.add(record);
            }
        }
    }

    public List<AuditRecord> getAuditRecords() {
        synchronized (auditRecords) {
            return new ArrayList<AuditRecord>(auditRecords);
        }
    }

    /** Atomically sets status and updatedAt to ensure consistent visibility. */
    public synchronized void setStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }

    /** Atomically sets report and updatedAt to ensure consistent visibility. */
    public synchronized void setReport(String report) {
        this.report = report;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public String getSkillId() { return skillId; }
    public Map<String, String> getInputs() { return inputs; }
    public String getModel() { return model; }
    public List<Message> getHistory() { return history; }
    public long getCreatedAt() { return createdAt; }
    public TaskStatus getStatus() { return status; }
    public String getReport() { return report; }
    public long getUpdatedAt() { return updatedAt; }
    public int getTranscriptLimit() { return transcriptLimit; }

    @Override
    public String toString() {
        return "AgentTask{taskId='" + taskId + "', status=" + status + "'}";
    }
}
