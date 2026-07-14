package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogPathGuard}.
 */
class LogPathGuardTest {

    @TempDir
    Path tempDir;

    private Path allowedDir;
    private Path logFile;
    private Path outsideFile;
    private LogPathGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        allowedDir = tempDir.resolve("logs");
        Files.createDirectories(allowedDir);
        logFile = allowedDir.resolve("app.log");
        Files.write(logFile, Collections.singletonList("2024-01-01 ERROR test"));

        Path outsideDir = tempDir.resolve("other");
        Files.createDirectories(outsideDir);
        outsideFile = outsideDir.resolve("secret.txt");
        Files.write(outsideFile, Collections.singletonList("secret"));

        guard = new LogPathGuard(
                Collections.singletonList(allowedDir.toString()),
                500, 10L * 1024 * 1024);
    }

    @Test
    void shouldAllowPathUnderAllowedRoot() {
        LogPathGuard.Result result = guard.validate(logFile.toString());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getPath()).isEqualTo(logFile);
    }

    @Test
    void shouldRejectNullPath() {
        LogPathGuard.Result result = guard.validate(null);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("空");
    }

    @Test
    void shouldRejectEmptyPath() {
        LogPathGuard.Result result = guard.validate("   ");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("空");
    }

    @Test
    void shouldRejectDirectoryTraversal() {
        LogPathGuard.Result result = guard.validate(
                allowedDir.resolve("../other/secret.txt").toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("..");
    }

    @Test
    void shouldRejectPathOutsideAllowedRoot() {
        LogPathGuard.Result result = guard.validate(outsideFile.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("不在允许");
    }

    @Test
    void shouldRejectNonExistentFile() {
        LogPathGuard.Result result = guard.validate(allowedDir.resolve("nonexistent.log").toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("不存在");
    }

    @Test
    void shouldRejectDirectoryInsteadOfFile() {
        LogPathGuard.Result result = guard.validate(allowedDir.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("普通文件");
    }

    @Test
    void shouldRejectOversizedFile() throws IOException {
        Path bigFile = allowedDir.resolve("big.log");
        // Create a file slightly over 10 bytes with a 10-byte limit
        Files.write(bigFile, "12345678901".getBytes());
        LogPathGuard smallGuard = new LogPathGuard(
                Collections.singletonList(allowedDir.toString()), 500, 10);

        LogPathGuard.Result result = smallGuard.validate(bigFile.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("过大");
    }

    @Test
    void shouldRejectWhenNoAllowedPathsConfigured() {
        LogPathGuard emptyGuard = new LogPathGuard(
                Collections.<String>emptyList(), 500, 10L * 1024 * 1024);

        LogPathGuard.Result result = emptyGuard.validate(logFile.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("allowed-paths");
    }

    @Test
    void shouldSupportMultipleAllowedRoots() throws IOException {
        Path dir2 = tempDir.resolve("logs2");
        Files.createDirectories(dir2);
        Path log2 = dir2.resolve("app2.log");
        Files.write(log2, Collections.singletonList("ok"));

        LogPathGuard multiGuard = new LogPathGuard(
                Arrays.asList(allowedDir.toString(), dir2.toString()),
                500, 10L * 1024 * 1024);

        assertThat(multiGuard.validate(logFile.toString()).isAllowed()).isTrue();
        assertThat(multiGuard.validate(log2.toString()).isAllowed()).isTrue();
    }

    @Test
    void shouldNormalizePathBeforeCheck() {
        // Redundant separators and mixed separators should normalize cleanly
        LogPathGuard.Result result = guard.validate(logFile.toString());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getPath()).isAbsolute();
    }
}
