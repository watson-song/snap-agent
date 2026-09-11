package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for {@link CodeGraphCli} — verify the integration-time builder
 * produces an H2 file that the runtime {@link H2CodeGraphIndex} can load,
 * including the key-source full-body excerpt and skills-mode filtering.
 */
class CodeGraphCliTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildH2FileWithSkillsModeAndFullBody() throws Exception {
        // Arrange: a business class referenced by a skill, and an unrelated class
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        writeJava(srcDir.resolve("AllocationPlanService.java"),
                "package com.test;\n"
                        + "public class AllocationPlanService {\n"
                        + "    public int compute(int a, int b) {\n"
                        + "        return a + b;\n"
                        + "    }\n"
                        + "}\n");
        writeJava(srcDir.resolve("UnrelatedService.java"),
                "package com.test;\n"
                        + "public class UnrelatedService {\n"
                        + "    public void noop() {}\n"
                        + "}\n");

        Path skillDir = tempDir.resolve("skills");
        Files.createDirectories(skillDir);
        writeJava(skillDir.resolve("allocation-plan.md"),
                "# 调拨计划\n排查 AllocationPlanService 的 compute 方法\n");

        Path outputDir = tempDir.resolve("data");
        Files.createDirectories(outputDir);

        // Act: integration-time build via the CLI
        int code = CodeGraphCli.run(new String[]{
                "--project-root", srcDir.toString(),
                "--scan-mode", "skills",
                "--skill-dir", skillDir.toString(),
                "--output", outputDir.resolve("codegraph").toString()
        });

        // Assert: exit 0 and the H2 file is loadable with skills filtering applied
        assertThat(code).isEqualTo(0);

        String url = "jdbc:h2:file:" + outputDir.resolve("codegraph").toString();
        H2CodeGraphIndex index = new H2CodeGraphIndex(url);
        try {
            CodeGraphNode service = index.findByName("AllocationPlanService").stream()
                    .filter(n -> n.getType() == CodeGraphNode.NodeType.CLASS)
                    .findFirst().orElse(null);
            assertThat(service).isNotNull();
            // Full method body preserved for offline diagnosis
            assertThat(service.getSourceCode()).contains("return a + b");

            // The unrelated class (not referenced by any skill) is filtered out
            assertThat(index.findByName("UnrelatedService")).isEmpty();
        } finally {
            index.close();
        }
    }

    private void writeJava(Path file, String content) throws Exception {
        Files.write(file, content.getBytes("UTF-8"));
    }
}
