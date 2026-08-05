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

    @Test
    void shouldHaveRelatedTableExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("v1.3.0 新增特性");
        assertThat(body).contains("关联表结构完整提取");
        assertThat(body).contains("外键关系");
        assertThat(body).contains("SQL JOIN");
        assertThat(body).contains("关联表识别策略");
    }

    @Test
    void shouldHaveExceptionHandlingExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("异常处理策略提取");
        assertThat(body).contains("try-catch");
        assertThat(body).contains("自定义异常类");
        assertThat(body).contains("DfproServerException");
        assertThat(body).contains("错误信息格式");
    }

    @Test
    void shouldHaveTechnicalImplementationExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("技术实现细节提取");
        assertThat(body).contains("动态表头");
        assertThat(body).contains("MenuDisplayEnum");
        assertThat(body).contains("批量处理");
        assertThat(body).contains("线程池");
        assertThat(body).contains("缓存策略");
    }

    @Test
    void shouldHaveModuleIntegrationExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("模块集成关系提取");
        assertThat(body).contains("数据流转");
        assertThat(body).contains("接口调用");
        assertThat(body).contains("共享数据");
        assertThat(body).contains("依赖关系");
    }

    @Test
    void shouldHaveUpdatedOutputRequirements() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("每个数据表必须包含完整字段列表和类型");
        assertThat(body).contains("必须包含异常处理策略");
        assertThat(body).contains("必须包含技术实现细节");
        assertThat(body).contains("必须包含模块集成关系");
    }

    @Test
    void shouldHaveLegacyProjectMode() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("v1.4.0 新增特性");
        assertThat(body).contains("老项目逆向分析模式");
        assertThat(body).contains("老项目逆向分析四步法");
    }

    @Test
    void shouldHaveDirectoryTreeScanning() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("目录树");
        assertThat(body).contains("宏观认知");
        assertThat(body).contains("tree -L 4");
    }

    @Test
    void shouldHaveDependencyDNAExtraction() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("依赖关系 DNA");
        assertThat(body).contains("pom.xml");
        assertThat(body).contains("@FeignClient");
        assertThat(body).contains("@DubboReference");
    }

    @Test
    void shouldHaveArchitectureReverseEngineering() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("架构分层与调用链逆向");
        assertThat(body).contains("分层职责表");
        assertThat(body).contains("核心链路时序图");
        assertThat(body).contains("外部依赖拓扑");
    }

    @Test
    void shouldHaveTechnicalDebtIdentification() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("技术债务识别");
        assertThat(body).contains("耦合风险");
        assertThat(body).contains("反模式");
        assertThat(body).contains("超大类");
    }

    @Test
    void shouldHaveLegacyProjectTips() throws IOException {
        String content = loadSkillContent();
        SkillMeta skill = loader.parse(content);

        String body = skill.getBody();
        assertThat(body).contains("弯道超车");
        assertThat(body).contains("不要一次性喂大文件");
        assertThat(body).contains("jdepend");
    }
}
