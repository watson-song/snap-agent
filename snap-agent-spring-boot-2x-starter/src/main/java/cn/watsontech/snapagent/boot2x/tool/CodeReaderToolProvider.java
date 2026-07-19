package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ToolProvider} implementation for reading source code files.
 *
 * <p>Tool name: {@code code_read}. Reads source files within the configured
 * project root ({@code snap-agent.code.project-root}). Supports line-range
 * selection and keyword filtering with context lines.</p>
 *
 * <p>All path safety is delegated to {@link CodePathGuard}. The tool never
 * writes, deletes, or modifies files.</p>
 */
public class CodeReaderToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CodeReaderToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"code_read\","
            + "\"description\":\"读取项目源码文件内容。支持按行范围读取和关键词过滤（带上下文）。路径必须在项目根目录下。\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"file_path\":{\"type\":\"string\",\"description\":\"项目内文件的相对路径（基于项目根目录）或绝对路径\"},"
            + "\"start_line\":{\"type\":\"integer\",\"description\":\"起始行号（1-based），默认 1\",\"default\":1},"
            + "\"end_line\":{\"type\":\"integer\",\"description\":\"结束行号（1-based），默认文件末尾\"},"
            + "\"keyword\":{\"type\":\"string\",\"description\":\"可选，只返回包含该关键词的行（上下文 ±2 行）\"},"
            + "\"max_lines\":{\"type\":\"integer\",\"description\":\"最大返回行数（默认 500）\",\"default\":500}"
            + "},"
            + "\"required\":[\"file_path\"]}}";

    private final CodePathGuard pathGuard;

    public CodeReaderToolProvider(CodePathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Override
    public String name() {
        return "code_read";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String filePath = extractString(args, "file_path");
        if (filePath == null || filePath.isEmpty()) {
            return ToolResult.error("missing required parameter: file_path", elapsed(start));
        }

        CodePathGuard.Result guardResult = pathGuard.validate(filePath);
        if (!guardResult.isAllowed()) {
            String reason = guardResult.getReason();
            log.warn("Code path rejected by guard: {}", reason);
            return ToolResult.error(reason, elapsed(start));
        }

        Path path = guardResult.getPath();
        int startLine = extractInt(args, "start_line", 1);
        int endLine = extractInt(args, "end_line", Integer.MAX_VALUE);
        String keyword = extractString(args, "keyword");
        int maxLines = extractInt(args, "max_lines", pathGuard.getMaxLines());
        if (maxLines <= 0 || maxLines > pathGuard.getMaxLines()) {
            maxLines = pathGuard.getMaxLines();
        }

        log.info("Reading code file: {} (startLine={}, endLine={}, keyword={}, maxLines={})",
                path, startLine, endLine, keyword, maxLines);

        try {
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int totalLines = allLines.size();

            // Clamp endLine to total
            if (endLine > totalLines) {
                endLine = totalLines;
            }
            if (startLine < 1) {
                startLine = 1;
            }

            List<LineEntry> resultLines;
            if (keyword != null && !keyword.isEmpty()) {
                resultLines = filterByKeyword(allLines, startLine, endLine, keyword, maxLines);
            } else {
                resultLines = readRange(allLines, startLine, endLine, maxLines);
            }

            String content = formatOutput(path, resultLines, startLine, endLine,
                    totalLines, keyword);
            boolean truncated = resultLines.size() >= maxLines
                    && (endLine - startLine + 1) > maxLines;
            long duration = elapsed(start);
            if (truncated) {
                return ToolResult.truncated(content, resultLines.size(), duration);
            }
            return ToolResult.success(content, resultLines.size(), duration);
        } catch (IOException e) {
            log.error("Code read failed: {}", e.getMessage());
            return ToolResult.error("Code read failed: " + e.getMessage(), elapsed(start));
        }
    }

    private List<LineEntry> readRange(List<String> allLines, int startLine,
                                       int endLine, int maxLines) {
        List<LineEntry> result = new ArrayList<LineEntry>();
        int from = Math.max(0, startLine - 1);
        int to = Math.min(allLines.size(), endLine);
        for (int i = from; i < to && result.size() < maxLines; i++) {
            result.add(new LineEntry(i + 1, allLines.get(i), false));
        }
        return result;
    }

    private List<LineEntry> filterByKeyword(List<String> allLines, int startLine,
                                             int endLine, String keyword, int maxLines) {
        String keywordLower = keyword.toLowerCase(Locale.ROOT);
        List<LineEntry> result = new ArrayList<LineEntry>();
        int from = Math.max(0, startLine - 1);
        int to = Math.min(allLines.size(), endLine);

        // Find matching lines, then include ±2 context lines
        java.util.Set<Integer> matchIndices = new java.util.HashSet<Integer>();
        for (int i = from; i < to; i++) {
            if (allLines.get(i).toLowerCase(Locale.ROOT).contains(keywordLower)) {
                matchIndices.add(i);
            }
        }

        // Expand context: ±2 lines around each match
        java.util.Set<Integer> includeIndices = new java.util.TreeSet<Integer>();
        for (int idx : matchIndices) {
            for (int c = Math.max(from, idx - 2); c <= Math.min(to - 1, idx + 2); c++) {
                includeIndices.add(c);
            }
        }

        for (int idx : includeIndices) {
            if (result.size() >= maxLines) {
                break;
            }
            boolean isMatch = matchIndices.contains(idx);
            result.add(new LineEntry(idx + 1, allLines.get(idx), isMatch));
        }
        return result;
    }

    private String formatOutput(Path path, List<LineEntry> lines, int startLine,
                                 int endLine, int totalLines, String keyword) {
        StringBuilder sb = new StringBuilder();
        // Compute display range (first and last line shown)
        int firstShown = lines.isEmpty() ? 0 : lines.get(0).lineNumber;
        int lastShown = lines.isEmpty() ? 0 : lines.get(lines.size() - 1).lineNumber;
        sb.append("# File: ").append(relativize(path)).append("\n");
        if (keyword != null && !keyword.isEmpty()) {
            sb.append("# Filter: keyword=\"").append(keyword).append("\"\n");
            sb.append("# Lines: ").append(firstShown).append("-").append(lastShown)
                    .append(" (of ").append(totalLines).append(")\n\n");
        } else {
            int effectiveEnd = Math.min(endLine, totalLines);
            sb.append("# Range: lines ").append(startLine).append("-").append(effectiveEnd)
                    .append(" (of ").append(totalLines).append(")\n\n");
        }
        for (LineEntry entry : lines) {
            String marker = entry.isKeywordMatch ? "→ " : "  ";
            sb.append(marker);
            sb.append(String.format("%5d", entry.lineNumber)).append("│");
            sb.append(entry.text).append("\n");
        }
        return sb.toString();
    }

    private String relativize(Path path) {
        try {
            Path rel = pathGuard.getProjectRoot().relativize(path);
            return rel.toString();
        } catch (IllegalArgumentException e) {
            // Path not under project root — show absolute
            return path.toString();
        }
    }

    // ---- arg extraction helpers (same pattern as LogReadToolProvider) ----

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

    /** Internal line entry for formatting. */
    private static final class LineEntry {
        final int lineNumber;
        final String text;
        final boolean isKeywordMatch;

        LineEntry(int lineNumber, String text, boolean isKeywordMatch) {
            this.lineNumber = lineNumber;
            this.text = text;
            this.isKeywordMatch = isKeywordMatch;
        }
    }
}
