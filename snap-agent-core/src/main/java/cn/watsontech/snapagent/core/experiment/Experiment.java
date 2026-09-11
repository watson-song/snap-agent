package cn.watsontech.snapagent.core.experiment;

import java.util.*;

/**
 * An A/B experiment that compares agent behavior across different configurations.
 *
 * <p>Each experiment runs the same skill + inputs against multiple variants
 * (different models, temperatures, system prompts) and records metrics
 * for comparison.</p>
 *
 * <p>Thread-safe: status transitions are synchronized.</p>
 */
public class Experiment {

    public enum Status { CREATED, RUNNING, COMPLETED, FAILED }

    private final String id;
    private final String name;
    private final String description;
    private final String skillId;
    private final Map<String, String> inputs;
    private final List<Variant> variants;
    private volatile Status status;
    private final long createdAt;
    private volatile long completedAt;

    public Experiment(String id, String name, String description,
                      String skillId, Map<String, String> inputs,
                      List<Variant> variants) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.skillId = skillId;
        this.inputs = inputs != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, String>(inputs))
                : Collections.<String, String>emptyMap();
        this.variants = variants != null
                ? Collections.unmodifiableList(new ArrayList<Variant>(variants))
                : Collections.<Variant>emptyList();
        this.status = Status.CREATED;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSkillId() { return skillId; }
    public Map<String, String> getInputs() { return inputs; }
    public List<Variant> getVariants() { return variants; }
    public Status getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
    public long getCompletedAt() { return completedAt; }

    public synchronized void setStatus(Status status) {
        this.status = status;
        if (status == Status.COMPLETED || status == Status.FAILED) {
            this.completedAt = System.currentTimeMillis();
        }
    }

    /**
     * A single variant (configuration) within an experiment.
     */
    public static class Variant {
        private final String name;
        private final String model;
        private final Double temperature;
        private final String systemPromptOverride;
        private volatile VariantResult result;

        public Variant(String name, String model, Double temperature, String systemPromptOverride) {
            this.name = name;
            this.model = model;
            this.temperature = temperature;
            this.systemPromptOverride = systemPromptOverride;
        }

        public String getName() { return name; }
        public String getModel() { return model; }
        public Double getTemperature() { return temperature; }
        public String getSystemPromptOverride() { return systemPromptOverride; }
        public VariantResult getResult() { return result; }
        public void setResult(VariantResult result) { this.result = result; }
    }

    /**
     * Result metrics for a single variant execution.
     */
    public static class VariantResult {
        private final long durationMs;
        private final int inputTokens;
        private final int outputTokens;
        private final double cost;
        private final String output;
        private final int iterationCount;
        private final String error;

        public VariantResult(long durationMs, int inputTokens, int outputTokens,
                             double cost, String output, int iterationCount, String error) {
            this.durationMs = durationMs;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cost = cost;
            this.output = output;
            this.iterationCount = iterationCount;
            this.error = error;
        }

        public long getDurationMs() { return durationMs; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public double getCost() { return cost; }
        public String getOutput() { return output; }
        public int getIterationCount() { return iterationCount; }
        public String getError() { return error; }
        public boolean isSuccess() { return error == null; }
    }
}
