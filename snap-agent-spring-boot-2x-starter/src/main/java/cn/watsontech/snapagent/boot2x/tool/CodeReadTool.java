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
 * <p>Supports two modes:</p>
 * <ul>
 *   <li><b>Local mode</b>: Reads files from server-side directories (default)</li>
 *   <li><b>Bridge mode</b>: Reads files from user's local machine via browser extension</li>
 * </ul>
 *
 * <p>Bridge mode requires:</p>
 * <ol>
 *   <li>User has installed SnapAgent Bridge Chrome extension</li>
 *   <li>User has authorized a code directory in the extension popup</li>
 *   <li>SnapAgent server has bridge.enabled=true</li>
 * </ol>
 */
public class CodeReadTool {

    private final String[] baseDirs;
    private final boolean useBridge;
    private final Object bridgeService; // IssueBridgeService or LlmBridgeService

    public CodeReadTool(String... baseDirs) {
        this.baseDirs = baseDirs;
        this.useBridge = false;
        this.bridgeService = null;
    }

    public CodeReadTool(boolean useBridge, Object bridgeService, String... baseDirs) {
        this.baseDirs = baseDirs;
        this.useBridge = useBridge;
        this.bridgeService = bridgeService;
    }

    @Tool(name = "code_read", description = "Read source code file by class name or relative path. " +
        "Supports .java, .py, .js, .ts, .md files. " +
        "Example: 'cn.watsontech.snapagent.core.memory.MessageChatMemoryAdvisor' or 'core/memory/MessageChatMemoryAdvisor.java'")
    public String readCode(
            @ToolParam(description = "Class name (e.g., MessageChatMemoryAdvisor) or relative path (e.g., core/memory/MessageChatMemoryAdvisor.java)") 
            String identifier) {
        
        // 如果使用 bridge 模式
        if (useBridge && bridgeService != null) {
            return readViaBridge(identifier);
        }
        
        // 本地模式
        return readLocal(identifier);
    }

    @Tool(name = "code_search", description = "Search for files containing specific text. " +
        "Returns file paths and matching lines.")
    public String searchCode(
            @ToolParam(description = "Text to search for") String query,
            @ToolParam(description = "File extension filter (e.g., '.java', '.py'). Leave empty for all.") 
            String extension) {
        
        // Bridge 模式暂不支持搜索，只支持本地
        return searchLocal(query, extension);
    }

    /**
     * 通过 bridge 读取本地文件
     */
    private String readViaBridge(String identifier) {
        try {
            // 转换类名为文件路径
            String filePath = convertToFilePath(identifier);
            
            // TODO: 通过 bridgeService 发送读取请求到浏览器
            // 这需要 IssueBridgeService 或类似的桥接服务
            // 目前返回提示
            return "Bridge mode file reading is not yet implemented. " +
                   "Please use local mode or wait for bridge implementation.";
        } catch (Exception e) {
            return "ERROR: Bridge read failed: " + e.getMessage();
        }
    }

    /**
     * 本地模式读取文件
     */
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

    /**
     * 本地模式搜索代码
     */
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

    /**
     * 将类名转换为文件路径
     */
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

    /**
     * 解析文件路径
     */
    private Path resolveFilePath(String identifier) {
        // 如果是文件路径
        if (identifier.contains("/") || identifier.endsWith(".java") 
            || identifier.endsWith(".py") || identifier.endsWith(".js")) {
            for (String baseDir : baseDirs) {
                Path path = Paths.get(baseDir, identifier);
                if (Files.exists(path)) return path;
            }
            return null;
        }

        // 如果是类名（转换为路径）
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
