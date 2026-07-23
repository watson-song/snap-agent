package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link YamlWorkflowLoader}.
 */
class YamlWorkflowLoaderTest {

    @TempDir
    Path tempDir;

    private static final String SIMPLE_YAML =
            "name: test-workflow\n" +
            "description: \"A test workflow\"\n" +
            "steps:\n" +
            "  - name: step1\n" +
            "    skill: health-check\n" +
            "    inputs:\n" +
            "      service: \"${trigger.service}\"\n" +
            "    onFailure: STOP\n" +
            "  - name: step2\n" +
            "    skill: error-spike\n" +
            "    condition: \"${step1.result.contains('error')}\"\n" +
            "    inputs:\n" +
            "      timeWindow: \"1h\"\n" +
            "    onFailure: SKIP\n";

    private void writeFile(String fileName, String content) throws IOException {
        Files.write(tempDir.resolve(fileName), content.getBytes());
    }

    @Test
    void shouldLoadSingleWorkflowFile() throws IOException {
        writeFile("test-workflow.yml", SIMPLE_YAML);
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);

        WorkflowDefinition wf = loader.load("test-workflow");
        assertThat(wf).isNotNull();
        assertThat(wf.getName()).isEqualTo("test-workflow");
        assertThat(wf.getDescription()).isEqualTo("A test workflow");
        assertThat(wf.getSteps()).hasSize(2);

        WorkflowStep step1 = wf.getSteps().get(0);
        assertThat(step1.getName()).isEqualTo("step1");
        assertThat(step1.getSkill()).isEqualTo("health-check");
        assertThat(step1.getInputs()).containsEntry("service", "${trigger.service}");
        assertThat(step1.getOnFailure()).isEqualTo("STOP");

        WorkflowStep step2 = wf.getSteps().get(1);
        assertThat(step2.getName()).isEqualTo("step2");
        assertThat(step2.getSkill()).isEqualTo("error-spike");
        assertThat(step2.getCondition()).isEqualTo("${step1.result.contains('error')}");
        assertThat(step2.getOnFailure()).isEqualTo("SKIP");
    }

    @Test
    void shouldReturnNullForNonexistentWorkflow() {
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("nonexistent");
        assertThat(wf).isNull();
    }

    @Test
    void shouldLoadAllWorkflowsFromDirectory() throws IOException {
        writeFile("wf1.yml", "name: wf1\ndescription: \"Workflow 1\"\nsteps:\n  - name: s1\n    skill: skill-a\n");
        writeFile("wf2.yml", "name: wf2\ndescription: \"Workflow 2\"\nsteps:\n  - name: s2\n    skill: skill-b\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        List<WorkflowDefinition> workflows = loader.loadAll();

        assertThat(workflows).hasSize(2);
        assertThat(workflows).extracting(WorkflowDefinition::getName)
                .containsExactlyInAnyOrder("wf1", "wf2");
    }

    @Test
    void shouldReturnEmptyListWhenDirectoryDoesNotExist() {
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir.resolve("nonexistent"));
        List<WorkflowDefinition> workflows = loader.loadAll();
        assertThat(workflows).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenDirIsNull() {
        YamlWorkflowLoader loader = new YamlWorkflowLoader(null);
        List<WorkflowDefinition> workflows = loader.loadAll();
        assertThat(workflows).isEmpty();
    }

    @Test
    void shouldHandleWorkflowWithoutSteps() throws IOException {
        writeFile("empty.yml", "name: empty-workflow\ndescription: \"No steps\"\n");
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);

        WorkflowDefinition wf = loader.load("empty");
        assertThat(wf).isNotNull();
        assertThat(wf.getName()).isEqualTo("empty-workflow");
        assertThat(wf.getSteps()).isEmpty();
    }

    @Test
    void shouldHandleWorkflowWithOnFailureDefaultsToStop() throws IOException {
        writeFile("defaults.yml",
                "name: defaults-workflow\n" +
                "steps:\n" +
                "  - name: s1\n" +
                "    skill: skill-a\n");
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);

        WorkflowDefinition wf = loader.load("defaults");
        assertThat(wf.getSteps().get(0).getOnFailure()).isEqualTo("STOP");
    }

    // ---- GAP-8: Bad / empty / malformed YAML tolerance ----

    @Test
    void shouldSkipMalformedYamlFileAndLoadValidOnes() throws IOException {
        // Valid file
        writeFile("good.yml",
                "name: good-workflow\n" +
                "description: \"valid\"\n" +
                "steps:\n" +
                "  - name: s1\n" +
                "    skill: skill-a\n");
        // Malformed YAML — tab used for indentation, which SnakeYAML rejects
        writeFile("bad.yml",
                "name: bad-workflow\n" +
                "\ttab-indent: not-allowed-in-yaml\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        List<WorkflowDefinition> workflows = loader.loadAll();

        // Only the valid file should be loaded
        assertThat(workflows).hasSize(1);
        assertThat(workflows.get(0).getName()).isEqualTo("good-workflow");
    }

    @Test
    void shouldReturnNullForMalformedYamlInLoad() throws IOException {
        // Tab at the start of a line — SnakeYAML throws ScannerException
        writeFile("corrupt.yml",
                "name: corrupt\n" +
                "\tbroken: yaml\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("corrupt");

        assertThat(wf).isNull();
    }

    @Test
    void shouldHandleEmptyYamlFile() throws IOException {
        // Completely empty file — SnakeYAML returns null
        writeFile("blank.yml", "");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("blank");

        assertThat(wf).isNull();
    }

    @Test
    void shouldSkipFileMissingNameField() throws IOException {
        writeFile("noname.yml",
                "description: \"has no name\"\n" +
                "steps:\n" +
                "  - name: s1\n" +
                "    skill: skill-a\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("noname");

        assertThat(wf).isNull();
    }

    @Test
    void shouldSkipFileWithEmptyNameField() throws IOException {
        writeFile("emptyname.yml",
                "name: \"\"\n" +
                "steps:\n" +
                "  - name: s1\n" +
                "    skill: skill-a\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("emptyname");

        assertThat(wf).isNull();
    }

    @Test
    void shouldReturnNullForNullOrEmptyNameInLoad() {
        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);

        assertThat(loader.load(null)).isNull();
        assertThat(loader.load("")).isNull();
    }

    @Test
    void shouldHandleStepMissingNameOrSkill() throws IOException {
        // step 1 has no name, step 2 has no skill — both should be skipped
        writeFile("partial.yml",
                "name: partial-workflow\n" +
                "steps:\n" +
                "  - skill: skill-a\n" +
                "  - name: s2\n" +
                "  - name: s3\n" +
                "    skill: skill-c\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("partial");

        assertThat(wf).isNotNull();
        // Only step s3 should survive (has both name and skill)
        assertThat(wf.getSteps()).hasSize(1);
        assertThat(wf.getSteps().get(0).getName()).isEqualTo("s3");
        assertThat(wf.getSteps().get(0).getSkill()).isEqualTo("skill-c");
    }

    @Test
    void shouldParseStepInputsAsStringValues() throws IOException {
        writeFile("inputs.yml",
                "name: inputs-wf\n" +
                "steps:\n" +
                "  - name: s1\n" +
                "    skill: skill-a\n" +
                "    inputs:\n" +
                "      service: \"${trigger.svc}\"\n" +
                "      count: 42\n" +
                "      flag: true\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("inputs");

        WorkflowStep step = wf.getSteps().get(0);
        assertThat(step.getInputs()).hasSize(3);
        assertThat(step.getInputs().get("service")).isEqualTo("${trigger.svc}");
        // Numeric and boolean values are toString()'d
        assertThat(step.getInputs().get("count")).isEqualTo("42");
        assertThat(step.getInputs().get("flag")).isEqualTo("true");
    }

    @Test
    void shouldHandleNullInputsInStep() throws IOException {
        writeFile("noinputs.yml",
                "name: no-inputs-wf\n" +
                "steps:\n" +
                "  - name: s1\n" +
                "    skill: skill-a\n");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        WorkflowDefinition wf = loader.load("noinputs");

        WorkflowStep step = wf.getSteps().get(0);
        assertThat(step.getInputs()).isEmpty();
    }

    @Test
    void shouldOnlyLoadYmlFilesNotOtherExtensions() throws IOException {
        writeFile("wf1.yml", "name: wf1\nsteps:\n  - name: s1\n    skill: a\n");
        writeFile("wf2.yaml", "name: wf2\nsteps:\n  - name: s2\n    skill: b\n");
        writeFile("readme.txt", "not a workflow");

        YamlWorkflowLoader loader = new YamlWorkflowLoader(tempDir);
        List<WorkflowDefinition> workflows = loader.loadAll();

        // Only .yml files should be loaded (glob is "*.yml")
        assertThat(workflows).hasSize(1);
        assertThat(workflows.get(0).getName()).isEqualTo("wf1");
    }
}
