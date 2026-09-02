package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
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

/**
 * 2.x read-only log file analysis tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code LogReadToolProvider}. Reads application log files
 * within configured allowed directories. Supports keyword search, log-level filtering,
 * and tail mode.</p>
 */
public class LogReadTools {

    private static final Logger log = LoggerFactory.getLogger(LogReadTools.class);

    private final LogPathGuard pathGuard;

    public LogReadTools(LogPathGuard pathGuard) {
        if (pathGuard == null) throw new IllegalArgumentException("pathGuard must not be null");
        this.pathGuard = pathGuard;
    }

    @Tool(name = "log_read", description = "Read and filter application log files. Supports keyword search, log level filtering, and tail mode for recent logs.")
    public String read(
            @ToolParam(description = "Absolute path to the log file (must be under an allowed directory)") String file_path,
            @ToolParam(description = "Optional case-insensitive substring filter", required = false) String keyword,
            @ToolParam(description = "Optional log level filter (ERROR/WARN/INFO/DEBUG)", required = false) String level,
            @ToolParam(description = "If true, return the most recent matching lines (default false)", required = false) Boolean tail,
            @ToolParam(description = "Max lines to return (default 500)", required = false) Integer max_lines) {

        LogPathGuard.Result guardResult = pathGuard.validate(file_path);
        if (!guardResult.isAllowed()) {
            log.warn("Log path rejected by guard: {}", guardResult.getReason());
            return "Error: " + guardResult.getReason();
        }

        Path path = guardResult.getPath();
        boolean tailMode = tail != null && tail;
        int maxLines = max_lines != null ? Math.min(max_lines, pathGuard.getMaxLines()) : pathGuard.getMaxLines();
        if (maxLines <= 0) maxLines = pathGuard.getMaxLines();

        log.info("Reading log file: {} (keyword={}, level={}, tail={}, maxLines={})",
                path, keyword, level, tailMode, maxLines);

        try {
            List<String> lines = readAndFilter(path, keyword, level, tailMode, maxLines);
            return formatOutput(path, lines, keyword, level, tailMode);
        } catch (IOException e) {
            log.error("Log read failed: {}", e.getMessage());
            return "Error: Log read failed: " + e.getMessage();
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
                if (!matches(line, keywordLower, levelUpper)) continue;
                if (!tail && matching.size() >= maxLines) break;
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
            if (!line.toLowerCase(Locale.ROOT).contains(keywordLower)) return false;
        }
        if (levelUpper != null && !levelUpper.isEmpty()) {
            if (!line.toUpperCase(Locale.ROOT).contains(levelUpper)) return false;
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
}
