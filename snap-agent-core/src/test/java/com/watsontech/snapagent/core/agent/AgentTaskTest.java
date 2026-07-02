package com.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskTest {

    @Test
    void shouldStartAsPendingWhenCreated() {
        AgentTask task = AgentTask.create("u1", "skill-1", null, "model-1");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getUserId()).isEqualTo("u1");
        assertThat(task.getSkillId()).isEqualTo("skill-1");
        assertThat(task.getModel()).isEqualTo("model-1");
        assertThat(task.getTaskId()).startsWith("sa_");
        assertThat(task.getCreatedAt()).isGreaterThan(0);
        assertThat(task.getUpdatedAt()).isEqualTo(task.getCreatedAt());
    }

    @Test
    void shouldTransitionStatusWhenSetStatusCalled() {
        AgentTask task = AgentTask.create("u", "s", null, "m");

        task.setStatus(TaskStatus.RUNNING);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getUpdatedAt()).isGreaterThanOrEqualTo(task.getCreatedAt());

        task.setStatus(TaskStatus.SUCCEEDED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void shouldAddTranscriptEventsInOrder() {
        AgentTask task = AgentTask.create("u", "s", null, "m");

        task.addTranscriptEvent(TranscriptEvent.thought("first"));
        task.addTranscriptEvent(TranscriptEvent.thought("second"));

        List<TranscriptEvent> transcript = task.getTranscript();
        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0).getText()).isEqualTo("first");
        assertThat(transcript.get(1).getText()).isEqualTo("second");
    }

    @Test
    void shouldEnforceTranscriptLimit() {
        AgentTask task = new AgentTask("t1", "u", "s", null, "m", 3, 100);

        task.addTranscriptEvent(TranscriptEvent.thought("1"));
        task.addTranscriptEvent(TranscriptEvent.thought("2"));
        task.addTranscriptEvent(TranscriptEvent.thought("3"));
        task.addTranscriptEvent(TranscriptEvent.thought("4")); // should be dropped

        assertThat(task.getTranscript()).hasSize(3);
    }

    @Test
    void shouldAddAuditRecords() {
        AgentTask task = AgentTask.create("u", "s", null, "m");

        AuditRecord rec = new AuditRecord("t", "u", "mysql_query", null, 5, false, 1L, 10L);
        task.addAuditRecord(rec);

        assertThat(task.getAuditRecords()).hasSize(1);
        assertThat(task.getAuditRecords().get(0).getToolName()).isEqualTo("mysql_query");
    }

    @Test
    void shouldEnforceAuditLimit() {
        AgentTask task = new AgentTask("t1", "u", "s", null, "m", 100, 2);

        task.addAuditRecord(new AuditRecord("t", "u", "tool", null, 0, false, 1L, 1L));
        task.addAuditRecord(new AuditRecord("t", "u", "tool", null, 0, false, 1L, 1L));
        task.addAuditRecord(new AuditRecord("t", "u", "tool", null, 0, false, 1L, 1L)); // dropped

        assertThat(task.getAuditRecords()).hasSize(2);
    }

    @Test
    void shouldSetReport() {
        AgentTask task = AgentTask.create("u", "s", null, "m");

        task.setReport("诊断报告");

        assertThat(task.getReport()).isEqualTo("诊断报告");
    }

    @Test
    void shouldHoldInputsMap() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("skuCode", "A001");
        AgentTask task = AgentTask.create("u", "s", inputs, "m");

        assertThat(task.getInputs().get("skuCode")).isEqualTo("A001");
    }

    @Test
    void shouldReturnDefensiveCopyOfTranscript() {
        AgentTask task = AgentTask.create("u", "s", null, "m");
        task.addTranscriptEvent(TranscriptEvent.thought("x"));

        List<TranscriptEvent> copy = task.getTranscript();
        copy.clear(); // should not affect original

        assertThat(task.getTranscript()).hasSize(1);
    }

    @Test
    void shouldBeThreadSafeWhenConcurrentAccess() throws Exception {
        final AgentTask task = AgentTask.create("u", "s", null, "m");
        int threadCount = 10;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>(null);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        task.addTranscriptEvent(TranscriptEvent.thought("t" + idx + "-" + j));
                        task.getTranscript();
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertThat(error.get()).isNull();
        assertThat(task.getTranscript().size()).isLessThanOrEqualTo(500);
    }
}
