package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 2.x code reading tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code CodeReaderToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI) to individual {@code @Tool} methods
 * discovered by {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.
 * Each method becomes a separate {@link cn.watsontech.snapagent.core.tool.ToolCallback}
 * with auto-generated JSON Schema, registered to
 * {@link cn.watsontech.snapagent.core.tool.ToolCallbackRegistry} alongside
 * built-in tools.</p>
 *
 * <p>Tool name: {@code code_read}. Reads source files within the configured
 * project root ({@code snap-agent.code.project-root}). Supports line-range
 * selection and keyword filtering with context lines.</p>
 *
 * <p>All path safety is delegated to {@link CodePathGuard}. The tool never
 * writes, deletes, or modifies files.</p>
 */
public class CodeReaderTools {

    private static final Logger log = LoggerFactory.getLogger(CodeReaderTools.class);

    private final CodePathGuard pathGuard;

    public CodeReaderTools(CodePathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Tool(name = "code_read", description = "读取项目源码文件内容。支持按行范围读取和关键词过滤（带上下文）。路径必须在项目根目录下。")
    public String codeRead(
            @ToolParam(description = "项目内文件的相对路径（基于项目根目录）或绝对路径", required = true) String file_path,
            @ToolParam(description = "起始行号（1-based），默认 1", required = false) Integer start_line,
            @ToolParam(description = "结束行号（1-based），默认文件末尾", required = false) Integer end_line,
            @ToolParam(description = "可选，只返回包含该关键词的行（上下文 ±2 行）", required = false) String keyword,
            @ToolParam(description = "最大返回行数（默认 500）", required = false) Integer max_lines) {

        if (file_path == null || file_path.isEmpty()) {
            return "Error: missing required parameter: file_path";
        }

        CodePathGuard.Result guardResult = pathGuard.validate(file_path);
        if (!guardResult.isAllowed()) {
            String reason = guardResult.getReason();
            log.warn("Code path rejected by guard: {}", reason);
            return "Error: " + reason;
        }

        Path path = guardResult.getPath();
        int startLine = start_line != null ? start_line : 1;
        int endLine = end_line != null ? end_line : Integer.MAX_VALUE;
        int maxLines = max_lines != null ? max_lines : 500;
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
            if (truncated) {
                return content + "\n# (truncated, showing " + resultLines.size()
                        + " of " + totalLines + " lines)\n";
            }
            return content;
        } catch (IOException e) {
            log.error("Code read failed: {}", e.getMessage());
            return "Error: Code read failed: " + e.getMessage();
        }
    }

    // ---- Internal helpers (reused from 1.x CodeReaderToolProvider) ----

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
        Set<Integer> matchIndices = new HashSet<Integer>();
        for (int i = from; i < to; i++) {
            if (allLines.get(i).toLowerCase(Locale.ROOT).contains(keywordLower)) {
                matchIndices.add(i);
            }
        }

        // Expand context: ±2 lines around each match
        Set<Integer> includeIndices = new TreeSet<Integer>();
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
