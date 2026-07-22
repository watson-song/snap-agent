package ${package};

import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ${className}Test {

    private ${className} provider;

    @BeforeEach
    void setUp() {
        provider = new ${className}();
    }

    @Test
    void nameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("${pluginId}");
    }

    @Test
    void schemaContainsToolType() {
        String schema = provider.schema();
        assertThat(schema).isNotEmpty();
        assertThat(schema).contains("\"name\"");
        assertThat(schema).contains("\"${toolType}\"");
    }

    @Test
    void executeWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isNull();
    }

    @Test
    void executeWithValidQueryReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test query");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("test query");
    }
}
