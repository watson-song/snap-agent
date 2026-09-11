package cn.watsontech.snapagent.boot2x.experiment;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.experiment.Experiment;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("DefaultExperimentRunner")
class DefaultExperimentRunnerTest {

    private AgentService agentService;
    private SkillRegistry skillRegistry;
    private DefaultExperimentRunner runner;

    @BeforeEach
    void setUp() {
        agentService = Mockito.mock(AgentService.class);
        skillRegistry = Mockito.mock(SkillRegistry.class);
        runner = new DefaultExperimentRunner(agentService, skillRegistry);
    }

    private SkillMeta createTestSkill() {
        return new SkillMeta("test-skill", "Test skill",
                Collections.<String>emptyList(),
                Collections.<cn.watsontech.snapagent.core.skill.InputSpec>emptyList(),
                "# Test body",
                SkillAvailability.AVAILABLE, null);
    }

    @Test
    @DisplayName("constructor rejects null AgentService")
    void shouldRejectNullAgentService() {
        assertThatThrownBy(() -> new DefaultExperimentRunner(null, skillRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AgentService");
    }

    @Test
    @DisplayName("constructor rejects null SkillRegistry")
    void shouldRejectNullSkillRegistry() {
        assertThatThrownBy(() -> new DefaultExperimentRunner(agentService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SkillRegistry");
    }

    @Test
    @DisplayName("run sets experiment to COMPLETED on success")
    void shouldCompleteOnSuccess() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        // Simulate successful execution
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("Test result output");
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        Experiment experiment = createExperiment("exp-1");
        runner.run(experiment);

        assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
        verify(agentService, times(2)).execute(any(AgentTask.class), eq(skill));
    }

    @Test
    @DisplayName("run sets experiment to FAILED when skill not found")
    void shouldFailWhenSkillNotFound() {
        when(skillRegistry.get("missing-skill")).thenReturn(null);

        Experiment experiment = createExperiment("exp-1");
        runner.run(experiment);

        assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.FAILED);
        verify(agentService, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    @Test
    @DisplayName("run stores variant results after execution")
    void shouldStoreVariantResults() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("Result output");
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        Experiment experiment = createExperiment("exp-1");
        runner.run(experiment);

        for (Experiment.Variant v : experiment.getVariants()) {
            assertThat(v.getResult()).isNotNull();
            assertThat(v.getResult().isSuccess()).isTrue();
            assertThat(v.getResult().getDurationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("run records error for failed variant")
    void shouldRecordFailedVariant() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            task.setReport("LLM timeout");
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        Experiment experiment = createExperiment("exp-1");
        runner.run(experiment);

        // Even with failed variants, experiment completes (all variants ran)
        assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
        for (Experiment.Variant v : experiment.getVariants()) {
            assertThat(v.getResult()).isNotNull();
            assertThat(v.getResult().isSuccess()).isFalse();
            assertThat(v.getResult().getError()).isEqualTo("LLM timeout");
        }
    }

    @Test
    @DisplayName("run handles exception from AgentService")
    void shouldHandleException() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        doThrow(new RuntimeException("Connection refused"))
                .when(agentService).execute(any(AgentTask.class), eq(skill));

        Experiment experiment = createExperiment("exp-1");
        runner.run(experiment);

        // Experiment should still complete — each variant captures its own error
        assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
        for (Experiment.Variant v : experiment.getVariants()) {
            assertThat(v.getResult()).isNotNull();
            assertThat(v.getResult().getError()).contains("Connection refused");
        }
    }

    @Test
    @DisplayName("isRunning returns true while experiment runs")
    void shouldTrackRunningState() {
        assertThat(runner.isRunning("exp-1")).isFalse();

        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        // Use a latch-like approach: check isRunning from inside the execute call
        final boolean[] wasRunning = {false};
        doAnswer(invocation -> {
            wasRunning[0] = runner.isRunning("exp-1");
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("ok");
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        Experiment experiment = createExperiment("exp-1");
        runner.run(experiment);

        assertThat(wasRunning[0]).isTrue();
        assertThat(runner.isRunning("exp-1")).isFalse();
    }

    @Test
    @DisplayName("run handles variant with null model (defaults to 'default')")
    void shouldHandleNullModel() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("ok");
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("q", "test");
        List<Experiment.Variant> variants = new ArrayList<Experiment.Variant>();
        variants.add(new Experiment.Variant("null-model", null, null, null));
        Experiment exp = new Experiment("exp-null", "Null Model", "desc", "test-skill", inputs, variants);

        runner.run(exp);

        assertThat(exp.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
        assertThat(exp.getVariants().get(0).getResult()).isNotNull();
    }

    @Test
    @DisplayName("run captures transcript metrics (tokens + iterations)")
    void shouldCaptureTranscriptMetrics() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("analysis result");
            // Simulate transcript events using factory methods
            task.addTranscriptEvent(cn.watsontech.snapagent.core.agent.TranscriptEvent.thought(
                    "Analyzing the log data carefully for anomalies"));
            task.addTranscriptEvent(cn.watsontech.snapagent.core.agent.TranscriptEvent.toolCall(
                    "tc1", "read_logs", java.util.Collections.<String, Object>emptyMap()));
            task.addTranscriptEvent(cn.watsontech.snapagent.core.agent.TranscriptEvent.thought(
                    "Found suspicious login pattern from IP 10.0.0.1"));
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        Experiment experiment = createExperiment("exp-metrics");
        runner.run(experiment);

        Experiment.VariantResult result = experiment.getVariants().get(0).getResult();
        assertThat(result).isNotNull();
        // 2 thoughts * ~50 chars / 4 = ~25 output tokens (approx)
        assertThat(result.getOutputTokens()).isGreaterThan(0);
        // toolCall factory creates events with text=null → input tokens = 0
        // (estimateTokens counts getText() on TYPE_TOOL_CALL events)
        assertThat(result.getInputTokens()).isEqualTo(0);
        // 2 thought events = 2 iterations
        assertThat(result.getIterationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("run assigns different cost for opus vs haiku model")
    void shouldCalculateDifferentCostsByModel() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("output text with enough content to generate tokens for cost comparison testing purposes");
            task.addTranscriptEvent(cn.watsontech.snapagent.core.agent.TranscriptEvent.thought(
                    "thinking about this problem with a reasonably long thought"));
            return null;
        }).when(agentService).execute(any(AgentTask.class), eq(skill));

        // Opus experiment
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("q", "test");
        List<Experiment.Variant> opusVariants = new ArrayList<Experiment.Variant>();
        opusVariants.add(new Experiment.Variant("opus", "claude-3-opus", 0.7, null));
        Experiment opusExp = new Experiment("exp-opus", "Opus", "desc", "test-skill", inputs, opusVariants);
        runner.run(opusExp);

        // Haiku experiment
        List<Experiment.Variant> haikuVariants = new ArrayList<Experiment.Variant>();
        haikuVariants.add(new Experiment.Variant("haiku", "claude-3-haiku", 0.5, null));
        Experiment haikuExp = new Experiment("exp-haiku", "Haiku", "desc", "test-skill", inputs, haikuVariants);
        runner.run(haikuExp);

        double opusCost = opusExp.getVariants().get(0).getResult().getCost();
        double haikuCost = haikuExp.getVariants().get(0).getResult().getCost();
        // Opus is more expensive than Haiku for same tokens
        assertThat(opusCost).isGreaterThan(haikuCost);
        assertThat(opusCost).isGreaterThan(0);
        assertThat(haikuCost).isGreaterThan(0);
    }

    @Test
    @DisplayName("run with empty variants list completes immediately")
    void shouldCompleteWithNoVariants() {
        SkillMeta skill = createTestSkill();
        when(skillRegistry.get("test-skill")).thenReturn(skill);

        Experiment exp = new Experiment("exp-empty", "Empty", "desc", "test-skill", null,
                Collections.<Experiment.Variant>emptyList());

        runner.run(exp);

        assertThat(exp.getStatus()).isEqualTo(Experiment.Status.COMPLETED);
        verify(agentService, never()).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    @Test
    @DisplayName("isRunning returns false for unknown experiment")
    void shouldReturnFalseForUnknown() {
        assertThat(runner.isRunning("unknown-id")).isFalse();
    }

    private Experiment createExperiment(String id) {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("query", "test query");

        List<Experiment.Variant> variants = new ArrayList<Experiment.Variant>();
        variants.add(new Experiment.Variant("gpt4", "gpt-4", 0.7, null));
        variants.add(new Experiment.Variant("haiku", "claude-3-haiku", 0.5, null));

        return new Experiment(id, "Test Experiment", "Test", "test-skill", inputs, variants);
    }
}
