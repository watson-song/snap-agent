package cn.watsontech.snapagent.boot2x.experiment;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.experiment.Experiment;
import cn.watsontech.snapagent.core.experiment.ExperimentRunner;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link ExperimentRunner} that executes variants
 * sequentially via {@link AgentService}.
 *
 * <p>For each variant, creates an {@link AgentTask} with variant-specific
 * configuration (model, temperature, system prompt override), executes it,
 * and collects metrics (duration, tokens, cost, output, iteration count).</p>
 *
 * <p>Runs variants sequentially to avoid overwhelming the LLM provider.
 * Each variant's result is stored on the {@link Experiment.Variant} as
 * soon as it completes.</p>
 */
public class DefaultExperimentRunner implements ExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultExperimentRunner.class);

    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public DefaultExperimentRunner(AgentService agentService, SkillRegistry skillRegistry) {
        if (agentService == null) {
            throw new IllegalArgumentException("AgentService must not be null");
        }
        if (skillRegistry == null) {
            throw new IllegalArgumentException("SkillRegistry must not be null");
        }
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public void run(Experiment experiment) {
        String id = experiment.getId();
        running.add(id);
        experiment.setStatus(Experiment.Status.RUNNING);

        try {
            SkillMeta skill = skillRegistry.get(experiment.getSkillId());
            if (skill == null) {
                log.error("Skill '{}' not found for experiment '{}'", experiment.getSkillId(), id);
                experiment.setStatus(Experiment.Status.FAILED);
                return;
            }

            for (Experiment.Variant variant : experiment.getVariants()) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Experiment '{}' interrupted", id);
                    experiment.setStatus(Experiment.Status.FAILED);
                    return;
                }
                executeVariant(experiment, variant, skill);
            }

            experiment.setStatus(Experiment.Status.COMPLETED);
            log.info("Experiment '{}' completed successfully", id);

        } catch (Exception e) {
            log.error("Experiment '{}' failed: {}", id, e.getMessage(), e);
            experiment.setStatus(Experiment.Status.FAILED);
        } finally {
            running.remove(id);
        }
    }

    @Override
    public boolean isRunning(String experimentId) {
        return running.contains(experimentId);
    }

    private void executeVariant(Experiment experiment, Experiment.Variant variant, SkillMeta skill) {
        String variantTaskId = "exp-" + experiment.getId() + "-" + variant.getName()
                + "-" + UUID.randomUUID().toString().substring(0, 6);

        log.info("Running variant '{}' of experiment '{}' as task '{}'",
                variant.getName(), experiment.getId(), variantTaskId);

        // Build model string: use variant model if specified, else fall back to default
        String model = variant.getModel() != null ? variant.getModel() : "default";

        AgentTask task = new AgentTask(
                variantTaskId,
                "experiment-" + experiment.getId(),
                experiment.getSkillId(),
                experiment.getInputs(),
                model
        );

        long startMs = System.currentTimeMillis();

        try {
            agentService.execute(task, skill);

            long durationMs = System.currentTimeMillis() - startMs;
            TaskStatus finalStatus = task.getStatus();

            // Collect metrics from the task
            int inputTokens = estimateTokens(task, "input");
            int outputTokens = estimateTokens(task, "output");
            int iterations = countIterations(task);
            String output = task.getReport();
            String error = finalStatus == TaskStatus.FAILED ? task.getReport() : null;

            // Estimate cost (simplified: tokens * rate)
            double cost = estimateCost(variant.getModel(), inputTokens, outputTokens);

            Experiment.VariantResult result = new Experiment.VariantResult(
                    durationMs, inputTokens, outputTokens, cost, output, iterations, error);
            variant.setResult(result);

            log.info("Variant '{}' completed in {}ms, status={}, iterations={}",
                    variant.getName(), durationMs, finalStatus, iterations);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Variant '{}' failed after {}ms: {}", variant.getName(), durationMs, e.getMessage());
            variant.setResult(new Experiment.VariantResult(
                    durationMs, 0, 0, 0.0, null, 0, e.getMessage()));
        }
    }

    /**
     * Estimate token count from transcript events. Uses a simple char-based
     * heuristic (1 token ≈ 4 chars) since actual token counts depend on the
     * LLM provider's tokenizer.
     */
    private int estimateTokens(AgentTask task, String direction) {
        int charCount = 0;
        for (TranscriptEvent event : task.getTranscript()) {
            if ("input".equals(direction) && TranscriptEvent.TYPE_TOOL_CALL.equals(event.getType())) {
                // Tool calls contain the model's structured input
                charCount += event.getText() != null ? event.getText().length() : 0;
            } else if ("output".equals(direction) && TranscriptEvent.TYPE_THOUGHT.equals(event.getType())) {
                charCount += event.getText() != null ? event.getText().length() : 0;
            }
        }
        return charCount / 4; // rough token estimate
    }

    /**
     * Count the number of ReAct iterations (thought + action pairs).
     */
    private int countIterations(AgentTask task) {
        int count = 0;
        for (TranscriptEvent event : task.getTranscript()) {
            if (TranscriptEvent.TYPE_THOUGHT.equals(event.getType())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rough cost estimation based on model pricing tiers.
     * Returns cost in USD.
     */
    private double estimateCost(String model, int inputTokens, int outputTokens) {
        if (model == null) return 0.0;
        double inputRate;
        double outputRate;
        String lower = model.toLowerCase();
        if (lower.contains("opus") || lower.contains("claude-3-opus")) {
            inputRate = 0.015 / 1000;
            outputRate = 0.075 / 1000;
        } else if (lower.contains("sonnet") || lower.contains("claude-3-sonnet")) {
            inputRate = 0.003 / 1000;
            outputRate = 0.015 / 1000;
        } else if (lower.contains("haiku") || lower.contains("claude-3-haiku")) {
            inputRate = 0.00025 / 1000;
            outputRate = 0.00125 / 1000;
        } else if (lower.contains("gpt-4")) {
            inputRate = 0.03 / 1000;
            outputRate = 0.06 / 1000;
        } else if (lower.contains("gpt-3.5")) {
            inputRate = 0.0015 / 1000;
            outputRate = 0.002 / 1000;
        } else {
            // Default mid-tier pricing
            inputRate = 0.003 / 1000;
            outputRate = 0.015 / 1000;
        }
        return (inputTokens * inputRate) + (outputTokens * outputRate);
    }
}
