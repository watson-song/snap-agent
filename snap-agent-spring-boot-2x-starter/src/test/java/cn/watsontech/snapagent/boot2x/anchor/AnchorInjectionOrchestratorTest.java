package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowEngine;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import cn.watsontech.snapagent.core.workflow.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnchorInjectionOrchestratorTest {

    private SnapAgentProperties.Anchor props;
    private LlmClient llmClient;
    private SkillRegistry skillRegistry;
    private YamlWorkflowLoader workflowLoader;
    private WorkflowEngine workflowEngine;
    private SecurityGateway securityGateway;
    private AnchorInjectionCache cache;
    private AnchorInjectionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        props = new SnapAgentProperties.Anchor();
        llmClient = mock(LlmClient.class);
        skillRegistry = mock(SkillRegistry.class);
        workflowLoader = mock(YamlWorkflowLoader.class);
        workflowEngine = mock(WorkflowEngine.class);
        securityGateway = mock(SecurityGateway.class);
        cache = new AnchorInjectionCache();
        orchestrator = new AnchorInjectionOrchestrator(
                llmClient, skillRegistry, workflowLoader, workflowEngine,
                cache, securityGateway, props);

        when(securityGateway.currentUserId()).thenReturn("user001");
    }

    @Test
    void shouldExecuteSkillOnCacheMiss() {
        SkillMeta skillMeta = new SkillMeta("announcement", "公告",
                Collections.<String>emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("announcement")).thenReturn(skillMeta);

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("<div class=\"notice\">Hello!</div>");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        InjectionRequest req = new InjectionRequest(
                "公告区域", "/dashboard", "announcement", null, 3600);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result).isNotNull();
        assertThat(result.getHtml()).contains("<div class=\"notice\">Hello!</div>");
        assertThat(result.isCached()).isFalse();

        // Verify result was cached
        InjectionResult cached = orchestrator.inject("user001", req);
        assertThat(cached.isCached()).isTrue();
    }

    @Test
    void shouldReturnCachedOnCacheHit() {
        SkillMeta skillMeta = new SkillMeta("announcement", "公告",
                Collections.<String>emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("announcement")).thenReturn(skillMeta);

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("<p>cached content</p>");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "announcement", null, 3600);

        // First call: miss, generates and caches
        orchestrator.inject("user001", req);
        // Second call: should hit cache, LLM should NOT be called again
        orchestrator.inject("user001", req);

        verify(llmClient).stream(any(), any(), anyString()); // only once
    }

    @Test
    void shouldNotCacheWhenTtlIsZero() {
        SkillMeta skillMeta = new SkillMeta("tip", "提示",
                Collections.<String>emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("tip")).thenReturn(skillMeta);

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("<p>fresh</p>");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        InjectionRequest req = new InjectionRequest(
                "tip", "/page", "tip", null, 0);

        InjectionResult result = orchestrator.inject("user001", req);
        assertThat(result.isCached()).isFalse();

        // Second call should also be a miss (no caching)
        InjectionResult result2 = orchestrator.inject("user001", req);
        assertThat(result2.isCached()).isFalse();
        verify(llmClient, org.mockito.Mockito.times(2)).stream(any(), any(), anyString());
    }

    @Test
    void shouldUseDifferentCacheKeysForDifferentUsers() {
        SkillMeta skillMeta = new SkillMeta("announcement", "公告",
                Collections.<String>emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("announcement")).thenReturn(skillMeta);

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("<p>content for user</p>");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "announcement", null, 3600);

        orchestrator.inject("user001", req);
        orchestrator.inject("user002", req);

        // Should call LLM twice — different users have different cache keys
        verify(llmClient, org.mockito.Mockito.times(2)).stream(any(), any(), anyString());
    }

    @Test
    void shouldExecuteWorkflowWhenWorkflowIdProvided() {
        WorkflowDefinition workflowDef = new WorkflowDefinition(
                "daily-summary", "Daily Summary",
                Collections.<WorkflowStep>emptyList());
        when(workflowLoader.load("daily-summary")).thenReturn(workflowDef);

        Map<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("step1", new StepResult("step1", "task1", "SUCCEEDED", "<p>workflow output</p>"));
        WorkflowResult wfResult = WorkflowResult.success("daily-summary", stepResults, 100);
        when(workflowEngine.execute(any(), any())).thenReturn(wfResult);

        InjectionRequest req = new InjectionRequest(
                "数据摘要", "/dashboard", null, "daily-summary", 3600);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result).isNotNull();
        assertThat(result.getHtml()).contains("<p>workflow output</p>");
        assertThat(result.isCached()).isFalse();
    }

    @Test
    void shouldThrowWhenSkillNotFound() {
        when(skillRegistry.get("nonexistent")).thenReturn(null);

        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "nonexistent", null, 3600);

        assertThatThrownBy(() -> orchestrator.inject("user001", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL_NOT_FOUND")
                .hasMessageContaining("nonexistent");
    }

    @Test
    void shouldThrowWhenWorkflowNotFound() {
        when(workflowLoader.load("nonexistent")).thenReturn(null);

        InjectionRequest req = new InjectionRequest(
                "数据", "/page", null, "nonexistent", 3600);

        assertThatThrownBy(() -> orchestrator.inject("user001", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WORKFLOW_NOT_FOUND")
                .hasMessageContaining("nonexistent");
    }

    @Test
    void shouldPreferSkillOverWorkflowWhenBothProvided() {
        SkillMeta skillMeta = new SkillMeta("skill1", "Skill 1",
                Collections.<String>emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("skill1")).thenReturn(skillMeta);

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("<p>from skill</p>");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        InjectionRequest req = new InjectionRequest(
                "anchor", "/page", "skill1", "workflow1", 3600);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result.getHtml()).contains("<p>from skill</p>");
        verify(workflowEngine, never()).execute(any(), any());
    }

    // ---- GAP-4: buildInjectionPrompt ----

    @Test
    void shouldIncludePageUrlAnchorNameAndUserIdInInjectionPrompt() {
        InjectionRequest req = new InjectionRequest(
                "公告区域", "/dashboard", "skill1", null, 3600);

        String prompt = orchestrator.buildInjectionPrompt("user001", req);

        assertThat(prompt).contains("/dashboard");
        assertThat(prompt).contains("公告区域");
        assertThat(prompt).contains("user001");
    }

    // ---- GAP-5: extractHtmlFromWorkflowResult (tested through inject) ----

    @Test
    void shouldReturnFallbackWhenWorkflowResultIsNull() {
        WorkflowDefinition workflowDef = new WorkflowDefinition(
                "wf-empty", "Empty WF", Collections.<WorkflowStep>emptyList());
        when(workflowLoader.load("wf-empty")).thenReturn(workflowDef);
        when(workflowEngine.execute(any(), any())).thenReturn(null);

        InjectionRequest req = new InjectionRequest(
                "摘要", "/page", null, "wf-empty", 0);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result.getHtml()).contains("工作流未返回内容");
    }

    @Test
    void shouldReturnFallbackWhenStepResultsAreEmpty() {
        WorkflowDefinition workflowDef = new WorkflowDefinition(
                "wf-empty-steps", "Empty Steps", Collections.<WorkflowStep>emptyList());
        when(workflowLoader.load("wf-empty-steps")).thenReturn(workflowDef);
        WorkflowResult emptyResult = WorkflowResult.success(
                "wf-empty-steps", new LinkedHashMap<String, StepResult>(), 50);
        when(workflowEngine.execute(any(), any())).thenReturn(emptyResult);

        InjectionRequest req = new InjectionRequest(
                "摘要", "/page", null, "wf-empty-steps", 0);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result.getHtml()).contains("工作流未返回内容");
    }

    @Test
    void shouldReturnFallbackWhenReportIsEmpty() {
        WorkflowDefinition workflowDef = new WorkflowDefinition(
                "wf-empty-report", "Empty Report", Collections.<WorkflowStep>emptyList());
        when(workflowLoader.load("wf-empty-report")).thenReturn(workflowDef);

        Map<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("step1", new StepResult("step1", "task1", "SUCCEEDED", ""));
        WorkflowResult emptyReportResult = WorkflowResult.success(
                "wf-empty-report", stepResults, 50);
        when(workflowEngine.execute(any(), any())).thenReturn(emptyReportResult);

        InjectionRequest req = new InjectionRequest(
                "摘要", "/page", null, "wf-empty-report", 0);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result.getHtml()).contains("工作流未返回内容");
    }

    @Test
    void shouldReturnFallbackWhenReportIsNull() {
        WorkflowDefinition workflowDef = new WorkflowDefinition(
                "wf-null-report", "Null Report", Collections.<WorkflowStep>emptyList());
        when(workflowLoader.load("wf-null-report")).thenReturn(workflowDef);

        Map<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("step1", new StepResult("step1", "task1", "SUCCEEDED", null));
        WorkflowResult nullReportResult = WorkflowResult.success(
                "wf-null-report", stepResults, 50);
        when(workflowEngine.execute(any(), any())).thenReturn(nullReportResult);

        InjectionRequest req = new InjectionRequest(
                "摘要", "/page", null, "wf-null-report", 0);

        InjectionResult result = orchestrator.inject("user001", req);

        assertThat(result.getHtml()).contains("工作流未返回内容");
    }

    // ---- GAP-6: LLM onError → INJECTION_FAILED ----

    @Test
    void shouldThrowInjectionFailedWhenLlmReportsError() {
        SkillMeta skillMeta = new SkillMeta("announcement", "公告",
                Collections.<String>emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("announcement")).thenReturn(skillMeta);

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onError("LLM timeout");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "announcement", null, 3600);

        assertThatThrownBy(() -> orchestrator.inject("user001", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("INJECTION_FAILED")
                .hasMessageContaining("LLM timeout");
    }

    @Test
    void shouldThrowInvalidInputWhenNoSkillOrWorkflowProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", null, null, 3600);

        assertThatThrownBy(() -> orchestrator.inject("user001", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_INPUT");
    }
}
