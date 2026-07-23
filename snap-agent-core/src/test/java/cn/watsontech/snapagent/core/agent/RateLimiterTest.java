package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

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

    // ---- GAP-7: rolloverHourIfNeeded (hour window rollover) ----

    @Test
    void shouldResetHourlyCountWhenHourWindowRollsOver() throws Exception {
        RateLimiter limiter = new RateLimiter(5, 10);

        // Acquire twice — hourlyCount = 2
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(2);

        // Simulate time moving to a new hour by setting currentHourStart to 2 hours ago
        long currentHour = currentHourMillis();
        setField(limiter, "currentHourStart", currentHour - (2 * 60L * 60L * 1000L));

        // Calling getHourlyCount triggers rolloverHourIfNeeded, which clears hourlyCounts
        assertThat(limiter.getHourlyCount("u1")).isZero();

        // The next acquire should start fresh (hourly = 1, not 3)
        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);
    }

    @Test
    void shouldClearAllUsersHourlyCountsOnRollover() throws Exception {
        RateLimiter limiter = new RateLimiter(5, 10);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.tryAcquire("u2")).isTrue();
        limiter.release("u2");
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);
        assertThat(limiter.getHourlyCount("u2")).isEqualTo(1);

        // Simulate hour rollover
        long currentHour = currentHourMillis();
        setField(limiter, "currentHourStart", currentHour - (60L * 60L * 1000L));

        assertThat(limiter.getHourlyCount("u1")).isZero();
        assertThat(limiter.getHourlyCount("u2")).isZero();
    }

    @Test
    void shouldNotResetHourlyCountWithinSameHour() throws Exception {
        RateLimiter limiter = new RateLimiter(5, 10);

        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);

        // Set currentHourStart to the actual current hour (no rollover)
        setField(limiter, "currentHourStart", currentHourMillis());

        // Within the same hour — count should persist
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);
    }

    @Test
    void shouldRespectHourlyLimitAfterRolloverResetsCount() throws Exception {
        // maxRunsPerHour = 2
        RateLimiter limiter = new RateLimiter(5, 2);

        // Exhaust hourly quota
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        assertThat(limiter.tryAcquire("u1")).isTrue();
        limiter.release("u1");
        // Third acquire should fail (quota exhausted)
        assertThat(limiter.tryAcquire("u1")).isFalse();

        // Simulate hour rollover — quota resets
        long currentHour = currentHourMillis();
        setField(limiter, "currentHourStart", currentHour - (60L * 60L * 1000L));

        // After rollover, acquire should succeed again
        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.getHourlyCount("u1")).isEqualTo(1);
    }

    private static long currentHourMillis() {
        long now = System.currentTimeMillis();
        return now - (now % (60L * 60L * 1000L));
    }

    private static void setField(Object target, String fieldName, long value) throws Exception {
        Field field = RateLimiter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }
}
