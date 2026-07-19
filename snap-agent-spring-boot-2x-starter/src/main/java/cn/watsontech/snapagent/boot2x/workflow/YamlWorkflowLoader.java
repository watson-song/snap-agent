package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@link WorkflowDefinition} instances from {@code .yml} files in a
 * filesystem directory.
 *
 * <p>Uses SnakeYAML (bundled with Spring Boot) to parse YAML into a raw
 * {@code Map<String,Object>} and then constructs {@link WorkflowDefinition}
 * and {@link WorkflowStep} objects manually. This avoids the need for
 * additional Jackson YAML modules and keeps the core workflow model
 * POJO-based.</p>
 *
 * <p>Each {@code .yml} file represents a single workflow. The file name
 * (without {@code .yml} extension) is not required to match the workflow's
 * internal {@code name} field — the {@code name} field in the YAML takes
 * precedence. However, {@link #load(String)} looks up files by name
 * (appending {@code .yml}).</p>
 */
public class YamlWorkflowLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlWorkflowLoader.class);

    private final Path workflowsDir;
    private final Yaml yaml;

    /**
     * Construct a loader for the given directory.
     *
     * @param workflowsDir the directory containing {@code .yml} workflow files
     *                    (may be {@code null} — {@link #loadAll()} returns an
     *                    empty list in that case)
     */
    public YamlWorkflowLoader(Path workflowsDir) {
        this.workflowsDir = workflowsDir != null
                ? workflowsDir.toAbsolutePath().normalize() : null;
        this.yaml = new Yaml();
    }

    /**
     * Loads all {@code .yml} files from the configured directory.
     *
     * @return an unmodifiable list of parsed workflow definitions (empty if
     *         the directory is null, does not exist, or contains no files)
     */
    public List<WorkflowDefinition> loadAll() {
        if (workflowsDir == null) {
            log.debug("workflows dir is null; no workflows loaded");
            return Collections.emptyList();
        }
        if (!Files.isDirectory(workflowsDir)) {
            log.debug("workflows dir does not exist: {}", workflowsDir);
            return Collections.emptyList();
        }

        List<WorkflowDefinition> result = new ArrayList<WorkflowDefinition>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workflowsDir, "*.yml")) {
            for (Path file : stream) {
                try {
                    WorkflowDefinition def = parseFile(file);
                    if (def != null) {
                        result.add(def);
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to parse workflow file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list workflow files in {}: {}", workflowsDir, e.getMessage());
            return Collections.emptyList();
        }

        log.info("Loaded {} workflow(s) from {}", result.size(), workflowsDir);
        return Collections.unmodifiableList(result);
    }

    /**
     * Loads a single workflow by name (file name without extension).
     *
     * @param name the workflow file name (without {@code .yml})
     * @return the parsed workflow definition, or {@code null} if the file
     *         does not exist or parsing fails
     */
    public WorkflowDefinition load(String name) {
        if (workflowsDir == null || name == null || name.isEmpty()) {
            return null;
        }
        Path file = workflowsDir.resolve(name + ".yml");
        if (!Files.exists(file)) {
            log.debug("Workflow file not found: {}", file);
            return null;
        }
        try {
            return parseFile(file);
        } catch (RuntimeException e) {
            log.warn("Failed to parse workflow file {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Parses a single YAML file into a {@link WorkflowDefinition}.
     */
    @SuppressWarnings("unchecked")
    private WorkflowDefinition parseFile(Path file) {
        Map<String, Object> raw;
        try (InputStream is = Files.newInputStream(file)) {
            raw = yaml.load(is);
        } catch (IOException e) {
            log.warn("Failed to read workflow file {}: {}", file, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("Failed to parse YAML in {}: {}", file, e.getMessage());
            return null;
        }

        if (raw == null) {
            log.warn("Workflow file {} is empty", file);
            return null;
        }

        String name = getString(raw, "name");
        if (name == null || name.isEmpty()) {
            log.warn("Workflow file {} missing 'name' field", file);
            return null;
        }

        String description = getString(raw, "description");

        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        Object stepsRaw = raw.get("steps");
        if (stepsRaw instanceof List) {
            for (Object item : (List<?>) stepsRaw) {
                if (!(item instanceof Map)) {
                    continue;
                }
                WorkflowStep step = parseStep((Map<String, Object>) item);
                if (step != null) {
                    steps.add(step);
                }
            }
        }

        return new WorkflowDefinition(name, description, steps);
    }

    /**
     * Parses a single step map into a {@link WorkflowStep}.
     */
    @SuppressWarnings("unchecked")
    private WorkflowStep parseStep(Map<String, Object> raw) {
        String name = getString(raw, "name");
        String skill = getString(raw, "skill");
        if (name == null || name.isEmpty() || skill == null || skill.isEmpty()) {
            log.warn("Workflow step missing 'name' or 'skill' field: {}", raw);
            return null;
        }

        String condition = getString(raw, "condition");
        String onFailure = getString(raw, "onFailure");

        Map<String, String> inputs = new LinkedHashMap<String, String>();
        Object inputsRaw = raw.get("inputs");
        if (inputsRaw instanceof Map) {
            Map<String, Object> rawMap = (Map<String, Object>) inputsRaw;
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                inputs.put(entry.getKey(), value);
            }
        }

        return new WorkflowStep(name, skill, condition, inputs, onFailure);
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
