package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolResult;

/**
 * Minimal @Tool-annotated provider for PluginUploader unit tests.
 * Has a public no-arg constructor so reflective instantiation works.
 */
public class SimpleTestToolProvider {

    @Tool(name = "simple-test-tool", description = "test tool for unit tests")
    public ToolResult execute() {
        return ToolResult.success("test-ok", 0, 0);
    }
}
