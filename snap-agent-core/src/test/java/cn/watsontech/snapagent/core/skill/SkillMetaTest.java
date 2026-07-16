package cn.watsontech.snapagent.core.skill;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMetaTest {

    @Test
    void shouldHoldAllFields() {
        InputSpec input = new InputSpec("k", "l", true, "string", null, null);
        SkillMeta meta = new SkillMeta("name-1", "desc-1",
                Arrays.asList("mysql_query"),
                Arrays.asList(input),
                "body text",
                SkillAvailability.AVAILABLE, null);

        assertThat(meta.getName()).isEqualTo("name-1");
        assertThat(meta.getDescription()).isEqualTo("desc-1");
        assertThat(meta.getTools()).containsExactly("mysql_query");
        assertThat(meta.getInputs()).hasSize(1);
        assertThat(meta.getBody()).isEqualTo("body text");
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getUnavailableReason()).isNull();
    }

    @Test
    void shouldReturnEmptyListWhenToolsNull() {
        SkillMeta meta = new SkillMeta("n", "d", null, null, "b",
                SkillAvailability.INVALID, "reason");

        assertThat(meta.getTools()).isEmpty();
        assertThat(meta.getInputs()).isEmpty();
        assertThat(meta.getUnavailableReason()).isEqualTo("reason");
    }

    @Test
    void shouldHaveToString() {
        SkillMeta meta = new SkillMeta("s1", "d", null, null, "b",
                SkillAvailability.AVAILABLE, null);

        assertThat(meta.toString()).contains("s1");
        assertThat(meta.toString()).contains("AVAILABLE");
    }

    @Test
    void shouldHoldAllAvailabilityValues() {
        for (SkillAvailability a : SkillAvailability.values()) {
            SkillMeta meta = new SkillMeta("n", "d", null, null, "b", a, null);
            assertThat(meta.getAvailability()).isEqualTo(a);
        }
    }

    @Test
    void shouldDefaultRequiredPermissionToEmptyString() {
        SkillMeta meta = new SkillMeta("n", "d", null, null, "b",
                SkillAvailability.AVAILABLE, null);

        assertThat(meta.getRequiredPermission()).isEmpty();
    }

    @Test
    void shouldHoldRequiredPermissionWhenProvided() {
        SkillMeta meta = new SkillMeta("n", "d",
                Collections.singletonList("mysql_query"),
                Collections.<InputSpec>emptyList(),
                Collections.<Shortcut>emptyList(),
                "b", SkillAvailability.AVAILABLE, null,
                "custom", false, "snap-agent:db-query");

        assertThat(meta.getRequiredPermission()).isEqualTo("snap-agent:db-query");
    }

    @Test
    void shouldReturnCopyWithRequiredPermissionViaWithMethod() {
        SkillMeta original = new SkillMeta("n", "d", null, null, "b",
                SkillAvailability.AVAILABLE, null);

        SkillMeta updated = original.withRequiredPermission("snap-agent:admin");

        assertThat(original.getRequiredPermission()).isEmpty();
        assertThat(updated.getRequiredPermission()).isEqualTo("snap-agent:admin");
        assertThat(updated.getName()).isEqualTo("n");
    }

    @Test
    void shouldPreserveRequiredPermissionThroughWithSourceAndOverrides() {
        SkillMeta original = new SkillMeta("n", "d", null, null,
                Collections.<Shortcut>emptyList(), "b",
                SkillAvailability.AVAILABLE, null,
                "custom", false, "snap-agent:db-query");

        SkillMeta withSource = original.withSource("builtin");
        SkillMeta withOverride = original.withOverridesBuiltin(true);

        assertThat(withSource.getRequiredPermission()).isEqualTo("snap-agent:db-query");
        assertThat(withOverride.getRequiredPermission()).isEqualTo("snap-agent:db-query");
    }

    @Test
    void shouldHandleNullRequiredPermissionAsEmptyString() {
        SkillMeta meta = new SkillMeta("n", "d", null, null,
                Collections.<Shortcut>emptyList(), "b",
                SkillAvailability.AVAILABLE, null,
                "custom", false, null);

        assertThat(meta.getRequiredPermission()).isEmpty();
    }
}
