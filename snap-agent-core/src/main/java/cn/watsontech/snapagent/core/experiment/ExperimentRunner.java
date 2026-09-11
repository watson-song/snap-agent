package cn.watsontech.snapagent.core.experiment;

/**
 * SPI for executing experiment variants.
 *
 * <p>The core module defines the experiment model and store. The starter
 * module provides an implementation that actually runs each variant by
 * creating agent tasks with variant-specific configuration (model,
 * temperature, system prompt override) and collecting metrics.</p>
 *
 * <p>Implementations must be thread-safe — multiple experiments may run
 * concurrently.</p>
 */
public interface ExperimentRunner {

    /**
     * Execute all variants of the given experiment.
     *
     * <p>For each variant, the runner should:</p>
     * <ol>
     *   <li>Create an agent task with the variant's model, temperature, and prompt</li>
     *   <li>Execute the task with the experiment's skill and inputs</li>
     *   <li>Collect metrics (duration, tokens, cost, output, iterations)</li>
     *   <li>Store the result on the variant via {@link Experiment.Variant#setResult}</li>
     * </ol>
     *
     * <p>The experiment status should be set to {@link Experiment.Status#RUNNING}
     * at the start and {@link Experiment.Status#COMPLETED} or
     * {@link Experiment.Status#FAILED} when done.</p>
     *
     * @param experiment the experiment to execute
     */
    void run(Experiment experiment);

    /**
     * Check whether the runner is currently executing an experiment.
     *
     * @param experimentId the experiment ID
     * @return true if the experiment is actively running
     */
    boolean isRunning(String experimentId);
}
