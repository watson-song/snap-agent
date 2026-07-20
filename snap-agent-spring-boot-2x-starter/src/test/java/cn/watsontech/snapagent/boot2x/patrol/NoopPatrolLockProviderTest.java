package cn.watsontech.snapagent.boot2x.patrol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NoopPatrolLockProvider}.
 *
 * <p>The default {@link cn.watsontech.snapagent.core.patrol.PatrolLockProvider}
 * used when the host doesn't supply a distributed-lock implementation. Suitable
 * for single-Pod deployments; always grants and no-ops on release.</p>
 */
class NoopPatrolLockProviderTest {

    @Test
    @DisplayName("tryAcquire always returns true regardless of patrolId or ttl")
    void tryAcquireShouldAlwaysReturnTrue() {
        NoopPatrolLockProvider provider = new NoopPatrolLockProvider();

        assertThat(provider.tryAcquire("patrol_1", 300L)).isTrue();
        assertThat(provider.tryAcquire("patrol_2", 0L)).isTrue();
        assertThat(provider.tryAcquire(null, -1L)).isTrue();
        assertThat(provider.tryAcquire("", 60L)).isTrue();
    }

    @Test
    @DisplayName("release does not throw regardless of patrolId")
    void releaseShouldNotThrow() {
        NoopPatrolLockProvider provider = new NoopPatrolLockProvider();

        provider.release("patrol_1");
        provider.release(null);
        provider.release("");
    }

    @Test
    @DisplayName("type returns 'noop'")
    void typeShouldReturnNoop() {
        NoopPatrolLockProvider provider = new NoopPatrolLockProvider();

        assertThat(provider.type()).isEqualTo("noop");
    }

    @Test
    @DisplayName("repeated tryAcquire calls always succeed (no actual lock state)")
    void repeatedAcquireShouldAlwaysSucceed() {
        NoopPatrolLockProvider provider = new NoopPatrolLockProvider();

        for (int i = 0; i < 5; i++) {
            assertThat(provider.tryAcquire("patrol_same", 60L)).isTrue();
        }
        // Even without releasing, subsequent acquires still succeed — the noop
        // provider has no internal state, which is the documented contract.
        assertThat(provider.tryAcquire("patrol_same", 60L)).isTrue();
    }
}
