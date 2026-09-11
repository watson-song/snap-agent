package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.tool.CodeReadTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code CodeReadTool} is wired against the host project root
 * ({@code snap-agent.code.project-root}) rather than SnapAgent's own source
 * directories.
 *
 * <p>This is a regression guard: in embedded deployments the host JVM runs
 * WITHOUT SnapAgent sources, so {@code code_read} must resolve host business
 * source files under {@code project-root}, not {@code user.dir}/snap-agent-core.</p>
 */
class ToolAutoConfigurationCodeReadTest {

    @TempDir
    Path projectRoot;

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SnapAgentAutoConfiguration.class));

    @Test
    void codeReadToolShouldReadHostProjectRoot() throws Exception {
        // Arrange: a host business class under project-root
        Path hostFile = projectRoot.resolve("com/example/HostService.java");
        Files.createDirectories(hostFile.getParent());
        Files.write(hostFile,
                ("package com.example;\n"
                        + "public class HostService {\n"
                        + "    public String greet() { return \"hello\"; }\n"
                        + "}\n").getBytes("UTF-8"));

        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.code.enabled=true",
                        "snap-agent.code.project-root=" + projectRoot.toString())
                .run(context -> {
                    CodeReadTool tool = context.getBean(CodeReadTool.class);
                    String result = tool.readCode("com.example.HostService");
                    assertThat(result).contains("public class HostService");
                    assertThat(result).contains("greet");
                });
    }
}
