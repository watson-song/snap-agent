package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileCostStore}.
 */
class FileCostStoreTest {

    @TempDir
    Path tempDir;

    private CostStore store;

    @BeforeEach
    void setUp() {
        store = new FileCostStore(tempDir.toString());
    }

    private CostRecord newRecord(String id, String userId, String skillName,
                                 long inputTokens, long outputTokens,
                                 BigDecimal cost, long timestamp) {
        return new CostRecord(id, userId, skillName, "task-" + id,
                "claude-sonnet-4-6", inputTokens, outputTokens, 0, cost, timestamp);
    }

    @Test
    void shouldSaveAndRetrieveByList() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-B", "database-query",
                200, 100, new BigDecimal("1.00"), now + 1000));

        List<CostRecord> all = store.list(0, now + 10000);
        assertThat(all).hasSize(2);
    }

    @Test
    void shouldFilterByUser() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-B", "health-check",
                200, 100, new BigDecimal("1.00"), now + 1000));
        store.save(newRecord("rec-3", "user-A", "database-query",
                150, 75, new BigDecimal("0.75"), now + 2000));

        List<CostRecord> userARecords = store.listByUser("user-A", 0, now + 10000);
        assertThat(userARecords).hasSize(2);
        for (CostRecord record : userARecords) {
            assertThat(record.getUserId()).isEqualTo("user-A");
        }
    }

    @Test
    void shouldFilterBySkill() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-B", "database-query",
                200, 100, new BigDecimal("1.00"), now + 1000));
        store.save(newRecord("rec-3", "user-A", "health-check",
                150, 75, new BigDecimal("0.75"), now + 2000));

        List<CostRecord> healthCheckRecords = store.listBySkill("health-check", 0, now + 10000);
        assertThat(healthCheckRecords).hasSize(2);
        for (CostRecord record : healthCheckRecords) {
            assertThat(record.getSkillName()).isEqualTo("health-check");
        }
    }

    @Test
    void shouldFilterByTimestampRange() {
        long base = 1_000_000L;
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), base));
        store.save(newRecord("rec-2", "user-A", "health-check",
                200, 100, new BigDecimal("1.00"), base + 5000));
        store.save(newRecord("rec-3", "user-A", "health-check",
                150, 75, new BigDecimal("0.75"), base + 10000));

        List<CostRecord> records = store.list(base + 2000, base + 8000);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getId()).isEqualTo("rec-2");
    }

    @Test
    void shouldSumCostByUser() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-A", "database-query",
                200, 100, new BigDecimal("1.00"), now + 1000));
        store.save(newRecord("rec-3", "user-B", "health-check",
                150, 75, new BigDecimal("0.75"), now + 2000));

        BigDecimal userACost = store.sumCostByUser("user-A", 0, now + 10000);
        assertThat(userACost).isEqualByComparingTo(new BigDecimal("1.50"));

        BigDecimal userBCost = store.sumCostByUser("user-B", 0, now + 10000);
        assertThat(userBCost).isEqualByComparingTo(new BigDecimal("0.75"));
    }

    @Test
    void shouldSumCostBySkill() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-B", "health-check",
                200, 100, new BigDecimal("1.00"), now + 1000));
        store.save(newRecord("rec-3", "user-A", "database-query",
                150, 75, new BigDecimal("0.75"), now + 2000));

        BigDecimal healthCost = store.sumCostBySkill("health-check", 0, now + 10000);
        assertThat(healthCost).isEqualByComparingTo(new BigDecimal("1.50"));
    }

    @Test
    void shouldSumGlobalCost() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-B", "database-query",
                200, 100, new BigDecimal("1.00"), now + 1000));

        BigDecimal total = store.sumCost(0, now + 10000);
        assertThat(total).isEqualByComparingTo(new BigDecimal("1.50"));
    }

    @Test
    void shouldCountByUser() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-A", "health-check",
                200, 100, new BigDecimal("1.00"), now + 1000));
        store.save(newRecord("rec-3", "user-B", "health-check",
                150, 75, new BigDecimal("0.75"), now + 2000));

        assertThat(store.countByUser("user-A", 0, now + 10000)).isEqualTo(2);
        assertThat(store.countByUser("user-B", 0, now + 10000)).isEqualTo(1);
        assertThat(store.countByUser("user-C", 0, now + 10000)).isEqualTo(0);
    }

    @Test
    void shouldCountBySkill() {
        long now = System.currentTimeMillis();
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), now));
        store.save(newRecord("rec-2", "user-B", "health-check",
                200, 100, new BigDecimal("1.00"), now + 1000));
        store.save(newRecord("rec-3", "user-A", "database-query",
                150, 75, new BigDecimal("0.75"), now + 2000));

        assertThat(store.countBySkill("health-check", 0, now + 10000)).isEqualTo(2);
        assertThat(store.countBySkill("database-query", 0, now + 10000)).isEqualTo(1);
    }

    @Test
    void shouldDeleteBeforeTimestamp() {
        long base = 1_000_000L;
        store.save(newRecord("rec-1", "user-A", "health-check",
                100, 50, new BigDecimal("0.50"), base));
        store.save(newRecord("rec-2", "user-A", "health-check",
                200, 100, new BigDecimal("1.00"), base + 5000));
        store.save(newRecord("rec-3", "user-A", "health-check",
                150, 75, new BigDecimal("0.75"), base + 10000));

        store.deleteBefore(base + 6000);

        List<CostRecord> remaining = store.list(0, base + 20000);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getId()).isEqualTo("rec-3");
    }

    @Test
    void shouldReturnEmptyListWhenNoRecords() {
        List<CostRecord> records = store.list(0, System.currentTimeMillis() + 10000);
        assertThat(records).isEmpty();
    }

    @Test
    void shouldReturnZeroSumWhenNoRecords() {
        BigDecimal sum = store.sumCost(0, System.currentTimeMillis() + 10000);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
