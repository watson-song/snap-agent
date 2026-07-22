package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginMetadataScannerTest {

    private final PluginMetadataScanner scanner = new PluginMetadataScanner();

    @TempDir
    Path tempDir;

    @Test
    void shouldFindPluginByAnnotation() throws Exception {
        // Build a JAR containing the compiled AnnotatedTestPlugin class
        Path testClassesDir = Paths.get("target/test-classes");
        Path jarPath = tempDir.resolve("test-plugin.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add the .class file for AnnotatedTestPlugin
            String classResourcePath = "cn/watsontech/snapagent/boot2x/tool/AnnotatedTestPlugin.class";
            File classFile = testClassesDir.resolve(classResourcePath).toFile();
            if (!classFile.exists()) {
                throw new IllegalStateException("Test class not found at " + classFile.getAbsolutePath());
            }
            jos.putNextEntry(new JarEntry(classResourcePath));
            Files.copy(classFile.toPath(), jos);
            jos.closeEntry();
        }

        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                PluginMetadataScannerTest.class.getClassLoader()
        );

        PluginMetadata metadata = scanner.scan(classLoader);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getPluginId()).isEqualTo("test-annotated-plugin");
        assertThat(metadata.getToolType()).isEqualTo("log_read");
        assertThat(metadata.getDisplayName()).isEqualTo("Test Plugin");
        assertThat(metadata.getDescription()).isEqualTo("Plugin for scanner tests");
        assertThat(metadata.getVersion()).isEqualTo("3.0.0");
        assertThat(metadata.isDefault()).isTrue();
        assertThat(metadata.getProviderClassName()).isEqualTo("cn.watsontech.snapagent.boot2x.tool.AnnotatedTestPlugin");
    }

    @Test
    void shouldFallbackToYamlWhenNoAnnotation() throws Exception {
        // Build a JAR with only a plugin-info.yml resource (no .class files with annotation)
        Path jarPath = tempDir.resolve("yaml-only-plugin.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add plugin-info.yml from test resources
            String yamlPath = "META-INF/snap-agent/plugin-info.yml";
            jos.putNextEntry(new JarEntry(yamlPath));
            try (InputStream yamlStream = getClass().getResourceAsStream("/plugin-info-scanner-fallback.yml")) {
                if (yamlStream == null) {
                    throw new IllegalStateException("Test YAML resource not found");
                }
                byte[] buffer = new byte[1024];
                int len;
                while ((len = yamlStream.read(buffer)) != -1) {
                    jos.write(buffer, 0, len);
                }
            }
            jos.closeEntry();
        }

        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                PluginMetadataScannerTest.class.getClassLoader()
        );

        PluginMetadata metadata = scanner.scan(classLoader);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getPluginId()).isEqualTo("yaml-only-plugin");
        assertThat(metadata.getToolType()).isEqualTo("trace_search");
        assertThat(metadata.getProviderClassName()).isEqualTo("com.example.TraceToolProvider");
        assertThat(metadata.getVersion()).isEqualTo("0.9.1");
        assertThat(metadata.isDefault()).isFalse();
    }

    @Test
    void shouldThrowWhenNoMetadataFound() throws Exception {
        // Build an empty JAR
        Path jarPath = tempDir.resolve("empty-plugin.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Empty jar — no entries
        }

        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                PluginMetadataScannerTest.class.getClassLoader()
        );

        assertThatThrownBy(() -> scanner.scan(classLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no plugin metadata found");
    }
}
