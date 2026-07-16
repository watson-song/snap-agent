package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.assertj.core.api.Assertions;

class InMemoryCostTrackerTest {

    private InMemoryCostTracker tracker = new InMemoryCostTracker(10.0, 50.0, 500.0);

    private CostRecord makeRecord(String userId, String skill, double cost, long ts) {
        return new CostRecord(userId, skill, "task-" + ts, "model-x",
                1000, 2000, 100, cost, ts);
    }

    @Test
    void record_storesRecord() {
        tracker.record(makeRecord("user1", "health-check", 0.5, 1000L));
        assertThat(tracker.size()).isEqualTo(1);
    }

    @Test
    void record_nullRecord_ignored() {
        tracker.record(null);
        assertThat(tracker.size()).isZero();
    }

    @Test
    void getSummary_filtersByUser() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        tracker.record(makeRecord("user2", "skill-a", 2.0, 2000L));
        tracker.record(makeRecord("user1", "skill-b", 3.0, 3000L));

        CostSummary summary = tracker.getSummary("user1", null, 0, 0);
        assertThat(summary.getDimension()).isEqualTo("user");
        assertThat(summary.getDimensionValue()).isEqualTo("user1");
        assertThat(summary.getTotalCost()).isEqualTo(4.0);
        assertThat(summary.getRequestCount()).isEqualTo(2);
        assertThat(summary.getBudget()).isEqualTo(10.0);
        assertThat(summary.getUtilization()).isEqualTo(0.4);
    }

    @Test
    void getSummary_filtersBySkill() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        tracker.record(makeRecord("user1", "skill-b", 2.0, 2000L));

        CostSummary summary = tracker.getSummary(null, "skill-a", 0, 0);
        assertThat(summary.getDimension()).isEqualTo("skill");
        assertThat(summary.getDimensionValue()).isEqualTo("skill-a");
        assertThat(summary.getTotalCost()).isEqualTo(1.0);
        assertThat(summary.getRequestCount()).isEqualTo(1);
    }

    @Test
    void getSummary_globalSummary() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        tracker.record(makeRecord("user2", "skill-b", 2.0, 2000L));

        CostSummary summary = tracker.getSummary(null, null, 0, 0);
        assertThat(summary.getDimension()).isEqualTo("global");
        assertThat(summary.getTotalCost()).isEqualTo(3.0);
        assertThat(summary.getRequestCount()).isEqualTo(2);
        assertThat(summary.getBudget()).isEqualTo(500.0);
    }

    @Test
    void getSummary_filtersByTimeRange() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        tracker.record(makeRecord("user1", "skill-a", 2.0, 2000L));
        tracker.record(makeRecord("user1", "skill-a", 3.0, 3000L));

        CostSummary summary = tracker.getSummary(null, null, 1500L, 2500L);
        assertThat(summary.getRequestCount()).isEqualTo(1);
        assertThat(summary.getTotalCost()).isEqualTo(2.0);
    }

    @Test
    void getRecords_returnsFilteredRecords() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        tracker.record(makeRecord("user2", "skill-a", 2.0, 2000L));
        tracker.record(makeRecord("user1", "skill-a", 3.0, 3000L));

        List<cn.watsontech.snapagent.core.cost.CostRecord> records = tracker.getRecords("user1", 0, 0);
        assertThat(records).hasSize(2);
        assertThat(records).allMatch(r -> "user1".equals(r.getUserId()));
    }

    @Test
    void getRecords_filtersByTimeRange() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        tracker.record(makeRecord("user1", "skill-a", 2.0, 2000L));

        List<cn.watsontech.snapagent.core.cost.CostRecord> records = tracker.getRecords(null, 1500L, 0);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getCost()).isEqualTo(2.0);
    }

    @Test
    void getSummary_emptyTracker_returnsZeros() {
        CostSummary summary = tracker.getSummary(null, null, 0, 0);
        assertThat(summary.getTotalCost()).isZero();
        assertThat(summary.getRequestCount()).isZero();
    }

    @Test
    void getSummary_userAndSkill_userSkillDimension() {
        tracker.record(makeRecord("user1", "skill-a", 1.0, 1000L));
        CostSummary summary = tracker.getSummary("user1", "skill-a", 0, 0);
        assertThat(summary.getDimension()).isEqualTo("user-skill");
    }
}
