package cn.watsontech.snapagent.core.patrol;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the patrol data-model POJOs introduced in v0.5.
 * <p>
 * These classes are simple immutable-style beans; the tests exercise
 * constructors, getters, setters, null-defensive copies and
 * {@code toString()} to keep jacoco line coverage above the ratchet
 * threshold.
 */
class PatrolModelTest {

    // ── AnomalyEvent ──────────────────────────────────────────────

    @Test
    void anomalyEventFullConstructorShouldReturnAllValues() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("cpu", 0.95);
        Map<String, String> inputs = new HashMap<>();
        inputs.put("service", "order-svc");

        AnomalyEvent event = new AnomalyEvent(
                "HIGH_CPU", "order-svc", "CPU > 90%", "health-patrol",
                meta, inputs);

        assertThat(event.getType()).isEqualTo("HIGH_CPU");
        assertThat(event.getSource()).isEqualTo("order-svc");
        assertThat(event.getMessage()).isEqualTo("CPU > 90%");
        assertThat(event.getSkillName()).isEqualTo("health-patrol");
        assertThat(event.getTimestamp()).isGreaterThan(0);
        assertThat(event.getMetadata()).containsEntry("cpu", 0.95);
        assertThat(event.getInputs()).containsEntry("service", "order-svc");
    }

    @Test
    void anomalyEventFullConstructorShouldDefendCopyMetadataAndInputs() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("k", "v");
        Map<String, String> inputs = new HashMap<>();
        inputs.put("i", "j");

        AnomalyEvent event = new AnomalyEvent(
                "T", "S", "M", "skill", meta, inputs);

        meta.put("k2", "v2");
        inputs.put("i2", "j2");

        assertThat(event.getMetadata()).hasSize(1).doesNotContainKey("k2");
        assertThat(event.getInputs()).hasSize(1).doesNotContainKey("i2");
    }

    @Test
    void anomalyEventFullConstructorShouldHandleNullMaps() {
        AnomalyEvent event = new AnomalyEvent(
                "T", "S", "M", "skill", null, null);

        assertThat(event.getMetadata()).isNotNull().isEmpty();
        assertThat(event.getInputs()).isNotNull().isEmpty();
    }

    @Test
    void anomalyEventDefaultConstructorShouldInitTimestampAndMaps() {
        AnomalyEvent event = new AnomalyEvent();

        assertThat(event.getTimestamp()).isGreaterThan(0);
        assertThat(event.getMetadata()).isNotNull().isEmpty();
        assertThat(event.getInputs()).isNotNull().isEmpty();
    }

    @Test
    void anomalyEventSettersShouldWork() {
        AnomalyEvent event = new AnomalyEvent();
        event.setType("TIMEOUT");
        event.setSource("db");
        event.setMessage("conn timeout");
        event.setTimestamp(123L);
        event.setSkillName("slow-query");

        Map<String, Object> meta = new HashMap<>();
        meta.put("query", "select *");
        event.setMetadata(meta);
        Map<String, String> inputs = new HashMap<>();
        inputs.put("env", "prod");
        event.setInputs(inputs);

        assertThat(event.getType()).isEqualTo("TIMEOUT");
        assertThat(event.getSource()).isEqualTo("db");
        assertThat(event.getMessage()).isEqualTo("conn timeout");
        assertThat(event.getTimestamp()).isEqualTo(123L);
        assertThat(event.getSkillName()).isEqualTo("slow-query");
        assertThat(event.getMetadata()).isEqualTo(meta);
        assertThat(event.getInputs()).isEqualTo(inputs);
    }

    @Test
    void anomalyEventSettersShouldHandleNullMaps() {
        AnomalyEvent event = new AnomalyEvent();
        event.setMetadata(null);
        event.setInputs(null);

        assertThat(event.getMetadata()).isNotNull().isEmpty();
        assertThat(event.getInputs()).isNotNull().isEmpty();
    }

    @Test
    void anomalyEventToStringShouldContainKeyFields() {
        AnomalyEvent event = new AnomalyEvent("ERR", "svc", "boom", "sk", null, null);
        String str = event.toString();
        assertThat(str).contains("AnomalyEvent")
                .contains("ERR").contains("svc").contains("boom");
    }

    // ── AlertConvergence ──────────────────────────────────────────

    @Test
    void alertConvergenceFullConstructorShouldReturnAllValues() {
        AlertConvergence alert = new AlertConvergence(
                "a-1", "fp-1", "HIGH_CPU", "order-svc",
                "CPU > 90%", "task-100");

        assertThat(alert.getId()).isEqualTo("a-1");
        assertThat(alert.getFingerprint()).isEqualTo("fp-1");
        assertThat(alert.getType()).isEqualTo("HIGH_CPU");
        assertThat(alert.getSource()).isEqualTo("order-svc");
        assertThat(alert.getFirstMessage()).isEqualTo("CPU > 90%");
        assertThat(alert.getRelatedTaskId()).isEqualTo("task-100");
        assertThat(alert.getStatus()).isEqualTo(AlertConvergence.STATUS_ACTIVE);
        assertThat(alert.getCount()).isEqualTo(1);
        assertThat(alert.getFirstSeen()).isGreaterThan(0);
        assertThat(alert.getLastSeen()).isEqualTo(alert.getFirstSeen());
    }

    @Test
    void alertConvergenceDefaultConstructorShouldInitStatusAndTimestamps() {
        AlertConvergence alert = new AlertConvergence();

        assertThat(alert.getStatus()).isEqualTo(AlertConvergence.STATUS_ACTIVE);
        assertThat(alert.getCount()).isEqualTo(1);
        assertThat(alert.getFirstSeen()).isGreaterThan(0);
        assertThat(alert.getLastSeen()).isGreaterThan(0);
    }

    @Test
    void alertConvergenceIncrementCountShouldBumpCountAndUpdateLastSeen() throws InterruptedException {
        AlertConvergence alert = new AlertConvergence("a", "fp", "T", "S", "M", "t");
        long firstLastSeen = alert.getLastSeen();

        Thread.sleep(5);
        alert.incrementCount();

        assertThat(alert.getCount()).isEqualTo(2);
        assertThat(alert.getLastSeen()).isGreaterThan(firstLastSeen);
    }

    @Test
    void alertConvergenceSettersShouldWork() {
        AlertConvergence alert = new AlertConvergence();
        alert.setId("id-1");
        alert.setFingerprint("fp-1");
        alert.setType("T");
        alert.setSource("S");
        alert.setFirstMessage("M");
        alert.setCount(5);
        alert.setFirstSeen(100L);
        alert.setLastSeen(200L);
        alert.setStatus(AlertConvergence.STATUS_RESOLVED);
        alert.setRelatedTaskId("task-1");

        assertThat(alert.getId()).isEqualTo("id-1");
        assertThat(alert.getFingerprint()).isEqualTo("fp-1");
        assertThat(alert.getType()).isEqualTo("T");
        assertThat(alert.getSource()).isEqualTo("S");
        assertThat(alert.getFirstMessage()).isEqualTo("M");
        assertThat(alert.getCount()).isEqualTo(5);
        assertThat(alert.getFirstSeen()).isEqualTo(100L);
        assertThat(alert.getLastSeen()).isEqualTo(200L);
        assertThat(alert.getStatus()).isEqualTo(AlertConvergence.STATUS_RESOLVED);
        assertThat(alert.getRelatedTaskId()).isEqualTo("task-1");
    }

    @Test
    void alertConvergenceToStringShouldContainKeyFields() {
        AlertConvergence alert = new AlertConvergence("a-1", "fp", "T", "S", "M", "t");
        String str = alert.toString();
        assertThat(str).contains("AlertConvergence")
                .contains("a-1").contains("T");
    }

    // ── PatrolTask ────────────────────────────────────────────────

    @Test
    void patrolTaskFiveArgConstructorShouldDelegateToSixArg() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("k", "v");
        PatrolTask task = new PatrolTask("p-1", "health-patrol",
                "0 */5 * * * ?", "user-1", inputs);

        assertThat(task.getId()).isEqualTo("p-1");
        assertThat(task.getSkillName()).isEqualTo("health-patrol");
        assertThat(task.getCron()).isEqualTo("0 */5 * * * ?");
        assertThat(task.getUserId()).isEqualTo("user-1");
        assertThat(task.isEnabled()).isTrue();
        assertThat(task.getInputs()).containsEntry("k", "v");
        assertThat(task.getAlertKeywords()).isNull();
    }

    @Test
    void patrolTaskSixArgConstructorShouldSetAllFields() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("k", "v");
        PatrolTask task = new PatrolTask("p-1", "health-patrol",
                "0 */5 * * * ?", "user-1", inputs, "error,timeout");

        assertThat(task.getAlertKeywords()).isEqualTo("error,timeout");
        assertThat(task.getInputs()).isNotSameAs(inputs);
    }

    @Test
    void patrolTaskSixArgConstructorShouldHandleNullInputs() {
        PatrolTask task = new PatrolTask("p", "s", "c", "u", null, "kw");
        assertThat(task.getInputs()).isNotNull().isEmpty();
    }

    @Test
    void patrolTaskDefaultConstructorShouldInitEnabledAndInputs() {
        PatrolTask task = new PatrolTask();
        assertThat(task.isEnabled()).isTrue();
        assertThat(task.getInputs()).isNotNull().isEmpty();
    }

    @Test
    void patrolTaskSettersShouldWork() {
        PatrolTask task = new PatrolTask();
        task.setId("id");
        task.setName("nightly check");
        task.setSkillName("sk");
        task.setCron("0 0 * * * ?");
        task.setUserId("u");
        task.setEnabled(false);
        task.setAlertKeywords("kw");
        Map<String, String> inputs = new HashMap<>();
        task.setInputs(inputs);

        assertThat(task.getId()).isEqualTo("id");
        assertThat(task.getName()).isEqualTo("nightly check");
        assertThat(task.getSkillName()).isEqualTo("sk");
        assertThat(task.getCron()).isEqualTo("0 0 * * * ?");
        assertThat(task.getUserId()).isEqualTo("u");
        assertThat(task.isEnabled()).isFalse();
        assertThat(task.getAlertKeywords()).isEqualTo("kw");
        assertThat(task.getInputs()).isEqualTo(inputs);
    }

    @Test
    void patrolTaskSetInputsShouldHandleNull() {
        PatrolTask task = new PatrolTask();
        task.setInputs(null);
        assertThat(task.getInputs()).isNotNull().isEmpty();
    }

    @Test
    void patrolTaskToStringShouldContainKeyFields() {
        PatrolTask task = new PatrolTask("p-1", "sk", "c", "u", null, "kw");
        String str = task.toString();
        assertThat(str).contains("PatrolTask")
                .contains("p-1").contains("sk").contains("c").contains("kw");
    }

    // ── PatrolReport ──────────────────────────────────────────────

    @Test
    void patrolReportFullConstructorShouldReturnAllValues() {
        PatrolReport report = new PatrolReport(
                "r-1", "p-1", "task-1", "health-patrol",
                1700000000000L, "SUCCEEDED", "all good", false);

        assertThat(report.getId()).isEqualTo("r-1");
        assertThat(report.getPatrolId()).isEqualTo("p-1");
        assertThat(report.getTaskId()).isEqualTo("task-1");
        assertThat(report.getSkillName()).isEqualTo("health-patrol");
        assertThat(report.getTriggeredAt()).isEqualTo(1700000000000L);
        assertThat(report.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(report.getSummary()).isEqualTo("all good");
        assertThat(report.isAnomalyDetected()).isFalse();
    }

    @Test
    void patrolReportDefaultConstructorShouldAllowSetters() {
        PatrolReport report = new PatrolReport();
        report.setId("r");
        report.setPatrolId("p");
        report.setTaskId("t");
        report.setUserId("u");
        report.setSkillName("s");
        report.setTriggeredAt(1L);
        report.setStatus("FAILED");
        report.setSummary("bad");
        report.setAnomalyDetected(true);

        assertThat(report.getUserId()).isEqualTo("u");
        assertThat(report.getStatus()).isEqualTo("FAILED");
        assertThat(report.isAnomalyDetected()).isTrue();
    }

    @Test
    void patrolReportToStringShouldContainKeyFields() {
        PatrolReport report = new PatrolReport(
                "r-1", "p-1", "t", "s", 1L, "SUCCEEDED", "ok", true);
        String str = report.toString();
        assertThat(str).contains("PatrolReport")
                .contains("r-1").contains("p-1").contains("SUCCEEDED");
    }

    // ── BugfixSuggestion ──────────────────────────────────────────

    @Test
    void bugfixSuggestionFullConstructorShouldReturnAllValues() {
        java.util.List<String> files = java.util.Arrays.asList("A.java", "B.java");
        java.util.List<String> commits = java.util.Arrays.asList("abc123");

        BugfixSuggestion sug = new BugfixSuggestion(
                "task-1", "NPE in parser", files,
                "add null check", BugfixSuggestion.CONFIDENCE_HIGH, commits);

        assertThat(sug.getTaskId()).isEqualTo("task-1");
        assertThat(sug.getRootCause()).isEqualTo("NPE in parser");
        assertThat(sug.getAffectedFiles()).containsExactly("A.java", "B.java");
        assertThat(sug.getSuggestion()).isEqualTo("add null check");
        assertThat(sug.getConfidence()).isEqualTo(BugfixSuggestion.CONFIDENCE_HIGH);
        assertThat(sug.getCommitRefs()).containsExactly("abc123");
    }

    @Test
    void bugfixSuggestionFullConstructorShouldDefendCopyLists() {
        java.util.List<String> files = new java.util.ArrayList<>();
        files.add("A.java");
        java.util.List<String> commits = new java.util.ArrayList<>();
        commits.add("abc");

        BugfixSuggestion sug = new BugfixSuggestion(
                "t", "rc", files, "fix", "HIGH", commits);

        files.add("B.java");
        commits.add("def");

        assertThat(sug.getAffectedFiles()).hasSize(1).doesNotContain("B.java");
        assertThat(sug.getCommitRefs()).hasSize(1).doesNotContain("def");
    }

    @Test
    void bugfixSuggestionFullConstructorShouldHandleNullLists() {
        BugfixSuggestion sug = new BugfixSuggestion(
                "t", "rc", null, "fix", "LOW", null);

        assertThat(sug.getAffectedFiles()).isNotNull().isEmpty();
        assertThat(sug.getCommitRefs()).isNotNull().isEmpty();
    }

    @Test
    void bugfixSuggestionDefaultConstructorShouldInitLists() {
        BugfixSuggestion sug = new BugfixSuggestion();
        assertThat(sug.getAffectedFiles()).isNotNull().isEmpty();
        assertThat(sug.getCommitRefs()).isNotNull().isEmpty();
    }

    @Test
    void bugfixSuggestionSettersShouldWork() {
        BugfixSuggestion sug = new BugfixSuggestion();
        sug.setTaskId("t");
        sug.setRootCause("rc");
        sug.setSuggestion("s");
        sug.setConfidence(BugfixSuggestion.CONFIDENCE_MEDIUM);

        java.util.List<String> files = new java.util.ArrayList<>();
        sug.setAffectedFiles(files);
        java.util.List<String> commits = new java.util.ArrayList<>();
        sug.setCommitRefs(commits);

        assertThat(sug.getTaskId()).isEqualTo("t");
        assertThat(sug.getRootCause()).isEqualTo("rc");
        assertThat(sug.getSuggestion()).isEqualTo("s");
        assertThat(sug.getConfidence()).isEqualTo(BugfixSuggestion.CONFIDENCE_MEDIUM);
        assertThat(sug.getAffectedFiles()).isEqualTo(files);
        assertThat(sug.getCommitRefs()).isEqualTo(commits);
    }

    @Test
    void bugfixSuggestionSettersShouldHandleNullLists() {
        BugfixSuggestion sug = new BugfixSuggestion();
        sug.setAffectedFiles(null);
        sug.setCommitRefs(null);

        assertThat(sug.getAffectedFiles()).isNotNull().isEmpty();
        assertThat(sug.getCommitRefs()).isNotNull().isEmpty();
    }

    @Test
    void bugfixSuggestionToStringShouldContainKeyFields() {
        BugfixSuggestion sug = new BugfixSuggestion(
                "task-1", "NPE", null, "fix", "HIGH", null);
        String str = sug.toString();
        assertThat(str).contains("BugfixSuggestion")
                .contains("task-1").contains("HIGH").contains("NPE");
    }
}
