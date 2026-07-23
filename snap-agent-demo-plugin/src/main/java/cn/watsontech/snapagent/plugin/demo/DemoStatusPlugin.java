package cn.watsontech.snapagent.plugin.demo;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolPluginAnnotation;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

/**
 * Demo Plugin - a sample tool provider for demonstrating plugin upload + enable.
 *
 * Provides a "system_status" tool that returns a summary of available tools
 * and plugin metadata. Used for testing the plugin lifecycle:
 * upload → enable → use in skill → disable.
 */
@ToolPluginAnnotation(
    id = "demo-status-plugin",
    toolType = "system_status",
    displayName = "Demo Status Plugin",
    description = "Returns system status information — a demo plugin for testing upload/enable lifecycle",
    version = "1.0.0"
)
public class DemoStatusPlugin implements ToolProvider {

    @Override
    public String name() {
        return "demo-status-plugin";
    }

    @Override
    public String schema() {
        return "{\"name\":\"system_status\","
             + "\"description\":\"Returns system status info (demo plugin)\","
             + "\"input_schema\":{\"type\":\"object\","
             + "\"properties\":{\"query\":{\"type\":\"string\","
             + "\"description\":\"Optional query to filter status info\"}},"
             + "\"required\":[]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        try {
            String query = args != null ? (String) args.get("query") : null;
            StringBuilder sb = new StringBuilder();
            sb.append("# Demo Status Plugin Report\n\n");
            sb.append("| Field | Value |\n|-------|-------|\n");
            sb.append("| Plugin ID | demo-status-plugin |\n");
            sb.append("| Version | 1.0.0 |\n");
            sb.append("| Tool Type | system_status |\n");
            sb.append("| Timestamp | ").append(System.currentTimeMillis()).append(" |\n");
            if (query != null && !query.isEmpty()) {
                sb.append("\n## Query Filter\n").append(query).append("\n");
            }
            sb.append("\n> This is a demo plugin uploaded via `/tools/plugins/upload` to verify the plugin lifecycle.\n");
            return ToolResult.success(sb.toString(), 1, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ToolResult.error("demo-status-plugin failed: " + e.getMessage(),
                                     System.currentTimeMillis() - start);
        }
    }
}
