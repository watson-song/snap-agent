package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link PluginRegistry} implementation backed by a
 * {@link ConcurrentHashMap}.
 *
 * <p>Thread-safe. System plugins cannot be unregistered.</p>
 */
public class InMemoryPluginRegistry implements PluginRegistry {

    private final ConcurrentMap<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> defaultPlugins = new ConcurrentHashMap<>();

    @Override
    public void register(PluginDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor cannot be null");
        }
        PluginDescriptor existing = plugins.putIfAbsent(descriptor.getPluginId(), descriptor);
        if (existing != null) {
            throw new IllegalStateException("plugin already registered: " + descriptor.getPluginId());
        }
        if (descriptor.isDefault() && descriptor.getToolType() != null) {
            String previousId = defaultPlugins.get(descriptor.getToolType());
            if (previousId != null && !previousId.equals(descriptor.getPluginId())) {
                PluginDescriptor previous = plugins.get(previousId);
                if (previous != null) {
                    previous.setDefault(false);
                }
            }
            defaultPlugins.put(descriptor.getToolType(), descriptor.getPluginId());
        }
    }

    @Override
    public PluginDescriptor getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    @Override
    public List<PluginDescriptor> listPlugins() {
        return Collections.unmodifiableList(new ArrayList<>(plugins.values()));
    }

    @Override
    public void unregister(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc == null) {
            return;
        }
        if (desc.isSystem()) {
            throw new IllegalStateException("cannot unregister system plugin: " + pluginId);
        }
        plugins.remove(pluginId);
    }

    @Override
    public Boolean toggleEnabled(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc == null) {
            return null;
        }
        boolean newState = !desc.isEnabled();
        desc.setEnabled(newState);
        return newState;
    }

    @Override
    public void enable(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc != null) {
            desc.setEnabled(true);
        }
    }

    @Override
    public void disable(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc != null) {
            desc.setEnabled(false);
        }
    }

    @Override
    public void setDefault(String toolType, String pluginId) {
        String previousId = defaultPlugins.get(toolType);
        if (previousId != null && !previousId.equals(pluginId)) {
            PluginDescriptor previous = plugins.get(previousId);
            if (previous != null) {
                previous.setDefault(false);
            }
        }
        defaultPlugins.put(toolType, pluginId);
        PluginDescriptor newDefault = plugins.get(pluginId);
        if (newDefault != null) {
            newDefault.setDefault(true);
        }
    }

    @Override
    public PluginDescriptor getDefault(String toolType) {
        String pluginId = defaultPlugins.get(toolType);
        if (pluginId == null) {
            return null;
        }
        return plugins.get(pluginId);
    }
}
