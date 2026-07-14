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
}
