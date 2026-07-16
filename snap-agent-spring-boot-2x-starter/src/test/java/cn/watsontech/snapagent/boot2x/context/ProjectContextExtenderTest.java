package cn.watsontech.snapagent.boot2x.context;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProjectContextExtender}.
 */
class ProjectContextExtenderTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private CodePathGuard pathGuard;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("myapp");
        // Create a multi-module Maven project:
        // myapp/
        //   pom.xml
        //   core/
        //     pom.xml
        //     src/main/java/com/example/core/
        //       Agent.java
        //       Task.java
        //   web/
        //     pom.xml
        //     src/main/java/com/example/web/
        //       Controller.java
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("pom.xml"), Arrays.asList("<project/>"));

        Path coreDir = projectRoot.resolve("core");
        Files.createDirectories(coreDir.resolve("src/main/java/com/example/core"));
        Files.write(coreDir.resolve("pom.xml"), Arrays.asList("<project/>"));
        Files.write(coreDir.resolve("src/main/java/com/example/core/Agent.java"),
                Arrays.asList("public class Agent {}"));
        Files.write(coreDir.resolve("src/main/java/com/example/core/Task.java"),
                Arrays.asList("public class Task {}"));

        Path webDir = projectRoot.resolve("web");
        Files.createDirectories(webDir.resolve("src/main/java/com/example/web"));
        Files.write(webDir.resolve("pom.xml"), Arrays.asList("<project/>"));
        Files.write(webDir.resolve("src/main/java/com/example/web/Controller.java"),
                Arrays.asList("public class Controller {}"));

        pathGuard = new CodePathGuard(projectRoot.toString(),
                Arrays.asList(".java", ".xml"), 500, 512L * 1024);
    }

    @Test
    void shouldGenerateSummaryWithModules() {
        ProjectContextExtender extender = new ProjectContextExtender(pathGuard, 5);

        String summary = extender.getCachedSummary();

        assertThat(summary).contains("项目结构");
        assertThat(summary).contains("项目根");
        assertThat(summary).contains("模块");
        // Each module should be listed
        assertThat(summary).contains("core");
        assertThat(summary).contains("web");
        // Java file counts
        assertThat(summary).contains("Java");
    }

    @Test
    void shouldIncludeKeyDirectoriesInSummary() {
        ProjectContextExtender extender = new ProjectContextExtender(pathGuard, 5);

        String summary = extender.getCachedSummary();

        assertThat(summary).contains("关键目录");
    }

    @Test
    void shouldCountJavaFilesPerModule() {
        ProjectContextExtender extender = new ProjectContextExtender(pathGuard, 5);

        String summary = extender.getCachedSummary();

        // core module has 2 Java files
        assertThat(summary).contains("2");
        // web module has 1 Java file
    }

    @Test
    void shouldExcludeBuildArtifactsFromSummary() throws IOException {
        Files.createDirectories(projectRoot.resolve("target/classes"));
        Files.write(projectRoot.resolve("target/classes/Compiled.class"),
                Arrays.asList("compiled"));

        ProjectContextExtender extender = new ProjectContextExtender(pathGuard, 5);

        String summary = extender.getCachedSummary();

        assertThat(summary).doesNotContain("target");
        assertThat(summary).doesNotContain("Compiled");
    }

    @Test
    void shouldReturnSameCachedSummaryOnMultipleCalls() {
        ProjectContextExtender extender = new ProjectContextExtender(pathGuard, 5);

        SkillMeta skill = new SkillMeta("test", "desc",
                Collections.emptyList(), Collections.emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        AgentTask task = AgentTask.create("u", "s", new HashMap<>(), "m");

        String first = extender.extend(skill, task);
        String second = extender.extend(skill, task);

        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldTruncateLongSummary() throws IOException {
        // Create many modules with long names to exceed the 1500 char limit
        for (int i = 0; i < 50; i++) {
            Path modDir = projectRoot.resolve("module-with-very-long-name-" + String.format("%03d", i));
            Files.createDirectories(modDir.resolve("src/main/java/com/example/mod" + i));
            Files.write(modDir.resolve("pom.xml"), Arrays.asList("<project/>"));
            Files.write(modDir.resolve("src/main/java/com/example/mod" + i + "/MainApplicationClass.java"),
                    Arrays.asList("public class MainApplicationClass {}"));
        }

        ProjectContextExtender extender = new ProjectContextExtender(pathGuard, 5);

        String summary = extender.getCachedSummary();

        assertThat(summary.length()).isLessThanOrEqualTo(1500);
        assertThat(summary).contains("截断");
    }

    @Test
    void shouldHandleEmptyProjectRoot() throws IOException {
        Path emptyRoot = tempDir.resolve("empty");
        Files.createDirectories(emptyRoot);
        CodePathGuard emptyGuard = new CodePathGuard(emptyRoot.toString(),
                Arrays.asList(".java"), 500, 1024);

        ProjectContextExtender extender = new ProjectContextExtender(emptyGuard, 3);

        String summary = extender.getCachedSummary();

        // Should not crash, should have the header
        assertThat(summary).contains("项目结构");
        // No modules found
        assertThat(summary).contains("关键目录");
    }
}
