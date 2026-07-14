package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecordTest {

    @Test
    void shouldHoldAllFields() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", "SELECT 1");

        AuditRecord rec = new AuditRecord("task-1", "user-1", "mysql_query",
                args, 5, false, 1234567890L, 42L);

        assertThat(rec.getTaskId()).isEqualTo("task-1");
        assertThat(rec.getUserId()).isEqualTo("user-1");
        assertThat(rec.getToolName()).isEqualTo("mysql_query");
        assertThat(rec.getArgs().get("sql")).isEqualTo("SELECT 1");
        assertThat(rec.getRowCount()).isEqualTo(5);
        assertThat(rec.isTruncated()).isFalse();
        assertThat(rec.getTimestamp()).isEqualTo(1234567890L);
        assertThat(rec.getDurationMs()).isEqualTo(42L);
    }

    @Test
    void shouldHandleNullArgs() {
        AuditRecord rec = new AuditRecord("t", "u", "tool", null, 0, false, 1L, 1L);

        assertThat(rec.getArgs()).isEmpty();
    }

    @Test
    void shouldHaveToString() {
        AuditRecord rec = new AuditRecord("t1", "u1", "mysql_query", null, 1, false, 1L, 10L);

        assertThat(rec.toString()).contains("t1");
        assertThat(rec.toString()).contains("mysql_query");
    }
}
