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
}
