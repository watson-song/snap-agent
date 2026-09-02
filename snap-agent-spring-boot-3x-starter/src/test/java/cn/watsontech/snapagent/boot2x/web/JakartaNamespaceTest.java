package cn.watsontech.snapagent.boot2x.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Jakarta EE namespace transformation was correctly applied.
 *
 * <p>These tests read the source files and assert the expected jakarta imports.
 * They do NOT require Spring context or a running application — they validate
 * the sync-from-2x.sh transformation at the source level.</p>
 *
 * <p>Only meaningful when built with JDK 17+ (which is the only target for
 * the 3.x starter). On JDK 8 these tests are skipped by surefire config.</p>
 */
@DisplayName("Spring Boot 3.x Jakarta namespace transformation")
class JakartaNamespaceTest {

    @Test
    @DisplayName("SnapAgentFilter uses jakarta.servlet, not javax.servlet")
    void snapAgentFilterShouldUseJakartaServlet() throws IOException {
        Path filterPath = resolveSourcePath(
                "cn/watsontech/snapagent/boot2x/web/SnapAgentFilter.java");
        if (filterPath == null || !Files.exists(filterPath)) {
            return; // Source not available (e.g., running from jar)
        }
        String content = new String(Files.readAllBytes(filterPath));
        assertThat(content).contains("import jakarta.servlet");
        assertThat(content).doesNotContain("import javax.servlet");
    }

    @Test
    @DisplayName("WebAutoConfiguration uses jakarta.servlet.Filter")
    void webAutoConfigShouldUseJakartaServlet() throws IOException {
        Path configPath = resolveSourcePath(
                "cn/watsontech/snapagent/boot2x/autoconfig/WebAutoConfiguration.java");
        if (configPath == null || !Files.exists(configPath)) {
            return;
        }
        String content = new String(Files.readAllBytes(configPath));
        assertThat(content).contains("import jakarta.servlet.Filter");
        assertThat(content).doesNotContain("import javax.servlet");
    }

    @Test
    @DisplayName("PeerSseRelay uses jakarta.annotation.PreDestroy")
    void peerSseRelayShouldUseJakartaAnnotation() throws IOException {
        Path relayPath = resolveSourcePath(
                "cn/watsontech/snapagent/boot2x/routing/PeerSseRelay.java");
        if (relayPath == null || !Files.exists(relayPath)) {
            return;
        }
        String content = new String(Files.readAllBytes(relayPath));
        assertThat(content).contains("import jakarta.annotation.PreDestroy");
        assertThat(content).doesNotContain("import javax.annotation");
    }

    /**
     * Resolve a source path relative to the module's src/main/java directory.
     * Returns null if the source directory is not found (e.g., running from jar).
     */
    private Path resolveSourcePath(String relativePath) {
        // Try Maven standard layout relative to working directory
        Path mavenPath = Paths.get("src/main/java", relativePath);
        if (Files.exists(mavenPath)) {
            return mavenPath;
        }
        // Try relative to module root (when run from project root)
        Path modulePath = Paths.get(
                "snap-agent-spring-boot-3x-starter/src/main/java", relativePath);
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        return null;
    }
}
