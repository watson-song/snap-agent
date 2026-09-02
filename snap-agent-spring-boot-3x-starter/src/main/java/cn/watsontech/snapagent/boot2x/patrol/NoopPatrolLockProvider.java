package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.patrol.PatrolLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op default implementation of {@link PatrolLockProvider}.
 *
 * <p>Always returns {@code true} from {@link #tryAcquire} — meaning this Pod
 * always runs the patrol. Suitable for single-Pod deployments.</p>
 *
 * <p>For multi-Pod deployments, implement a custom {@link PatrolLockProvider}
 * using Redis {@code SET NX EX}, a database row lock, or Kubernetes Lease,
 * and register it as a Spring bean — this default will be skipped via
 * {@code @ConditionalOnMissingBean}.</p>
 */
public class NoopPatrolLockProvider implements PatrolLockProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopPatrolLockProvider.class);

    @Override
    public boolean tryAcquire(String patrolId, long ttlSeconds) {
        log.debug("NoopPatrolLockProvider: acquire {} (ttl={}s) — single-Pod mode, always grants",
                patrolId, ttlSeconds);
        return true;
    }

    @Override
    public void release(String patrolId) {
        log.debug("NoopPatrolLockProvider: release {}", patrolId);
    }

    @Override
    public String type() {
        return "noop";
    }
}
