package cn.watsontech.snapagent.demo;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;

import org.springframework.stereotype.Component;

/**
 * Trivial tool for the E2E demo — echoes its arguments back as JSON.
 *
 * <p>Uses 2.x {@code @Tool} annotation pattern. Discovered by
 * {@code ToolCallbacks.from()} and wrapped as {@code ToolCallback[]}.</p>
 */
@Component
public class EchoToolProvider {

    @Tool(name = "echo", description = "Echoes the input text back. Used by the health-check skill.")
    public String echo(
            @ToolParam(description = "Text to echo") String text) {
        return "echo: " + (text != null ? text : "");
    }
}
