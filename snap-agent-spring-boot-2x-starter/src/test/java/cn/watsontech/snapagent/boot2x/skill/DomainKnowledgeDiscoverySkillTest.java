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
 * Tests for the domain-knowledge-discovery skill (v2.1.0).
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
        assertThat(skill.getTools()).contains("project_structure", "read_code", "generate_module_arch");
    }

    @Test
    void shouldHaveWorkflowSteps() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 1");
        assertThat(body).contains("Step 2");
        assertThat(body).contains("Step 3");
        assertThat(body).contains("Step 4");
        assertThat(body).contains("Step 11");
        assertThat(body).contains("tables:");
        assertThat(body).contains("services:");
        assertThat(body).contains("entry_points:");
    }

    @Test
    void shouldHaveOutputFormat() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("输出规范");
        assertThat(body).contains("文件名使用英文");
        assertThat(body).contains("kebab-case");
    }

    @Test
    void shouldHaveControllerScanning() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("controller/");
        assertThat(body).contains("REST API");
        assertThat(body).contains("@RestController");
        assertThat(body).contains("@RequestMapping");
    }

    @Test
    void shouldHaveEnumExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("enums/");
        assertThat(body).contains("枚举");
    }

    @Test
    void shouldHaveMermaidDiagram() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("mermaid");
        assertThat(body).contains("graph TD");
    }

    @Test
    void shouldHaveBatchGenerationMode() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("批量生成");
        assertThat(body).contains("汇总报告");
    }

    @Test
    void shouldHaveDtoVoAnalysis() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 5");
        assertThat(body).contains("DTO/VO");
        assertThat(body).contains("验证注解");
        assertThat(body).contains("@NotNull");
        assertThat(body).contains("@Size");
    }

    @Test
    void shouldHaveBusinessRuleExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 7");
        assertThat(body).contains("业务规则");
        assertThat(body).contains("已知陷阱");
        assertThat(body).contains("TODO/FIXME");
    }

    @Test
    void shouldHaveSqlPerformanceAnalysis() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 8");
        assertThat(body).contains("SQL 性能");
        assertThat(body).contains("SELECT *");
        assertThat(body).contains("WHERE 条件");
    }

    @Test
    void shouldHaveMultiTenantRecognition() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("多租户");
        assertThat(body).contains("tenant_id");
        assertThat(body).contains("TenantLineHandler");
    }

    @Test
    void shouldHaveDependencyAnalysis() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 9");
        assertThat(body).contains("依赖关系");
        assertThat(body).contains("循环依赖");
        assertThat(body).contains("generate_module_arch");
    }

    @Test
    void shouldHaveDataFlowAnalysis() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 10");
        assertThat(body).contains("数据流向");
        assertThat(body).contains("数据生成");
        assertThat(body).contains("数据消费");
    }

    @Test
    void shouldHaveTableStructureExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 4");
        assertThat(body).contains("@TableName");
        assertThat(body).contains("@TableId");
    }

    @Test
    void shouldHaveProjectScaleAssessment() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("项目规模");
        assertThat(body).contains("小型项目");
        assertThat(body).contains("中型项目");
        assertThat(body).contains("大型项目");
    }

    @Test
    void shouldHaveErrorHandling() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("错误处理");
        assertThat(body).contains("工具不可用");
        assertThat(body).contains("降级策略");
        assertThat(body).contains("循环依赖");
    }

    @Test
    void shouldHaveOutputTemplate() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("业务描述");
        assertThat(body).contains("核心服务");
        assertThat(body).contains("数据流向");
        assertThat(body).contains("已知陷阱");
    }

    @Test
    void shouldHaveCrossModuleScanning() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("跨模块扫描");
        assertThat(body).contains("基础设施模块");
        assertThat(body).contains("系统管理模块");
    }

    @Test
    void shouldHaveCommentedCodeDetection() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("注释代码检测");
        assertThat(body).contains("被注释的 Controller");
    }

    @Test
    void shouldHaveInfrastructureDomainHandling() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("Step 10.5");
        assertThat(body).contains("基础设施域识别");
        assertThat(body).contains("常量/枚举服务");
    }

    @Test
    void shouldHaveScheduledTaskExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("@Scheduled");
        assertThat(body).contains("定时任务");
    }
}
