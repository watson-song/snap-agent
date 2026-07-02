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
import java.util.Collection;
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
    void shouldKeepInvalidSkillWhenFrontmatterBroken() throws IOException {
        String content = "# no frontmatter\nbody\n";
        Files.write(tempDir.resolve("bad.md"), content.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(tempDir, dispatcher);

        // Invalid skills are still loaded (with availability=INVALID) so the UI can show them
        SkillMeta meta = registry.get(null);
        // The name is null for this broken file, so it won't be in the map by name
        // but it should appear in all() as an invalid entry
        Collection<SkillMeta> all = registry.all();
        assertThat(all).hasSize(1);
        SkillMeta first = all.iterator().next();
        assertThat(first.getAvailability()).isEqualTo(SkillAvailability.INVALID);
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

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getAvailable()).isEqualTo(1);
        assertThat(result.getUnavailable()).isEqualTo(1);
        assertThat(result.getInvalid()).isEqualTo(1);
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

        // With no dispatcher, all tools are considered missing → UNAVAILABLE
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

        Set<Thread> threads = new HashSet<>();
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

        // No exception thrown — concurrent access is safe
        assertThat(registry.get("skill-a")).isNotNull();
    }
}
