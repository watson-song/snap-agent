package cn.watsontech.snapagent.boot2x.closure;

import cn.watsontech.snapagent.core.closure.IssueClosure;
import cn.watsontech.snapagent.core.closure.SolutionOption;
import cn.watsontech.snapagent.core.closure.SolutionSuggestion;
import cn.watsontech.snapagent.core.closure.SolutionSuggester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rule-based {@link SolutionSuggester} that matches root cause keywords
 * to predefined solution templates.
 *
 * <p>Template matching is keyword-based: the transcript summary is scanned
 * for known patterns (e.g. "参数缺失", "连接超时", "数据为空") and the
 * corresponding template is used to generate solution options.</p>
 *
 * <p>If no template matches, a generic fallback template is used.</p>
 */
public class TemplateSolutionSuggester implements SolutionSuggester {

    private static final Logger log = LoggerFactory.getLogger(TemplateSolutionSuggester.class);

    @Override
    public SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary) {
        String rootCause = issue.getRootCauseSummary();
        if (rootCause == null || rootCause.isEmpty()) {
            rootCause = transcriptSummary != null ? transcriptSummary : "";
        }

        String lowerCause = rootCause.toLowerCase();
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        String recommendedId;
        String rationale;

        if (lowerCause.contains("参数") && (lowerCause.contains("缺失")
                || lowerCause.contains("缺少") || lowerCause.contains("未生成"))) {
            options.add(new SolutionOption(
                    "opt-1", "手动插入参数记录",
                    "直接向参数表插入缺失的记录，立即恢复功能。",
                    "low", true));
            options.add(new SolutionOption(
                    "opt-2", "修改任务过滤条件",
                    "调整初始化任务的过滤逻辑，确保后续不再遗漏。",
                    "medium", false));
            recommendedId = "opt-1";
            rationale = "推荐方案1作为临时修复，方案2作为长期改进。";
        } else if (lowerCause.contains("连接") && (lowerCause.contains("超时")
                || lowerCause.contains("失败") || lowerCause.contains("拒绝"))) {
            options.add(new SolutionOption(
                    "opt-1", "检查并调整连接池配置",
                    "查看 max-pool-size 配置，确认连接池是否打满。",
                    "low", true));
            options.add(new SolutionOption(
                    "opt-2", "增加超时时间",
                    "调整数据库连接超时参数，适应高负载场景。",
                    "low", true));
            options.add(new SolutionOption(
                    "opt-3", "排查慢查询和长事务",
                    "定位占用连接的慢查询或未提交事务，优化或终止。",
                    "medium", false));
            recommendedId = "opt-1";
            rationale = "先排查连接池配置，再考虑超时和慢查询。";
        } else if (lowerCause.contains("数据") && (lowerCause.contains("为空")
                || lowerCause.contains("缺失") || lowerCause.contains("不存在"))) {
            options.add(new SolutionOption(
                    "opt-1", "检查上游数据任务",
                    "确认上游 ETL/同步任务是否正常执行，排查数据源。",
                    "medium", false));
            options.add(new SolutionOption(
                    "opt-2", "补数据",
                    "手动补入缺失的数据记录，恢复业务流程。",
                    "low", true));
            recommendedId = "opt-2";
            rationale = "先补数据恢复业务，再排查上游任务根因。";
        } else if (lowerCause.contains("权限") || lowerCause.contains("鉴权")
                || lowerCause.contains("认证")) {
            options.add(new SolutionOption(
                    "opt-1", "检查用户权限配置",
                    "确认当前用户的权限是否包含所需操作权限。",
                    "low", false));
            options.add(new SolutionOption(
                    "opt-2", "联系管理员授权",
                    "请系统管理员为用户补充缺失的权限。",
                    "low", false));
            recommendedId = "opt-1";
            rationale = "先自查权限配置，再联系管理员。";
        } else {
            // Generic fallback
            options.add(new SolutionOption(
                    "opt-1", "根据代码图谱定位修复点",
                    "使用 code_graph 工具定位相关代码和调用链，确定修复位置。",
                    "medium", false));
            options.add(new SolutionOption(
                    "opt-2", "联系负责人确认",
                    "联系相关模块负责人，确认根因和修复方案。",
                    "low", false));
            recommendedId = "opt-1";
            rationale = "优先通过代码图谱自助定位，无法确定时联系负责人。";
        }

        String suggestionId = "sug_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.info("TemplateSolutionSuggester generated {} options for issue {}", options.size(), issue.getId());
        return new SolutionSuggestion(suggestionId, options, recommendedId, null, rationale);
    }
}
