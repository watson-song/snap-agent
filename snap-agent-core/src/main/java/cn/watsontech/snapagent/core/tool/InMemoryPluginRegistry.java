package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    @Override
    public void register(PluginDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor cannot be null");
        }
        PluginDescriptor existing = plugins.putIfAbsent(descriptor.getPluginId(), descriptor);
        if (existing != null) {
            throw new IllegalStateException("plugin already registered: " + descriptor.getPluginId());
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
}
