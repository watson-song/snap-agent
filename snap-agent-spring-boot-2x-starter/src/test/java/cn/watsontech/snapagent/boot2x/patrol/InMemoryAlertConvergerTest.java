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
}
