package com.watsontech.snapagent.boot2x.tool;

import com.watsontech.snapagent.core.tool.ToolContext;
import com.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogReadToolProvider}.
 */
class LogReadToolProviderTest {

    @TempDir
    Path tempDir;

    private Path logFile;
    private LogReadToolProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        logFile = tempDir.resolve("app.log");
        Files.write(logFile, Arrays.asList(
                "2024-01-01 10:00:00 INFO  Starting application",
                "2024-01-01 10:00:01 DEBUG Loading config",
                "2024-01-01 10:00:02 WARN  Cache miss for key foo",
                "2024-01-01 10:00:03 ERROR NullPointerException at com.app.Service",
                "2024-01-01 10:00:04 ERROR Database connection failed",
                "2024-01-01 10:00:05 INFO  Retrying connection",
                "2024-01-01 10:00:06 ERROR Timeout waiting for response",
                "2024-01-01 10:00:07 INFO  Application started"));

        LogPathGuard guard = new LogPathGuard(
                Collections.singletonList(tempDir.toString()),
                500, 10L * 1024 * 1024);
        provider = new LogReadToolProvider(guard);
    }

    @Test
    void shouldReturnNameLogRead() {
        assertThat(provider.name()).isEqualTo("log_read");
    }

    @Test
    void shouldReturnSchemaContainingFilePathProperty() {
        String schema = provider.schema();

        assertThat(schema).contains("log_read");
        assertThat(schema).contains("file_path");
        assertThat(schema).contains("required");
    }

    @Test
    void shouldReadAllLinesWithoutFilter() {
        Map<String, Object> args = args("file_path", logFile.toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Starting application");
        assertThat(result.getContent()).contains("Application started");
        assertThat(result.getRowCount()).isEqualTo(8);
    }

    @Test
    void shouldFilterByKeyword() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("keyword", "ERROR");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
        assertThat(result.getContent()).contains("NullPointerException");
        assertThat(result.getContent()).contains("Database connection failed");
        assertThat(result.getContent()).contains("Timeout waiting for response");
        assertThat(result.getContent()).doesNotContain("Starting application");
    }

    @Test
    void shouldFilterByLevel() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("level", "ERROR");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
    }

    @Test
    void shouldFilterByLevelCaseInsensitive() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("level", "error");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
    }

    @Test
    void shouldFilterByKeywordAndLevelCombined() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("keyword", "Database");
        args.put("level", "ERROR");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getContent()).contains("Database connection failed");
    }

    @Test
    void shouldReturnMostRecentLinesInTailMode() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("tail", true);
        args.put("max_lines", 3);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
        assertThat(result.getContent()).contains("Timeout waiting for response");
        assertThat(result.getContent()).contains("Application started");
        assertThat(result.getContent()).doesNotContain("Starting application");
    }

    @Test
    void shouldCapAtMaxLines() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("max_lines", 2);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldCapAtGuardMaxLinesWhenExceeded() {
        LogPathGuard smallGuard = new LogPathGuard(
                Collections.singletonList(tempDir.toString()), 2, 10L * 1024 * 1024);
        LogReadToolProvider smallProvider = new LogReadToolProvider(smallGuard);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("max_lines", 1000);

        ToolResult result = smallProvider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectMissingFilePath() {
        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("file_path");
    }

    @Test
    void shouldRejectPathOutsideAllowedDir() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", "/etc/passwd");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("不在允许");
    }

    @Test
    void shouldRejectNonExistentFile() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", tempDir.resolve("nope.log").toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("不存在");
    }

    @Test
    void shouldIncludeMetadataInOutput() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("keyword", "ERROR");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.getContent()).contains("# Log:");
        assertThat(result.getContent()).contains("# Filters:");
        assertThat(result.getContent()).contains("keyword=\"ERROR\"");
        assertThat(result.getContent()).contains("# Lines:");
    }

    @Test
    void shouldReturnEmptyResultWhenNoMatches() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("keyword", "nonexistent_keyword_xyz");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
        assertThat(result.getContent()).contains("# Lines: 0");
    }

    @Test
    void shouldHandleEmptyFile() throws IOException {
        Path emptyLog = tempDir.resolve("empty.log");
        Files.createFile(emptyLog);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", emptyLog.toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
    }

    @Test
    void shouldTailWithKeywordFilter() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", logFile.toString());
        args.put("keyword", "ERROR");
        args.put("tail", true);
        args.put("max_lines", 2);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        // Last 2 ERROR lines
        assertThat(result.getContent()).contains("Database connection failed");
        assertThat(result.getContent()).contains("Timeout waiting for response");
        assertThat(result.getContent()).doesNotContain("NullPointerException");
    }

    private Map<String, Object> args(String key, String value) {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put(key, value);
        return args;
    }

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }
}
