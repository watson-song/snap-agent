package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

/**
 * Minimal ToolProvider for PluginUploader unit tests.
 * Has a public no-arg constructor so reflective instantiation works.
 */
public class SimpleTestToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "simple-test-tool";
    }

    @Override
    public String schema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        return ToolResult.success("test-ok", 0, 0);
    }
}
