package cn.watsontech.snapagent.boot2x.tool.mcp;

public final class McpToolInfo {
    private final String name;
    private final String description;
    private final String inputSchema;

    public McpToolInfo(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
}
