package cn.watsontech.snapagent.boot2x.closure;

import cn.watsontech.snapagent.core.closure.IssueClosure;
import cn.watsontech.snapagent.core.closure.IssueStatus;
import cn.watsontech.snapagent.core.closure.SolutionOption;
import cn.watsontech.snapagent.core.closure.SolutionSuggestion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSolutionSuggesterTest {

    private final TemplateSolutionSuggester suggester = new TemplateSolutionSuggester();

    private IssueClosure makeIssue(String rootCause) {
        return new IssueClosure("issue-1", "conv-1", "task-1",
                "health-check", rootCause);
    }

    @Test
    void suggest_paramMissing_generatesParamTemplate() {
        IssueClosure issue = makeIssue("补货参数缺失，SKU-001 未生成补货策略");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.getOptions()).hasSize(2);
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getTitle)
                .contains("手动插入参数记录", "修改任务过滤条件");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("opt-1");
        assertThat(suggestion.getRationale()).contains("临时");
    }

    @Test
    void suggest_connectionTimeout_generatesConnectionTemplate() {
        IssueClosure issue = makeIssue("数据库连接超时，连接池已打满");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion.getOptions()).hasSize(3);
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getTitle)
                .contains("检查并调整连接池配置", "增加超时时间");
    }

    @Test
    void suggest_dataEmpty_generatesDataTemplate() {
        IssueClosure issue = makeIssue("查询结果数据为空，上游任务可能未执行");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion.getOptions()).hasSize(2);
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getTitle)
                .contains("检查上游数据任务", "补数据");
    }

    @Test
    void suggest_permissionIssue_generatesPermissionTemplate() {
        IssueClosure issue = makeIssue("用户权限不足，无法访问该资源");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion.getOptions()).hasSize(2);
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getTitle)
                .contains("检查用户权限配置", "联系管理员授权");
    }

    @Test
    void suggest_unknownRootCause_generatesGenericTemplate() {
        IssueClosure issue = makeIssue("某个未知的问题导致系统异常");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);

        assertThat(suggestion.getOptions()).hasSize(2);
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getTitle)
                .contains("根据代码图谱定位修复点", "联系负责人确认");
    }

    @Test
    void suggest_emptyRootCause_usesTranscriptSummary() {
        IssueClosure issue = makeIssue("");
        SolutionSuggestion suggestion = suggester.suggest(issue, "连接失败，超时");

        assertThat(suggestion.getOptions()).hasSize(3);
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getTitle)
                .contains("检查并调整连接池配置");
    }

    @Test
    void suggestion_hasId() {
        IssueClosure issue = makeIssue("test");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);
        assertThat(suggestion.getId()).isNotNull().startsWith("sug_");
    }

    @Test
    void options_haveValidIds() {
        IssueClosure issue = makeIssue("test");
        SolutionSuggestion suggestion = suggester.suggest(issue, null);
        for (SolutionOption opt : suggestion.getOptions()) {
            assertThat(opt.getId()).isNotNull().isNotEmpty();
        }
    }

    @Test
    void suggestedIssue_statusRemainsDiagnosed() {
        IssueClosure issue = makeIssue("test");
        suggester.suggest(issue, null);
        // Suggester does not change issue status — caller is responsible
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
    }
}
