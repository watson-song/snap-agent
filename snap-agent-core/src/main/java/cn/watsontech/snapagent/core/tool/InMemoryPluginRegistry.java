package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link PluginRegistry} backed by {@link ConcurrentHashMap}.
 *
 * <p>Thread-safe. Plugins are keyed by {@code pluginId} in a concurrent map,
 * and a second map tracks which {@code pluginId} is the default for each
 * {@code toolType}. The default-promotion logic ensures that when the default
 * plugin of a type is unregistered, the next available plugin of the same type
 * is auto-promoted; if none remains, the type has no default.</p>
 *
 * <p>This implementation directly mutates the {@code isDefault} and
 * {@code enabled} volatile flags on {@link PluginDescriptor} instances. Because
 * {@code setDefault} / {@code setEnabled} are package-private, the registry
 * must reside in the same package as the descriptor.</p>
 *
 * <p>System plugins (see {@link PluginDescriptor#isSystem()}) are protected
 * from unregistration. User-supplied plugins may be unregistered at any time.</p>
 */
public class InMemoryPluginRegistry implements PluginRegistry {

    private final ConcurrentHashMap<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> defaultsByType = new ConcurrentHashMap<>();

    @Override
    public synchronized void register(PluginDescriptor descriptor) {
        if (descriptor == null || descriptor.getPluginId() == null) {
            throw new IllegalArgumentException("descriptor and pluginId must not be null");
        }
        PluginDescriptor existing = plugins.putIfAbsent(descriptor.getPluginId(), descriptor);
        if (existing != null) {
            throw new IllegalArgumentException("plugin already registered: " + descriptor.getPluginId());
        }
        if (descriptor.isDefault()) {
            // Clear the old default for this toolType to avoid inconsistent state
            String oldDefaultId = defaultsByType.get(descriptor.getToolType());
            if (oldDefaultId != null) {
                PluginDescriptor oldDefault = plugins.get(oldDefaultId);
                if (oldDefault != null) {
                    oldDefault.setDefault(false);
                }
            }
            descriptor.setDefault(true);
            defaultsByType.put(descriptor.getToolType(), descriptor.getPluginId());
        } else if (defaultsByType.putIfAbsent(descriptor.getToolType(), descriptor.getPluginId()) == null) {
            // No existing default — this plugin becomes the default
            descriptor.setDefault(true);
        } else {
            descriptor.setDefault(false);
        }
    }

    @Override
    public synchronized void unregister(String pluginId) {
        if (pluginId == null) return;
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc == null) return;
        if (desc.isSystem()) {
            throw new UnsupportedOperationException("cannot unregister system plugin: " + pluginId);
        }
        plugins.remove(pluginId);
        String toolType = desc.getToolType();
        if (defaultsByType.remove(toolType, pluginId)) {
            for (PluginDescriptor remaining : plugins.values()) {
                if (remaining.getToolType().equals(toolType)) {
                    remaining.setDefault(true);
                    defaultsByType.put(toolType, remaining.getPluginId());
                    break;
                }
            }
        }
    }

    @Override
    public void enable(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc != null) desc.setEnabled(true);
    }

    @Override
    public void disable(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc != null) desc.setEnabled(false);
    }

    @Override
    public synchronized void setDefault(String toolType, String pluginId) {
        PluginDescriptor target = plugins.get(pluginId);
        if (target == null) return;
        for (PluginDescriptor p : plugins.values()) {
            if (p.getToolType().equals(toolType)) {
                p.setDefault(false);
            }
        }
        target.setDefault(true);
        defaultsByType.put(toolType, pluginId);
    }

    @Override
    public PluginDescriptor getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    @Override
    public PluginDescriptor getDefault(String toolType) {
        String pluginId = defaultsByType.get(toolType);
        if (pluginId == null) return null;
        return plugins.get(pluginId);
    }

    @Override
    public List<PluginDescriptor> getPluginsForType(String toolType) {
        List<PluginDescriptor> result = new ArrayList<>();
        for (PluginDescriptor p : plugins.values()) {
            if (p.getToolType().equals(toolType)) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public List<PluginDescriptor> list() {
        return new ArrayList<>(plugins.values());
    }
}
