package cn.watsontech.snapagent.boot2x.tool.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds MCP tool providers discovered on startup. Each provider is also
 * registered as an individual singleton on the
 * {@link org.springframework.beans.factory.config.ConfigurableListableBeanFactory
 * BeanFactory} so {@code ObjectProvider<ToolProvider>} in the toolDispatcher
 * picks them up automatically. This holder additionally lets the toolDispatcher
 * add them explicitly, ensuring correct ordering regardless of bean creation
 * timing.
 */
public class McpBootstrap {
    private final List<McpToolProvider> providers = new ArrayList<McpToolProvider>();

    public void addProvider(McpToolProvider provider) {
        providers.add(provider);
    }

    public List<McpToolProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }
}
