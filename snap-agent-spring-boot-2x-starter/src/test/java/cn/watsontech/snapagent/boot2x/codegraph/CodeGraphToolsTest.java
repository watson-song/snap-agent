package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CodeGraphTools} — verifies the 2.x @Tool annotation pattern
 * where ToolCallbacks.from() discovers 4 tool methods and wraps them as
 * ToolCallback[] registered to ToolCallbackRegistry.
 *
 * <p>Covers TDD spec 12-codegraph US-10 (AC17~AC24): @Tool reflection,
 * call_chain/reverse_chain/impact_analysis/find tool execution, maxDepth,
 * no-match, and ToolCallbackRegistry integration.</p>
 */
class CodeGraphToolsTest {

    private CodeGraphTools tools;
    private InMemoryCodeGraphIndex index;
    private ToolCallback[] callbacks;

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
        tools = new CodeGraphTools(index, 5, 3);
        callbacks = ToolCallbacks.from(tools);
    }

    private ToolCallback findByName(String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("callback not found: " + name);
    }

    private Map<String, Object> args(String query) {
        Map<String, Object> map = new HashMap<String, Object>();
        if (query != null) map.put("query", query);
        return map;
    }

    private Map<String, Object> argsWithDepth(String query, int maxDepth) {
        Map<String, Object> map = args(query);
        map.put("maxDepth", maxDepth);
        return map;
    }

    // ---- AC17: ToolCallbacks.from() discovers 4 @Tool methods ----

    @Test
    void shouldReflectFourToolMethods() {
        assertThat(callbacks).hasSize(4);
        assertThat(findByName("call_chain")).isNotNull();
        assertThat(findByName("reverse_chain")).isNotNull();
        assertThat(findByName("impact_analysis")).isNotNull();
        assertThat(findByName("find")).isNotNull();
    }

    @Test
    void eachCallbackShouldHaveNonEmptyJsonSchema() {
        for (ToolCallback cb : callbacks) {
            assertThat(cb.getJsonSchema()).isNotEmpty();
            assertThat(cb.getJsonSchema()).contains("\"type\":\"object\"");
            assertThat(cb.getDescription()).isNotEmpty();
        }
    }

    @Test
    void callbackNamesShouldMatchToolAnnotationNames() {
        assertThat(findByName("call_chain").getName()).isEqualTo("call_chain");
        assertThat(findByName("reverse_chain").getName()).isEqualTo("reverse_chain");
        assertThat(findByName("impact_analysis").getName()).isEqualTo("impact_analysis");
        assertThat(findByName("find").getName()).isEqualTo("find");
    }

    // ---- AC18: call_chain tool ----

    @Test
    void callChain_shouldReturnForwardPath() {
        ToolResult result = findByName("call_chain").execute(args("com.test.A#a()"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("正向调用链");
        assertThat(result.getContent()).contains("com.test.B#b()");
        assertThat(result.getContent()).contains("com.test.C#c()");
    }

    // ---- reverse_chain tool ----

    @Test
    void reverseChain_shouldReturnCallers() {
        ToolResult result = findByName("reverse_chain").execute(args("com.test.B#b()"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("反向调用链");
        assertThat(result.getContent()).contains("com.test.A#a()");
    }

    // ---- impact_analysis tool ----

    @Test
    void impactAnalysis_shouldReturnAffectedNodes() {
        ToolResult result = findByName("impact_analysis").execute(args("com.test.B#b()"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("变更影响范围");
        assertThat(result.getContent()).contains("com.test.A#a()");
    }

    // ---- AC21: find tool ----

    @Test
    void find_shouldReturnMatchingNodes() {
        ToolResult result = findByName("find").execute(args("b"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("匹配节点");
        assertThat(result.getContent()).contains("com.test.B");
    }

    // ---- Fuzzy match by method name ----

    @Test
    void callChain_fuzzyMatchMethodName() {
        ToolResult result = findByName("call_chain").execute(args("a"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("正向调用链");
    }

    // ---- No match ----

    @Test
    void callChain_noMatch_returnsNotFoundMessage() {
        ToolResult result = findByName("call_chain").execute(args("NonExistent#method()"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("未找到");
    }

    @Test
    void find_noMatch_returnsNotFoundMessage() {
        ToolResult result = findByName("find").execute(args("xyzNonExistent"), null);
        assertThat(result.getContent()).contains("未找到");
    }

    // ---- maxDepth param ----

    @Test
    void callChain_respectsMaxDepthParam() {
        Map<String, Object> map = argsWithDepth("com.test.A#a()", 1);
        ToolResult result = findByName("call_chain").execute(map, null);
        assertThat(result.getContent()).contains("com.test.B#b()");
        assertThat(result.getContent()).doesNotContain("com.test.C#c()");
    }

    // ---- Null/empty query handling ----

    @Test
    void callChain_nullQuery_returnsNotFound() {
        ToolResult result = findByName("call_chain").execute(args(null), null);
        assertThat(result.getContent()).contains("未找到");
    }

    @Test
    void find_emptyQuery_returnsNotFound() {
        ToolResult result = findByName("find").execute(args(""), null);
        assertThat(result.getContent()).contains("未找到");
    }

    // ---- P2-18: i18n support — English messages ----

    @Test
    void shouldReturnEnglishMessagesWhenConfigured() {
        CodeGraphTools englishTools = new CodeGraphTools(index, 5, 3, new EnglishCodeGraphMessages());
        ToolCallback[] englishCallbacks = ToolCallbacks.from(englishTools);

        ToolCallback callChainCb = null;
        for (ToolCallback cb : englishCallbacks) {
            if (cb.getName().equals("call_chain")) {
                callChainCb = cb;
                break;
            }
        }
        assertThat(callChainCb).isNotNull();

        ToolResult result = callChainCb.execute(args("com.test.A#a()"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Forward call chain");
        assertThat(result.getContent()).doesNotContain("正向调用链");
    }
}
