package cn.watsontech.snapagent.core.llm;

/**
 * Definition of a tool exposed to the LLM.
 */
public final class ToolDef {

    private final String name;
    private final String description;
    private final String inputSchema;

    public ToolDef(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    @Override
    public String toString() {
        return "ToolDef{name='" + name + "', description='" + description + "'}";
    }
}
