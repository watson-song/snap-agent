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

        // classpath: is normalized to classpath*: which finds both test and main resources
        assertThat(skills).extracting(SkillMeta::getName)
                .contains("builtin-standalone", "builtin-dir-skill");
    }

    @Test
    void shouldSkipAuxiliaryMdFilesInDirectorySkill() {
        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        // REFERENCE.md should NOT appear as a skill
        assertThat(skills).extracting(SkillMeta::getName)
                .doesNotContain("REFERENCE");
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
     * Verifies that every shipped builtin skill file (under
     * {@code src/main/resources/docs/skills/}) parses correctly and is
     * AVAILABLE. Guards against YAML malformations (e.g. unescaped ASCII
     * double quotes inside a YAML double-quoted string) that would make
     * a skill silently INVALID at runtime.
     */
    @Test
    void shippedBuiltinSkillsShouldParseAndBeAvailable() throws IOException {
        String[] skillFiles = {
                "docs/skills/code-analysis.md",
                "docs/skills/config-diff.md",
                "docs/skills/database-query.md",
                "docs/skills/error-spike-investigation.md",
                "docs/skills/health-check.md",
                "docs/skills/health-patrol.md",
                "docs/skills/log-analysis.md",
                "docs/skills/ops-health-check.md",
                "docs/skills/redis-query.md",
                "docs/skills/slow-query-analysis.md",
                "docs/skills/solution-suggest.md",
                "docs/skills/trend-prediction.md",
                "docs/skills/verify-fix.md"
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
                assertThat(meta.getDescription()).as("description for %s", path).isNotEmpty();
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
