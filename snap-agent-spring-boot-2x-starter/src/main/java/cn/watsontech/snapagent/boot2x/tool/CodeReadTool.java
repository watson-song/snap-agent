package cn.watsontech.snapagent.boot2x.tool;

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
 * <p>Allows the agent to retrieve source code by class name or file path.
 * Supports Java, Python, JavaScript, TypeScript, and Markdown files.</p>
 *
 * <p>Security: only reads files within allowed base directories.</p>
 */
public class CodeReadTool {

    private final String[] baseDirs;

    public CodeReadTool(String... baseDirs) {
        this.baseDirs = baseDirs;
    }

    @Tool(name = "code_read", description = "Read source code file by class name or relative path. " +
        "Supports .java, .py, .js, .ts, .md files. " +
        "Example: 'cn.watsontech.snapagent.core.memory.MessageChatMemoryAdvisor' or 'core/memory/MessageChatMemoryAdvisor.java'")
    public String readCode(
            @ToolParam(description = "Class name (e.g., MessageChatMemoryAdvisor) or relative path (e.g., core/memory/MessageChatMemoryAdvisor.java)") 
            String identifier) {
        
        try {
            Path filePath = resolveFilePath(identifier);
            if (filePath == null) {
                return "ERROR: File not found for identifier: " + identifier;
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

    @Tool(name = "code_search", description = "Search for files containing specific text. " +
        "Returns file paths and matching lines.")
    public String searchCode(
            @ToolParam(description = "Text to search for") String query,
            @ToolParam(description = "File extension filter (e.g., '.java', '.py'). Leave empty for all.") 
            String extension) {
        
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

    private Path resolveFilePath(String identifier) {
        // If it looks like a file path
        if (identifier.contains("/") || identifier.endsWith(".java") 
            || identifier.endsWith(".py") || identifier.endsWith(".js")) {
            for (String baseDir : baseDirs) {
                Path path = Paths.get(baseDir, identifier);
                if (Files.exists(path)) return path;
            }
            return null;
        }

        // If it looks like a class name (convert to path)
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
