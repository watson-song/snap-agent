package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@link ToolProvider} implementation for viewing git history.
 *
 * <p>Tool name: {@code git_log}. Provides three modes:
 * <ul>
 *   <li>{@code log} — commit history (optionally limited to a file)</li>
 *   <li>{@code blame} — line-level author attribution for a file</li>
 *   <li>{@code show} — view a specific commit's details</li>
 * </ul></p>
 *
 * <p>Uses {@link ProcessBuilder} with argument lists (never shell) to prevent
 * command injection. {@code commit_hash} is validated against
 * {@code ^[0-9a-f]{7,40}$}. All file paths pass through {@link CodePathGuard}.</p>
 */
public class GitLogToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(GitLogToolProvider.class);

    private static final Pattern COMMIT_HASH_PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");

    private static final int PROCESS_TIMEOUT_SECONDS = 10;
    private static final int MAX_ENTRIES_LIMIT = 100;

    private static final String SCHEMA = "{\"name\":\"git_log\","
            + "\"description\":\"查看项目的 git 历史。支持 log（提交历史）、blame（行级别作者）和 show（查看具体 commit）。\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"file_path\":{\"type\":\"string\",\"description\":\"可选，限定到指定文件的 git 历史\"},"
            + "\"mode\":{\"type\":\"string\",\"enum\":[\"log\",\"blame\",\"show\"],\"description\":\"操作模式\",\"default\":\"log\"},"
            + "\"max_entries\":{\"type\":\"integer\",\"description\":\"最大返回条数（默认 20）\",\"default\":20},"
            + "\"commit_hash\":{\"type\":\"string\",\"description\":\"commit hash（仅 show 模式）\"}"
            + "}}}";

    private final CodePathGuard pathGuard;

    public GitLogToolProvider(CodePathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Override
    public String name() {
        return "git_log";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String mode = extractString(args, "mode", "log");
        String filePath = extractString(args, "file_path");
        int maxEntries = extractInt(args, "max_entries", 20);
        if (maxEntries < 1 || maxEntries > MAX_ENTRIES_LIMIT) {
            maxEntries = 20;
        }
        String commitHash = extractString(args, "commit_hash");

        // Validate commit hash for show mode
        if ("show".equals(mode)) {
            if (commitHash == null || commitHash.isEmpty()) {
                return ToolResult.error("show 模式需要 commit_hash 参数", elapsed(start));
            }
            if (!COMMIT_HASH_PATTERN.matcher(commitHash).matches()) {
                return ToolResult.error("commit_hash 格式无效，只接受 7-40 位十六进制字符", elapsed(start));
            }
        }

        // Validate file path if provided
        Path validatedPath = null;
        if (filePath != null && !filePath.isEmpty()) {
            CodePathGuard.Result guardResult = pathGuard.validate(filePath);
            if (!guardResult.isAllowed()) {
                return ToolResult.error(guardResult.getReason(), elapsed(start));
            }
            validatedPath = guardResult.getPath();
        }

        // blame mode requires a file path
        if ("blame".equals(mode) && validatedPath == null) {
            return ToolResult.error("blame 模式需要 file_path 参数", elapsed(start));
        }

        List<String> command = buildCommand(mode, validatedPath, maxEntries, commitHash);
        if (command == null) {
            return ToolResult.error("不支持的 mode: " + mode, elapsed(start));
        }

        log.info("Executing git command: {} (mode={})", command, mode);

        try {
            String output = runGit(command);
            String content = formatOutput(mode, output, validatedPath, maxEntries, commitHash);
            int lineCount = countLines(output);
            long duration = elapsed(start);
            boolean truncated = lineCount >= maxEntries && "log".equals(mode);
            if (truncated) {
                return ToolResult.truncated(content, lineCount, duration);
            }
            return ToolResult.success(content, lineCount, duration);
        } catch (IOException e) {
            log.error("Git command failed: {}", e.getMessage());
            return ToolResult.error("Git command failed: " + e.getMessage(), elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Git command timed out", elapsed(start));
        }
    }

    private List<String> buildCommand(String mode, Path filePath, int maxEntries, String commitHash) {
        List<String> cmd = new ArrayList<String>();
        cmd.add("git");

        if ("log".equals(mode)) {
            cmd.add("log");
            cmd.add("--oneline");
            cmd.add("-n");
            cmd.add(String.valueOf(maxEntries));
            if (filePath != null) {
                cmd.add("--");
                cmd.add(filePath.toString());
            }
        } else if ("blame".equals(mode)) {
            cmd.add("blame");
            cmd.add("-l");  // show long revision
            cmd.add("-L");
            cmd.add("1," + maxEntries);  // limit line range
            cmd.add(filePath.toString());
        } else if ("show".equals(mode)) {
            cmd.add("show");
            cmd.add("--stat");
            cmd.add(commitHash);
        } else {
            return null;
        }

        return cmd;
    }

    private String runGit(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(pathGuard.getProjectRoot().toFile());
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
        }

        // Read stderr (for error diagnostics, not returned to user but logged)
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git command timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errMsg = stderr.toString().trim();
            if (errMsg.isEmpty()) {
                errMsg = "git exited with code " + exitCode;
            }
            throw new IOException("Git error: " + errMsg);
        }

        return stdout.toString();
    }

    private String formatOutput(String mode, String output, Path filePath,
                                 int maxEntries, String commitHash) {
        StringBuilder sb = new StringBuilder();
        if ("log".equals(mode)) {
            sb.append("# Git Log (max ").append(maxEntries).append(")");
            if (filePath != null) {
                sb.append(" — ").append(relativize(filePath));
            }
            sb.append("\n\n");
            sb.append(output);
        } else if ("blame".equals(mode)) {
            sb.append("# Git Blame: ").append(relativize(filePath))
                    .append(" (max ").append(maxEntries).append(" lines)\n\n");
            sb.append(output);
        } else {
            sb.append("# Git Show: ").append(commitHash).append("\n\n");
            sb.append(output);
        }
        return sb.toString();
    }

    private String relativize(Path path) {
        try {
            Path rel = pathGuard.getProjectRoot().relativize(path);
            return rel.toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    // ---- arg extraction helpers ----

    private String extractString(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return value != null ? String.valueOf(value) : null;
    }

    private String extractString(Map<String, Object> args, String key, String defaultValue) {
        String value = extractString(args, key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    private int extractInt(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
