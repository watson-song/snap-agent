package cn.watsontech.snapagent.boot2x.plugin;

import cn.watsontech.snapagent.core.plugin.ToolPlugin;
import cn.watsontech.snapagent.core.plugin.ToolPluginInfo;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import cn.watsontech.snapagent.core.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleToolPluginRegistryTest {

    private ToolProvider makeProvider(String name) {
        return new ToolProvider() {
            @Override
            public String name() { return name; }
            @Override
            public String schema() { return "{}"; }
            @Override
            public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.success("ok", 0, 0L);
            }
        };
    }

    private ToolPlugin makePlugin(String id, String desc, String... toolNames) {
        List<ToolProvider> providers = new java.util.ArrayList<ToolProvider>();
        for (String name : toolNames) {
            providers.add(makeProvider(name));
        }
        return new ToolPlugin() {
            @Override
            public String pluginId() { return id; }
            @Override
            public String description() { return desc; }
            @Override
            public List<ToolProvider> toolProviders() { return providers; }
        };
    }

    @Test
    void listPlugins_emptyRegistry_returnsEmptyList() {
        SimpleToolPluginRegistry registry = new SimpleToolPluginRegistry(Collections.emptyList());
        assertThat(registry.listPlugins()).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void listPlugins_singlePlugin_returnsInfo() {
        SimpleToolPluginRegistry registry = new SimpleToolPluginRegistry(
                Collections.singletonList(makePlugin("mysql", "MySQL tools", "query", "explain")));
        List<ToolPluginInfo> infos = registry.listPlugins();
        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).getPluginId()).isEqualTo("mysql");
        assertThat(infos.get(0).getDescription()).isEqualTo("MySQL tools");
        assertThat(infos.get(0).getToolNames()).containsExactly("query", "explain");
    }

    @Test
    void listPlugins_multiplePlugins_returnsAll() {
        SimpleToolPluginRegistry registry = new SimpleToolPluginRegistry(Arrays.asList(
                makePlugin("mysql", "MySQL tools", "query"),
                makePlugin("redis", "Redis tools", "get", "exists")));
        List<ToolPluginInfo> infos = registry.listPlugins();
        assertThat(infos).hasSize(2);
        assertThat(infos.get(0).getPluginId()).isEqualTo("mysql");
        assertThat(infos.get(1).getPluginId()).isEqualTo("redis");
    }

    @Test
    void listPlugins_nullProviders_returnsEmptyToolNames() {
        ToolPlugin plugin = new ToolPlugin() {
            @Override
            public String pluginId() { return "empty"; }
            @Override
            public String description() { return "No tools"; }
            @Override
            public List<ToolProvider> toolProviders() { return null; }
        };
        SimpleToolPluginRegistry registry = new SimpleToolPluginRegistry(
                Collections.singletonList(plugin));
        List<ToolPluginInfo> infos = registry.listPlugins();
        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).getToolNames()).isEmpty();
    }

    @Test
    void constructor_nullList_treatedAsEmpty() {
        SimpleToolPluginRegistry registry = new SimpleToolPluginRegistry(null);
        assertThat(registry.size()).isZero();
    }
}
