package cn.watsontech.snapagent.core.tool;

import java.util.List;

/**
 * SPI for managing the lifecycle of registered tool plugins.
 *
 * <p>A plugin registry holds all active {@link PluginDescriptor} instances and
 * provides operations to register, unregister, enable, disable and promote
 * plugins. The registry is responsible for tracking which plugin is the
 * <em>default</em> for a given {@code toolType} (i.e. the
 * {@link ToolProvider#name()}) so that the tool dispatcher can resolve a single
 * provider when multiple plugins target the same tool.</p>
 *
 * <p>Implementations are expected to be thread-safe because plugins may be
 * registered or toggled at runtime from arbitrary threads (e.g. an external-JAR
 * hot-load thread or an admin REST call) while the agent execution loop reads
 * the registry concurrently.</p>
 *
 * <p>The default in-memory implementation is
 * {@link InMemoryPluginRegistry}; hosts may provide a custom bean to replace
 * it (e.g. a database-backed registry).</p>
 */
public interface PluginRegistry {

    /**
     * Register a new plugin descriptor.
     *
     * <p>If the descriptor's {@code toolType} does not yet have a default, the
     * descriptor is automatically promoted to default regardless of its
     * {@code isDefault} flag. If a default already exists for the
     * {@code toolType}, the descriptor's {@code isDefault} flag is cleared.</p>
     *
     * @param descriptor the plugin metadata; must not be {@code null} and its
     *                   {@code pluginId} must not be {@code null}
     * @throws IllegalArgumentException if the {@code pluginId} is already
     *                                  registered or {@code descriptor} is
     *                                  {@code null}
     */
    void register(PluginDescriptor descriptor);

    /**
     * Unregister a plugin by id.
     *
     * <p>System plugins (see {@link PluginDescriptor#isSystem()}) cannot be
     * unregistered. If the removed plugin was the default for its
     * {@code toolType}, the next available plugin of the same type is
     * auto-promoted to default; if no other plugin remains, the type has no
     * default.</p>
     *
     * @param pluginId the id of the plugin to remove; {@code null} is a no-op
     * @throws UnsupportedOperationException if the plugin is a system plugin
     */
    void unregister(String pluginId);

    /**
     * Enable a previously registered plugin.
     *
     * @param pluginId the id of the plugin to enable; no-op if unknown
     */
    void enable(String pluginId);

    /**
     * Disable a registered plugin so it is skipped by the dispatcher.
     *
     * @param pluginId the id of the plugin to disable; no-op if unknown
     */
    void disable(String pluginId);

    /**
     * Promote a plugin to be the default for its {@code toolType}, clearing
     * the default flag on all other plugins of the same type.
     *
     * @param toolType  the tool type to set the default for
     * @param pluginId  the id of the plugin to promote; no-op if unknown
     */
    void setDefault(String toolType, String pluginId);

    /**
     * Look up a plugin by id.
     *
     * @param pluginId the id to look up
     * @return the matching descriptor, or {@code null} if not registered
     */
    PluginDescriptor getPlugin(String pluginId);

    /**
     * Return the default plugin for a given {@code toolType}.
     *
     * @param toolType the tool type to resolve
     * @return the default descriptor, or {@code null} if no plugin is
     *         registered for the type
     */
    PluginDescriptor getDefault(String toolType);

    /**
     * Return all registered plugins for a given {@code toolType}, including
     * disabled ones.
     *
     * @param toolType the tool type to filter by
     * @return a new list of matching descriptors; empty if none
     */
    List<PluginDescriptor> getPluginsForType(String toolType);

    /**
     * Return a snapshot of all registered plugins.
     *
     * @return a new list of all descriptors; empty if the registry is empty
     */
    List<PluginDescriptor> list();
}
