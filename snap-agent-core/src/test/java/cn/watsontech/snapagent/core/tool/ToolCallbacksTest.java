package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@DisplayName("ToolCallbacks.from() 反射工厂")
class ToolCallbacksTest {

    static class MultiTool {
        @Tool(name = "query", description = "查询数据")
        public String query(@ToolParam(description = "SQL语句") String sql) {
            return "result: " + sql;
        }

        @Tool(name = "insert", description = "插入数据")
        public String insert(@ToolParam(description = "数据") String data) {
            return "inserted: " + data;
        }

        @Tool(name = "delete", description = "删除数据")
        public String delete(@ToolParam(description = "条件") String condition) {
            return "deleted: " + condition;
        }
    }

    static class DefaultNameTool {
        @Tool(description = "默认name用方法名")
        public String ping(@ToolParam(description = "消息") String msg) {
            return "pong: " + msg;
        }
    }

    static class NoToolClass {
        public String regularMethod() { return "hello"; }
    }

    static class MissingToolParam {
        @Tool(description = "缺少ToolParam")
        public String query(String sql) { return sql; }
    }

    static class NonStringReturn {
        @Tool(name = "count", description = "计数")
        public int count(@ToolParam(description = "表名") String table) {
            return 42;
        }
    }

    static class ReturnDirectTool {
        @Tool(name = "render", description = "渲染", returnDirect = true)
        public String render(@ToolParam(description = "模板") String template) {
            return "<html>" + template + "</html>";
        }
    }

    private ToolCallback findByName(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("callback not found: " + name);
    }

    @Test
    @DisplayName("多个 @Tool 方法 → ToolCallback[3] (UC-11)")
    void multipleToolMethods() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        assertThat(callbacks).hasSize(3);
        assertThat(findByName(callbacks, "query")).isNotNull();
        assertThat(findByName(callbacks, "insert")).isNotNull();
        assertThat(findByName(callbacks, "delete")).isNotNull();
    }

    @Test
    @DisplayName("description 正确提取")
    void descriptionExtracted() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        assertThat(findByName(callbacks, "query").getDescription()).isEqualTo("查询数据");
    }

    @Test
    @DisplayName("默认 name 用方法名 (UC-01)")
    void defaultNameIsMethodName() {
        ToolCallback[] callbacks = ToolCallbacks.from(new DefaultNameTool());
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getName()).isEqualTo("ping");
    }

    @Test
    @DisplayName("无 @Tool 方法返回空数组 (UC-12)")
    void noToolMethodsReturnsEmpty() {
        ToolCallback[] callbacks = ToolCallbacks.from(new NoToolClass());
        assertThat(callbacks).isEmpty();
    }

    @Test
    @DisplayName("缺少 @ToolParam 抛 IllegalStateException (UC-13)")
    void missingToolParamThrows() {
        assertThatThrownBy(() -> ToolCallbacks.from(new MissingToolParam()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("parameter missing @ToolParam");
    }

    @Test
    @DisplayName("非 String 返回 → String.valueOf (UC-14)")
    void nonStringReturn() {
        ToolCallback[] callbacks = ToolCallbacks.from(new NonStringReturn());
        assertThat(callbacks).hasSize(1);
        ToolResult result = callbacks[0].execute(
            new HashMap<String, Object>() {{ put("table", "users"); }}, null);
        assertThat(result.getContent()).isEqualTo("42");
    }

    @Test
    @DisplayName("returnDirect=true 正确传递 (UC-02)")
    void returnDirectPropagated() {
        ToolCallback[] callbacks = ToolCallbacks.from(new ReturnDirectTool());
        assertThat(callbacks[0].isReturnDirect()).isTrue();
    }

    @Test
    @DisplayName("execute 调用真实方法")
    void executeCallsRealMethod() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        ToolCallback queryCb = findByName(callbacks, "query");
        Map<String, Object> args = new HashMap<>();
        args.put("sql", "SELECT 1");
        ToolResult result = queryCb.execute(args, null);
        assertThat(result.getContent()).isEqualTo("result: SELECT 1");
    }

    @Test
    @DisplayName("getJsonSchema 生成正确 JSON")
    void jsonSchemaGenerated() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        String schema = findByName(callbacks, "query").getJsonSchema();
        assertThat(schema).contains("\"type\":\"object\"");
        assertThat(schema).contains("\"sql\"");
        assertThat(schema).contains("\"string\"");
        assertThat(schema).contains("\"SQL语句\"");
        assertThat(schema).contains("\"required\"");
    }
}
