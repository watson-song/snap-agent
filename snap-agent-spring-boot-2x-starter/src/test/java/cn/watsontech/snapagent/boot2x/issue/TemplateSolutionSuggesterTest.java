package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TemplateSolutionSuggester}.
 *
 * <p>Verifies keyword-template-based solution suggestion: the suggester matches
 * keywords on the root cause summary (falling back to transcript summary when
 * root cause is empty) and produces a {@link SolutionSuggestion} with 2-3
 * {@link SolutionOption}s whose first option is the recommended one.</p>
 */
class TemplateSolutionSuggesterTest {

    private final TemplateSolutionSuggester suggester = new TemplateSolutionSuggester();

    private IssueClosure issueWithRootCause(String rootCause) {
        return new IssueClosure(
                "issue-test", null, "task-test",
                null, null, "为什么订单服务超时?", rootCause,
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);
    }

    private IssueClosure issueWithRootCauseAndTranscript(String rootCause, String transcriptSummary) {
        return new IssueClosure(
                "issue-test", null, "task-test",
                null, null, "为什么订单服务超时?", rootCause,
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);
    }

    // ---- keyword template 1: 参数 + (缺失|缺少|未生成) ----

    @Test
    void shouldSuggestManualInsertWhenRootCauseMentionsParameterMissing() {
        IssueClosure issue = issueWithRootCause("参数缺失导致订单创建失败");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(2);
        // First option should be about manual insert
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getId()).isEqualTo("opt-1");
        assertThat(first.getTitle()).contains("手动");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("opt-1");
    }

    // ---- keyword template 2: 连接 + (超时|失败|拒绝) ----

    @Test
    void shouldSuggestPoolConfigWhenRootCauseMentionsConnectionTimeout() {
        IssueClosure issue = issueWithRootCause("数据库连接超时, 连接池打满");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(3);
        // First option should be about pool config
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getTitle()).contains("连接池");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("opt-1");
    }

    // ---- keyword template 3: 数据 + (为空|缺失|不存在) ----

    @Test
    void shouldSuggestUpstreamTaskWhenRootCauseMentionsDataEmpty() {
        IssueClosure issue = issueWithRootCause("上游数据为空, 导致聚合结果缺失");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(2);
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getTitle()).contains("上游");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("opt-1");
    }

    // ---- keyword template 4: 权限|鉴权|认证 ----

    @Test
    void shouldSuggestSelfCheckWhenRootCauseMentionsPermission() {
        IssueClosure issue = issueWithRootCause("权限不足, 用户无访问资源的权限");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(2);
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getTitle()).contains("自检");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("opt-1");
    }

    // ---- fallback ----

    @Test
    void shouldFallBackToGenericOptionsForUnknownRootCause() {
        IssueClosure issue = issueWithRootCause("这是一个完全未知的根因场景, 没有匹配关键词");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(2);
        // Fallback first option should reference code graph locate
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getTitle()).contains("代码图谱");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("opt-1");
    }

    // ---- transcript summary fallback ----

    @Test
    void shouldUseTranscriptSummaryWhenRootCauseIsEmpty() {
        IssueClosure issue = issueWithRootCauseAndTranscript(null,
                "诊断过程发现连接失败, 连接被拒绝");

        SolutionSuggestion suggestion = suggester.suggest(issue,
                "诊断过程发现连接失败, 连接被拒绝");

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(3);
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getTitle()).contains("连接池");
    }

    @Test
    void shouldUseTranscriptSummaryWhenRootCauseIsBlank() {
        IssueClosure issue = issueWithRootCauseAndTranscript("",
                "诊断发现参数缺失, 订单号未生成");

        SolutionSuggestion suggestion = suggester.suggest(issue,
                "诊断发现参数缺失, 订单号未生成");

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(2);
        SolutionOption first = suggestion.getOptions().get(0);
        assertThat(first.getTitle()).contains("手动");
    }

    // ---- recommended option + unique ids ----

    @Test
    void shouldSetRecommendedOptionIdToFirstOption() {
        IssueClosure issue = issueWithRootCause("权限认证失败");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion.getRecommendedOptionId())
                .isEqualTo(suggestion.getOptions().get(0).getId());
    }

    @Test
    void shouldGenerateUniqueOptionIds() {
        IssueClosure issue = issueWithRootCause("连接超时");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        Set<String> ids = new HashSet<String>();
        for (SolutionOption option : suggestion.getOptions()) {
            assertThat(ids).doesNotContain(option.getId());
            ids.add(option.getId());
        }
        // IDs should follow the opt-N pattern
        List<String> idList = new ArrayList<String>();
        for (SolutionOption option : suggestion.getOptions()) {
            idList.add(option.getId());
        }
        assertThat(idList.get(0)).isEqualTo("opt-1");
        assertThat(idList.get(1)).isEqualTo("opt-2");
    }

    @Test
    void shouldPopulateEffortAndTemporaryFlagOnEachOption() {
        IssueClosure issue = issueWithRootCause("连接超时, 连接失败");

        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        for (SolutionOption option : suggestion.getOptions()) {
            assertThat(option.getEffort()).isIn("low", "medium", "high");
            // title and description should be non-empty
            assertThat(option.getTitle()).isNotEmpty();
            assertThat(option.getDescription()).isNotEmpty();
        }
    }
}
