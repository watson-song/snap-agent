package com.watsontech.snapagent.boot2x.skill;

import com.watsontech.snapagent.core.skill.SkillLoader;
import com.watsontech.snapagent.core.skill.SkillMeta;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the built-in skill protection mechanism in {@link ClasspathSkillScanner}.
 *
 * <p>Verifies that SnapAgent JAR resources take precedence over host project
 * classpath resources when both define a skill with the same name.</p>
 */
class ClasspathSkillScannerProtectionTest {

    private static final String SKILL_TEMPLATE =
            "---\nname: %s\ndescription: %s\ntools: []\n---\n# %s\n";

    @Test
    void shouldPreferSnapAgentJarResourcesOverHostResources() throws Exception {
        // Simulate: SnapAgent JAR has "database-query" and host project also has "database-query"
        Resource[] resources = new Resource[]{
                mockResource("file:/project/target/classes/docs/skills/database-query.md",
                        String.format(SKILL_TEMPLATE, "database-query",
                                "Host version of database-query", "Host")),
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/database-query.md",
                        String.format(SKILL_TEMPLATE, "database-query",
                                "SnapAgent built-in version", "SnapAgent"))
        };

        MockResolver resolver = new MockResolver(resources);
        ClasspathSkillScanner scanner = new ClasspathSkillScanner(new SkillLoader(), resolver);

        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getName()).isEqualTo("database-query");
        assertThat(skills.get(0).getDescription()).contains("SnapAgent built-in");
    }

    @Test
    void shouldLoadHostOnlySkillsWhenNoConflict() throws Exception {
        // SnapAgent has "health-check", host has "custom-skill" (no conflict)
        Resource[] resources = new Resource[]{
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/health-check.md",
                        String.format(SKILL_TEMPLATE, "health-check",
                                "SnapAgent built-in", "SnapAgent")),
                mockResource("file:/project/target/classes/docs/skills/custom-skill.md",
                        String.format(SKILL_TEMPLATE, "custom-skill",
                                "Host custom skill", "Host"))
        };

        MockResolver resolver = new MockResolver(resources);
        ClasspathSkillScanner scanner = new ClasspathSkillScanner(new SkillLoader(), resolver);

        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).hasSize(2);
        assertThat(skills).extracting(SkillMeta::getName)
                .containsExactlyInAnyOrder("health-check", "custom-skill");
    }

    @Test
    void shouldLoadAllSnapAgentJarSkills() throws Exception {
        Resource[] resources = new Resource[]{
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/health-check.md",
                        String.format(SKILL_TEMPLATE, "health-check", "Health", "SnapAgent")),
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/database-query.md",
                        String.format(SKILL_TEMPLATE, "database-query", "DB", "SnapAgent")),
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/redis-query.md",
                        String.format(SKILL_TEMPLATE, "redis-query", "Redis", "SnapAgent")),
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/log-analysis.md",
                        String.format(SKILL_TEMPLATE, "log-analysis", "Logs", "SnapAgent"))
        };

        MockResolver resolver = new MockResolver(resources);
        ClasspathSkillScanner scanner = new ClasspathSkillScanner(new SkillLoader(), resolver);

        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).hasSize(4);
        assertThat(skills).extracting(SkillMeta::getName)
                .containsExactlyInAnyOrder("health-check", "database-query", "redis-query", "log-analysis");
    }

    @Test
    void shouldLoadAllHostSkillsWhenNoSnapAgentJarPresent() throws Exception {
        Resource[] resources = new Resource[]{
                mockResource("file:/project/target/classes/docs/skills/custom-skill.md",
                        String.format(SKILL_TEMPLATE, "custom-skill", "Custom", "Host")),
                mockResource("file:/project/target/classes/docs/skills/another-skill.md",
                        String.format(SKILL_TEMPLATE, "another-skill", "Another", "Host"))
        };

        MockResolver resolver = new MockResolver(resources);
        ClasspathSkillScanner scanner = new ClasspathSkillScanner(new SkillLoader(), resolver);

        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).hasSize(2);
        assertThat(skills).extracting(SkillMeta::getName)
                .containsExactlyInAnyOrder("custom-skill", "another-skill");
    }

    @Test
    void shouldHandleMultipleConflicts() throws Exception {
        Resource[] resources = new Resource[]{
                // SnapAgent JAR versions
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/database-query.md",
                        String.format(SKILL_TEMPLATE, "database-query", "SnapAgent DB", "SnapAgent")),
                mockResource("jar:file:/repo/snap-agent-spring-boot-2x-starter-0.2.jar!/docs/skills/log-analysis.md",
                        String.format(SKILL_TEMPLATE, "log-analysis", "SnapAgent Log", "SnapAgent")),
                // Host versions (should be skipped)
                mockResource("file:/project/target/classes/docs/skills/database-query.md",
                        String.format(SKILL_TEMPLATE, "database-query", "Host DB", "Host")),
                mockResource("file:/project/target/classes/docs/skills/log-analysis.md",
                        String.format(SKILL_TEMPLATE, "log-analysis", "Host Log", "Host")),
                // Host-only skill (should be loaded)
                mockResource("file:/project/target/classes/docs/skills/my-skill.md",
                        String.format(SKILL_TEMPLATE, "my-skill", "My Skill", "Host"))
        };

        MockResolver resolver = new MockResolver(resources);
        ClasspathSkillScanner scanner = new ClasspathSkillScanner(new SkillLoader(), resolver);

        List<SkillMeta> skills = scanner.scan("classpath:/docs/skills/");

        assertThat(skills).hasSize(3);
        assertThat(skills).extracting(SkillMeta::getName)
                .containsExactlyInAnyOrder("database-query", "log-analysis", "my-skill");
        // Verify SnapAgent versions won
        SkillMeta dbSkill = skills.stream()
                .filter(s -> "database-query".equals(s.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("database-query not found"));
        assertThat(dbSkill.getDescription()).contains("SnapAgent DB");
        SkillMeta logSkill = skills.stream()
                .filter(s -> "log-analysis".equals(s.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("log-analysis not found"));
        assertThat(logSkill.getDescription()).contains("SnapAgent Log");
    }

    // ---- helpers ----

    private static Resource mockResource(String url, String content) {
        return new MockResource(url, content);
    }

    private static class MockResolver implements ResourcePatternResolver {
        private final Resource[] resources;

        MockResolver(Resource[] resources) {
            this.resources = resources;
        }

        @Override
        public Resource[] getResources(String pattern) {
            return resources;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public Resource getResource(String location) {
            return resources.length > 0 ? resources[0] : null;
        }
    }

    private static class MockResource extends AbstractResource {
        private final String url;
        private final String content;

        MockResource(String url, String content) {
            this.url = url;
            this.content = content;
        }

        @Override
        public String getFilename() {
            int lastSlash = url.lastIndexOf('/');
            return lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
        }

        @Override
        public java.net.URL getURL() {
            try {
                return new java.net.URL(url);
            } catch (java.net.MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String getDescription() {
            return "MockResource[" + url + "]";
        }
    }
}
