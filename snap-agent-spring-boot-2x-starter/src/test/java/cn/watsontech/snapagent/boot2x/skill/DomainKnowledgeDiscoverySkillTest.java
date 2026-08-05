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

    @Test
    void shouldHaveControllerScanning() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("controller/");
        assertThat(body).contains("REST API");
        assertThat(body).contains("api_endpoints:");
    }

    @Test
    void shouldHaveEnumExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("enums/");
        assertThat(body).contains("业务枚举");
        assertThat(body).contains("StatusEnum");
    }

    @Test
    void shouldHaveMermaidDiagram() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Mermaid");
        assertThat(body).contains("```mermaid");
        assertThat(body).contains("业务概念关系图");
    }

    @Test
    void shouldHaveBatchGenerationMode() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("批量生成模式");
        assertThat(body).contains("批次 1");
        assertThat(body).contains("核心业务域");
    }

    @Test
    void shouldHaveDtoVoIdentification() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("v1.2.0 新增特性");
        assertThat(body).contains("DTO/VO 类识别增强");
        assertThat(body).contains("dto/");
        assertThat(body).contains("vo/");
        assertThat(body).contains("验证注解");
    }

    @Test
    void shouldHaveBusinessRuleExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("业务规则智能提取");
        assertThat(body).contains("@NotNull");
        assertThat(body).contains("@Size");
        assertThat(body).contains("条件判断");
        assertThat(body).contains("状态转换");
    }

    @Test
    void shouldHaveServiceDependencyAnalysis() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("服务依赖深度分析");
        assertThat(body).contains("调用链");
        assertThat(body).contains("循环依赖");
        assertThat(body).contains("依赖深度");
    }

    @Test
    void shouldHaveMultiTenantRecognition() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("多租户模式识别");
        assertThat(body).contains("tenant_id");
        assertThat(body).contains("租户隔离");
        assertThat(body).contains("跨租户操作");
    }

    @Test
    void shouldHaveSqlExtractionEnhancement() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("SQL 提取增强");
        assertThat(body).contains("@Select");
        assertThat(body).contains("@Insert");
        assertThat(body).contains("性能分析");
        assertThat(body).contains("N+1 查询");
    }
}
