package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SkillRegistry subdirectory scanning behavior (2.x fix).
 *
 * <p>Verifies that knowledge files in subdirectories are NOT loaded as skills.</p>
 */
class SkillRegistrySubdirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldNotLoadMdFilesFromSubdirectories() throws IOException {
        // Create a skill file in root
        Path rootSkill = tempDir.resolve("test-skill.md");
        Files.write(rootSkill, Collections.singletonList(
            "---\nname: test-skill\ndescription: Test skill\n---\n# Test"
        ));

        // Create a subdirectory with .md files (simulating knowledge files)
        Path subDir = tempDir.resolve("domain-knowledge");
        Files.createDirectories(subDir);
        Path knowledgeFile = subDir.resolve("snap-agent-advisor-system.md");
        Files.write(knowledgeFile, Collections.singletonList(
            "---\nname: snap-agent-advisor-system\ndescription: Knowledge file\n---\n# Knowledge"
        ));

        // Create registry and refresh
        ToolCallbackRegistry toolRegistry = new ToolCallbackRegistryImpl();
        SkillRegistry registry = new SkillRegistry(tempDir, Collections.emptyList(), toolRegistry);
        registry.refresh();

        // Verify: only root skill should be loaded
        List<SkillMeta> skills = registry.getAll();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getName()).isEqualTo("test-skill");
    }

    @Test
    void shouldLoadDirectorySkillWithSkillMd() throws IOException {
        // Create a directory skill (subdirectory with SKILL.md)
        Path dirSkill = tempDir.resolve("my-directory-skill");
        Files.createDirectories(dirSkill);
        Path skillMd = dirSkill.resolve("SKILL.md");
        Files.write(skillMd, Collections.singletonList(
            "---\nname: my-directory-skill\ndescription: Directory skill\n---\n# Dir Skill"
        ));

        // Create registry and refresh
        ToolCallbackRegistry toolRegistry = new ToolCallbackRegistryImpl();
        SkillRegistry registry = new SkillRegistry(tempDir, Collections.emptyList(), toolRegistry);
        registry.refresh();

        // Verify: directory skill should be loaded
        List<SkillMeta> skills = registry.getAll();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getName()).isEqualTo("my-directory-skill");
    }

    @Test
    void shouldNotLoadMdFilesFromNestedSubdirectories() throws IOException {
        // Create nested subdirectories with .md files
        Path nestedDir = tempDir.resolve("level1").resolve("level2");
        Files.createDirectories(nestedDir);
        Path nestedFile = nestedDir.resolve("nested-knowledge.md");
        Files.write(nestedFile, Collections.singletonList(
            "---\nname: nested-knowledge\ndescription: Nested knowledge\n---\n# Nested"
        ));

        // Create registry and refresh
        ToolCallbackRegistry toolRegistry = new ToolCallbackRegistryImpl();
        SkillRegistry registry = new SkillRegistry(tempDir, Collections.emptyList(), toolRegistry);
        registry.refresh();

        // Verify: no skills should be loaded from nested subdirectories
        List<SkillMeta> skills = registry.getAll();
        assertThat(skills).isEmpty();
    }
}
