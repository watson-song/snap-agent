package cn.watsontech.snapagent.core.patrol;

/**
 * Distributed lock SPI for multi-Pod patrol coordination.
 *
 * <p>When multiple Pods run the same cron patrol, this SPI prevents duplicate
 * execution: only the Pod that successfully acquires the lock runs the patrol,
 * others skip. The lock must auto-expire (via TTL) so that a crashed Pod does
 * not permanently block the patrol.</p>
 *
 * <p><b>Default implementation: {@code NoopPatrolLockProvider}</b> — always
 * acquires (single-Pod mode). Hosts deploying to multi-Pod environments should
 * implement this with Redis {@code SET NX EX}, a database row lock, or a
 * Kubernetes Lease / ConfigMap lock, and register it as a Spring bean — the
 * default will be skipped via {@code @ConditionalOnMissingBean}.</p>
 *
 * <p>Acquire is called by the patrol scheduler at each cron tick, before
 * running the skill. Release is called in a {@code finally} block after the
 * patrol completes (whether it succeeds, fails, or throws).</p>
 *
 * @see cn.watsontech.snapagent.boot2x.patrol.NoopPatrolLockProvider
 */
public interface PatrolLockProvider {

    /**
     * Attempts to acquire a lock for the given patrol ID.
     *
     * @param patrolId    the patrol task ID (used as the lock key)
     * @param ttlSeconds  lock time-to-live; if the holder crashes, the lock
     *                    auto-expires after this many seconds
     * @return {@code true} if this Pod acquired the lock and should run the
     *         patrol; {@code false} if another Pod already holds it (this Pod
     *         should skip the current tick)
     */
    boolean tryAcquire(String patrolId, long ttlSeconds);

    /**
     * Releases the lock held by this Pod for the given patrol ID.
     *
     * <p>Implementations should be idempotent — releasing a lock not held by
     * this Pod must be a no-op (not an exception).</p>
     *
     * @param patrolId the patrol task ID
     */
    void release(String patrolId);

    /**
     * Returns the implementation type identifier (e.g. {@code "noop"},
     * {@code "redis"}, {@code "k8s-lease"}). Used for logging and the
     * {@code /patrol/status} endpoint.
     */
    default String type() {
        return "noop";
    }
}
