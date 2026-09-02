package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.core.vcs.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks in-memory file changes during a fix execution session.
 * Changes are NOT written to disk — they are collected and committed
 * via VcsClient REST API.
 */
public class FixContext {

    private final String projectRoot;
    private final Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();

    public FixContext(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Adds or replaces a file change in the context.
     */
    public void addChange(String filePath, String content, String action) {
        changes.put(filePath, new FileChange(filePath, content, action));
    }

    /**
     * Returns the current content for a file.
     * Priority: in-memory change > disk read.
     * Returns null if file doesn't exist on disk.
     */
    public String getFileContent(String filePath) {
        FileChange existing = changes.get(filePath);
        if (existing != null && existing.getContent() != null) {
            return existing.getContent();
        }
        try {
            Path path = resolvePath(filePath);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), "UTF-8");
            }
        } catch (IOException e) {
            // ignore — return null
        }
        return null;
    }

    /**
     * Returns all tracked changes.
     */
    public List<FileChange> getChanges() {
        return new ArrayList<>(changes.values());
    }

    /**
     * Returns the number of tracked changes.
     */
    public int size() {
        return changes.size();
    }

    /**
     * Resolves a relative file path against projectRoot.
     * Throws if path traversal is detected.
     */
    public Path resolvePath(String filePath) {
        Path resolved = Paths.get(projectRoot, filePath).normalize();
        Path root = Paths.get(projectRoot).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal detected: " + filePath);
        }
        return resolved;
    }

    /**
     * Returns the file extension (e.g. ".java").
     */
    public static String getExtension(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx >= 0) {
            return filePath.substring(dotIdx).toLowerCase();
        }
        return "";
    }
}
