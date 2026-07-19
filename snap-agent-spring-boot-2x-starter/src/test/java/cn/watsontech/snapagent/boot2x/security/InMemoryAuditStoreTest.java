package cn.watsontech.snapagent.boot2x.security;

import cn.watsontech.snapagent.core.security.AuditEntry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditStoreTest {

    @Test
    void shouldRecordAndQueryEntries() {
        InMemoryAuditStore store = new InMemoryAuditStore(100);
        store.record(new AuditEntry("user1", "POST", "/runs", "RUN_SKILL", null, System.currentTimeMillis()));
        store.record(new AuditEntry("user1", "GET", "/skills", "LIST_SKILLS", null, System.currentTimeMillis()));
        store.record(new AuditEntry("user2", "POST", "/runs", "RUN_SKILL", null, System.currentTimeMillis()));

        List<AuditEntry> user1Entries = store.query("user1", null, 100, 0);
        assertThat(user1Entries).hasSize(2);
        assertThat(user1Entries).allMatch(e -> "user1".equals(e.getUserId()));
    }

    @Test
    void shouldEvictOldEntriesWhenBufferFull() {
        InMemoryAuditStore store = new InMemoryAuditStore(3);
        for (int i = 0; i < 5; i++) {
            store.record(new AuditEntry("user1", "GET", "/skills", "LIST_SKILLS", null, i));
        }
        List<AuditEntry> all = store.query(null, null, 100, 0);
        assertThat(all).hasSize(3);
        // Newest 3 entries (timestamps 2, 3, 4) should remain; oldest 2 (0, 1) evicted
        assertThat(all.get(0).getTimestamp()).isEqualTo(4L);
        assertThat(all).extracting(AuditEntry::getTimestamp).doesNotContain(0L, 1L);
    }

    @Test
    void shouldFilterByAction() {
        InMemoryAuditStore store = new InMemoryAuditStore(100);
        store.record(new AuditEntry("user1", "POST", "/runs", "RUN_SKILL", null, 1L));
        store.record(new AuditEntry("user1", "POST", "/skills/refresh", "REFRESH_SKILLS", null, 2L));

        List<AuditEntry> results = store.query("user1", "RUN_SKILL", 100, 0);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAction()).isEqualTo("RUN_SKILL");
    }

    @Test
    void shouldPaginateResults() {
        InMemoryAuditStore store = new InMemoryAuditStore(100);
        for (int i = 0; i < 10; i++) {
            store.record(new AuditEntry("user1", "GET", "/skills", "LIST_SKILLS", null, i));
        }
        List<AuditEntry> page1 = store.query("user1", null, 5, 0);
        assertThat(page1).hasSize(5);
        List<AuditEntry> page2 = store.query("user1", null, 5, 5);
        assertThat(page2).hasSize(5);
    }
}
