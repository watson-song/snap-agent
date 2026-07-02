package com.watsontech.snapagent.core.skill;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class InputSpecTest {

    @Test
    void shouldHoldAllFields() {
        InputSpec spec = new InputSpec("skuCode", "件号", true, "string",
                null, null);

        assertThat(spec.getKey()).isEqualTo("skuCode");
        assertThat(spec.getLabel()).isEqualTo("件号");
        assertThat(spec.isRequired()).isTrue();
        assertThat(spec.getType()).isEqualTo("string");
        assertThat(spec.getOptions()).isEmpty();
        assertThat(spec.getDefaultValue()).isNull();
    }

    @Test
    void shouldHoldOptionsForEnumType() {
        InputSpec spec = new InputSpec("env", "环境", true, "enum",
                Arrays.asList("sit", "uat", "prod"), "sit");

        assertThat(spec.getOptions()).containsExactly("sit", "uat", "prod");
        assertThat(spec.getDefaultValue()).isEqualTo("sit");
    }

    @Test
    void shouldReturnEmptyOptionsWhenNullPassed() {
        InputSpec spec = new InputSpec("k", "l", false, "string", null, null);

        assertThat(spec.getOptions()).isEmpty();
    }

    @Test
    void shouldHaveToString() {
        InputSpec spec = new InputSpec("key1", "label1", true, "string", null, null);

        assertThat(spec.toString()).contains("key1");
        assertThat(spec.toString()).contains("label1");
    }
}
