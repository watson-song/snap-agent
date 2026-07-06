package com.watsontech.snapagent.core.skill;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SkillLoader} frontmatter parsing.
 */
class SkillLoaderTest {

    private final SkillLoader loader = new SkillLoader();

    @Test
    void shouldParseValidFrontmatterWhenAllFieldsPresent() {
        String content = "---\n"
                + "name: test-skill\n"
                + "description: 测试 skill\n"
                + "tools: [mysql_query]\n"
                + "inputs:\n"
                + "  - key: skuCode\n"
                + "    label: 件号\n"
                + "    required: true\n"
                + "    type: string\n"
                + "---\n"
                + "# Body\n"
                + "WHERE sku_code='{skuCode}'\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getName()).isEqualTo("test-skill");
        assertThat(meta.getDescription()).isEqualTo("测试 skill");
        assertThat(meta.getTools()).containsExactly("mysql_query");
        assertThat(meta.getInputs()).hasSize(1);
        assertThat(meta.getInputs().get(0).getKey()).isEqualTo("skuCode");
        assertThat(meta.getInputs().get(0).getLabel()).isEqualTo("件号");
        assertThat(meta.getInputs().get(0).isRequired()).isTrue();
        assertThat(meta.getInputs().get(0).getType()).isEqualTo("string");
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getBody()).contains("# Body");
        assertThat(meta.getBody()).contains("{skuCode}");
    }

    @Test
    void shouldReturnEmptyInputsWhenNoInputsDeclared() {
        String content = "---\n"
                + "name: simple\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "body text\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getInputs()).isEmpty();
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getBody()).contains("body text");
    }

    @Test
    void shouldMarkInvalidWhenNameMissing() {
        String content = "---\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("name");
    }

    @Test
    void shouldMarkInvalidWhenDescriptionMissing() {
        String content = "---\n"
                + "name: s1\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("description");
    }

    @Test
    void shouldDefaultToEmptyToolsWhenToolsMissing() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getTools()).isEmpty();
    }

    @Test
    void shouldMarkInvalidWhenFirstLineNotFrontmatterDelimiter() {
        String content = "# Not a skill\n"
                + "name: s1\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("frontmatter");
    }

    @Test
    void shouldMarkInvalidWhenNoClosingDelimiter() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("frontmatter");
    }

    @Test
    void shouldMarkInvalidWhenInputMissingKey() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "inputs:\n"
                + "  - label: no-key\n"
                + "    required: true\n"
                + "    type: string\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("key");
    }

    @Test
    void shouldParseEnumInputWithOptions() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "inputs:\n"
                + "  - key: env\n"
                + "    label: 环境\n"
                + "    required: true\n"
                + "    type: enum\n"
                + "    options: [sit, uat, prod]\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getInputs()).hasSize(1);
        InputSpec input = meta.getInputs().get(0);
        assertThat(input.getType()).isEqualTo("enum");
        assertThat(input.getOptions()).containsExactly("sit", "uat", "prod");
    }

    @Test
    void shouldParseInputWithDefaultValue() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "inputs:\n"
                + "  - key: env\n"
                + "    label: 环境\n"
                + "    required: false\n"
                + "    type: string\n"
                + "    default: sit\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getInputs().get(0).getDefaultValue()).isEqualTo("sit");
        assertThat(meta.getInputs().get(0).isRequired()).isFalse();
    }

    @Test
    void shouldExtractBodyAfterClosingDelimiter() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "## Phase 1\n"
                + "Some diagnostic content\n"
                + "WHERE sku_code='{skuCode}'\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getBody()).startsWith("## Phase 1");
        assertThat(meta.getBody()).contains("{skuCode}");
    }

    @Test
    void shouldParseMultipleTools() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query, redis_get]\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getTools()).containsExactly("mysql_query", "redis_get");
    }

    @Test
    void shouldHandleNullContent() {
        SkillMeta meta = loader.parse(null);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
    }

    @Test
    void shouldHandleEmptyContent() {
        SkillMeta meta = loader.parse("");

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
    }

    @Test
    void shouldMarkInvalidWhenToolsNotList() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: mysql_query\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("tools");
    }

    @Test
    void shouldParseInputsBlockListFormat() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "inputs:\n"
                + "  - { key: skuCode, label: 件号, required: true, type: string }\n"
                + "  - { key: env, label: 环境, required: false, type: enum, options: [sit, uat] }\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getInputs()).hasSize(2);
        assertThat(meta.getInputs().get(0).getKey()).isEqualTo("skuCode");
        assertThat(meta.getInputs().get(1).getKey()).isEqualTo("env");
        assertThat(meta.getInputs().get(1).getOptions()).containsExactly("sit", "uat");
    }

    @Test
    void shouldParseShortcutsWhenPresent() {
        String content = "---\n"
                + "name: redis-query\n"
                + "description: Redis key inspection\n"
                + "tools: [redis_get]\n"
                + "shortcuts:\n"
                + "  - label: \"检查Key\"\n"
                + "    message: \"检查一个Redis Key是否存在\"\n"
                + "  - label: \"读取Key值\"\n"
                + "    message: \"读取一个Redis Key的值\"\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getShortcuts()).hasSize(2);
        assertThat(meta.getShortcuts().get(0).getLabel()).isEqualTo("检查Key");
        assertThat(meta.getShortcuts().get(0).getMessage()).isEqualTo("检查一个Redis Key是否存在");
        assertThat(meta.getShortcuts().get(1).getLabel()).isEqualTo("读取Key值");
        assertThat(meta.getShortcuts().get(1).getMessage()).isEqualTo("读取一个Redis Key的值");
    }

    @Test
    void shouldDefaultToEmptyShortcutsWhenNotDeclared() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getShortcuts()).isEmpty();
    }

    @Test
    void shouldMarkInvalidWhenShortcutMissingLabel() {
        String content = "---\n"
                + "name: s1\n"
                + "description: desc\n"
                + "tools: [mysql_query]\n"
                + "shortcuts:\n"
                + "  - message: \"only message\"\n"
                + "---\n"
                + "body\n";

        SkillMeta meta = loader.parse(content);

        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.INVALID);
        assertThat(meta.getUnavailableReason()).contains("shortcut");
    }
}
