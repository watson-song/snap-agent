package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowEngine;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.StepResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates anchor content injection: cache lookup → prompt construction
 * → skill/workflow execution → result caching.
 *
 * <p>On cache hit, returns the cached HTML immediately without executing
 * any LLM call. On cache miss, constructs an injection context prompt,
 * executes the bound skill or workflow, caches the result with the
 * requested TTL, and returns the HTML.</p>
 *
 * <p>Cache key is per-user: {@code userId:sourceId:anchorName:pageUrl}.</p>
 */
public class AnchorInjectionOrchestrator {

    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final YamlWorkflowLoader workflowLoader;   // may be null
    private final WorkflowEngine workflowEngine;        // may be null
    private final AnchorInjectionCache cache;
    private final SecurityGateway securityGateway;
    private final SnapAgentProperties.Anchor props;

    public AnchorInjectionOrchestrator(LlmClient llmClient,
                                       SkillRegistry skillRegistry,
                                       YamlWorkflowLoader workflowLoader,
                                       WorkflowEngine workflowEngine,
                                       AnchorInjectionCache cache,
                                       SecurityGateway securityGateway,
                                       SnapAgentProperties.Anchor props) {
        this.llmClient = llmClient;
        this.skillRegistry = skillRegistry;
        this.workflowLoader = workflowLoader;
        this.workflowEngine = workflowEngine;
        this.cache = cache;
        this.securityGateway = securityGateway;
        this.props = props;
    }

    /**
     * Executes an injection request: check cache, run skill/workflow if miss,
     * cache result, return HTML.
     */
    public InjectionResult inject(String userId, InjectionRequest req) {
        // 1. Build cache key
        String cacheKey = buildCacheKey(userId, req);

        // 2. Check cache (only if TTL > 0)
        if (req.getCacheTtl() > 0) {
            InjectionCacheEntry cached = cache.get(cacheKey);
            if (cached != null) {
                return new InjectionResult(cached.getHtml(), true, cached.getGeneratedAt());
            }
        }

        // 3. Build injection context prompt
        String prompt = buildInjectionPrompt(userId, req);

        // 4. Execute skill or workflow (skill takes precedence)
        String html;
        if (req.getSkillId() != null && !req.getSkillId().isEmpty()) {
            html = executeSkill(req.getSkillId(), prompt);
        } else if (req.getWorkflowId() != null && !req.getWorkflowId().isEmpty()) {
            html = executeWorkflow(req.getWorkflowId(), prompt, req);
        } else {
            throw new IllegalArgumentException("INVALID_INPUT: no skillId or workflowId provided");
        }

        // 5. Build result
        Instant now = Instant.now();
        InjectionResult result = new InjectionResult(html, false, now);

        // 6. Cache result (only if TTL > 0)
        if (req.getCacheTtl() > 0) {
            long effectiveTtl = props.resolveEffectiveTtl(req.getCacheTtl());
            cache.put(cacheKey, html, now, effectiveTtl);
        }

        return result;
    }

    /** Builds the per-user cache key. */
    String buildCacheKey(String userId, InjectionRequest req) {
        String source = req.getSourceId();
        return userId + ":" + source + ":" + req.getAnchorName() + ":" + req.getPageUrl();
    }

    /** Constructs the injection context prompt sent to the skill. */
    String buildInjectionPrompt(String userId, InjectionRequest req) {
        return "你正在为页面 \"" + req.getPageUrl() + "\" 的 \"" + req.getAnchorName()
                + "\" 区域生成页面内容。\n"
                + "当前用户：" + userId + "\n"
                + "当前时间：" + LocalDateTime.now() + "\n\n"
                + "要求：\n"
                + "1. 返回纯 HTML 片段，不要包含 <html>、<body>、<head> 等外层标签\n"
                + "2. 内容应为可直接嵌入页面的完整 HTML（可含内联 <style> 和 <script>）\n"
                + "3. 你可以自由设计内容风格、布局和配色，让内容新颖且吸引人\n"
                + "4. 内容应与当前用户身份和页面上下文相关\n"
                + "5. 不要使用外联 CSS/JS，所有样式和脚本必须内联\n";
    }

    /** Executes a skill to generate HTML content. */
    private String executeSkill(String skillId, String prompt) {
        SkillMeta skill = skillRegistry.get(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("SKILL_NOT_FOUND: " + skillId);
        }

        LlmRequest request = new LlmRequest(
                "你是 SnapAgent 内容生成助手，负责为页面锚点生成 HTML 内容。",
                Collections.singletonList(Message.user(prompt)),
                Collections.emptyList(),
                null,
                props.getInjectionMaxTokens(),
                true    // streaming — collect output via onThought
        );

        StringBuilder html = new StringBuilder();
        llmClient.stream(request, new LlmEventSink() {
            @Override
            public void onThought(String text) {
                html.append(text);
            }

            @Override
            public void onToolUse(String id, String name, Map<String, Object> input) {
            }

            @Override
            public void onToolResult(String toolUseId, String result) {
            }

            @Override
            public void onStop(String stopReason) {
            }

            @Override
            public void onError(String message) {
                throw new RuntimeException("INJECTION_FAILED: LLM error: " + message);
            }
        }, "inject-" + UUID.randomUUID().toString());

        return html.toString();
    }

    /** Executes a workflow to generate HTML content. */
    private String executeWorkflow(String workflowId, String prompt, InjectionRequest req) {
        if (workflowLoader == null) {
            throw new IllegalArgumentException("WORKFLOW_NOT_FOUND: workflow loader not configured");
        }

        WorkflowDefinition workflow = workflowLoader.load(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("WORKFLOW_NOT_FOUND: " + workflowId);
        }

        if (workflowEngine == null) {
            throw new IllegalArgumentException("WORKFLOW_NOT_FOUND: workflow engine not configured");
        }

        Map<String, String> triggerInputs = new HashMap<>();
        triggerInputs.put("prompt", prompt);
        triggerInputs.put("anchorName", req.getAnchorName());
        triggerInputs.put("pageUrl", req.getPageUrl());
        triggerInputs.put("userId", securityGateway.currentUserId());

        WorkflowResult result = workflowEngine.execute(workflow, triggerInputs);

        return extractHtmlFromWorkflowResult(result);
    }

    /** Extracts HTML from workflow result: last step's report text. */
    private String extractHtmlFromWorkflowResult(WorkflowResult result) {
        if (result == null || result.getStepResults() == null
                || result.getStepResults().isEmpty()) {
            return "<p>（工作流未返回内容）</p>";
        }

        // Get the last step's report
        StepResult lastResult = null;
        for (StepResult stepResult : result.getStepResults().values()) {
            lastResult = stepResult;
        }

        if (lastResult != null && lastResult.getReport() != null
                && !lastResult.getReport().isEmpty()) {
            return lastResult.getReport();
        }

        return "<p>（工作流未返回内容）</p>";
    }
}
