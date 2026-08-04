package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DomainKnowledgeLoader} — verifies YAML frontmatter parsing
 * and directory loading.
 */
class DomainKnowledgeLoaderTest {

    @TempDir
    Path tempDir;

    private DomainKnowledgeLoader loader;
    private InMemoryDomainKnowledgeIndex index;

    @BeforeEach
    void setUp() {
        index = new InMemoryDomainKnowledgeIndex();
        loader = new DomainKnowledgeLoader(index, null);
    }

    // ---- AC1: Parse file with frontmatter ----

    @Test
    void parseFile_withFrontmatter_shouldExtractAllFields() throws Exception {
        writeMd("allocation-plan.md",
                "---\n"
                + "name: 调拨计划\n"
                + "tables: [drp_allocation_plan, drp_allocation_detail]\n"
                + "services: [AllocationPlanService, BalanceAlgorithmService]\n"
                + "entry_points: [ReplenishmentPlanTask.generate()]\n"
                + "related_concepts: [补货策略, 安全库存]\n"
                + "tags: [replenishment, allocation]\n"
                + "---\n\n"
                + "# 调拨计划\n\n"
                + "## 业务描述\n"
                + "航材消耗件在多基地间的库存平衡调拨。\n\n"
                + "## 业务规则\n"
                + "1. 只有航材消耗件才走多基地平衡算法\n");

        DomainKnowledge dk = loader.parseFile(tempDir.resolve("allocation-plan.md"));

        assertThat(dk).isNotNull();
        assertThat(dk.getName()).isEqualTo("调拨计划");
        assertThat(dk.getTables()).containsExactly("drp_allocation_plan", "drp_allocation_detail");
        assertThat(dk.getServices()).containsExactly("AllocationPlanService", "BalanceAlgorithmService");
        assertThat(dk.getEntryPoints()).containsExactly("ReplenishmentPlanTask.generate()");
        assertThat(dk.getRelatedConcepts()).containsExactly("补货策略", "安全库存");
        assertThat(dk.getTags()).containsExactly("replenishment", "allocation");
        assertThat(dk.getContent()).contains("# 调拨计划");
        assertThat(dk.getContent()).contains("航材消耗件在多基地间的库存平衡调拨");
    }

    // AC1b: Block list format (- item)
    @Test
    void parseFile_withBlockListFormat_shouldParseCorrectly() throws Exception {
        writeMd("test.md",
                "---\n"
                + "name: 测试概念\n"
                + "tables:\n"
                + "  - table_a\n"
                + "  - table_b\n"
                + "services:\n"
                + "  - ServiceA\n"
                + "---\n\n"
                + "内容\n");

        DomainKnowledge dk = loader.parseFile(tempDir.resolve("test.md"));

        assertThat(dk).isNotNull();
        assertThat(dk.getName()).isEqualTo("测试概念");
        assertThat(dk.getTables()).containsExactly("table_a", "table_b");
        assertThat(dk.getServices()).containsExactly("ServiceA");
    }

    // ---- AC2: File without frontmatter ----

    @Test
    void parseFile_withoutFrontmatter_shouldReturnNull() throws Exception {
        writeMd("no-frontmatter.md", "# Just a regular markdown\n\nNo frontmatter here.");

        DomainKnowledge dk = loader.parseFile(tempDir.resolve("no-frontmatter.md"));
        assertThat(dk).isNull();
    }

    @Test
    void parseFile_incompleteFrontmatter_shouldReturnNull() throws Exception {
        writeMd("incomplete.md", "---\nname: test\n\nNo closing delimiter.");

        DomainKnowledge dk = loader.parseFile(tempDir.resolve("incomplete.md"));
        assertThat(dk).isNull();
    }

    @Test
    void parseFile_noNameInFrontmatter_shouldReturnNull() throws Exception {
        writeMd("no-name.md", "---\ntables: [t1]\n---\n\nContent");

        DomainKnowledge dk = loader.parseFile(tempDir.resolve("no-name.md"));
        assertThat(dk).isNull();
    }

    // ---- AC3: Load directory ----

    @Test
    void loadFromDirectory_withMultipleFiles_shouldLoadAll() throws Exception {
        writeMd("concept1.md", "---\nname: 概念一\ntables: [t1]\n---\n内容一");
        writeMd("concept2.md", "---\nname: 概念二\ntables: [t2]\n---\n内容二");
        writeMd("not-md.txt", "This is not a markdown file");

        List<DomainKnowledge> results = loader.loadFromDirectory(tempDir);

        assertThat(results).hasSize(2);
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.findByName("概念一")).isNotNull();
        assertThat(index.findByName("概念二")).isNotNull();
    }

    // ---- AC4: Non-existent directory ----

    @Test
    void loadFromDirectory_nonExistent_shouldReturnEmpty() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        List<DomainKnowledge> results = loader.loadFromDirectory(nonExistent);
        assertThat(results).isEmpty();
    }

    @Test
    void loadFromDirectory_null_shouldReturnEmpty() {
        List<DomainKnowledge> results = loader.loadFromDirectory(null);
        assertThat(results).isEmpty();
    }

    // ---- parseContent ----

    @Test
    void parseContent_validContent_shouldParse() {
        String content = "---\nname: 测试\ntables: [t1, t2]\n---\n\n正文内容";
        DomainKnowledge dk = loader.parseContent(content);

        assertThat(dk).isNotNull();
        assertThat(dk.getName()).isEqualTo("测试");
        assertThat(dk.getTables()).containsExactly("t1", "t2");
        assertThat(dk.getContent()).isEqualTo("正文内容");
    }

    @Test
    void parseContent_null_shouldReturnNull() {
        assertThat(loader.parseContent(null)).isNull();
        assertThat(loader.parseContent("")).isNull();
    }

    // ---- Frontmatter parser edge cases ----

    @Test
    void parseFrontmatter_mixedFormats_shouldParse() {
        String frontmatter = "name: 混合测试\n"
                + "tables: [t1, t2]\n"
                + "services:\n"
                + "  - ServiceA\n"
                + "  - ServiceB\n"
                + "tags: [tag1]\n";

        java.util.Map<String, Object> result = loader.parseFrontmatter(frontmatter);

        assertThat(result).containsEntry("name", "混合测试");
        Object tables = result.get("tables");
        assertThat(tables).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) tables).containsExactly("t1", "t2");
        Object services = result.get("services");
        assertThat(services).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) services).containsExactly("ServiceA", "ServiceB");
    }

    // ---- Helpers ----

    private void writeMd(String filename, String content) throws Exception {
        File file = tempDir.resolve(filename).toFile();
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}
