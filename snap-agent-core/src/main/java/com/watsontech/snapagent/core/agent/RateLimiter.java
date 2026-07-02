package com.watsontech.snapagent.core.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter enforcing per-user concurrent run limits and hourly run counts.
 *
 * <ul>
 *   <li>Per-user concurrency: {@code ConcurrentHashMap<userId, AtomicInteger>} —
 *       incremented on acquire, decremented on release.</li>
 *   <li>Hourly count: in-memory {@code Map<userId, AtomicInteger>} with a rolling
 *       hour window. When the hour rolls over, counters reset.
 *       Uses CAS loop for atomic check-and-increment to prevent TOCTOU.</li>
 * </ul>
 */
public class RateLimiter {

    private final int maxConcurrentPerUser;
    private final int maxRunsPerHour;

    private final ConcurrentHashMap<String, AtomicInteger> concurrentCounts =
            new ConcurrentHashMap<String, AtomicInteger>();
    private final ConcurrentHashMap<String, AtomicInteger> hourlyCounts =
            new ConcurrentHashMap<String, AtomicInteger>();

    private volatile long currentHourStart;

    /**
     * @param maxConcurrentPerUser max concurrent running tasks per user
     * @param maxRunsPerHour       max tasks per user per hour
     */
    public RateLimiter(int maxConcurrentPerUser, int maxRunsPerHour) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.maxRunsPerHour = maxRunsPerHour;
        this.currentHourStart = currentHourMillis();
    }

    /**
     * Attempts to acquire a run slot for the given user.
     *
     * @return {@code true} if both concurrency and hourly limits allow a new run
     */
    public boolean tryAcquire(String userId) {
        if (userId == null) {
            return false;
        }
        rolloverHourIfNeeded();

        // Atomically check and increment hourly count (prevents TOCTOU)
        AtomicInteger hourly = hourlyCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        while (true) {
            int current = hourly.get();
            if (current >= maxRunsPerHour) {
                return false;
            }
            if (hourly.compareAndSet(current, current + 1)) {
                break; // Hourly slot acquired
            }
        }

        // Check concurrency limit
        AtomicInteger concurrent = concurrentCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        while (true) {
            int current = concurrent.get();
            if (current >= maxConcurrentPerUser) {
                // Roll back hourly increment since concurrency check failed
                hourly.decrementAndGet();
                return false;
            }
            if (concurrent.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Releases a concurrency slot after a task finishes (hourly count stays). */
    public void release(String userId) {
        if (userId == null) {
            return;
        }
        AtomicInteger concurrent = concurrentCounts.get(userId);
        if (concurrent != null) {
            concurrent.decrementAndGet();
        }
    }

    /**
     * Rolls back both concurrency and hourly counts for a task that was accepted
     * by {@link #tryAcquire} but never actually executed (e.g. the thread pool
     * rejected the submission). Without this, a rejected task permanently leaks
     * one slot from the hourly quota.
     */
    public void releaseRejected(String userId) {
        if (userId == null) {
            return;
        }
        AtomicInteger concurrent = concurrentCounts.get(userId);
        if (concurrent != null) {
            concurrent.decrementAndGet();
        }
        AtomicInteger hourly = hourlyCounts.get(userId);
        if (hourly != null) {
            hourly.decrementAndGet();
        }
    }

    /** Returns the current concurrent run count for a user. */
    public int getConcurrentCount(String userId) {
        AtomicInteger count = concurrentCounts.get(userId);
        return count == null ? 0 : count.get();
    }

    /** Returns the hourly run count for a user in the current hour window. */
    public long getHourlyCount(String userId) {
        rolloverHourIfNeeded();
        AtomicInteger count = hourlyCounts.get(userId);
        return count == null ? 0 : count.get();
    }

    private void rolloverHourIfNeeded() {
        long hourStart = currentHourMillis();
        if (hourStart != currentHourStart) {
            synchronized (this) {
                if (currentHourMillis() != currentHourStart) {
                    currentHourStart = currentHourMillis();
                    hourlyCounts.clear();
                }
            }
        }
    }

    private static long currentHourMillis() {
        long now = System.currentTimeMillis();
        return now - (now % (60L * 60L * 1000L));
    }

    int getMaxRunsPerHour() {
        return maxRunsPerHour;
    }

    int getMaxConcurrentPerUser() {
        return maxConcurrentPerUser;
    }
}
