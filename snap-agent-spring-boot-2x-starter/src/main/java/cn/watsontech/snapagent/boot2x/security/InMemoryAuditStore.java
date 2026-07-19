package cn.watsontech.snapagent.boot2x.security;

import cn.watsontech.snapagent.core.security.AuditEntry;
import cn.watsontech.snapagent.core.security.AuditStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * In-memory ring-buffer implementation of {@link AuditStore}.
 * Evicts oldest entries when capacity is reached.
 */
public class InMemoryAuditStore implements AuditStore {

    private final ArrayBlockingQueue<AuditEntry> buffer;

    public InMemoryAuditStore(int capacity) {
        this.buffer = new ArrayBlockingQueue<AuditEntry>(capacity);
    }

    @Override
    public void record(AuditEntry entry) {
        if (entry == null) return;
        if (!buffer.offer(entry)) {
            buffer.poll();
            buffer.offer(entry);
        }
    }

    @Override
    public List<AuditEntry> query(String userId, String action, int limit, int offset) {
        List<AuditEntry> matched = new ArrayList<AuditEntry>();
        for (AuditEntry e : buffer) {
            if (userId != null && !userId.equals(e.getUserId())) continue;
            if (action != null && !action.equals(e.getAction())) continue;
            matched.add(e);
        }
        matched.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        if (offset >= matched.size()) return Collections.emptyList();
        int end = Math.min(offset + limit, matched.size());
        return new ArrayList<AuditEntry>(matched.subList(offset, end));
    }

    @Override
    public int count(String userId, String action) {
        int count = 0;
        for (AuditEntry e : buffer) {
            if (userId != null && !userId.equals(e.getUserId())) continue;
            if (action != null && !action.equals(e.getAction())) continue;
            count++;
        }
        return count;
    }
}
