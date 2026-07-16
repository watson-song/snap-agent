package cn.watsontech.snapagent.core.patrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatrolReportStoreTest {

    private PatrolReportStore store;

    @BeforeEach
    void setUp() {
        store = new PatrolReportStore(100);
    }

    @Test
    void saveAndGetReportsReturnsSortedByTriggeredAtDesc() {
        PatrolReport r1 = new PatrolReport();
        r1.setTriggeredAt(1000L);
        r1.setSkillName("skill-a");
        r1.setStatus("COMPLETED");

        PatrolReport r2 = new PatrolReport();
        r2.setTriggeredAt(3000L);
        r2.setSkillName("skill-b");
        r2.setStatus("COMPLETED");

        PatrolReport r3 = new PatrolReport();
        r3.setTriggeredAt(2000L);
        r3.setSkillName("skill-c");
        r3.setStatus("COMPLETED");

        store.save(r1);
        store.save(r2);
        store.save(r3);

        List<PatrolReport> reports = store.getReports("user1", 10, 0);

        assertThat(reports).hasSize(3);
        assertThat(reports.get(0).getTriggeredAt()).isEqualTo(3000L);
        assertThat(reports.get(1).getTriggeredAt()).isEqualTo(2000L);
        assertThat(reports.get(2).getTriggeredAt()).isEqualTo(1000L);
    }

    @Test
    void paginationReturnsCorrectPage() {
        for (int i = 0; i < 10; i++) {
            PatrolReport r = new PatrolReport();
            r.setTriggeredAt(1000L + i);
            store.save(r);
        }

        // Sorted desc: triggeredAt = 1009, 1008, ..., 1000
        // offset=2, limit=5 → should get 1007, 1006, 1005, 1004, 1003
        List<PatrolReport> page = store.getReports("user1", 5, 2);

        assertThat(page).hasSize(5);
        assertThat(page.get(0).getTriggeredAt()).isEqualTo(1007L);
        assertThat(page.get(4).getTriggeredAt()).isEqualTo(1003L);
    }

    @Test
    void countReturnsTotalReportCount() {
        assertThat(store.count("user1")).isEqualTo(0);

        store.save(createReport(1000L));
        store.save(createReport(2000L));
        store.save(createReport(3000L));

        assertThat(store.count("user1")).isEqualTo(3);
    }

    @Test
    void ringBufferEvictsOldestWhenFull() {
        PatrolReportStore smallStore = new PatrolReportStore(3);

        PatrolReport r1 = createReport(1000L);
        r1.setId("r1");
        PatrolReport r2 = createReport(2000L);
        r2.setId("r2");
        PatrolReport r3 = createReport(3000L);
        r3.setId("r3");
        PatrolReport r4 = createReport(4000L);
        r4.setId("r4");

        smallStore.save(r1);
        smallStore.save(r2);
        smallStore.save(r3);
        smallStore.save(r4);

        // r1 should be evicted
        assertThat(smallStore.count("user1")).isEqualTo(3);
        assertThat(smallStore.get("r1")).isNull();

        // r2, r3, r4 should still be present
        assertThat(smallStore.get("r2")).isNotNull();
        assertThat(smallStore.get("r3")).isNotNull();
        assertThat(smallStore.get("r4")).isNotNull();
    }

    private PatrolReport createReport(long triggeredAt) {
        PatrolReport r = new PatrolReport();
        r.setTriggeredAt(triggeredAt);
        r.setSkillName("test-skill");
        r.setStatus("COMPLETED");
        return r;
    }
}
