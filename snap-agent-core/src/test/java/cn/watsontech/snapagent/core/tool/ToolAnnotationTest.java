package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@Tool / @ToolParam 注解")
class ToolAnnotationTest {

    static class TestTool {
        @Tool(name = "mysql_query", description = "执行SQL查询")
        public String query(@ToolParam(description = "SQL语句") String sql) {
            return "result: " + sql;
        }

        @Tool(description = "默认name用方法名")
        public String insert(@ToolParam(description = "插入数据") String data) {
            return data;
        }

        @Tool(name = "render_html", description = "渲染HTML", returnDirect = true)
        public String render() {
            return "<html></html>";
        }
    }

    @Test
    @DisplayName("@Tool 显式 name + description")
    void toolWithNameAndDescription() throws Exception {
        Method method = TestTool.class.getMethod("query", String.class);
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool.name()).isEqualTo("mysql_query");
        assertThat(tool.description()).isEqualTo("执行SQL查询");
        assertThat(tool.returnDirect()).isFalse();
    }

    @Test
    @DisplayName("@Tool 默认 name 为空 (ToolCallbacks.from 用方法名)")
    void toolDefaultNameIsEmpty() throws Exception {
        Method method = TestTool.class.getMethod("insert", String.class);
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool.name()).isEmpty();
        assertThat(tool.description()).isEqualTo("默认name用方法名");
    }

    @Test
    @DisplayName("@Tool returnDirect=true")
    void toolReturnDirect() throws Exception {
        Method method = TestTool.class.getMethod("render");
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool.returnDirect()).isTrue();
    }

    @Test
    @DisplayName("@ToolParam description + required 默认 true")
    void toolParamDefaults() throws Exception {
        Method method = TestTool.class.getMethod("query", String.class);
        ToolParam param = (ToolParam) method.getParameterAnnotations()[0][0];
        assertThat(param.description()).isEqualTo("SQL语句");
        assertThat(param.required()).isTrue();
    }

    @Test
    @DisplayName("@Tool @Retention RUNTIME — 可反射读取")
    void retentionRuntime() throws Exception {
        Method method = TestTool.class.getMethod("query", String.class);
        // If retention weren't RUNTIME, this would return null
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool).isNotNull();
        // Also verify @ToolParam is RUNTIME
        ToolParam param = (ToolParam) method.getParameterAnnotations()[0][0];
        assertThat(param).isNotNull();
    }
}
