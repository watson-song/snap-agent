package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolPluginAnnotation;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans a {@link URLClassLoader} for plugin metadata.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Scan all JAR URLs for classes annotated with {@link ToolPluginAnnotation}.</li>
 *   <li>If no annotation found, attempt to read {@code META-INF/snap-agent/plugin-info.yml}.</li>
 *   <li>If neither succeeds, throw {@link IllegalStateException}.</li>
 * </ol>
 */
public class PluginMetadataScanner {

    private final PluginInfoYmlParser ymlParser = new PluginInfoYmlParser();

    /**
     * Scans the given class loader's JAR URLs for plugin metadata.
     *
     * @param classLoader a URLClassLoader whose getURLs() returns JAR paths
     * @return the first PluginMetadata found
     * @throws IllegalStateException if no metadata is found
     */
    public PluginMetadata scan(URLClassLoader classLoader) {
        // Phase 1: scan for @ToolPluginAnnotation
        PluginMetadata annotationMetadata = scanForAnnotation(classLoader);
        if (annotationMetadata != null) {
            return annotationMetadata;
        }

        // Phase 2: fallback to plugin-info.yml
        PluginMetadata ymlMetadata = scanForYaml(classLoader);
        if (ymlMetadata != null) {
            return ymlMetadata;
        }

        // Phase 3: nothing found
        throw new IllegalStateException("no plugin metadata found in JAR (neither @ToolPluginAnnotation nor plugin-info.yml)");
    }

    private PluginMetadata scanForAnnotation(URLClassLoader classLoader) {
        for (URL url : classLoader.getURLs()) {
            if (!url.getFile().endsWith(".jar")) {
                continue;
            }
            PluginMetadata metadata = scanJarForAnnotation(url, classLoader);
            if (metadata != null) {
                return metadata;
            }
        }
        return null;
    }

    private PluginMetadata scanJarForAnnotation(URL jarUrl, URLClassLoader classLoader) {
        try (JarFile jarFile = new JarFile(Paths.get(jarUrl.toURI()).toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }

                String className = name.replace('/', '.').substring(0, name.length() - ".class".length());
                Class<?> clazz;
                try {
                    clazz = classLoader.loadClass(className);
                } catch (Throwable t) {
                    // Class could not be loaded (e.g., missing dependency); skip
                    continue;
                }

                if (clazz.isAnnotationPresent(ToolPluginAnnotation.class)) {
                    ToolPluginAnnotation ann = clazz.getAnnotation(ToolPluginAnnotation.class);
                    return new PluginMetadata(
                            ann.id(),
                            ann.toolType(),
                            ann.displayName(),
                            ann.description(),
                            ann.version(),
                            ann.isDefault(),
                            className
                    );
                }
            }
        } catch (IOException | java.net.URISyntaxException e) {
            // JAR could not be opened; skip
        }
        return null;
    }

    private PluginMetadata scanForYaml(URLClassLoader classLoader) {
        try {
            // Check all JAR URLs for the YAML resource
            for (URL url : classLoader.getURLs()) {
                if (!url.getFile().endsWith(".jar")) {
                    continue;
                }
                try (JarFile jarFile = new JarFile(Paths.get(url.toURI()).toFile())) {
                    JarEntry ymlEntry = jarFile.getJarEntry("META-INF/snap-agent/plugin-info.yml");
                    if (ymlEntry != null) {
                        try (InputStream is = jarFile.getInputStream(ymlEntry)) {
                            PluginInfoYml info = ymlParser.parse(is);
                            if (info != null) {
                                return new PluginMetadata(
                                        info.getId(),
                                        info.getToolType(),
                                        info.getDisplayName(),
                                        info.getDescription(),
                                        info.getVersion() != null ? info.getVersion() : "1.0.0",
                                        info.isDefault(),
                                        info.getProviderClass()
                                );
                            }
                        }
                    }
                }
            }

            // Also check classloader resources (for non-JAR classpaths)
            Enumeration<URL> resources = classLoader.getResources("META-INF/snap-agent/plugin-info.yml");
            while (resources.hasMoreElements()) {
                URL resourceUrl = resources.nextElement();
                try (InputStream is = resourceUrl.openStream()) {
                    PluginInfoYml info = ymlParser.parse(is);
                    if (info != null) {
                        return new PluginMetadata(
                                info.getId(),
                                info.getToolType(),
                                info.getDisplayName(),
                                info.getDescription(),
                                info.getVersion() != null ? info.getVersion() : "1.0.0",
                                info.isDefault(),
                                info.getProviderClass()
                        );
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException e) {
            // Resource access failed; fall through to null
        }
        return null;
    }
}
