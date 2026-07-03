package com.watsontech.snapagent.boot2x.tool;

import com.watsontech.snapagent.core.tool.ToolContext;
import com.watsontech.snapagent.core.tool.ToolProvider;
import com.watsontech.snapagent.core.tool.ToolResult;
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
 * {@link ToolProvider} implementation for read-only log file analysis.
 *
 * <p>Tool name: {@code log_read}. Reads application log files within configured
 * allowed directories ({@code snap-agent.logs.allowed-paths}). Supports keyword
 * search, log-level filtering, and tail mode for recent lines.</p>
 *
 * <p>All path safety is delegated to {@link LogPathGuard}. The tool never writes,
 * deletes, or modifies log files.</p>
 */
public class LogReadToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(LogReadToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"log_read\","
            + "\"description\":\"Read and filter application log files. Supports keyword search, log level filtering, and tail mode for recent logs.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"file_path\":{\"type\":\"string\",\"description\":\"Absolute path to the log file (must be under an allowed directory)\"},"
            + "\"keyword\":{\"type\":\"string\",\"description\":\"Optional case-insensitive substring filter\"},"
            + "\"level\":{\"type\":\"string\",\"enum\":[\"ERROR\",\"WARN\",\"INFO\",\"DEBUG\"],\"description\":\"Optional log level filter\"},"
            + "\"tail\":{\"type\":\"boolean\",\"description\":\"If true, return the most recent matching lines (default false)\",\"default\":false},"
            + "\"max_lines\":{\"type\":\"integer\",\"description\":\"Max lines to return (default 500)\",\"default\":500}"
            + "},"
            + "\"required\":[\"file_path\"]}}";

    private final LogPathGuard pathGuard;

    public LogReadToolProvider(LogPathGuard pathGuard) {
        if (pathGuard == null) {
            throw new IllegalArgumentException("pathGuard must not be null");
        }
        this.pathGuard = pathGuard;
    }

    @Override
    public String name() {
        return "log_read";
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

        LogPathGuard.Result guardResult = pathGuard.validate(filePath);
        if (!guardResult.isAllowed()) {
            String reason = guardResult.getReason();
            log.warn("Log path rejected by guard: {}", reason);
            return ToolResult.error(reason, elapsed(start));
        }

        Path path = guardResult.getPath();
        String keyword = extractString(args, "keyword");
        String level = extractString(args, "level");
        boolean tail = extractBoolean(args, "tail", false);
        int maxLines = extractInt(args, "max_lines", pathGuard.getMaxLines());
        if (maxLines <= 0 || maxLines > pathGuard.getMaxLines()) {
            maxLines = pathGuard.getMaxLines();
        }

        log.info("Reading log file: {} (keyword={}, level={}, tail={}, maxLines={})",
                path, keyword, level, tail, maxLines);

        try {
            List<String> lines = readAndFilter(path, keyword, level, tail, maxLines);
            String content = formatOutput(path, lines, keyword, level, tail);
            boolean truncated = lines.size() >= maxLines;
            long duration = elapsed(start);
            if (truncated) {
                return ToolResult.truncated(content, lines.size(), duration);
            }
            return ToolResult.success(content, lines.size(), duration);
        } catch (IOException e) {
            log.error("Log read failed: {}", e.getMessage());
            return ToolResult.error("Log read failed: " + e.getMessage(), elapsed(start));
        }
    }

    private List<String> readAndFilter(Path path, String keyword, String level,
                                       boolean tail, int maxLines) throws IOException {
        String keywordLower = keyword != null ? keyword.toLowerCase(Locale.ROOT) : null;
        String levelUpper = level != null ? level.toUpperCase(Locale.ROOT) : null;

        List<String> matching = new ArrayList<String>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!matches(line, keywordLower, levelUpper)) {
                    continue;
                }
                if (!tail && matching.size() >= maxLines) {
                    break;
                }
                matching.add(line);
            }
        }

        if (tail) {
            int from = Math.max(0, matching.size() - maxLines);
            return new ArrayList<String>(matching.subList(from, matching.size()));
        }
        return matching;
    }

    private boolean matches(String line, String keywordLower, String levelUpper) {
        if (keywordLower != null && !keywordLower.isEmpty()) {
            if (!line.toLowerCase(Locale.ROOT).contains(keywordLower)) {
                return false;
            }
        }
        if (levelUpper != null && !levelUpper.isEmpty()) {
            if (!line.toUpperCase(Locale.ROOT).contains(levelUpper)) {
                return false;
            }
        }
        return true;
    }

    private String formatOutput(Path path, List<String> lines, String keyword,
                                String level, boolean tail) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Log: ").append(path).append("\n");
        sb.append("# Filters: ");
        List<String> filters = new ArrayList<String>();
        if (keyword != null) filters.add("keyword=\"" + keyword + "\"");
        if (level != null) filters.add("level=" + level);
        filters.add(tail ? "tail=true" : "tail=false");
        sb.append(String.join(", ", filters)).append("\n");
        sb.append("# Lines: ").append(lines.size()).append("\n\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

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

    private boolean extractBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
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
}
