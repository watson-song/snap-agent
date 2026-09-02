package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 2.x git history tool exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code GitLogToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI) to a single {@code @Tool} method
 * discovered by {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.
 *
 * <p>Provides three modes:
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
public class GitLogTools {

    private static final Logger log = LoggerFactory.getLogger(GitLogTools.class);

    private static final Pattern COMMIT_HASH_PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");

    private static final int PROCESS_TIMEOUT_SECONDS = 10;
    private static final int MAX_ENTRIES_LIMIT = 100;

    private final CodePathGuard pathGuard;

    public GitLogTools(CodePathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Tool(name = "git_log", description = "查看项目的 git 历史。支持 log（提交历史）、blame（行级别作者）和 show（查看具体 commit）。")
    public String gitLog(
            @ToolParam(description = "可选，限定到指定文件的 git 历史", required = false) String file_path,
            @ToolParam(description = "操作模式: log/blame/show（可选，默认 log）", required = false) String mode,
            @ToolParam(description = "最大返回条数（可选，默认 20，上限 100）", required = false) Integer max_entries,
            @ToolParam(description = "commit hash（仅 show 模式必填，只接受 7-40 位十六进制字符）", required = false) String commit_hash) {

        String modeStr = mode != null && !mode.isEmpty() ? mode : "log";
        int maxEntries = max_entries != null ? max_entries : 20;
        if (maxEntries < 1 || maxEntries > MAX_ENTRIES_LIMIT) {
            maxEntries = 20;
        }

        // Validate commit hash for show mode
        if ("show".equals(modeStr)) {
            if (commit_hash == null || commit_hash.isEmpty()) {
                return "Error: show 模式需要 commit_hash 参数";
            }
            if (!COMMIT_HASH_PATTERN.matcher(commit_hash).matches()) {
                return "Error: commit_hash 格式无效，只接受 7-40 位十六进制字符";
            }
        }

        // Validate file path if provided
        Path validatedPath = null;
        if (file_path != null && !file_path.isEmpty()) {
            CodePathGuard.Result guardResult = pathGuard.validate(file_path);
            if (!guardResult.isAllowed()) {
                return "Error: " + guardResult.getReason();
            }
            validatedPath = guardResult.getPath();
        }

        // blame mode requires a file path
        if ("blame".equals(modeStr) && validatedPath == null) {
            return "Error: blame 模式需要 file_path 参数";
        }

        List<String> command = buildCommand(modeStr, validatedPath, maxEntries, commit_hash);
        if (command == null) {
            return "Error: 不支持的 mode: " + modeStr;
        }

        log.info("Executing git command: {} (mode={})", command, modeStr);

        try {
            String output = runGit(command);
            String content = formatOutput(modeStr, output, validatedPath, maxEntries, commit_hash);
            int lineCount = countLines(output);
            boolean truncated = lineCount >= maxEntries && "log".equals(modeStr);
            if (truncated) {
                return content + "\n# (output may be truncated — increase max_entries to see more)\n";
            }
            return content;
        } catch (IOException e) {
            log.error("Git command failed: {}", e.getMessage());
            return "Error: Git command failed: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Git command timed out";
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

        // Read stdout and stderr on separate threads to avoid deadlock
        // when the process fills one pipe buffer while we're reading the other.
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            } catch (IOException e) {
                // Stream closed — acceptable during destroyForcibly
            }
        });
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            } catch (IOException e) {
                // Stream closed — acceptable during destroyForcibly
            }
        });
        stdoutThread.start();
        stderrThread.start();

        // Wait for the process with a timeout — this now actually works
        // because we're not blocked on stream reads
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutThread.interrupt();
            stderrThread.interrupt();
            throw new IOException("Git command timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
        }

        // Wait for stream readers to finish (process has exited, streams will EOF)
        stdoutThread.join(2000);
        stderrThread.join(2000);

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
}
