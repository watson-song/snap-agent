package cn.watsontech.snapagent.core.patrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for {@link PatrolReport} instances using a ring buffer backed
 * by {@link ArrayBlockingQueue} with a {@link ConcurrentHashMap} for ID lookup.
 *
 * <p>When the ring buffer is full, the oldest report is evicted (polled) and
 * removed from the lookup map before the new report is stored.</p>
 *
 * <p>Thread-safe.</p>
 */
public class PatrolReportStore {

    private final int capacity;
    private final ArrayBlockingQueue<PatrolReport> ringBuffer;
    private final ConcurrentHashMap<String, PatrolReport> idIndex;

    public PatrolReportStore(int capacity) {
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
     *
     * @param userId the user ID for filtering (null returns all reports)
     * @param limit  maximum number of reports to return
     * @param offset number of reports to skip
     */
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

    /**
     * Retrieves a single report by its ID.
     *
     * @param reportId the report ID
     * @return the report, or null if not found
     */
    public PatrolReport get(String reportId) {
        return idIndex.get(reportId);
    }

    private String generateId() {
        return "pr_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
