package cn.watsontech.snapagent.boot2x.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Code file path safety guard.
 *
 * <p>Enforces that source code reads stay within the configured project root
 * directory. Rejects directory traversal ({@code ..}), non-existent files,
 * directories, files with disallowed extensions, and files exceeding the size
 * cap.</p>
 *
 * <p>This is the primary defence against the LLM reading sensitive files
 * outside the project (e.g. {@code /etc/passwd}, {@code .env}, {@code .pem}).</p>
 */
public class CodePathGuard {

    private final Path projectRoot;
    private final Set<String> allowedExtensions;
    private final int maxLines;
    private final long maxFileBytes;

    public CodePathGuard(String projectRoot, List<String> allowedExtensions,
                         int maxLines, long maxFileBytes) {
        if (projectRoot == null || projectRoot.trim().isEmpty()) {
            throw new IllegalArgumentException("projectRoot must not be empty");
        }
        this.projectRoot = Paths.get(projectRoot).toAbsolutePath().normalize();
        this.allowedExtensions = new HashSet<String>();
        if (allowedExtensions != null) {
            for (String ext : allowedExtensions) {
                if (ext != null && !ext.trim().isEmpty()) {
                    String e = ext.toLowerCase(java.util.Locale.ROOT);
                    if (!e.startsWith(".")) {
                        e = "." + e;
                    }
                    this.allowedExtensions.add(e);
                }
            }
        }
        this.maxLines = maxLines;
        this.maxFileBytes = maxFileBytes;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public Set<String> getAllowedExtensions() {
        return new HashSet<String>(allowedExtensions);
    }

    /**
     * Validate the given file path.
     *
     * @param filePath raw path from the LLM tool_use block (absolute or relative
     *                to project root)
     * @return a {@link Result} with either the normalized path (allowed) or a
     *         rejection reason
     */
    public Result validate(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return Result.reject("代码路径被拒绝：路径为空");
        }

        // Reject directory traversal before any filesystem access
        if (filePath.contains("..")) {
            return Result.reject("代码路径被拒绝：禁止目录穿越 (..)");
        }

        Path path;
        try {
            // Resolve relative paths against project root
            Path raw = Paths.get(filePath);
            if (raw.isAbsolute()) {
                path = raw.toAbsolutePath().normalize();
            } else {
                path = projectRoot.resolve(filePath).toAbsolutePath().normalize();
            }
        } catch (RuntimeException e) {
            return Result.reject("代码路径被拒绝：无效路径 " + e.getMessage());
        }

        // Must be under projectRoot
        if (!path.startsWith(projectRoot)) {
            return Result.reject("代码路径被拒绝：不在项目根目录下 " + path);
        }

        if (!Files.exists(path)) {
            return Result.reject("代码文件不存在: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return Result.reject("不是普通文件: " + path);
        }

        // Extension whitelist
        String fileName = path.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return Result.reject("代码文件被拒绝：无扩展名或扩展名为空 " + fileName);
        }
        String ext = fileName.substring(dotIdx).toLowerCase(java.util.Locale.ROOT);
        if (!allowedExtensions.contains(ext)) {
            return Result.reject("代码文件被拒绝：扩展名不在白名单 " + ext);
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

    /**
     * Resolve a path argument (absolute or relative to project root) to an
     * absolute normalized path within the project, without checking existence
     * or extension. Used by {@code project_structure} for directory scanning.
     *
     * @param pathStr path string (may be null/empty for project root itself)
     * @return normalized absolute path under project root, or null if rejected
     */
    public Path resolveWithinProject(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            return projectRoot;
        }
        if (pathStr.contains("..")) {
            return null;
        }
        Path raw = Paths.get(pathStr);
        Path resolved = raw.isAbsolute()
                ? raw.toAbsolutePath().normalize()
                : projectRoot.resolve(pathStr).toAbsolutePath().normalize();
        if (!resolved.startsWith(projectRoot)) {
            return null;
        }
        return resolved;
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
