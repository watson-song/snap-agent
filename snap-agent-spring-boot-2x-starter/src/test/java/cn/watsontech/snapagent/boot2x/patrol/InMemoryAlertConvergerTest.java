package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAlertConvergerTest {

    private InMemoryAlertConverger converger;

    @BeforeEach
    void setUp() {
        converger = new InMemoryAlertConverger(100, 30);
    }

    private AnomalyEvent event(String type, String source, String message, long timestamp) {
        AnomalyEvent e = new AnomalyEvent();
        e.setType(type);
        e.setSource(source);
        e.setMessage(message);
        e.setTimestamp(timestamp);
        return e;
    }

    @Test
    @DisplayName("record creates new AlertConvergence with fingerprint")
    void shouldCreateNewAlert() {
        AnomalyEvent ev = event("ERROR_SPIKE", "order-service", "NPE at line 87", 1000L);
        AlertConvergence result = converger.record(ev);

        assertThat(result.getCount()).isEqualTo(1);
        assertThat(result.getFingerprint()).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AlertConvergence.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("same type+source deduplicates and increments count")
    void shouldDeduplicateAndIncrement() {
        AlertConvergence result1 = converger.record(event("ERROR_SPIKE", "order-service", "NPE at line 87", 1000L));
        AlertConvergence result2 = converger.record(event("ERROR_SPIKE", "order-service", "NPE at line 87", 2000L));

        assertThat(result2.getId()).isEqualTo(result1.getId());
        assertThat(result2.getCount()).isEqualTo(2);
        assertThat(result2.getLastSeen()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("different source creates separate alert")
    void shouldCreateSeparateForDifferentSource() {
        AlertConvergence result1 = converger.record(event("ERROR_SPIKE", "order-service", "NPE", 1000L));
        AlertConvergence result2 = converger.record(event("ERROR_SPIKE", "payment-service", "NPE", 2000L));

        assertThat(result1.getId()).isNotEqualTo(result2.getId());
    }

    @Test
    @DisplayName("query returns alerts sorted by lastSeen desc")
    void shouldQuerySortedByLastSeenDesc() {
        converger.record(event("TYPE_A", "svc-a", "msg1", 1000L));
        converger.record(event("TYPE_B", "svc-b", "msg2", 2000L));
        converger.record(event("TYPE_A", "svc-a", "msg3", 3000L));

        List<AlertConvergence> results = converger.query(null, null, 10, 0);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getLastSeen()).isGreaterThanOrEqualTo(results.get(1).getLastSeen());
    }

    @Test
    @DisplayName("resolve changes status to RESOLVED")
    void shouldResolveAlert() {
        AlertConvergence alert = converger.record(event("ERROR_SPIKE", "svc", "msg", 1000L));
        converger.resolve(alert.getId());

        List<AlertConvergence> results = converger.query(null, null, 10, 0);
        AlertConvergence found = results.stream()
                .filter(a -> a.getId().equals(alert.getId()))
                .findFirst().orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo(AlertConvergence.STATUS_RESOLVED);
    }

    @Test
    @DisplayName("count returns total alert count")
    void shouldCountAlerts() {
        converger.record(event("A", "svc-a", "msg", 1000L));
        converger.record(event("B", "svc-b", "msg", 2000L));
        assertThat(converger.count(null, null)).isEqualTo(2);
    }

    @Test
    @DisplayName("ring buffer evicts oldest when full")
    void shouldEvictOldestWhenFull() {
        converger = new InMemoryAlertConverger(3, 30);
        converger.record(event("A", "svc-a", "msg", 1000L));
        converger.record(event("B", "svc-b", "msg", 2000L));
        converger.record(event("C", "svc-c", "msg", 3000L));
        converger.record(event("D", "svc-d", "msg", 4000L));

        assertThat(converger.count(null, null)).isEqualTo(3);
        List<AlertConvergence> results = converger.query(null, null, 10, 0);
        assertThat(results).extracting(AlertConvergence::getSource)
                .doesNotContain("svc-a")
                .contains("svc-b", "svc-c", "svc-d");
    }

    // ── autoResolveStale (GAP-1) ───────────────────────────────────

    @Test
    @DisplayName("autoResolveStale resolves expired alerts while keeping fresh ones ACTIVE on query")
    void shouldAutoResolveStaleAlertsOnQuery() {
        // Use a 30-minute auto-resolve window with realistic timestamps so the
        // stale alert falls outside the threshold and the fresh one stays active.
        converger = new InMemoryAlertConverger(100, 30);
        long now = System.currentTimeMillis();
        long oneHourAgo = now - 60 * 60_000L;

        // Stale alert — lastSeen is 1 hour ago, exceeds 30-min threshold.
        AlertConvergence stale = converger.record(
                event("STALE_TYPE", "svc-a", "old issue", oneHourAgo));
        // Fresh alert — lastSeen is now, within threshold.
        AlertConvergence fresh = converger.record(
                event("FRESH_TYPE", "svc-b", "new issue", now));

        // Before query, both are ACTIVE.
        assertThat(stale.getStatus()).isEqualTo(AlertConvergence.STATUS_ACTIVE);
        assertThat(fresh.getStatus()).isEqualTo(AlertConvergence.STATUS_ACTIVE);

        // query() triggers autoResolveStale lazily.
        List<AlertConvergence> results = converger.query(null, null, 10, 0);

        AlertConvergence foundStale = results.stream()
                .filter(a -> a.getId().equals(stale.getId())).findFirst().orElse(null);
        AlertConvergence foundFresh = results.stream()
                .filter(a -> a.getId().equals(fresh.getId())).findFirst().orElse(null);

        assertThat(foundStale).isNotNull();
        assertThat(foundStale.getStatus()).isEqualTo(AlertConvergence.STATUS_RESOLVED);
        assertThat(foundFresh).isNotNull();
        assertThat(foundFresh.getStatus()).isEqualTo(AlertConvergence.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("autoResolveStale does not touch already-RESOLVED alerts")
    void shouldNotReprocessResolvedAlerts() {
        converger = new InMemoryAlertConverger(100, 30);
        long now = System.currentTimeMillis();
        AlertConvergence alert = converger.record(event("T", "svc", "msg", now));
        converger.resolve(alert.getId());
        assertThat(alert.getStatus()).isEqualTo(AlertConvergence.STATUS_RESOLVED);

        // query triggers autoResolveStale — should not throw or revert status.
        List<AlertConvergence> results = converger.query(null, null, 10, 0);
        AlertConvergence found = results.stream()
                .filter(a -> a.getId().equals(alert.getId())).findFirst().orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo(AlertConvergence.STATUS_RESOLVED);
    }

    // ── query filter by type (GAP-2) ───────────────────────────────

    @Test
    @DisplayName("query filters by type when type parameter is provided")
    void shouldQueryFilterByType() {
        long now = System.currentTimeMillis();
        converger.record(event("ERROR_SPIKE", "svc-a", "msg1", now));
        converger.record(event("DB_DOWN", "svc-b", "msg2", now));
        converger.record(event("ERROR_SPIKE", "svc-c", "msg3", now));

        List<AlertConvergence> results = converger.query(null, "ERROR_SPIKE", 10, 0);
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(a -> "ERROR_SPIKE".equals(a.getType()));
    }

    @Test
    @DisplayName("query returns all alerts when type is null or empty")
    void shouldQueryReturnAllWhenTypeIsNullOrEmpty() {
        long now = System.currentTimeMillis();
        converger.record(event("A", "svc-a", "msg", now));
        converger.record(event("B", "svc-b", "msg", now));

        assertThat(converger.query(null, null, 10, 0)).hasSize(2);
        assertThat(converger.query(null, "", 10, 0)).hasSize(2);
    }

    @Test
    @DisplayName("query with type filter returns empty when no match")
    void shouldQueryReturnEmptyForUnknownType() {
        long now = System.currentTimeMillis();
        converger.record(event("A", "svc-a", "msg", now));

        assertThat(converger.query(null, "NONEXISTENT", 10, 0)).isEmpty();
    }

    // ── count filter by type (GAP-3) ───────────────────────────────

    @Test
    @DisplayName("count filters by type when type parameter is provided")
    void shouldCountFilterByType() {
        long now = System.currentTimeMillis();
        converger.record(event("ERROR_SPIKE", "svc-a", "msg1", now));
        converger.record(event("DB_DOWN", "svc-b", "msg2", now));
        converger.record(event("ERROR_SPIKE", "svc-c", "msg3", now));

        assertThat(converger.count(null, "ERROR_SPIKE")).isEqualTo(2);
        assertThat(converger.count(null, "DB_DOWN")).isEqualTo(1);
        assertThat(converger.count(null, "NONEXISTENT")).isEqualTo(0);
    }

    @Test
    @DisplayName("count returns total when type is null or empty")
    void shouldCountReturnTotalWhenTypeIsNullOrEmpty() {
        long now = System.currentTimeMillis();
        converger.record(event("A", "svc-a", "msg", now));
        converger.record(event("B", "svc-b", "msg", now));

        assertThat(converger.count(null, null)).isEqualTo(2);
        assertThat(converger.count(null, "")).isEqualTo(2);
    }

    @Test
    @DisplayName("query respects limit and offset after type filtering")
    void shouldQueryApplyLimitAndOffsetAfterTypeFilter() {
        long now = System.currentTimeMillis();
        converger.record(event("T", "svc-a", "msg", now));
        converger.record(event("T", "svc-b", "msg", now));
        converger.record(event("T", "svc-c", "msg", now));

        // offset beyond size returns empty
        assertThat(converger.query(null, "T", 10, 10)).isEmpty();
        // limit=2 returns only 2
        assertThat(converger.query(null, "T", 2, 0)).hasSize(2);
        // offset=2 skips first 2, returns 1
        assertThat(converger.query(null, "T", 10, 2)).hasSize(1);
    }
}
