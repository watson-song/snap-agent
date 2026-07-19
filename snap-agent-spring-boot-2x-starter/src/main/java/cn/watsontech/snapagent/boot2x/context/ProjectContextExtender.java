package cn.watsontech.snapagent.boot2x.context;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.SystemPromptExtender;
import cn.watsontech.snapagent.core.skill.SkillMeta;
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

/**
 * {@link SystemPromptExtender} that injects a project structure summary
 * into the system prompt.
 *
 * <p>Scans the project root at construction time (once, at startup) and caches
 * the result as a String. The summary identifies Maven/Gradle modules, counts
 * Java files per module, and lists key directories.</p>
 *
 * <p>The cached summary is truncated to ~1500 characters to keep the system
 * prompt within reasonable token bounds.</p>
 */
public class ProjectContextExtender implements SystemPromptExtender {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextExtender.class);

    private static final Set<String> EXCLUDED_DIRS = new HashSet<String>(Arrays.asList(
            "target", ".git", "node_modules", "build", ".idea", ".settings",
            "dist", ".gradle", ".mvn", "__pycache__"));

    private static final int MAX_SUMMARY_CHARS = 1500;

    private final String cachedSummary;

    public ProjectContextExtender(CodePathGuard pathGuard, int structureDepth) {
        this.cachedSummary = generateSummary(pathGuard.getProjectRoot(), structureDepth);
        log.info("ProjectContextExtender initialized (summary {} chars)", cachedSummary.length());
    }

    @Override
    public String extend(SkillMeta skill, AgentTask task) {
        return cachedSummary;
    }

    /**
     * Get the cached summary (visible for testing).
     */
    String getCachedSummary() {
        return cachedSummary;
    }

    private String generateSummary(Path projectRoot, int maxDepth) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 项目结构\n\n");
        sb.append("项目根: ").append(projectRoot).append("\n\n");

        // Find modules (directories containing pom.xml or build.gradle)
        List<ModuleInfo> modules = findModules(projectRoot, maxDepth);
        if (!modules.isEmpty()) {
            sb.append("模块:\n");
            for (ModuleInfo mod : modules) {
                String relPath = relativize(projectRoot, mod.path);
                if (relPath.isEmpty()) {
                    relPath = ".";
                }
                sb.append("- ").append(mod.name)
                        .append(" (").append(relPath).append(")")
                        .append(" — ").append(mod.javaFileCount).append(" 个 Java 文件\n");
            }
            sb.append("\n");
        }

        // List key directories (depth <= maxDepth, excluding build artifacts)
        sb.append("关键目录:\n");
        List<String> keyDirs = findKeyDirectories(projectRoot, maxDepth);
        if (keyDirs.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (String dir : keyDirs) {
                sb.append("- ").append(dir).append("\n");
            }
        }

        // Truncate to max chars
        String result = sb.toString();
        if (result.length() > MAX_SUMMARY_CHARS) {
            result = result.substring(0, MAX_SUMMARY_CHARS - 20)
                    + "\n... (截断)\n";
        }
        return result;
    }

    private List<ModuleInfo> findModules(Path root, int maxDepth) {
        List<ModuleInfo> modules = new ArrayList<ModuleInfo>();
        findModulesRecursive(root, root, 0, maxDepth, modules);
        return modules;
    }

    private void findModulesRecursive(Path root, Path current, int depth,
                                       int maxDepth, List<ModuleInfo> modules) {
        if (depth > maxDepth || modules.size() >= 20) {
            return;
        }

        // Check if current directory is a module (has pom.xml or build.gradle)
        if (Files.exists(current.resolve("pom.xml"))
                || Files.exists(current.resolve("build.gradle"))) {
            String name = current.getFileName() != null
                    ? current.getFileName().toString() : root.getFileName().toString();
            int javaCount = countJavaFiles(current, 10);
            modules.add(new ModuleInfo(current, name, javaCount));
        }

        if (depth >= maxDepth) {
            return;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(current)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> !EXCLUDED_DIRS.contains(d.getFileName().toString()))
                    .forEach(d -> findModulesRecursive(root, d, depth + 1, maxDepth, modules));
        } catch (IOException e) {
            log.warn("Failed to list directory {}: {}", current, e.getMessage());
        }
    }

    private int countJavaFiles(Path moduleRoot, int depth) {
        int[] count = {0};
        countJavaFilesRecursive(moduleRoot, 0, depth, count);
        return count[0];
    }

    private void countJavaFilesRecursive(Path dir, int depth, int maxDepth, int[] count) {
        if (depth > maxDepth || count[0] >= 500) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                if (Files.isRegularFile(p)) {
                    String name = p.getFileName().toString();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".java")) {
                        count[0]++;
                    }
                } else if (Files.isDirectory(p)
                        && !EXCLUDED_DIRS.contains(p.getFileName().toString())) {
                    countJavaFilesRecursive(p, depth + 1, maxDepth, count);
                }
            });
        } catch (IOException e) {
            // Skip
        }
    }

    private List<String> findKeyDirectories(Path root, int maxDepth) {
        List<String> dirs = new ArrayList<String>();
        findKeyDirsRecursive(root, root, 0, maxDepth, dirs);
        return dirs;
    }

    private void findKeyDirsRecursive(Path root, Path current, int depth,
                                       int maxDepth, List<String> dirs) {
        if (depth >= maxDepth || dirs.size() >= 15) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(current)) {
            List<Path> subdirs = new ArrayList<Path>();
            stream.filter(Files::isDirectory)
                    .filter(d -> !EXCLUDED_DIRS.contains(d.getFileName().toString()))
                    .forEach(subdirs::add);

            for (Path sub : subdirs) {
                String relPath = relativize(root, sub).replace('\\', '/');
                String dirName = sub.getFileName().toString();
                // Include meaningful directory names
                if (isMeaningfulDir(dirName, relPath)) {
                    dirs.add(relPath + "/");
                }
                findKeyDirsRecursive(root, sub, depth + 1, maxDepth, dirs);
            }
        } catch (IOException e) {
            // Skip
        }
    }

    private boolean isMeaningfulDir(String dirName, String relPath) {
        // Skip trivial directories
        if ("src".equals(dirName) || "main".equals(dirName) || "test".equals(dirName)) {
            return false;
        }
        // Include directories that look like packages or config dirs
        return relPath.contains("java/") || relPath.contains("resources/")
                || "config".equals(dirName) || "controller".equals(dirName)
                || "service".equals(dirName) || "mapper".equals(dirName)
                || "model".equals(dirName) || "entity".equals(dirName)
                || "dto".equals(dirName) || "web".equals(dirName)
                || "autoconfig".equals(dirName) || "tool".equals(dirName)
                || "agent".equals(dirName) || "skill".equals(dirName)
                || "routing".equals(dirName) || "security".equals(dirName);
    }

    private String relativize(Path root, Path path) {
        try {
            Path rel = root.relativize(path);
            return rel.toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    /** Internal module info. */
    private static final class ModuleInfo {
        final Path path;
        final String name;
        final int javaFileCount;

        ModuleInfo(Path path, String name, int javaFileCount) {
            this.path = path;
            this.name = name;
            this.javaFileCount = javaFileCount;
        }
    }
}
