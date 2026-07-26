package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * 2.x project structure tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code ProjectStructureToolProvider} (which implemented
 * the now-removed {@code ToolProvider} SPI) to individual {@code @Tool} methods
 * discovered by {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.
 * Each method becomes a separate {@link cn.watsontech.snapagent.core.tool.ToolCallback}
 * with auto-generated JSON Schema, registered to
 * {@link cn.watsontech.snapagent.core.tool.ToolCallbackRegistry} alongside
 * built-in tools.</p>
 *
 * <p>Tool name: {@code project_structure}. Walks the directory tree under the
 * configured project root up to a specified depth, skipping build artifacts and
 * version control directories. Returns a tree-formatted text layout.</p>
 *
 * <p>All path safety is delegated to {@link CodePathGuard}. The tool never
 * writes, deletes, or modifies files.</p>
 */
public class ProjectStructureTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectStructureTools.class);

    private static final Set<String> EXCLUDED_DIRS = new HashSet<String>(Arrays.asList(
            "target", ".git", "node_modules", "build", ".idea", ".settings",
            "dist", ".gradle", ".mvn", "__pycache__"));

    private final CodePathGuard pathGuard;

    public ProjectStructureTools(CodePathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Tool(name = "project_structure", description = "扫描项目目录结构，返回树形布局。可指定子路径和扫描深度。")
    public String projectStructure(
            @ToolParam(description = "要扫描的子路径（相对项目根目录），默认为项目根", required = false) String path,
            @ToolParam(description = "扫描深度（默认 3，范围 1-10）", required = false) Integer depth,
            @ToolParam(description = "可选，只返回路径名包含此关键词的条目", required = false) String pattern) {

        int effectiveDepth = depth != null ? depth : 3;
        if (effectiveDepth <= 0 || effectiveDepth > 10) {
            effectiveDepth = 3;
        }
        String patternLower = pattern != null && !pattern.isEmpty()
                ? pattern.toLowerCase(Locale.ROOT) : null;

        Path scanRoot = pathGuard.resolveWithinProject(path);
        if (scanRoot == null) {
            return "Error: 路径被拒绝：包含 .. 或不在项目根目录下";
        }
        if (!Files.exists(scanRoot)) {
            return "Error: 路径不存在: " + scanRoot;
        }

        log.info("Scanning project structure: {} (depth={}, pattern={})", scanRoot, effectiveDepth, pattern);

        try {
            List<Entry> entries = scan(scanRoot, effectiveDepth, patternLower);
            String content = formatOutput(scanRoot, entries, effectiveDepth, pattern);
            boolean truncated = entries.size() >= 500;
            if (truncated) {
                return content + "\n# (truncated at 500 entries)\n";
            }
            return content;
        } catch (IOException e) {
            log.error("Project structure scan failed: {}", e.getMessage());
            return "Error: Scan failed: " + e.getMessage();
        }
    }

    // ---- Internal helpers (reused from 1.x ProjectStructureToolProvider) ----

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
        try (Stream<Path> stream = Files.list(current)) {
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
