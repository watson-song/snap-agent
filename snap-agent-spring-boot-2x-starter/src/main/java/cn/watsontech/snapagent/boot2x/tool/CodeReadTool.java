package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.bridge.FileBridgeService;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Tool for reading source code files.
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li><b>Local mode</b> (default): Reads files from server-side directories
 *       using Java NIO. Used when running standalone locally.</li>
 *   <li><b>Bridge mode</b>: Reads files from user's local machine via
 *       Chrome extension's fileSystem API. Used when server is remote.</li>
 * </ul>
 *
 * <p>Bridge mode requires:</p>
 * <ol>
 *   <li>User has installed SnapAgent Bridge Chrome extension v1.1.0+</li>
 *   <li>User has authorized a code directory in the extension popup</li>
 *   <li>SnapAgent server has bridge.enabled=true</li>
 * </ol>
 */
public class CodeReadTool {

    private final String[] baseDirs;
    private final FileBridgeService fileBridgeService;

    /** Local mode constructor. */
    public CodeReadTool(String... baseDirs) {
        this.baseDirs = baseDirs;
        this.fileBridgeService = null;
    }

    /** Bridge mode constructor. */
    public CodeReadTool(FileBridgeService fileBridgeService, String... baseDirs) {
        this.baseDirs = baseDirs;
        this.fileBridgeService = fileBridgeService;
    }

    @Tool(name = "code_read", description = "Read source code file by class name or relative path. " +
        "Supports .java, .py, .js, .ts, .md files. " +
        "Example: 'cn.watsontech.snapagent.core.memory.MessageChatMemoryAdvisor' or 'core/memory/MessageChatMemoryAdvisor.java'")
    public String readCode(
            @ToolParam(description = "Class name (e.g., MessageChatMemoryAdvisor) or relative path (e.g., core/memory/MessageChatMemoryAdvisor.java)")
            String identifier) {

        // Try bridge mode first if available
        if (fileBridgeService != null && fileBridgeService.isActive()) {
            String filePath = convertToFilePath(identifier);
            return readViaBridge(filePath);
        }

        // Fallback to local mode
        return readLocal(identifier);
    }

    @Tool(name = "code_search", description = "Search for files containing specific text. " +
        "Returns file paths and matching lines.")
    public String searchCode(
            @ToolParam(description = "Text to search for") String query,
            @ToolParam(description = "File extension filter (e.g., '.java', '.py'). Leave empty for all.")
            String extension) {
        return searchLocal(query, extension);
    }

    // ---- Bridge mode ----

    private String readViaBridge(String filePath) {
        try {
            FileBridgeService.FileReadResult result = fileBridgeService.readFile(filePath, 15000);
            if (result.isSuccess()) {
                return "File: " + filePath + " (via bridge)\n\n" + result.getContent();
            } else {
                return "ERROR (bridge): " + result.getError();
            }
        } catch (Exception e) {
            return "ERROR: Bridge read failed: " + e.getMessage();
        }
    }

    // ---- Local mode ----

    private String readLocal(String identifier) {
        try {
            Path filePath = resolveFilePath(identifier);
            if (filePath == null) {
                return "ERROR: File not found for identifier: " + identifier +
                       "\n\nSearched in:\n" + String.join("\n", baseDirs);
            }

            if (!Files.exists(filePath)) {
                return "ERROR: File does not exist: " + filePath;
            }

            String content = Files.lines(filePath)
                .collect(Collectors.joining("\n"));

            return "File: " + filePath + "\n\n" + content;
        } catch (IOException e) {
            return "ERROR: Failed to read file: " + e.getMessage();
        }
    }

    private String searchLocal(String query, String extension) {
        try {
            StringBuilder result = new StringBuilder();
            int maxResults = 20;
            int count = 0;

            for (String baseDir : baseDirs) {
                Path basePath = Paths.get(baseDir);
                if (!Files.exists(basePath)) continue;

                java.util.stream.Stream<Path> stream = Files.walk(basePath);
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file)) {
                        String fileName = file.toString();
                        if (extension != null && !extension.isEmpty()
                            && !fileName.endsWith(extension)) {
                            continue;
                        }

                        try {
                            String content = new String(Files.readAllBytes(file));
                            if (content.contains(query)) {
                                result.append("File: ").append(file).append("\n");
                                String[] lines = content.split("\n");
                                for (int i = 0; i < lines.length && i < 5; i++) {
                                    if (lines[i].contains(query)) {
                                        result.append("  Line ").append(i + 1)
                                              .append(": ").append(lines[i]).append("\n");
                                    }
                                }
                                result.append("\n");
                                count++;
                                if (count >= maxResults) break;
                            }
                        } catch (IOException e) {
                            // Skip unreadable files
                        }
                    }
                }
                if (count >= maxResults) break;
            }

            if (count == 0) {
                return "No files found containing: " + query;
            }

            return "Found " + count + " file(s):\n\n" + result.toString();
        } catch (IOException e) {
            return "ERROR: Search failed: " + e.getMessage();
        }
    }

    // ---- Helpers ----

    private String convertToFilePath(String identifier) {
        if (identifier.contains("/") || identifier.endsWith(".java")
            || identifier.endsWith(".py") || identifier.endsWith(".js")) {
            return identifier;
        }

        String className = identifier;
        if (className.endsWith(".java")) {
            className = className.substring(0, className.length() - 5);
        }

        return className.replace('.', '/') + ".java";
    }

    private Path resolveFilePath(String identifier) {
        if (identifier.contains("/") || identifier.endsWith(".java")
            || identifier.endsWith(".py") || identifier.endsWith(".js")) {
            for (String baseDir : baseDirs) {
                Path path = Paths.get(baseDir, identifier);
                if (Files.exists(path)) return path;
            }
            return null;
        }

        String className = identifier;
        String extension = ".java";

        if (className.endsWith(".java")) {
            className = className.substring(0, className.length() - 5);
        }

        String relativePath = className.replace('.', '/') + extension;

        for (String baseDir : baseDirs) {
            Path path = Paths.get(baseDir, relativePath);
            if (Files.exists(path)) return path;
        }

        return null;
    }
}
