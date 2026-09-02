package cn.watsontech.snapagent.core.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SkillTemplate")
class SkillTemplateTest {

    @Test
    @DisplayName("constructor sets all fields")
    void shouldSetAllFields() {
        SkillTemplate t = new SkillTemplate(
                "test-skill", "A test skill", "Dev",
                Arrays.asList("test", "example"),
                "🧪", "Author", "1.0",
                "---\nname: test-skill\n---\n# Test",
                Arrays.asList("tool_a", "tool_b"),
                Collections.<InputSpec>emptyList(),
                false
        );

        assertThat(t.getName()).isEqualTo("test-skill");
        assertThat(t.getDescription()).isEqualTo("A test skill");
        assertThat(t.getCategory()).isEqualTo("Dev");
        assertThat(t.getTags()).containsExactly("test", "example");
        assertThat(t.getIcon()).isEqualTo("🧪");
        assertThat(t.getAuthor()).isEqualTo("Author");
        assertThat(t.getVersion()).isEqualTo("1.0");
        assertThat(t.getContent()).contains("test-skill");
        assertThat(t.getTools()).containsExactly("tool_a", "tool_b");
        assertThat(t.isInstalled()).isFalse();
    }

    @Test
    @DisplayName("defaults applied for null optional fields")
    void shouldApplyDefaults() {
        SkillTemplate t = new SkillTemplate(
                "skill", "desc", null,
                null, null, null, null, "content",
                null, null, false
        );

        assertThat(t.getIcon()).isEqualTo("📋");
        assertThat(t.getAuthor()).isEqualTo("SnapAgent");
        assertThat(t.getVersion()).isEqualTo("1.0");
        assertThat(t.getTags()).isEmpty();
        assertThat(t.getTools()).isEmpty();
        assertThat(t.getInputs()).isEmpty();
    }

    @Test
    @DisplayName("withInstalled returns copy with new installed flag")
    void shouldReturnCopyWithInstalledFlag() {
        SkillTemplate original = new SkillTemplate(
                "skill", "desc", "Cat",
                Collections.<String>emptyList(), "📋", "Auth", "1.0",
                "content", Collections.<String>emptyList(),
                Collections.<InputSpec>emptyList(), false
        );

        SkillTemplate installed = original.withInstalled(true);

        assertThat(installed.isInstalled()).isTrue();
        assertThat(original.isInstalled()).isFalse();
        assertThat(installed.getName()).isEqualTo("skill");
        assertThat(installed.getDescription()).isEqualTo("desc");
    }

    @Test
    @DisplayName("tags list is unmodifiable")
    void shouldReturnUnmodifiableTags() {
        SkillTemplate t = new SkillTemplate(
                "skill", "desc", null,
                Arrays.asList("a", "b"), null, null, null, "content",
                null, null, false
        );

        assertThat(t.getTags()).containsExactly("a", "b");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> t.getTags().add("c"));
    }
}
