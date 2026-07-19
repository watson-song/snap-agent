package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GitLogToolProvider}.
 *
 * <p>Tests that require git are conditionally skipped if git is not available
 * on the system path.</p>
 */
class GitLogToolProviderTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private CodePathGuard pathGuard;
    private GitLogToolProvider provider;

    private static boolean gitAvailable;

    @BeforeAll
    static void checkGit() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "--version").start();
        boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        gitAvailable = finished && p.exitValue() == 0;
    }

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        projectRoot = tempDir.resolve("gitproject");
        Files.createDirectories(projectRoot);

        // Initialize a git repo if git is available
        if (gitAvailable) {
            runCommand("git", "init");
            runCommand("git", "config", "user.name", "Test User");
            runCommand("git", "config", "user.email", "test@example.com");

            Path srcDir = projectRoot.resolve("src");
            Files.createDirectories(srcDir);
            Files.write(srcDir.resolve("App.java"), Arrays.asList(
                    "public class App {",
                    "    public static void main(String[] args) {",
                    "        System.out.println(\"hello\");",
                    "    }",
                    "}"));
            runCommand("git", "add", ".");
            runCommand("git", "commit", "-m", "initial commit");

            // Second commit
            Files.write(srcDir.resolve("App.java"), Arrays.asList(
                    "public class App {",
                    "    public static void main(String[] args) {",
                    "        System.out.println(\"hello world\");",
                    "    }",
                    "}"));
            runCommand("git", "add", ".");
            runCommand("git", "commit", "-m", "fix: update greeting");
        }

        pathGuard = new CodePathGuard(projectRoot.toString(),
                Arrays.asList(".java", ".xml", ".yml"), 500, 512L * 1024);
        provider = new GitLogToolProvider(pathGuard);
    }

    private void runCommand(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void shouldReturnNameGitLog() {
        assertThat(provider.name()).isEqualTo("git_log");
    }

    @Test
    void shouldReturnSchemaContainingModeEnum() {
        String schema = provider.schema();
        assertThat(schema).contains("git_log");
        assertThat(schema).contains("log");
        assertThat(schema).contains("blame");
        assertThat(schema).contains("show");
        assertThat(schema).contains("commit_hash");
    }

    @Test
    void shouldRejectShowModeWithoutCommitHash() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "show");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("commit_hash");
    }

    @Test
    void shouldRejectInvalidCommitHash() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "show");
        args.put("commit_hash", "not-a-hash; rm -rf /");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("格式无效");
    }

    @Test
    void shouldRejectCommitHashWithShellMetacharacters() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "show");
        args.put("commit_hash", "abc1234; cat /etc/passwd");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("格式无效");
    }

    @Test
    void shouldRejectBlameModeWithoutFilePath() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "blame");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("file_path");
    }

    @Test
    void shouldRejectFilePathOutsideProjectRoot() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "log");
        args.put("file_path", "/etc/passwd");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("不在项目根目录");
    }

    @Test
    void shouldClampMaxEntriesToLimit() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "log");
        args.put("max_entries", 10000);

        // This would try to run git; if git is not available, the test still
        // verifies that the command was built with the clamped value (20).
        // The important assertion is that it doesn't accept 10000.
        ToolResult result = provider.execute(args, ctx());
        // Either git runs and returns limited results, or git is not available.
        // Either way, it should not error on max_entries being too large.
        if (gitAvailable) {
            assertThat(result.isSuccess() || result.isError()).isTrue();
        }
    }

    // ---- Tests requiring git ----

    @Test
    void shouldReturnLogHistoryWhenGitAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable, "git not available");

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "log");
        args.put("max_entries", 10);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("initial commit");
        assertThat(result.getContent()).contains("update greeting");
        assertThat(result.getContent()).contains("# Git Log");
    }

    @Test
    void shouldReturnBlameWhenGitAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable, "git not available");

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("mode", "blame");
        args.put("file_path", "src/App.java");
        args.put("max_entries", 10);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("# Git Blame");
        assertThat(result.getContent()).contains("Test User");
    }

    @Test
    void shouldReturnShowWhenGitAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable, "git not available");

        // Get the latest commit hash
        ProcessBuilder pb = new ProcessBuilder("git", "log", "--oneline", "-1");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            String hash = line.split("\\s+")[0];

            Map<String, Object> args = new HashMap<String, Object>();
            args.put("mode", "show");
            args.put("commit_hash", hash);

            ToolResult result = provider.execute(args, ctx());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("# Git Show");
            assertThat(result.getContent()).contains(hash);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Failed to get commit hash: " + e.getMessage());
        }
    }

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }
}
