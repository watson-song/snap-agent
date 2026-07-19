package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory AlertConverger using fingerprint-based deduplication.
 *
 * <p>Fingerprint = SHA-256(type|source) — same type+source converges into one alert.
 * Ring-buffer eviction when capacity is reached. Auto-resolves alerts that haven't
 * been updated within {@code autoResolveMinutes} (checked lazily on query).</p>
 */
public class InMemoryAlertConverger implements AlertConverger {

    private final ArrayBlockingQueue<String> ringBuffer;
    private final ConcurrentHashMap<String, AlertConvergence> alerts = new ConcurrentHashMap<>();
    private final int autoResolveMinutes;
    private final AtomicLong idCounter = new AtomicLong(0);

    public InMemoryAlertConverger(int capacity, int autoResolveMinutes) {
        this.ringBuffer = new ArrayBlockingQueue<>(capacity);
        this.autoResolveMinutes = autoResolveMinutes;
    }

    @Override
    public AlertConvergence record(AnomalyEvent event) {
        String fingerprint = computeFingerprint(event);
        synchronized (fingerprint.intern()) {
            AlertConvergence existing = findActiveByFingerprint(fingerprint);
            if (existing != null) {
                existing.incrementCount();
                existing.setLastSeen(event.getTimestamp());
                return existing;
            }
            String id = "alert_" + idCounter.incrementAndGet();
            AlertConvergence alert = new AlertConvergence(
                    id, fingerprint, event.getType(), event.getSource(),
                    event.getMessage(), null);
            alert.setFirstSeen(event.getTimestamp());
            alert.setLastSeen(event.getTimestamp());
            alerts.put(id, alert);
            if (!ringBuffer.offer(id)) {
                String evicted = ringBuffer.poll();
                if (evicted != null) {
                    alerts.remove(evicted);
                }
                ringBuffer.offer(id);
            }
            return alert;
        }
    }

    private AlertConvergence findActiveByFingerprint(String fingerprint) {
        for (AlertConvergence alert : alerts.values()) {
            if (fingerprint.equals(alert.getFingerprint())
                    && AlertConvergence.STATUS_ACTIVE.equals(alert.getStatus())) {
                return alert;
            }
        }
        return null;
    }

    private void autoResolveStale() {
        long threshold = System.currentTimeMillis() - (autoResolveMinutes * 60_000L);
        for (AlertConvergence alert : alerts.values()) {
            if (AlertConvergence.STATUS_ACTIVE.equals(alert.getStatus())
                    && alert.getLastSeen() < threshold) {
                alert.setStatus(AlertConvergence.STATUS_RESOLVED);
            }
        }
    }

    @Override
    public List<AlertConvergence> query(String userId, String type, int limit, int offset) {
        autoResolveStale();
        List<AlertConvergence> all = new ArrayList<>(alerts.values());
        Collections.sort(all, (a, b) -> Long.compare(b.getLastSeen(), a.getLastSeen()));
        if (type != null && !type.isEmpty()) {
            List<AlertConvergence> filtered = new ArrayList<>();
            for (AlertConvergence a : all) {
                if (type.equals(a.getType())) {
                    filtered.add(a);
                }
            }
            all = filtered;
        }
        if (offset >= all.size()) {
            return Collections.emptyList();
        }
        int end = Math.min(offset + limit, all.size());
        return new ArrayList<>(all.subList(offset, end));
    }

    @Override
    public long count(String userId, String type) {
        if (type == null || type.isEmpty()) {
            return alerts.size();
        }
        long c = 0;
        for (AlertConvergence a : alerts.values()) {
            if (type.equals(a.getType())) {
                c++;
            }
        }
        return c;
    }

    @Override
    public void resolve(String alertId) {
        AlertConvergence alert = alerts.get(alertId);
        if (alert != null) {
            alert.setStatus(AlertConvergence.STATUS_RESOLVED);
        }
    }

    private String computeFingerprint(AnomalyEvent event) {
        String key = event.getType() + "|" + event.getSource();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(key.hashCode());
        }
    }
}
