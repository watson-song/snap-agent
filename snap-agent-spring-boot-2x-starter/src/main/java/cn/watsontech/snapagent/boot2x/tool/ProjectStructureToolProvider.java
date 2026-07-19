package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolProvider} implementation for scanning project directory structure.
 *
 * <p>Tool name: {@code project_structure}. Walks the directory tree under the
 * configured project root up to a specified depth, skipping build artifacts and
 * version control directories. Returns a tree-formatted text layout.</p>
 *
 * <p>All path safety is delegated to {@link CodePathGuard}. The tool never
 * writes, deletes, or modifies files.</p>
 */
public class ProjectStructureToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ProjectStructureToolProvider.class);

    private static final Set<String> EXCLUDED_DIRS = new HashSet<String>(Arrays.asList(
            "target", ".git", "node_modules", "build", ".idea", ".settings",
            "dist", ".gradle", ".mvn", "__pycache__"));

    private static final String SCHEMA = "{\"name\":\"project_structure\","
            + "\"description\":\"扫描项目目录结构，返回树形布局。可指定子路径和扫描深度。\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"path\":{\"type\":\"string\",\"description\":\"要扫描的子路径（相对项目根目录），默认为项目根\"},"
            + "\"depth\":{\"type\":\"integer\",\"description\":\"扫描深度（默认 3）\",\"default\":3},"
            + "\"pattern\":{\"type\":\"string\",\"description\":\"可选，只返回路径名包含此关键词的条目\"}"
            + "}}}";

    private final CodePathGuard pathGuard;

    public ProjectStructureToolProvider(CodePathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Override
    public String name() {
        return "project_structure";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String pathStr = extractString(args, "path");
        int depth = extractInt(args, "depth", 3);
        if (depth <= 0 || depth > 10) {
            depth = 3;
        }
        String pattern = extractString(args, "pattern");
        String patternLower = pattern != null && !pattern.isEmpty()
                ? pattern.toLowerCase(Locale.ROOT) : null;

        Path scanRoot = pathGuard.resolveWithinProject(pathStr);
        if (scanRoot == null) {
            return ToolResult.error("路径被拒绝：包含 .. 或不在项目根目录下", elapsed(start));
        }
        if (!Files.exists(scanRoot)) {
            return ToolResult.error("路径不存在: " + scanRoot, elapsed(start));
        }

        log.info("Scanning project structure: {} (depth={}, pattern={})", scanRoot, depth, pattern);

        try {
            List<Entry> entries = scan(scanRoot, depth, patternLower);
            String content = formatOutput(scanRoot, entries, depth, pattern);
            long duration = elapsed(start);
            int fileCount = (int) entries.stream().filter(e -> !e.isDirectory).count();
            int dirCount = (int) entries.stream().filter(e -> e.isDirectory).count();
            boolean truncated = entries.size() >= 500;
            if (truncated) {
                return ToolResult.truncated(content, entries.size(), duration);
            }
            return ToolResult.success(content, entries.size(), duration);
        } catch (IOException e) {
            log.error("Project structure scan failed: {}", e.getMessage());
            return ToolResult.error("Scan failed: " + e.getMessage(), elapsed(start));
        }
    }

    private List<Entry> scan(Path root, int maxDepth, String patternLower) throws IOException {
        List<Entry> entries = new ArrayList<Entry>();
        scanRecursive(root, root, 0, maxDepth, patternLower, entries);
        return entries;
    }

    private void scanRecursive(Path root, Path current, int depth, int maxDepth,
                                String patternLower, List<Entry> entries) throws IOException {
        if (entries.size() >= 500) {
            return;
        }
        if (depth >= maxDepth) {
            return;
        }

        List<Path> children = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(current)) {
            stream.forEach(children::add);
        }
        // Sort: directories first, then by name
        children.sort((a, b) -> {
            boolean aDir = Files.isDirectory(a);
            boolean bDir = Files.isDirectory(b);
            if (aDir != bDir) {
                return aDir ? -1 : 1;
            }
            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
        });

        for (Path child : children) {
            String name = child.getFileName().toString();

            // Skip excluded directories
            if (Files.isDirectory(child) && isExcludedDir(name)) {
                continue;
            }

            // Apply pattern filter
            Path relative = root.relativize(child);
            String relPath = relative.toString().replace('\\', '/');
            if (patternLower != null && !relPath.toLowerCase(Locale.ROOT).contains(patternLower)) {
                // If it's a directory, we might still want to descend to find matching children
                if (Files.isDirectory(child) && depth + 1 < maxDepth) {
                    scanRecursive(root, child, depth + 1, maxDepth, patternLower, entries);
                }
                continue;
            }

            boolean isDir = Files.isDirectory(child);
            int indent = depth;
            entries.add(new Entry(relPath, isDir, indent));

            if (isDir && depth + 1 < maxDepth) {
                scanRecursive(root, child, depth + 1, maxDepth, patternLower, entries);
            }
        }
    }

    private boolean isExcludedDir(String name) {
        return EXCLUDED_DIRS.contains(name);
    }

    private String formatOutput(Path scanRoot, List<Entry> entries, int depth, String pattern) {
        StringBuilder sb = new StringBuilder();
        String displayPath;
        try {
            displayPath = pathGuard.getProjectRoot().relativize(scanRoot).toString();
            if (displayPath.isEmpty()) {
                displayPath = ".";
            }
        } catch (IllegalArgumentException e) {
            displayPath = scanRoot.toString();
        }
        sb.append("# Project Structure: ").append(displayPath)
                .append(" (depth=").append(depth).append(")\n\n");

        for (Entry entry : entries) {
            for (int i = 0; i < entry.indentLevel; i++) {
                sb.append("  ");
            }
            sb.append(entry.isDirectory ? "├── " : "    ");
            sb.append(entry.path);
            sb.append(entry.isDirectory ? "/" : "");
            sb.append("\n");
        }

        long fileCount = entries.stream().filter(e -> !e.isDirectory).count();
        long dirCount = entries.stream().filter(e -> e.isDirectory).count();
        sb.append("\n# ").append(fileCount).append(" files, ")
                .append(dirCount).append(" directories");
        if (pattern != null) {
            sb.append(" (pattern=\"").append(pattern).append("\")");
        }
        sb.append("\n");

        return sb.toString();
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

    /** Internal entry for tree formatting. */
    private static final class Entry {
        final String path;
        final boolean isDirectory;
        final int indentLevel;

        Entry(String path, boolean isDirectory, int indentLevel) {
            this.path = path;
            this.isDirectory = isDirectory;
            this.indentLevel = indentLevel;
        }
    }
}
