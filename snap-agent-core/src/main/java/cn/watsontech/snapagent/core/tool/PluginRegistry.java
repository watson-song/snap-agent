package cn.watsontech.snapagent.core.tool;

import java.util.List;

/**
 * SPI for plugin registration, lookup, and lifecycle management.
 *
 * <p>Host applications implement this to store plugin metadata in memory,
 * a database, or an external system. The starter module provides a default
 * {@code InMemoryPluginRegistry}.</p>
 *
 * <p>System plugins (created by the host at startup, not uploaded via JAR)
 * have {@code system=true} and cannot be unregistered.</p>
 */
public interface PluginRegistry {

    /**
     * Registers a new plugin descriptor.
     *
     * @param descriptor the plugin to register
     * @throws IllegalStateException if a plugin with the same ID is already registered
     */
    void register(PluginDescriptor descriptor);

    /**
     * Returns the descriptor for the given plugin ID, or null if not found.
     */
    PluginDescriptor getPlugin(String pluginId);

    /**
     * Returns all registered plugins (unmodifiable view).
     */
    List<PluginDescriptor> listPlugins();

    /**
     * Unregisters a plugin. System plugins cannot be unregistered.
     *
     * @param pluginId the plugin ID to unregister
     * @throws IllegalStateException if the plugin is a system plugin
     */
    void unregister(String pluginId);

    /**
     * Toggles the enabled flag on a plugin.
     *
     * @param pluginId the plugin ID
     * @return the new enabled state, or null if the plugin doesn't exist
     */
    Boolean toggleEnabled(String pluginId);
}
