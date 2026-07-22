package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles uploading and registration of plugin JARs.
 * <p>
 * Upload flow:
 * <ol>
 *   <li>Save the uploaded MultipartFile to a temporary file.</li>
 *   <li>Create a URLClassLoader from the temp JAR to scan metadata.</li>
 *   <li>Use PluginMetadataScanner to discover plugin ID and provider class.</li>
 *   <li>Reject if plugin ID is already registered.</li>
 *   <li>Move JAR to final path: {@code <uploadDir>/<pluginId>/plugin.jar}.</li>
 *   <li>Create a new URLClassLoader from the final JAR path.</li>
 *   <li>Load and instantiate the ToolProvider class reflectively.</li>
 *   <li>Extract plugin-specific config via PluginConfigExtractor.</li>
 *   <li>Build SimplePluginContext and PluginDescriptor, then register.</li>
 * </ol>
 */
public class PluginUploader {

    /** Allowed pattern for pluginId — alphanumeric, hyphens, underscores only (prevents path traversal). */
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final Path uploadDirPath;
    private final PluginRegistry registry;
    private final PluginMetadataScanner scanner;
    private final PluginConfigExtractor configExtractor;
    private final Environment environment;

    public PluginUploader(Path uploadDirPath,
                          PluginRegistry registry,
                          PluginMetadataScanner scanner,
                          PluginConfigExtractor configExtractor,
                          Environment environment) {
        this.uploadDirPath = uploadDirPath;
        this.registry = registry;
        this.scanner = scanner;
        this.configExtractor = configExtractor;
        this.environment = environment;
    }

    public PluginDescriptor upload(MultipartFile jarFile) {
        // Step 1: Save to temp file
        Path tempJar;
        try {
            tempJar = Files.createTempFile("plugin-upload-", ".jar");
            jarFile.transferTo(tempJar.toFile());
        } catch (IOException e) {
            throw new RuntimeException("failed to save uploaded JAR to temp file", e);
        }

        // Step 2: Create URLClassLoader from temp JAR for scanning
        URLClassLoader scanClassLoader;
        try {
            scanClassLoader = new URLClassLoader(
                    new URL[]{tempJar.toUri().toURL()},
                    getClass().getClassLoader()
            );
        } catch (IOException e) {
            throw new RuntimeException("failed to create URLClassLoader for scanning", e);
        }

        // Step 3: Scan for metadata
        PluginMetadata metadata = scanner.scan(scanClassLoader);

        // Step 3b: Validate pluginId format (prevent path traversal)
        if (metadata.getPluginId() == null
                || !PLUGIN_ID_PATTERN.matcher(metadata.getPluginId()).matches()) {
            closeQuietly(scanClassLoader);
            deleteQuietly(tempJar);
            throw new IllegalArgumentException(
                    "invalid pluginId: " + metadata.getPluginId()
                            + " (only alphanumeric, hyphens, underscores allowed)");
        }

        // Step 4: Check for duplicate registration
        if (registry.getPlugin(metadata.getPluginId()) != null) {
            closeQuietly(scanClassLoader);
            deleteQuietly(tempJar);
            throw new IllegalStateException("plugin already registered: " + metadata.getPluginId());
        }

        // Step 5: Move JAR to final path
        Path pluginDir = uploadDirPath.resolve(metadata.getPluginId());
        Path finalJarPath = pluginDir.resolve("plugin.jar");
        try {
            Files.createDirectories(pluginDir);
            Files.move(tempJar, finalJarPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            closeQuietly(scanClassLoader);
            deleteQuietly(tempJar);
            throw new RuntimeException("failed to move JAR to final path: " + finalJarPath, e);
        }

        // Close scan ClassLoader before creating the production one
        closeQuietly(scanClassLoader);

        // Step 6: Create production URLClassLoader from final JAR path
        URLClassLoader pluginClassLoader;
        try {
            pluginClassLoader = new URLClassLoader(
                    new URL[]{finalJarPath.toUri().toURL()},
                    getClass().getClassLoader()
            );
        } catch (IOException e) {
            throw new RuntimeException("failed to create URLClassLoader for plugin JAR: " + finalJarPath, e);
        }

        // Step 7: Load provider class
        Class<?> providerClass;
        try {
            providerClass = pluginClassLoader.loadClass(metadata.getProviderClassName());
        } catch (ClassNotFoundException e) {
            closeQuietly(pluginClassLoader);
            throw new RuntimeException("failed to load provider class: " + metadata.getProviderClassName(), e);
        }

        // Step 8: Instantiate provider (Java 8 reflective style)
        ToolProvider provider;
        try {
            Object instance = providerClass.newInstance();
            provider = (ToolProvider) instance;
        } catch (InstantiationException | IllegalAccessException | ClassCastException e) {
            closeQuietly(pluginClassLoader);
            throw new RuntimeException("failed to instantiate provider: " + metadata.getProviderClassName(), e);
        }

        // Step 9: Extract config from environment
        Map<String, Object> config = configExtractor.extract(environment, metadata.getPluginId());

        // Step 10: Build SimplePluginContext
        SimplePluginContext pluginContext = new SimplePluginContext(config);

        // Step 11: Build PluginDescriptor
        PluginDescriptor descriptor = new PluginDescriptor(
                metadata.getPluginId(),
                metadata.getToolType(),
                metadata.getDisplayName() != null ? metadata.getDisplayName() : "",
                metadata.getDescription() != null ? metadata.getDescription() : "",
                metadata.getVersion() != null ? metadata.getVersion() : "1.0.0",
                metadata.isDefault(),   // isDefault
                true,                   // enabled
                false,                  // system
                provider,
                pluginClassLoader,
                finalJarPath,
                pluginContext
        );

        // Step 12: Register
        registry.register(descriptor);

        return descriptor;
    }

    /**
     * Cleans up resources associated with an unregistered plugin:
     * closes the URLClassLoader and deletes the JAR file from disk.
     *
     * @param descriptor the descriptor of the plugin being unregistered
     */
    public void cleanupPlugin(PluginDescriptor descriptor) {
        if (descriptor == null) return;

        // Close the URLClassLoader to release the JAR file handle
        ClassLoader cl = descriptor.getClassLoader();
        if (cl instanceof URLClassLoader) {
            try {
                ((URLClassLoader) cl).close();
            } catch (IOException e) {
                // ignore — best-effort cleanup
            }
        }

        // Delete the JAR file and its parent directory from disk
        Path jarPath = descriptor.getJarPath();
        if (jarPath != null) {
            deleteQuietly(jarPath);
            Path parentDir = jarPath.getParent();
            if (parentDir != null) {
                deleteQuietly(parentDir);
            }
        }
    }

    private void closeQuietly(URLClassLoader classLoader) {
        try {
            if (classLoader != null) {
                classLoader.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    private void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
