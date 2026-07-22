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
}
