package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillLoader;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClasspathSkillScanner}.
 *
 * <p>Test resources are at {@code src/test/resources/docs/skills/}:</p>
 * <ul>
 *   <li>{@code standalone/builtin-standalone.md} - a standalone skill</li>
 *   <li>{@code dir-skill/SKILL.md} - a directory skill entry file</li>
 *   <li>{@code dir-skill/REFERENCE.md} - auxiliary file (should be skipped)</li>
 * </ul>
 */
class ClasspathSkillScannerTest {

    private final ClasspathSkillScanner scanner = new ClasspathSkillScanner();

    @Test
    void shouldScanStandaloneAndDirectorySkillsFromClasspath() {
        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).hasSize(2);
        assertThat(skills).extracting(SkillMeta::getName)
                .containsExactlyInAnyOrder("builtin-standalone", "builtin-dir-skill");
    }

    @Test
    void shouldSkipAuxiliaryMdFilesInDirectorySkill() {
        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        // REFERENCE.md should NOT appear as a skill
        assertThat(skills).extracting(SkillMeta::getName)
                .doesNotContain("REFERENCE");
        assertThat(skills).hasSize(2);
    }

    @Test
    void shouldParseDirectorySkillFromBody() {
        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        SkillMeta dirSkill = skills.stream()
                .filter(s -> "builtin-dir-skill".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("builtin-dir-skill not found"));

        assertThat(dirSkill.getDescription()).contains("directory builtin skill");
        assertThat(dirSkill.getBody()).contains("Directory Builtin Skill");
    }

    @Test
    void shouldReturnEmptyWhenClasspathDirIsEmpty() {
        List<SkillMeta> skills = scanner.scan("classpath:/nonexistent/");

        assertThat(skills).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenClasspathDirIsNull() {
        List<SkillMeta> skills = scanner.scan(null);

        assertThat(skills).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenClasspathDirIsEmptyString() {
        List<SkillMeta> skills = scanner.scan("");

        assertThat(skills).isEmpty();
    }

    @Test
    void shouldReturnAllSkillsAsAvailable() {
        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).allSatisfy(skill ->
                assertThat(skill.getAvailability()).isEqualTo(SkillAvailability.AVAILABLE));
    }

    /**
     * Verifies that the shipped builtin skill files (under
     * {@code src/main/resources/docs/skills/}) parse correctly and reference
     * only registered tool names. Guards against the {@code query_database}
     * vs {@code mysql_query} naming bug that previously made health-check
     * UNAVAILABLE at runtime.
     */
    @Test
    void shippedBuiltinSkillsShouldParseAndReferenceRegisteredTools() throws IOException {
        String[] skillFiles = {
                "docs/skills/health-check.md",
                "docs/skills/database-query.md",
                "docs/skills/redis-query.md",
                "docs/skills/log-analysis.md"
        };
        SkillLoader loader = new SkillLoader();
        ClassLoader cl = getClass().getClassLoader();
        for (String path : skillFiles) {
            try (InputStream is = cl.getResourceAsStream(path)) {
                assertThat(is).as("shipped builtin skill not found on classpath: %s", path).isNotNull();
                String content = readAll(is);
                SkillMeta meta = loader.parse(content);
                assertThat(meta.getAvailability())
                        .as("shipped skill %s is not AVAILABLE: %s", path, meta.getUnavailableReason())
                        .isEqualTo(SkillAvailability.AVAILABLE);
                assertThat(meta.getName()).as("name for %s", path).isNotNull();
                for (String tool : meta.getTools()) {
                    assertThat(tool)
                            .as("tool %s referenced by %s is not a registered tool", tool, path)
                            .isIn("mysql_query", "redis_get", "log_read");
                }
            }
        }
    }

    private static String readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
