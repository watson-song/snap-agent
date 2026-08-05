package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.SkillLoader;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the domain-knowledge-discovery skill.
 * Verifies that the skill file can be parsed and has the correct structure.
 */
class DomainKnowledgeDiscoverySkillTest {

    private final SkillLoader loader = new SkillLoader();

    private String loadSkillContent() throws IOException {
        Path skillPath = Paths.get("src/main/resources/docs/skills/domain-knowledge-discovery.md");
        return new String(Files.readAllBytes(skillPath), "UTF-8");
    }

    @Test
    void shouldParseDomainKnowledgeDiscoverySkill() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        assertThat(skill).isNotNull();
        assertThat(skill.getName()).isEqualTo("domain-knowledge-discovery");
        assertThat(skill.getDescription()).contains("领域知识");
    }

    @Test
    void shouldHaveCorrectTools() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        assertThat(skill.getTools()).isNotNull();
        assertThat(skill.getTools()).contains("project_structure", "read_code");
    }

    @Test
    void shouldHaveWorkflowSteps() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 1");
        assertThat(body).contains("Step 2");
        assertThat(body).contains("Step 3");
        assertThat(body).contains("YAML frontmatter");
        assertThat(body).contains("tables:");
        assertThat(body).contains("services:");
        assertThat(body).contains("entry_points:");
    }

    @Test
    void shouldHaveOutputFormat() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("输出要求");
        assertThat(body).contains(".md 文件");
        assertThat(body).contains("文件名使用英文");
    }
}
