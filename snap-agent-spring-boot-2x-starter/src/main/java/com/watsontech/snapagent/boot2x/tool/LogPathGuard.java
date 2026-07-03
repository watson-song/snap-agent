package com.watsontech.snapagent.boot2x.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Log file path safety guard.
 *
 * <p>Enforces that log reads stay within configured allowed directories.
 * Rejects directory traversal ({@code ..}), non-existent files, directories,
 * and files exceeding the size cap.</p>
 *
 * <p>This is the primary defence against the LLM reading sensitive files
 * outside the intended log directory (e.g. {@code /etc/passwd}).</p>
 */
public class LogPathGuard {

    private final List<Path> allowedRoots;
    private final int maxLines;
    private final long maxFileBytes;

    public LogPathGuard(List<String> allowedPaths, int maxLines, long maxFileBytes) {
        this.allowedRoots = new ArrayList<Path>();
        if (allowedPaths != null) {
            for (String p : allowedPaths) {
                if (p != null && !p.trim().isEmpty()) {
                    this.allowedRoots.add(Paths.get(p).toAbsolutePath().normalize());
                }
            }
        }
        this.maxLines = maxLines;
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    /**
     * Validate the given file path.
     *
     * @param filePath raw path from the LLM tool_use block
     * @return a {@link Result} with either the canonical path (allowed) or a rejection reason
     */
    public Result validate(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return Result.reject("日志路径被拒绝：路径为空");
        }

        // Reject directory traversal before any filesystem access
        if (filePath.contains("..")) {
            return Result.reject("日志路径被拒绝：禁止目录穿越 (..)");
        }

        Path path;
        try {
            path = Paths.get(filePath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Result.reject("日志路径被拒绝：无效路径 " + e.getMessage());
        }

        if (allowedRoots.isEmpty()) {
            return Result.reject("日志路径被拒绝：未配置 allowed-paths（snap-agent.logs.allowed-paths）");
        }

        boolean allowed = false;
        for (Path root : allowedRoots) {
            if (path.startsWith(root)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return Result.reject("日志路径被拒绝：不在允许的日志目录下 " + path);
        }

        if (!Files.exists(path)) {
            return Result.reject("日志文件不存在: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return Result.reject("不是普通文件: " + path);
        }

        try {
            long size = Files.size(path);
            if (size > maxFileBytes) {
                return Result.reject("文件过大: " + size + " bytes（上限 " + maxFileBytes + "）");
            }
        } catch (IOException e) {
            return Result.reject("无法读取文件大小: " + e.getMessage());
        }

        return Result.ok(path);
    }

    /** Immutable validation result. */
    public static final class Result {
        private final Path path;
        private final String reason;

        private Result(Path path, String reason) {
            this.path = path;
            this.reason = reason;
        }

        /** Allowed result with the canonical path. */
        public static Result ok(Path path) {
            return new Result(path, null);
        }

        /** Rejected result with a human-readable reason. */
        public static Result reject(String reason) {
            return new Result(null, reason);
        }

        public boolean isAllowed() {
            return path != null;
        }

        public Path getPath() {
            return path;
        }

        public String getReason() {
            return reason;
        }
    }
}
