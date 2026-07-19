package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodeReaderToolProvider}.
 */
class CodeReaderToolProviderTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path javaFile;
    private CodeReaderToolProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        javaFile = projectRoot.resolve("src/main/java/com/example/OrderService.java");
        Files.write(javaFile, Arrays.asList(
                "package com.example;",
                "",
                "public class OrderService {",
                "    public void createOrder() {",
                "        // TODO: validate input",
                "        System.out.println(\"creating order\");",
                "    }",
                "",
                "    public void cancelOrder() {",
                "        // skipValidation is temporary",
                "        System.out.println(\"cancelling\");",
                "    }",
                "}"));

        CodePathGuard guard = new CodePathGuard(projectRoot.toString(),
                Arrays.asList(".java", ".xml", ".yml", ".properties"),
                500, 512L * 1024);
        provider = new CodeReaderToolProvider(guard);
    }

    @Test
    void shouldReturnNameCodeRead() {
        assertThat(provider.name()).isEqualTo("code_read");
    }

    @Test
    void shouldReturnSchemaContainingFilePathProperty() {
        String schema = provider.schema();
        assertThat(schema).contains("code_read");
        assertThat(schema).contains("file_path");
        assertThat(schema).contains("required");
    }

    @Test
    void shouldReadFullFile() {
        Map<String, Object> args = args("file_path", javaFile.toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(13);
        assertThat(result.getContent()).contains("public class OrderService");
        assertThat(result.getContent()).contains("createOrder");
    }

    @Test
    void shouldReadRelativePath() {
        Map<String, Object> args = args("file_path", "src/main/java/com/example/OrderService.java");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(13);
    }

    @Test
    void shouldReadLineRange() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", javaFile.toString());
        args.put("start_line", 4);
        args.put("end_line", 7);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(4);
        assertThat(result.getContent()).contains("createOrder");
        assertThat(result.getContent()).doesNotContain("cancelOrder");
        // Line numbers should be 4-7
        assertThat(result.getContent()).contains("    4│");
        assertThat(result.getContent()).contains("    7│");
    }

    @Test
    void shouldFilterByKeywordWithContext() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", javaFile.toString());
        args.put("keyword", "System.out.println");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        // Two matching lines (line 6 and line 11), each with ±2 context
        // Line 6 match: lines 4,5,6,7,8
        // Line 11 match: lines 9,10,11,12,13
        assertThat(result.getContent()).contains("creating order");
        assertThat(result.getContent()).contains("cancelling");
        // Marker for keyword matches
        assertThat(result.getContent()).contains("→ ");
    }

    @Test
    void shouldCapAtMaxLines() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", javaFile.toString());
        args.put("max_lines", 5);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(5);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldRejectMissingFilePath() {
        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("file_path");
    }

    @Test
    void shouldRejectPathOutsideProjectRoot() {
        Map<String, Object> args = args("file_path", "/etc/passwd");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("不在项目根目录");
    }

    @Test
    void shouldRejectDisallowedExtension() throws IOException {
        Path txtFile = projectRoot.resolve("notes.txt");
        Files.write(txtFile, Arrays.asList("secret notes"));

        Map<String, Object> args = args("file_path", txtFile.toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("白名单");
    }

    @Test
    void shouldRejectNonExistentFile() {
        Map<String, Object> args = args("file_path", "src/main/java/com/NonExistent.java");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("不存在");
    }

    @Test
    void shouldIncludeMetadataInOutput() {
        Map<String, Object> args = args("file_path", javaFile.toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.getContent()).contains("# File:");
        assertThat(result.getContent()).contains("OrderService.java");
        assertThat(result.getContent()).contains("# Range:");
        assertThat(result.getContent()).contains("(of 13)");
    }

    @Test
    void shouldReturnEmptyResultWhenNoKeywordMatches() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("file_path", javaFile.toString());
        args.put("keyword", "nonexistent_xyz_123");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
    }

    @Test
    void shouldHandleEmptyFile() throws IOException {
        Path emptyFile = projectRoot.resolve("src/main/java/com/example/Empty.java");
        Files.createFile(emptyFile);

        Map<String, Object> args = args("file_path", emptyFile.toString());

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
    }

    @Test
    void shouldRelativizePathInOutput() {
        Map<String, Object> args = args("file_path", "src/main/java/com/example/OrderService.java");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.getContent()).contains("src/main/java/com/example/OrderService.java");
        assertThat(result.getContent()).doesNotContain(projectRoot.toString());
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
