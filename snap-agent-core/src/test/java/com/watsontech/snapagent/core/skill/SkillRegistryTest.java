package com.watsontech.snapagent.core.skill;

import com.watsontech.snapagent.core.tool.ToolDispatcher;
import com.watsontech.snapagent.core.tool.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SkillRegistry}.
 */
class SkillRegistryTest {

    @TempDir
    Path tempDir;

    private ToolDispatcher dispatcher;
    private ToolProvider mysqlProvider;

    @BeforeEach
    void setUp() {
        mysqlProvider = mock(ToolProvider.class);
        when(mysqlProvider.name()).thenReturn("mysql_query");
        dispatcher = new ToolDispatcher(Arrays.asList(mysqlProvider), 50000);
    }

    private void writeSkill(String fileName, String name, String tools) throws IOException {
        String content = "---\n"
                + "name: " + name + "\n"
                + "description: desc for " + name + "\n"
                + "tools: [" + tools + "]\n"
                + "---\n"
                + "body for " + name + "\n";
        Files.write(tempDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8));
    }

    private SkillMeta builtinSkill(String name, String tools) {
        return new SkillMeta(name, "builtin " + name,
                Arrays.asList(tools.split(", ")),
                Collections.<InputSpec>emptyList(), "builtin body",
                SkillAvailability.AVAILABLE, null, "builtin", false);
    }

    @Test
    void shouldLoadThreeSkillsWhenDirectoryHasThreeMdFiles() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");
        writeSkill("b.md", "skill-b", "mysql_query");
        writeSkill("c.md", "skill-c", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).hasSize(3);
        assertThat(registry.get("skill-a")).isNotNull();
        assertThat(registry.get("skill-b")).isNotNull();
        assertThat(registry.get("skill-c")).isNotNull();
    }

    @Test
    void shouldReturnAvailableWhenAllToolsRegistered() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        SkillMeta meta = registry.get("skill-a");
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE);
        assertThat(meta.getUnavailableReason()).isNull();
    }

    @Test
    void shouldReturnUnavailableWhenToolMissing() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query, redis_get");

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        SkillMeta meta = registry.get("skill-a");
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.UNAVAILABLE);
        assertThat(meta.getUnavailableReason()).contains("redis_get");
    }

    @Test
    void shouldSkipInvalidSkillWhenFrontmatterBroken() throws IOException {
        String content = "# no frontmatter\nbody\n";
        Files.write(tempDir.resolve("bad.md"), content.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).isEmpty();
        assertThat(registry.get(null)).isNull();
    }

    @Test
    void shouldReturnEmptyWhenDirectoryDoesNotExist() {
        Path nonExistent = tempDir.resolve("nonexistent-dir");

        SkillRegistry registry = new SkillRegistry(nonExistent, dispatcher);

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenDirectoryHasNoMdFiles() throws IOException {
        Files.createFile(tempDir.resolve("readme.txt"));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void shouldPickUpNewFilesWhenRefreshCalled() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");
        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);
        assertThat(registry.all()).hasSize(1);

        writeSkill("b.md", "skill-b", "mysql_query");
        registry.refresh();

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.get("skill-b")).isNotNull();
    }

    @Test
    void shouldRemoveDeletedSkillsWhenRefreshCalled() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");
        writeSkill("b.md", "skill-b", "mysql_query");
        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);
        assertThat(registry.all()).hasSize(2);

        Files.delete(tempDir.resolve("b.md"));
        registry.refresh();

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("skill-a")).isNotNull();
        assertThat(registry.get("skill-b")).isNull();
    }

    @Test
    void shouldUpdateBodyWhenFileModifiedAndRefreshCalled() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");
        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);
        assertThat(registry.get("skill-a").getBody()).contains("body for skill-a");

        String newContent = "---\n"
                + "name: skill-a\n"
                + "description: desc for skill-a\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "NEW BODY CONTENT\n";
        Files.write(tempDir.resolve("a.md"), newContent.getBytes(StandardCharsets.UTF_8));
        registry.refresh();

        assertThat(registry.get("skill-a").getBody()).contains("NEW BODY CONTENT");
    }

    @Test
    void shouldReturnNullWhenSkillNotFound() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");
        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void shouldReturnCountsWhenRefreshCalled() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");
        writeSkill("b.md", "skill-b", "mysql_query, redis_get");
        String invalidContent = "# no frontmatter\n";
        Files.write(tempDir.resolve("c.md"), invalidContent.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);
        SkillRegistry.RefreshResult result = registry.refresh();

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getAvailable()).isEqualTo(1);
        assertThat(result.getUnavailable()).isEqualTo(1);
        assertThat(result.getInvalid()).isEqualTo(0);
    }

    @Test
    void shouldHandleNullDirectory() {
        SkillRegistry registry = new SkillRegistry(null, dispatcher);

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void shouldHandleNullDispatcher() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir, null);

        SkillMeta meta = registry.get("skill-a");
        assertThat(meta.getAvailability()).isEqualTo(SkillAvailability.UNAVAILABLE);
    }

    @Test
    void shouldBeThreadSafeWhenConcurrentAccess() throws Exception {
        writeSkill("a.md", "skill-a", "mysql_query");
        final SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        Runnable reader = () -> {
            for (int i = 0; i < 100; i++) {
                registry.get("skill-a");
                registry.all();
            }
        };
        Runnable refresher = () -> {
            for (int i = 0; i < 50; i++) {
                registry.refresh();
            }
        };

        Set<Thread> threads = new HashSet<Thread>();
        for (int i = 0; i < 4; i++) {
            threads.add(new Thread(reader));
        }
        threads.add(new Thread(refresher));
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(registry.get("skill-a")).isNotNull();
    }

    // ---- Two-tier (builtin + custom) tests ----

    @Test
    void shouldLoadBuiltinSkillsOnlyWhenUploadDirIsEmpty() {
        SkillMeta builtin = builtinSkill("builtin-skill", "mysql_query");
        SkillRegistry registry = new SkillRegistry(null,
                Arrays.asList(builtin), dispatcher);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("builtin-skill")).isNotNull();
        assertThat(registry.get("builtin-skill").getSource()).isEqualTo("builtin");
    }

    @Test
    void shouldMergeBuiltinAndCustomSkills() throws IOException {
        writeSkill("custom.md", "custom-skill", "mysql_query");
        SkillMeta builtin = builtinSkill("builtin-skill", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir,
                Arrays.asList(builtin), dispatcher);

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.get("builtin-skill").getSource()).isEqualTo("builtin");
        assertThat(registry.get("custom-skill").getSource()).isEqualTo("custom");
    }

    @Test
    void shouldLetCustomOverrideBuiltinWhenSameName() throws IOException {
        writeSkill("override.md", "shared-skill", "mysql_query");
        SkillMeta builtin = builtinSkill("shared-skill", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir,
                Arrays.asList(builtin), dispatcher);

        // Only the custom version appears (builtin is shadowed)
        assertThat(registry.all()).hasSize(1);
        SkillMeta merged = registry.get("shared-skill");
        assertThat(merged.getSource()).isEqualTo("custom");
        assertThat(merged.isOverridesBuiltin()).isTrue();
        assertThat(merged.getBody()).contains("body for shared-skill");
    }

    @Test
    void shouldRestoreBuiltinWhenCustomDeleted() throws IOException {
        writeSkill("override.md", "shared-skill", "mysql_query");
        SkillMeta builtin = builtinSkill("shared-skill", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir,
                Arrays.asList(builtin), dispatcher);

        assertThat(registry.get("shared-skill").getSource()).isEqualTo("custom");

        Files.delete(tempDir.resolve("override.md"));
        registry.refresh();

        SkillMeta restored = registry.get("shared-skill");
        assertThat(restored.getSource()).isEqualTo("builtin");
        assertThat(restored.isOverridesBuiltin()).isFalse();
    }

    @Test
    void shouldReportBuiltinExistence() throws IOException {
        SkillMeta builtin = builtinSkill("builtin-only", "mysql_query");
        writeSkill("custom.md", "custom-only", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir,
                Arrays.asList(builtin), dispatcher);

        assertThat(registry.isBuiltin("builtin-only")).isTrue();
        assertThat(registry.isBuiltin("custom-only")).isFalse();
        assertThat(registry.isBuiltin("nonexistent")).isFalse();
    }

    @Test
    void shouldReturnCustomSkillPathForDelete() throws IOException {
        writeSkill("standalone.md", "my-skill", "mysql_query");
        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        Path path = registry.getCustomSkillPath("my-skill");
        assertThat(path).isNotNull();
        assertThat(path.getFileName().toString()).isEqualTo("standalone.md");
    }

    // ---- Directory skill tests ----

    @Test
    void shouldParseDirectorySkillFromSkillMd() throws IOException {
        Path skillDir = tempDir.resolve("my-dir-skill");
        Files.createDirectories(skillDir);
        String skillContent = "---\n"
                + "name: dir-skill\n"
                + "description: a directory skill\n"
                + "tools: [mysql_query]\n"
                + "---\n"
                + "body of dir skill\n";
        Files.write(skillDir.resolve("SKILL.md"), skillContent.getBytes(StandardCharsets.UTF_8));
        // Auxiliary files — should be ignored
        Files.write(skillDir.resolve("REFERENCE.md"),
                "# reference\n".getBytes(StandardCharsets.UTF_8));
        Files.write(skillDir.resolve("config.json"),
                "{}".getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("dir-skill")).isNotNull();
        assertThat(registry.get("dir-skill").getBody()).contains("body of dir skill");
    }

    @Test
    void shouldReturnDirectoryPathForDirectorySkill() throws IOException {
        Path skillDir = tempDir.resolve("dir-skill");
        Files.createDirectories(skillDir);
        String skillContent = "---\nname: dir-skill\ndescription: d\ntools: [mysql_query]\n---\nbody\n";
        Files.write(skillDir.resolve("SKILL.md"), skillContent.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        Path path = registry.getCustomSkillPath("dir-skill");
        assertThat(path).isNotNull();
        assertThat(Files.isDirectory(path)).isTrue();
        assertThat(path.getFileName().toString()).isEqualTo("dir-skill");
    }

    @Test
    void shouldSkipAuxiliaryMdFilesInDirectorySkill() throws IOException {
        Path skillDir = tempDir.resolve("dir-skill");
        Files.createDirectories(skillDir);
        String skillContent = "---\nname: dir-skill\ndescription: d\ntools: [mysql_query]\n---\nbody\n";
        Files.write(skillDir.resolve("SKILL.md"), skillContent.getBytes(StandardCharsets.UTF_8));
        // REFERENCE.md has no frontmatter — should NOT appear as a separate skill
        Files.write(skillDir.resolve("REFERENCE.md"),
                "# reference doc\n".getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("dir-skill")).isNotNull();
        assertThat(registry.get("REFERENCE")).isNull();
    }

    @Test
    void shouldRecurseIntoOrganizationalDirectories() throws IOException {
        Path orgDir = tempDir.resolve("category");
        Files.createDirectories(orgDir);
        // Organizational dir has no SKILL.md → standalone .md files inside are parsed
        String skillContent = "---\nname: nested-skill\ndescription: d\ntools: [mysql_query]\n---\nbody\n";
        Files.write(orgDir.resolve("nested.md"),
                skillContent.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("nested-skill")).isNotNull();
    }

    @Test
    void shouldHandleNestedDirectorySkill() throws IOException {
        Path orgDir = tempDir.resolve("diagnostics");
        Path skillDir = orgDir.resolve("perf-check");
        Files.createDirectories(skillDir);
        String skillContent = "---\nname: perf-skill\ndescription: d\ntools: [mysql_query]\n---\nbody\n";
        Files.write(skillDir.resolve("SKILL.md"),
                skillContent.getBytes(StandardCharsets.UTF_8));
        Files.write(skillDir.resolve("REF.md"),
                "# ref\n".getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("perf-skill")).isNotNull();
    }

    @Test
    void shouldHandleDuplicateCustomNamesWithLastWins() throws IOException {
        writeSkill("a.md", "dup-skill", "mysql_query");
        // Create same name in a subdirectory SKILL.md
        Path skillDir = tempDir.resolve("dup-dir");
        Files.createDirectories(skillDir);
        String content = "---\nname: dup-skill\ndescription: from dir\ntools: [mysql_query]\n---\nfrom dir\n";
        Files.write(skillDir.resolve("SKILL.md"), content.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("dup-skill")).isNotNull();
    }

    @Test
    void shouldSetSourceCustomOnAllUploadSkills() throws IOException {
        writeSkill("a.md", "skill-a", "mysql_query");

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        assertThat(registry.get("skill-a").getSource()).isEqualTo("custom");
        assertThat(registry.get("skill-a").isOverridesBuiltin()).isFalse();
    }
}
