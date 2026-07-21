package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolReportStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation of {@link PatrolReportStore}.
 *
 * <p>Backed by an {@link ArrayBlockingQueue} ring buffer (default 500 entries)
 * with a {@link ConcurrentHashMap} for ID lookup. When the ring buffer is full,
 * the oldest report is evicted (polled) and removed from the lookup map before
 * the new report is stored.</p>
 *
 * <p><b>State is lost on Pod restart.</b> For persistence across restarts or
 * multi-Pod visibility, implement a custom {@link PatrolReportStore} (e.g.
 * database-backed) and register it as a Spring bean.</p>
 *
 * <p>Thread-safe.</p>
 */
public class InMemoryPatrolReportStore implements PatrolReportStore {

    private final int capacity;
    private final ArrayBlockingQueue<PatrolReport> ringBuffer;
    private final ConcurrentHashMap<String, PatrolReport> idIndex;

    public InMemoryPatrolReportStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.ringBuffer = new ArrayBlockingQueue<PatrolReport>(capacity);
        this.idIndex = new ConcurrentHashMap<String, PatrolReport>();
    }

    /**
     * Saves a patrol report. Auto-generates an ID if the report's id is null.
     * If the ring buffer is full, the oldest report is evicted first.
     *
     * <p>Synchronized to ensure the poll-evict-offer sequence is atomic,
     * preventing ring buffer / id index inconsistency under concurrent access.</p>
     */
    @Override
    public synchronized void save(PatrolReport report) {
        if (report.getId() == null || report.getId().isEmpty()) {
            report.setId(generateId());
        }

        // If at capacity, evict oldest
        if (ringBuffer.size() >= capacity) {
            PatrolReport oldest = ringBuffer.poll();
            if (oldest != null) {
                idIndex.remove(oldest.getId());
            }
        }

        ringBuffer.offer(report);
        idIndex.put(report.getId(), report);
    }

    /**
     * Returns reports for a given user sorted by triggeredAt descending, with
     * pagination. Reports with null userId are system-generated and visible
     * to all users.
     */
    @Override
    public List<PatrolReport> getReports(String userId, int limit, int offset) {
        List<PatrolReport> all = new ArrayList<PatrolReport>(ringBuffer);
        // Filter by userId (null userId on report = system-generated, visible to all)
        if (userId != null) {
            List<PatrolReport> filtered = new ArrayList<PatrolReport>();
            for (PatrolReport r : all) {
                if (r.getUserId() == null || userId.equals(r.getUserId())) {
                    filtered.add(r);
                }
            }
            all = filtered;
        }
        Collections.sort(all, new Comparator<PatrolReport>() {
            @Override
            public int compare(PatrolReport a, PatrolReport b) {
                return Long.compare(b.getTriggeredAt(), a.getTriggeredAt());
            }
        });

        int fromIndex = Math.min(offset, all.size());
        int toIndex = Math.min(fromIndex + limit, all.size());
        return all.subList(fromIndex, toIndex);
    }

    /**
     * Returns the total number of reports for a given user.
     * Reports with null userId are system-generated and counted for all users.
     */
    @Override
    public long count(String userId) {
        if (userId == null) {
            return ringBuffer.size();
        }
        long c = 0;
        for (PatrolReport r : ringBuffer) {
            if (r.getUserId() == null || userId.equals(r.getUserId())) {
                c++;
            }
        }
        return c;
    }

    @Override
    public PatrolReport get(String reportId) {
        return idIndex.get(reportId);
    }

    private String generateId() {
        return "pr_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
