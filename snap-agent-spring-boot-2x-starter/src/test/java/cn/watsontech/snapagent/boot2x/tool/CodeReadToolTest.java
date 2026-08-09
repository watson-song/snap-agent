package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodeReadToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadJavaFileByClassName() throws IOException {
        // Create test Java file
        Path javaFile = tempDir.resolve("cn/watsontech/Test.java");
        Files.createDirectories(javaFile.getParent());
        Files.write(javaFile, "package cn.watsontech;\npublic class Test {}".getBytes());

        CodeReadTool tool = new CodeReadTool(tempDir.toString());
        String result = tool.readCode("cn.watsontech.Test");

        assertThat(result).contains("public class Test");
        assertThat(result).contains("File:");
    }

    @Test
    void shouldReadJavaFileByPath() throws IOException {
        Path javaFile = tempDir.resolve("Test.java");
        Files.write(javaFile, "public class Test {}".getBytes());

        CodeReadTool tool = new CodeReadTool(tempDir.toString());
        String result = tool.readCode("Test.java");

        assertThat(result).contains("public class Test");
    }

    @Test
    void shouldReturnErrorWhenFileNotFound() {
        CodeReadTool tool = new CodeReadTool(tempDir.toString());
        String result = tool.readCode("NonExistentClass");

        assertThat(result).startsWith("ERROR: File not found");
    }

    @Test
    void shouldSearchCode() throws IOException {
        Path javaFile = tempDir.resolve("SearchTest.java");
        Files.write(javaFile, "public class SearchTest {\n  void hello() {}\n}".getBytes());

        CodeReadTool tool = new CodeReadTool(tempDir.toString());
        String result = tool.searchCode("SearchTest", ".java");

        assertThat(result).contains("SearchTest.java");
        assertThat(result).contains("Line 1");
    }

    @Test
    void shouldReturnNoResultsWhenSearchFails() {
        CodeReadTool tool = new CodeReadTool(tempDir.toString());
        String result = tool.searchCode("unique_string_xyz", ".java");

        assertThat(result).contains("No files found");
    }
}
