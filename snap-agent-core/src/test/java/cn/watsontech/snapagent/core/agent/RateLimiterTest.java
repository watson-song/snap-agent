package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void shouldAcquireWhenUnderConcurrencyLimit() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.getConcurrentCount("u1")).isEqualTo(1);
    }

    @Test
    void shouldRejectWhenConcurrencyLimitReached() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u1")).isFalse();
    }

    @Test
    void shouldNotAffectOtherUsersWhenOneUserAtLimit() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u2")).isTrue();
    }

    @Test
    void shouldAllowAgainAfterRelease() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.tryAcquire("u1")).isTrue();
    }

    @Test
    void shouldRejectWhenHourlyLimitReached() {
        RateLimiter limiter = new RateLimiter(10, 2);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.tryAcquire("u1")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenUserIdNull() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.tryAcquire(null)).isFalse();
    }

    @Test
    void shouldReturnZeroConcurrentWhenUserUnknown() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.getConcurrentCount("unknown")).isZero();
    }

    @Test
    void shouldReturnZeroHourlyWhenUserUnknown() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.getHourlyCount("unknown")).isZero();
    }

    @Test
    void shouldNotGoNegativeWhenReleaseCalledWithoutAcquire() {
        RateLimiter limiter = new RateLimiter(1, 20);

        limiter.release("u1"); // no-op, should not go negative

        assertThat(limiter.getConcurrentCount("u1")).isZero();
    }

    @Test
    void shouldAllowMultipleUsersConcurrently() {
        RateLimiter limiter = new RateLimiter(1, 20);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u2")).isTrue();
        assertThat(limiter.tryAcquire("u3")).isTrue();
        assertThat(limiter.getConcurrentCount("u1")).isEqualTo(1);
        assertThat(limiter.getConcurrentCount("u2")).isEqualTo(1);
        assertThat(limiter.getConcurrentCount("u3")).isEqualTo(1);
    }

    @Test
    void shouldReturnConfiguredLimits() {
        RateLimiter limiter = new RateLimiter(3, 50);

        assertThat(limiter.getMaxConcurrentPerUser()).isEqualTo(3);
        assertThat(limiter.getMaxRunsPerHour()).isEqualTo(50);
    }

    @Test
    void shouldRollbackBothConcurrencyAndHourlyOnReleaseRejected() {
        RateLimiter limiter = new RateLimiter(5, 3);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.getConcurrentCount("u1")).isEqualTo(1);
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);

        // Simulate a rejected execution — both counters must roll back
        limiter.releaseRejected("u1");

        assertThat(limiter.getConcurrentCount("u1")).isZero();
        assertThat(limiter.getHourlyCount("u1")).isZero();
    }

    @Test
    void shouldNotLeakHourlyQuotaWhenRejected() {
        RateLimiter limiter = new RateLimiter(5, 2);

        // Acquire, get rejected, releaseRejected — should not consume hourly quota
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.releaseRejected("u1");
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.releaseRejected("u1");
        // Third acquire should still succeed because the first two were rolled back
        assertThat(limiter.tryAcquire("u1")).isTrue();
    }

    @Test
    void shouldKeepHourlyCountWhenNormalRelease() {
        RateLimiter limiter = new RateLimiter(5, 2);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1"); // normal release — hourly stays
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);
        assertThat(limiter.getConcurrentCount("u1")).isZero();
    }
}
