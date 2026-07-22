package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolPluginAnnotation;

@ToolPluginAnnotation(id = "test-annotated-plugin", toolType = "log_read", displayName = "Test Plugin", description = "Plugin for scanner tests", version = "3.0.0", isDefault = true)
public class AnnotatedTestPlugin {
    // No need to implement ToolProvider for scanner tests — scanner only reads annotation metadata
}
