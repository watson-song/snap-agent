package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.List;

/**
 * 工具插件元数据 SPI。宿主可实现此接口声明插件信息。
 *
 * <p>This SPI provides a metadata layer only — it does not affect tool
 * discovery. Since v0.1, the {@link ToolProvider} interface combined with
 * {@code @Component} is the automatic discovery mechanism: any
 * {@link ToolProvider} bean on the classpath is collected by the
 * {@link ToolDispatcher} auto-configuration. {@code ToolPlugin} lets hosts
 * additionally advertise plugin name/version/description and the list of
 * tool names a plugin contributes, which is exposed via the
 * {@code GET /tools/plugins} endpoint for operational visibility.</p>
 *
 * <p>Implementations are encouraged to also implement {@link ToolProvider}
 * (or register a separate {@link ToolProvider} bean) so their tools are
 * discoverable. The defaults below allow minimal implementations to only
 * declare {@link #name()} and {@link #version()}.</p>
 */
public interface ToolPlugin {

    /**
     * 插件名 (唯一标识)。
     *
     * @return the plugin name
     */
    String name();

    /**
     * 版本。
     *
     * @return the plugin version
     */
    String version();

    /**
     * 描述。默认返回空字符串。
     *
     * @return the plugin description, empty by default
     */
    default String description() {
        return "";
    }

    /**
     * 提供的工具名列表。默认返回空列表。
     *
     * @return unmodifiable list of tool names this plugin contributes
     *         (empty by default)
     */
    default List<String> toolNames() {
        return Collections.emptyList();
    }
}
