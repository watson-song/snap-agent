package cn.watsontech.snapagent.boot2x.tool.mcp;

import cn.watsontech.snapagent.core.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds MCP tool callbacks discovered on startup. Each callback is also
 * registered as an individual singleton on the
 * {@link org.springframework.beans.factory.config.ConfigurableListableBeanFactory
 * BeanFactory} so {@code ObjectProvider} in the pluginRegistry picks them up
 * automatically. This holder additionally lets the pluginRegistry add them
 * explicitly, ensuring correct ordering regardless of bean creation timing.
 */
public class McpBootstrap {
    private final List<ToolCallback> callbacks = new ArrayList<ToolCallback>();

    public void addCallback(ToolCallback callback) {
        callbacks.add(callback);
    }

    public List<ToolCallback> getCallbacks() {
        return Collections.unmodifiableList(callbacks);
    }
}
