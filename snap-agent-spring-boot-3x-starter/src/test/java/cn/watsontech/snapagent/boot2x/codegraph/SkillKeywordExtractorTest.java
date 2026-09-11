package cn.watsontech.snapagent.boot2x.codegraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SkillKeywordExtractor} — the standalone keyword extractor
 * shared by {@link CodeGraphCli} and the auto-configuration.
 */
class SkillKeywordExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExtractKeywordsFromSkillContent() {
        String content = "调拨计划 AllocationPlanService 生成 drp_allocation_plan "
                + "调用 getAllocationPlan 方法，包 com.watsontech.allocation";
        Set<String> keywords = SkillKeywordExtractor.extractFromContent(content);

        assertThat(keywords).contains("AllocationPlanService");
        assertThat(keywords).contains("getAllocationPlan");
        assertThat(keywords).contains("drp_allocation_plan");
        assertThat(keywords).contains("com.watsontech.allocation");
    }

    @Test
    void shouldScanDirectoryAndCollectKeywords() throws IOException {
        Path skill = tempDir.resolve("allocation-plan-diagnose.md");
        Files.write(skill,
                ("# 调拨计划排查\n"
                        + "检查 AllocationPlanService 的 getAllocationPlan 方法\n"
                        + "涉及 drp_allocation_plan 表\n").getBytes("UTF-8"));

        Set<String> keywords = SkillKeywordExtractor.extractFromDirectory(tempDir);

        assertThat(keywords).contains("AllocationPlanService");
        assertThat(keywords).contains("getAllocationPlan");
        assertThat(keywords).contains("drp_allocation_plan");
    }

    @Test
    void shouldReturnEmptyForMissingDirectory() {
        Set<String> keywords = SkillKeywordExtractor.extractFromDirectory(
                tempDir.resolve("nonexistent"));
        assertThat(keywords).isEmpty();
    }
}
