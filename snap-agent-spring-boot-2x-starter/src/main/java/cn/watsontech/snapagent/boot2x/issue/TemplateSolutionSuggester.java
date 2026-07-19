package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggester;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link SolutionSuggester} implementation that uses keyword-template
 * matching on the root cause summary (falling back to the transcript summary
 * when the root cause is blank) to produce a {@link SolutionSuggestion} with
 * 2-3 candidate {@link SolutionOption}s.
 *
 * <p>Matching is intentionally simple ({@link String#contains(CharSequence)}):
 * the first matching template wins. When no template matches, a generic
 * fallback suggestion is returned. The first option is always the recommended
 * one ({@code recommendedOptionId = "opt-1"}).</p>
 *
 * <p>Templates:
 * <ol>
 *   <li><b>参数</b> + (缺失|缺少|未生成) → 手动补齐参数 / 修复过滤逻辑</li>
 *   <li><b>连接</b> + (超时|失败|拒绝) → 调整连接池配置 / 增加超时时间 / 排查慢查询</li>
 *   <li><b>数据</b> + (为空|缺失|不存在) → 检查上游任务 / 手动补数据</li>
 *   <li><b>权限|鉴权|认证</b> → 权限自检 / 联系管理员</li>
 *   <li><b>fallback</b> → 代码图谱定位 / 联系负责人</li>
 * </ol>
 * </p>
 */
public class TemplateSolutionSuggester implements SolutionSuggester {

    @Override
    public SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary) {
        String text = pickSourceText(issue, transcriptSummary);

        if (containsAny(text, "参数") && containsAny(text, "缺失", "缺少", "未生成")) {
            return buildParameterTemplate();
        }
        if (containsAny(text, "连接") && containsAny(text, "超时", "失败", "拒绝")) {
            return buildConnectionTemplate();
        }
        if (containsAny(text, "数据") && containsAny(text, "为空", "缺失", "不存在")) {
            return buildDataTemplate();
        }
        if (containsAny(text, "权限", "鉴权", "认证")) {
            return buildPermissionTemplate();
        }
        return buildFallbackTemplate();
    }

    // ---- source text selection ----

    /**
     * Returns the root cause summary when non-blank, otherwise falls back to
     * the transcript summary. When both are blank, an empty string is used
     * (which forces the fallback template).
     */
    private String pickSourceText(IssueClosure issue, String transcriptSummary) {
        String rootCause = issue != null ? issue.getRootCause() : null;
        if (rootCause != null && !rootCause.trim().isEmpty()) {
            return rootCause;
        }
        if (transcriptSummary != null && !transcriptSummary.trim().isEmpty()) {
            return transcriptSummary;
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ---- templates ----

    private SolutionSuggestion buildParameterTemplate() {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        options.add(new SolutionOption("opt-1",
                "手动补齐缺失参数",
                "定位缺失参数的来源, 通过配置或接口手动补齐, 快速恢复服务.",
                "low", true));
        options.add(new SolutionOption("opt-2",
                "修复参数过滤逻辑",
                "排查参数生成链路, 修复导致参数缺失的过滤/校验逻辑, 防止复发.",
                "medium", false));
        return new SolutionSuggestion(options, "opt-1",
                "根因指向参数缺失, 优先手动补齐恢复, 再修复生成逻辑.",
                null);
    }

    private SolutionSuggestion buildConnectionTemplate() {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        options.add(new SolutionOption("opt-1",
                "调整连接池配置",
                "扩容连接池上限或缩短空闲回收时间, 缓解连接打满.",
                "low", false));
        options.add(new SolutionOption("opt-2",
                "增加超时时间",
                "临时调大连接超时阈值, 为慢请求预留更多时间.",
                "low", true));
        options.add(new SolutionOption("opt-3",
                "排查慢查询",
                "定位占用连接的慢 SQL, 加索引或拆分大事务, 根治连接耗尽.",
                "high", false));
        return new SolutionSuggestion(options, "opt-1",
                "根因指向连接异常, 优先调连接池, 再排查慢查询.",
                null);
    }

    private SolutionSuggestion buildDataTemplate() {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        options.add(new SolutionOption("opt-1",
                "检查上游任务",
                "排查上游数据生产任务是否正常执行, 修复上游以恢复数据供给.",
                "medium", false));
        options.add(new SolutionOption("opt-2",
                "手动补数据",
                "从备份或日志中手动补齐缺失数据, 临时恢复下游计算.",
                "low", true));
        return new SolutionSuggestion(options, "opt-1",
                "根因指向数据缺失, 优先修复上游, 再补齐历史数据.",
                null);
    }

    private SolutionSuggestion buildPermissionTemplate() {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        options.add(new SolutionOption("opt-1",
                "权限自检",
                "核实当前账号的权限配置与资源访问策略, 修正授权范围.",
                "low", false));
        options.add(new SolutionOption("opt-2",
                "联系管理员",
                "向系统管理员申请所需权限, 或确认鉴权服务是否正常.",
                "low", false));
        return new SolutionSuggestion(options, "opt-1",
                "根因指向权限/鉴权问题, 优先自检权限配置.",
                null);
    }

    private SolutionSuggestion buildFallbackTemplate() {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        options.add(new SolutionOption("opt-1",
                "代码图谱定位",
                "通过代码知识图谱定位相关模块与调用链, 缩小排查范围.",
                "medium", false));
        options.add(new SolutionOption("opt-2",
                "联系负责人",
                "联系模块负责人或 OnCall, 获取人工支持与历史上下文.",
                "low", false));
        return new SolutionSuggestion(options, "opt-1",
                "未命中已知模板, 建议先通过代码图谱定位, 再寻求人工支持.",
                null);
    }
}
