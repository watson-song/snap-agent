package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphToolProviderTest {

    private CodeGraphToolProvider provider;
    private InMemoryCodeGraphIndex index;

    @BeforeEach
    void setUp() {
        CodeGraphNode a = new CodeGraphNode("com.test.A#a()", CodeGraphNode.NodeType.METHOD,
                "a", "com.test", "com.test.A", "void", "A.java", 10);
        CodeGraphNode b = new CodeGraphNode("com.test.B#b()", CodeGraphNode.NodeType.METHOD,
                "b", "com.test", "com.test.B", "void", "B.java", 20);
        CodeGraphNode c = new CodeGraphNode("com.test.C#c()", CodeGraphNode.NodeType.METHOD,
                "c", "com.test", "com.test.C", "void", "C.java", 30);
        CodeGraphNode classB = new CodeGraphNode("com.test.B", CodeGraphNode.NodeType.CLASS,
                "B", "com.test", "com.test.B", "", "B.java", 1);

        CodeGraph graph = new CodeGraph(
                Arrays.asList(a, b, c, classB),
                Arrays.asList(
                        new CodeGraphEdge("com.test.A#a()", "com.test.B#b()", CodeGraphEdge.EdgeType.CALLS, "line 10"),
                        new CodeGraphEdge("com.test.B#b()", "com.test.C#c()", CodeGraphEdge.EdgeType.CALLS, "line 20")));
        index = new InMemoryCodeGraphIndex(graph);
        provider = new CodeGraphToolProvider(index, 5, 3);
    }

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }

    @Test
    void execute_callChain_returnsForwardPath() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "call_chain");
        args.put("query", "com.test.A#a()");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("正向调用链");
        assertThat(result.getContent()).contains("com.test.B#b()");
        assertThat(result.getContent()).contains("com.test.C#c()");
    }

    @Test
    void execute_reverseChain_returnsCallers() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "reverse_chain");
        args.put("query", "com.test.B#b()");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("反向调用链");
        assertThat(result.getContent()).contains("com.test.A#a()");
    }

    @Test
    void execute_impactAnalysis_returnsAffectedNodes() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "impact_analysis");
        args.put("query", "com.test.B#b()");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("变更影响范围");
        assertThat(result.getContent()).contains("com.test.A#a()");
    }

    @Test
    void execute_find_returnsMatchingNodes() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "find");
        args.put("query", "b");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("匹配节点");
        assertThat(result.getContent()).contains("com.test.B");
    }

    @Test
    void execute_findByMethodName_fuzzyMatch() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "call_chain");
        args.put("query", "a");

        ToolResult result = provider.execute(args, ctx());

        // "a" should fuzzy-match method "a" in A#a()
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("正向调用链");
    }

    @Test
    void execute_unknownTool_returnsError() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "unknown");
        args.put("query", "test");

        ToolResult result = provider.execute(args, ctx());
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("unknown tool");
    }

    @Test
    void execute_missingToolParam_returnsError() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "test");

        ToolResult result = provider.execute(args, ctx());
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("missing required parameter: tool");
    }

    @Test
    void execute_missingQueryParam_returnsError() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "find");

        ToolResult result = provider.execute(args, ctx());
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("missing required parameter: query");
    }

    @Test
    void execute_callChain_noMatch_returnsNoResultMessage() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "call_chain");
        args.put("query", "com.test.NonExistent#method()");

        ToolResult result = provider.execute(args, ctx());
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("未找到");
    }

    @Test
    void name_returnsCodeGraphTools() {
        assertThat(provider.name()).isEqualTo("code_graph_tools");
    }

    @Test
    void schema_isValidJson() {
        String schema = provider.schema();
        assertThat(schema).contains("code_graph_tools");
        assertThat(schema).contains("call_chain");
        assertThat(schema).contains("reverse_chain");
        assertThat(schema).contains("impact_analysis");
        assertThat(schema).contains("find");
    }

    @Test
    void execute_respectsMaxDepthParam() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("tool", "call_chain");
        args.put("query", "com.test.A#a()");
        args.put("max_depth", 1);

        ToolResult result = provider.execute(args, ctx());

        // With max_depth=1, should only return B#b(), not C#c()
        assertThat(result.getContent()).contains("com.test.B#b()");
        assertThat(result.getContent()).doesNotContain("com.test.C#c()");
    }
}
