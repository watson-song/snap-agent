package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SkillHotReloaderTest {

    /**
     * On macOS the JDK's WatchService is a polling implementation
     * ({@code PollingWatchService}) that checks the filesystem every ~2 seconds
     * when registered with {@code HIGH} sensitivity (the default {@code MEDIUM}
     * polls every 10 seconds). {@code SkillHotReloader} registers with
     * {@code HIGH}, so events are delivered within ~2-3 seconds. We use
     * Mockito's {@code timeout(10000)} to wait up to 10 seconds for robustness
     * on slow CI machines.
     */
    @Test
    void shouldCallRefreshWhenFileCreated(@TempDir Path tempDir) throws Exception {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillRegistry.RefreshResult rr = new SkillRegistry.RefreshResult(2, 2, 0, 0);
        when(registry.refresh()).thenReturn(rr);

        SkillHotReloader reloader = new SkillHotReloader(tempDir, registry, 500);
        reloader.start();

        // Create a new skill file
        Files.write(tempDir.resolve("test-skill.md"),
                "---\nname: test-skill\ndescription: test\n---\nbody".getBytes());

        // Wait for WatchService to detect the change and trigger refresh()
        verify(registry, timeout(10000).atLeastOnce()).refresh();

        reloader.stop();
    }

    @Test
    void shouldHandleNonExistentDirGracefully() {
        SkillRegistry registry = mock(SkillRegistry.class);
        Path nonexistent = Paths.get("/tmp/nonexistent-skill-dir-12345");
        SkillHotReloader reloader = new SkillHotReloader(nonexistent, registry, 1000);
        // start should not throw
        reloader.start();
        reloader.stop();
    }
}
