package com.watsontech.snapagent.demo;

import com.watsontech.snapagent.core.tool.ToolContext;
import com.watsontech.snapagent.core.tool.ToolProvider;
import com.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Trivial tool provider for the E2E demo — echoes its arguments back as JSON.
 *
 * <p>Registered as a Spring bean so {@code SnapAgentAutoConfiguration}'s
 * {@code ToolDispatcher} picks it up (the dispatcher collects all
 * {@link ToolProvider} beans in the context).</p>
 */
@Component
public class EchoToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String schema() {
        return "{\"name\":\"echo\",\"description\":\"Echoes the input text back. "
                + "Used by the health-check skill.\",\"input_schema\":{"
                + "\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                + "\"description\":\"Text to echo\"}},\"required\":[\"text\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        Object text = args.get("text");
        String echoed = text != null ? text.toString() : "";
        return ToolResult.success("echo: " + echoed, 0, System.currentTimeMillis() - start);
    }
}
