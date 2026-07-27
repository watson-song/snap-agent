package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FixGuardTest {

    private FixGuard createGuard() {
        SnapAgentProperties.Fix.Guard cfg = new SnapAgentProperties.Fix.Guard();
        cfg.setIncludePaths(new java.util.ArrayList<>(java.util.Arrays.asList(
                "src/main/java/**", "src/main/resources/**", "src/test/java/**")));
        cfg.setExcludePaths(new java.util.ArrayList<>(java.util.Arrays.asList(
                "**/SecurityConfig.java", "**/application*.yml", "**/*Config.java")));
        cfg.setAllowedExtensions(new java.util.ArrayList<>(java.util.Arrays.asList(
                ".java", ".xml", ".yml", ".properties", ".sql", ".md")));
        cfg.setMaxFileCount(20);
        cfg.setMaxFileSize(512000);
        return new FixGuard(cfg);
    }

    @Test
    void shouldAllowJavaFileInSrcMain() {
        FixGuard guard = createGuard();
        assertThatCode(() -> guard.validate("src/main/java/com/example/Service.java",
                "public class Service {}", "CREATE", 0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectPathTraversal() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("../etc/passwd", "content", "CREATE", 0))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void shouldRejectExcludedSecurityConfig() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/SecurityConfig.java",
                "content", "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exclude");
    }

    @Test
    void shouldRejectExcludedApplicationYml() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/resources/application.yml",
                "content", "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exclude");
    }

    @Test
    void shouldRejectDisallowedExtension() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/script.sh",
                "content", "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("extension");
    }

    @Test
    void shouldRejectTooManyFiles() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/Service.java",
                "content", "CREATE", 20))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("max");
    }

    @Test
    void shouldRejectFileTooLarge() {
        FixGuard guard = createGuard();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600000; i++) big.append("x");
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/Big.java",
                big.toString(), "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("size");
    }

    @Test
    void shouldRejectEmptyContentForUpdate() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/Service.java",
                "", "UPDATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("empty");
    }
}
