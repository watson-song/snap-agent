package cn.watsontech.snapagent.core.skill;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMetaBuilderTest {

    @Test
    void builderShouldCreateSkillMetaWithDefaults() {
        SkillMeta meta = SkillMeta.builder()
                .name("test-skill")
                .description("A test skill")
                .build();

        assertThat(meta.getName()).isEqualTo("test-skill");
        assertThat(meta.getDescription()).isEqualTo("A test skill");
        assertThat(meta.getTools()).isNull();
        assertThat(meta.getInputs()).isEmpty();
        assertThat(meta.getShortcuts()).isEmpty();
        assertThat(meta.getOutputFormat()).isEmpty();
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getSource()).isEqualTo("custom");
        assertThat(meta.isOverridesBuiltin()).isFalse();
        assertThat(meta.getRequiredPermission()).isEmpty();
        assertThat(meta.getMode()).isEqualTo(SkillMode.READ_ONLY);
    }

    @Test
    void builderShouldSetAllFields() {
        SkillMeta meta = SkillMeta.builder()
                .name("full-skill")
                .description("Full skill")
                .tools(Arrays.asList("tool1", "tool2"))
                .inputs(Collections.<InputSpec>emptyList())
                .shortcuts(Collections.singletonList(new Shortcut("Go", "hello")))
                .body("# Body content")
                .outputFormat("json")
                .availability(SkillAvailability.UNAVAILABLE)
                .unavailableReason("missing tool")
                .source("builtin")
                .overridesBuiltin(true)
                .requiredPermission("snap-agent:admin")
                .mode(SkillMode.READ_WRITE)
                .build();

        assertThat(meta.getName()).isEqualTo("full-skill");
        assertThat(meta.getTools()).containsExactly("tool1", "tool2");
        assertThat(meta.getOutputFormat()).isEqualTo("json");
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.UNAVAILABLE);
        assertThat(meta.getUnavailableReason()).isEqualTo("missing tool");
        assertThat(meta.getSource()).isEqualTo("builtin");
        assertThat(meta.isOverridesBuiltin()).isTrue();
        assertThat(meta.getRequiredPermission()).isEqualTo("snap-agent:admin");
        assertThat(meta.getMode()).isEqualTo(SkillMode.READ_WRITE);
    }

    @Test
    void builderShouldProduceEquivalentResultToConstructor() {
        SkillMeta fromConstructor = new SkillMeta("name", "desc",
                Arrays.asList("t1"), Collections.<InputSpec>emptyList(),
                Collections.<Shortcut>emptyList(), "body", "fmt",
                SkillAvailability.AVAILABLE, null, "custom", false,
                "perm", SkillMode.READ_WRITE);

        SkillMeta fromBuilder = SkillMeta.builder()
                .name("name")
                .description("desc")
                .tools(Arrays.asList("t1"))
                .body("body")
                .outputFormat("fmt")
                .requiredPermission("perm")
                .mode(SkillMode.READ_WRITE)
                .build();

        assertThat(fromBuilder.getName()).isEqualTo(fromConstructor.getName());
        assertThat(fromBuilder.getDescription()).isEqualTo(fromConstructor.getDescription());
        assertThat(fromBuilder.getTools()).isEqualTo(fromConstructor.getTools());
        assertThat(fromBuilder.getBody()).isEqualTo(fromConstructor.getBody());
        assertThat(fromBuilder.getOutputFormat()).isEqualTo(fromConstructor.getOutputFormat());
        assertThat(fromBuilder.getRequiredPermission()).isEqualTo(fromConstructor.getRequiredPermission());
        assertThat(fromBuilder.getMode()).isEqualTo(fromConstructor.getMode());
    }
}
