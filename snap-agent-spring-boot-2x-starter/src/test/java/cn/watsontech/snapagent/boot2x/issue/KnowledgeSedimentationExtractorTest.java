package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KnowledgeSedimentationExtractor}.
 */
class KnowledgeSedimentationExtractorTest {

    private KnowledgeSedimentationExtractor extractor = new KnowledgeSedimentationExtractor();

    private SolutionSuggestion suggestionOf(String... titles) {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        int index = 1;
        for (String title : titles) {
            options.add(new SolutionOption("opt-" + index, title, title, "medium", false));
            index++;
        }
        String recommended = options.isEmpty() ? null : "opt-1";
        return new SolutionSuggestion(options, recommended, null, null);
    }

    private VerificationResult verificationOf(boolean passed, String summary) {
        return new VerificationResult(passed, summary, "FIX_IN_PROGRESS",
                passed ? "SUCCEEDED" : "FAILED", 1_500L);
    }

    @Test
    void shouldExtractFragmentWithCorrectTitleAndSource() {
        IssueClosure issue = new IssueClosure(
                "issue-001", null, "task-100",
                null, null, "为什么订单服务超时?", "连接池打满",
                suggestionOf("方案1", "方案2"), null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getTitle()).isEqualTo("问题: 为什么订单服务超时?");
        assertThat(fragment.getSource()).isEqualTo("sedimentation:issue-001");
    }

    @Test
    void shouldBuildContentWithProblemAndRootCauseSections() {
        IssueClosure issue = new IssueClosure(
                "issue-002", null, "task-200",
                null, null, "数据库查询慢", "缺少索引",
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getContent()).contains("## 问题");
        assertThat(fragment.getContent()).contains("数据库查询慢");
        assertThat(fragment.getContent()).contains("## 根因");
        assertThat(fragment.getContent()).contains("缺少索引");
        assertThat(fragment.getContent()).contains("## 解决方案");
    }

    @Test
    void shouldUseSelectedSolutionWhenPresent() {
        IssueClosure issue = new IssueClosure(
                "issue-003", null, "task-300",
                null, null, "查询慢", "缺索引",
                suggestionOf("方案1", "方案2"), "方案2: 加索引",
                IssueStatus.SOLUTION_PROPOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getContent()).contains("方案2: 加索引");
        // Should NOT list all solution options when selectedSolution is present
        assertThat(fragment.getContent()).doesNotContain("- [medium] 方案1");
    }

    @Test
    void shouldListAllSolutionsWhenNoSelectedSolution() {
        IssueClosure issue = new IssueClosure(
                "issue-004", null, "task-400",
                null, null, "查询慢", "缺索引",
                suggestionOf("方案A", "方案B"), null,
                IssueStatus.SOLUTION_PROPOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getContent()).contains("- [medium] 方案A: 方案A");
        assertThat(fragment.getContent()).contains("- [medium] 方案B: 方案B");
    }

    @Test
    void shouldIncludeVerificationSectionWhenPresent() {
        IssueClosure issue = new IssueClosure(
                "issue-005", null, "task-500",
                null, null, "查询慢", "缺索引",
                suggestionOf("方案1"), "方案1",
                IssueStatus.VERIFIED, null,
                verificationOf(true, "修复后查询时间从5s降到50ms"), null,
                1_000L, 3_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getContent()).contains("## 验证结果");
        assertThat(fragment.getContent()).contains("passed: true");
        assertThat(fragment.getContent()).contains("修复后查询时间从5s降到50ms");
    }

    @Test
    void shouldTruncateLongUserQueryInTitle() {
        // Build a query longer than 60 characters to trigger truncation
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("测试查询");
        }
        String longQuery = sb.toString(); // 80 characters
        IssueClosure issue = new IssueClosure(
                "issue-006", null, "task-600",
                null, null, longQuery, "root cause",
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getTitle()).startsWith("问题: ");
        // Title should be truncated: "问题: " (4 chars) + 60 chars + "..." = 67 chars
        assertThat(fragment.getTitle()).endsWith("...");
        assertThat(fragment.getTitle().length()).isLessThanOrEqualTo(4 + 60 + 3);
        // Content should still have the full query
        assertThat(fragment.getContent()).contains(longQuery);
    }

    @Test
    void shouldSetMetadataWithCategory() {
        IssueClosure issue = new IssueClosure(
                "issue-007", null, "task-700",
                null, null, "query", "root cause",
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getMetadata()).isNotNull();
        assertThat(fragment.getMetadata()).containsEntry("category", "经验沉淀");
    }

    // ---- GAP-6 (P2): no suggestion + no selectedSolution boundary ----

    @Test
    void shouldEmitEmptySolutionSectionWhenNoSuggestionAndNoSelectedSolution() {
        // Both solution and selectedSolution are null — the "## 解决方案"
        // section header must exist but contain no list items or text.
        IssueClosure issue = new IssueClosure(
                "issue-008", null, "task-800",
                null, null, "查询超时", "连接池耗尽",
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        String content = fragment.getContent();
        assertThat(content).contains("## 解决方案");
        // No solution-option list items should appear
        assertThat(content).doesNotContain("- [");
        // The 解决方案 section should be empty (followed by end-of-content
        // or the 验证结果 section, with no solution body in between)
        int solutionIdx = content.indexOf("## 解决方案");
        int solutionEnd = content.indexOf("\n\n", solutionIdx);
        if (solutionEnd < 0) {
            solutionEnd = content.length();
        }
        String solutionSection = content.substring(
                solutionIdx + "## 解决方案".length(), solutionEnd).trim();
        assertThat(solutionSection).isEmpty();
    }

    @Test
    void shouldEmitEmptySolutionSectionWhenSuggestionHasEmptyOptions() {
        // SolutionSuggestion present but with empty options list — should
        // not crash, should not emit any list items.
        IssueClosure issue = new IssueClosure(
                "issue-009", null, "task-900",
                null, null, "查询超时", "连接池耗尽",
                new SolutionSuggestion(new ArrayList<SolutionOption>(), null, null, null),
                null,
                IssueStatus.SOLUTION_PROPOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        String content = fragment.getContent();
        assertThat(content).contains("## 解决方案");
        // Empty options list → no list items
        assertThat(content).doesNotContain("- [");
    }

    @Test
    void shouldEmitSolutionSectionWithoutVerificationWhenVerificationIsNull() {
        // No verification result → the 验证结果 section must be absent
        IssueClosure issue = new IssueClosure(
                "issue-010", null, "task-1000",
                null, null, "查询超时", "连接池耗尽",
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);

        KnowledgeFragment fragment = extractor.extract(issue);

        assertThat(fragment.getContent()).doesNotContain("## 验证结果");
        // Content should end after the empty 解决方案 section
        assertThat(fragment.getContent()).endsWith("## 解决方案\n");
    }
}
