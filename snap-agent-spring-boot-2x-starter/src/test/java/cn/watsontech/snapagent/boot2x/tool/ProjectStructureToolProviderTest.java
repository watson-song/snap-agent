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
 * Unit tests for {@link ProjectStructureToolProvider}.
 */
class ProjectStructureToolProviderTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private ProjectStructureToolProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("project");
        // Create a small project tree:
        // project/
        //   pom.xml
        //   src/main/java/com/example/
        //     Controller.java
        //     Service.java
        //   src/test/java/com/example/
        //     ServiceTest.java
        //   target/   <- excluded
        //     compiled.dat
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        Files.createDirectories(projectRoot.resolve("src/test/java/com/example"));
        Files.createDirectories(projectRoot.resolve("target"));
        Files.write(projectRoot.resolve("pom.xml"), Arrays.asList("<project/>"));
        Files.write(projectRoot.resolve("src/main/java/com/example/Controller.java"),
                Arrays.asList("public class Controller {}"));
        Files.write(projectRoot.resolve("src/main/java/com/example/Service.java"),
                Arrays.asList("public class Service {}"));
        Files.write(projectRoot.resolve("src/test/java/com/example/ServiceTest.java"),
                Arrays.asList("public class ServiceTest {}"));
        Files.write(projectRoot.resolve("target/compiled.dat"),
                Arrays.asList("compiled"));

        CodePathGuard guard = new CodePathGuard(projectRoot.toString(),
                Arrays.asList(".java", ".xml", ".dat"), 500, 512L * 1024);
        provider = new ProjectStructureToolProvider(guard);
    }

    @Test
    void shouldReturnNameProjectStructure() {
        assertThat(provider.name()).isEqualTo("project_structure");
    }

    @Test
    void shouldReturnSchemaContainingDepthAndPath() {
        String schema = provider.schema();
        assertThat(schema).contains("project_structure");
        assertThat(schema).contains("path");
        assertThat(schema).contains("depth");
        assertThat(schema).contains("pattern");
    }

    @Test
    void shouldScanFullProjectTree() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("depth", 6);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Controller.java");
        assertThat(result.getContent()).contains("Service.java");
        assertThat(result.getContent()).contains("ServiceTest.java");
        assertThat(result.getContent()).contains("pom.xml");
        // target/ should be excluded
        assertThat(result.getContent()).doesNotContain("compiled.dat");
        assertThat(result.getContent()).doesNotContain("target");
    }

    @Test
    void shouldScanSubPath() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("path", "src/main/java/com/example");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Controller.java");
        assertThat(result.getContent()).contains("Service.java");
        assertThat(result.getContent()).doesNotContain("ServiceTest.java");
    }

    @Test
    void shouldRespectDepthLimit() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("depth", 1);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        // depth=1 means only top-level: pom.xml, src/
        assertThat(result.getContent()).contains("pom.xml");
        assertThat(result.getContent()).contains("src/");
        // Deeper entries should not appear
        assertThat(result.getContent()).doesNotContain("Controller.java");
        assertThat(result.getContent()).doesNotContain("Service.java");
    }

    @Test
    void shouldFilterByPattern() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("pattern", "Service");
        args.put("depth", 6);

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Service.java");
        assertThat(result.getContent()).contains("ServiceTest.java");
        assertThat(result.getContent()).doesNotContain("Controller.java");
        assertThat(result.getContent()).contains("pattern=\"Service\"");
    }

    @Test
    void shouldExcludeTargetAndGitDirectories() throws IOException {
        Files.createDirectories(projectRoot.resolve(".git"));
        Files.write(projectRoot.resolve(".git/config"), Arrays.asList("[core]"));

        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.getContent()).doesNotContain(".git");
        assertThat(result.getContent()).doesNotContain("target");
    }

    @Test
    void shouldRejectTraversalInPath() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("path", "../other");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("..");
    }

    @Test
    void shouldRejectNonExistentPath() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("path", "nonexistent");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("不存在");
    }

    @Test
    void shouldIncludeFileAndDirCountInOutput() {
        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.getContent()).contains("files");
        assertThat(result.getContent()).contains("directories");
    }

    @Test
    void shouldFormatAsTreeWithIndentation() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("path", "src/main/java/com/example");

        ToolResult result = provider.execute(args, ctx());

        // Files at indent level 0 (direct children of the scanned path)
        assertThat(result.getContent()).contains("Controller.java");
        assertThat(result.getContent()).contains("Service.java");
    }

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }
}
