package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginUploaderTest {

    @TempDir
    Path tempDir;

    private PluginRegistry registry;
    private PluginMetadataScanner scanner;
    private PluginConfigExtractor configExtractor;
    private MockEnvironment env;
    private PluginUploader uploader;

    @BeforeEach
    void setUp() {
        registry = mock(PluginRegistry.class);
        scanner = mock(PluginMetadataScanner.class);
        configExtractor = mock(PluginConfigExtractor.class);
        env = new MockEnvironment();
        uploader = new PluginUploader(tempDir, registry, scanner, configExtractor, env);
    }

    @Test
    void shouldRejectPluginIdWithPathTraversal() throws Exception {
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, Paths.get("target/test-classes"));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "evil.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "../../etc/evil", "log_read", "Evil", "Evil plugin",
                "1.0.0", false,
                "cn.watsontech.snapagent.boot2x.tool.SimpleTestToolProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);

        assertThatThrownBy(() -> uploader.upload(jarFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid pluginId")
                .hasMessageContaining("../../etc/evil");

        verify(registry, never()).register(any());
    }

    @Test
    void shouldRejectPluginIdWithSpecialCharacters() throws Exception {
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, Paths.get("target/test-classes"));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "bad.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "evil/plugin", "log_read", "Bad", "Bad", "1.0.0", false,
                "cn.watsontech.snapagent.boot2x.tool.SimpleTestToolProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);

        assertThatThrownBy(() -> uploader.upload(jarFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid pluginId");

        verify(registry, never()).register(any());
    }

    @Test
    void shouldCleanupUrlClassLoaderAndJarFileOnUnregister() throws Exception {
        // First upload a plugin to get a real descriptor with URLClassLoader and jarPath
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, Paths.get("target/test-classes"));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "cleanup.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "cleanup-test", "log_read", "Cleanup", "Cleanup", "1.0.0", false,
                "cn.watsontech.snapagent.boot2x.tool.SimpleTestToolProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);
        when(registry.getPlugin("cleanup-test")).thenReturn(null);

        PluginDescriptor descriptor = uploader.upload(jarFile);
        assertThat(descriptor.getJarPath()).exists();
        assertThat(descriptor.getClassLoader()).isNotNull();

        // Act: cleanup
        uploader.cleanupPlugin(descriptor);

        // Assert: JAR file and parent directory deleted
        assertThat(descriptor.getJarPath()).doesNotExist();
        assertThat(descriptor.getJarPath().getParent()).doesNotExist();
    }

    @Test
    void shouldCleanupPluginWithNullDescriptorGracefully() {
        uploader.cleanupPlugin(null);
        // No exception thrown
    }

    @Test
    void shouldCleanupPluginWithNullClassLoaderAndJarPath() {
        PluginDescriptor desc = new PluginDescriptor(
                "no-jar", "log_read", "NoJar", "NoJar", "1.0.0",
                false, true, false, mock(ToolProvider.class), null, null, null);
        uploader.cleanupPlugin(desc);
        // No exception thrown
    }

    @Test
    void shouldUploadJarAndRegisterPlugin() throws Exception {
        // Arrange: a JAR containing SimpleTestToolProvider
        Path testClassesDir = Paths.get("target/test-classes");
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, testClassesDir);

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "my-plugin.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "upload-test-plugin", "log_read", "Upload Test", "Test plugin",
                "1.0.0", false,
                "cn.watsontech.snapagent.boot2x.tool.SimpleTestToolProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);
        when(registry.getPlugin("upload-test-plugin")).thenReturn(null);
        when(configExtractor.extract(eq(env), eq("upload-test-plugin")))
                .thenReturn(java.util.Collections.singletonMap("base-url", "http://test:8080"));

        // Act
        PluginDescriptor descriptor = uploader.upload(jarFile);

        // Assert
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.getPluginId()).isEqualTo("upload-test-plugin");
        assertThat(descriptor.getToolType()).isEqualTo("log_read");
        assertThat(descriptor.getDisplayName()).isEqualTo("Upload Test");
        assertThat(descriptor.getDescription()).isEqualTo("Test plugin");
        assertThat(descriptor.getVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.isEnabled()).isTrue();
        assertThat(descriptor.isSystem()).isFalse();
        assertThat(descriptor.getProvider()).isNotNull();
        assertThat(descriptor.getProvider().name()).isEqualTo("simple-test-tool");
        assertThat(descriptor.getPluginContext().getConfiguration())
                .containsEntry("base-url", "http://test:8080");

        ArgumentCaptor<PluginDescriptor> captor = ArgumentCaptor.forClass(PluginDescriptor.class);
        verify(registry).register(captor.capture());
        PluginDescriptor registered = captor.getValue();
        assertThat(registered.getPluginId()).isEqualTo("upload-test-plugin");
        assertThat(registered.getJarPath()).exists();
        assertThat(registered.getJarPath().toString()).endsWith("plugin.jar");

        Path expectedJarDir = tempDir.resolve("upload-test-plugin");
        assertThat(registered.getJarPath().getParent()).isEqualTo(expectedJarDir);
    }

    @Test
    void shouldThrowWhenPluginIdAlreadyRegistered() throws Exception {
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, Paths.get("target/test-classes"));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "dup.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "dup-plugin", "log_read", "Dup", "Dup", "1.0.0", false,
                "cn.watsontech.snapagent.boot2x.tool.SimpleTestToolProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);
        // PluginDescriptor is final — use a real instance instead of a mock
        PluginDescriptor existing = new PluginDescriptor(
                "dup-plugin", "log_read", "Dup", "Dup", "1.0.0",
                true, true, false, mock(ToolProvider.class), null, null, null);
        when(registry.getPlugin("dup-plugin")).thenReturn(existing);

        assertThatThrownBy(() -> uploader.upload(jarFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugin already registered")
                .hasMessageContaining("dup-plugin");

        verify(registry, never()).register(any());
    }

    @Test
    void shouldThrowWhenProviderClassNotFound() throws Exception {
        Path jarPath = tempDir.resolve("input.jar");
        buildEmptyJar(jarPath);

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "badclass.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "badclass-plugin", "log_read", "Bad", "Bad", "1.0.0", false,
                "com.nonexistent.NoSuchProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);
        when(registry.getPlugin("badclass-plugin")).thenReturn(null);

        assertThatThrownBy(() -> uploader.upload(jarFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed to load provider class")
                .hasMessageContaining("com.nonexistent.NoSuchProvider");
    }

    @Test
    void shouldThrowWhenProviderInstantiationFails() throws Exception {
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, Paths.get("target/test-classes"));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "noinit.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        // java.lang.Runnable is an interface — cannot be instantiated
        PluginMetadata metadata = new PluginMetadata(
                "noinit-plugin", "log_read", "NoInit", "NoInit", "1.0.0", false,
                "java.lang.Runnable"
        );
        when(scanner.scan(any())).thenReturn(metadata);
        when(registry.getPlugin("noinit-plugin")).thenReturn(null);

        assertThatThrownBy(() -> uploader.upload(jarFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed to instantiate provider");
    }

    @Test
    void shouldSaveJarToCorrectPath() throws Exception {
        Path jarPath = tempDir.resolve("input.jar");
        buildTestJar(jarPath, Paths.get("target/test-classes"));
        long originalSize = Files.size(jarPath);

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "save.jar", "application/java-archive",
                Files.readAllBytes(jarPath)
        );

        PluginMetadata metadata = new PluginMetadata(
                "save-test-plugin", "log_read", "Save", "Save", "1.0.0", false,
                "cn.watsontech.snapagent.boot2x.tool.SimpleTestToolProvider"
        );
        when(scanner.scan(any())).thenReturn(metadata);
        when(registry.getPlugin("save-test-plugin")).thenReturn(null);

        uploader.upload(jarFile);

        Path savedJar = tempDir.resolve("save-test-plugin").resolve("plugin.jar");
        assertThat(savedJar).exists();
        assertThat(Files.size(savedJar)).isEqualTo(originalSize);
    }

    // --- Helper methods ---

    private void buildTestJar(Path jarPath, Path testClassesDir) throws IOException {
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jarPath))) {
            String classPath = "cn/watsontech/snapagent/boot2x/tool/SimpleTestToolProvider.class";
            java.io.File classFile = testClassesDir.resolve(classPath).toFile();
            if (!classFile.exists()) {
                throw new IllegalStateException("Test class not found: " + classFile.getAbsolutePath());
            }
            jos.putNextEntry(new java.util.jar.JarEntry(classPath));
            Files.copy(classFile.toPath(), jos);
            jos.closeEntry();
        }
    }

    private void buildEmptyJar(Path jarPath) throws IOException {
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jarPath))) {
            // Empty JAR — no entries
        }
    }
}
