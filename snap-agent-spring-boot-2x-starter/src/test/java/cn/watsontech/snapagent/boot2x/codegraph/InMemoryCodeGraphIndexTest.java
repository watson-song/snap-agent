package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCodeGraphIndexTest {

    private CodeGraphNode methodNode(String id, String name, String className) {
        return new CodeGraphNode(id, CodeGraphNode.NodeType.METHOD, name,
                "com.test", className, "void", "Foo.java", 10);
    }

    private CodeGraphNode classNode(String id, String name) {
        return new CodeGraphNode(id, CodeGraphNode.NodeType.CLASS, name,
                "com.test", id, "", "Foo.java", 1);
    }

    private CodeGraph buildTestGraph() {
        // A#a() → B#b() → C#c()
        // D#d() → B#b()
        // B implements InterfaceB
        CodeGraphNode a = methodNode("com.test.A#a()", "a", "com.test.A");
        CodeGraphNode b = methodNode("com.test.B#b()", "b", "com.test.B");
        CodeGraphNode c = methodNode("com.test.C#c()", "c", "com.test.C");
        CodeGraphNode d = methodNode("com.test.D#d()", "d", "com.test.D");
        CodeGraphNode interfaceB = classNode("com.test.InterfaceB", "InterfaceB");

        CodeGraphNode classA = classNode("com.test.A", "A");
        CodeGraphNode classB = classNode("com.test.B", "B");
        CodeGraphNode classC = classNode("com.test.C", "C");
        CodeGraphNode classD = classNode("com.test.D", "D");

        List<CodeGraphNode> nodes = Arrays.asList(a, b, c, d, classA, classB, classC, classD, interfaceB);
        List<CodeGraphEdge> edges = Arrays.asList(
                new CodeGraphEdge("com.test.A#a()", "com.test.B#b()", CodeGraphEdge.EdgeType.CALLS, "line 10"),
                new CodeGraphEdge("com.test.B#b()", "com.test.C#c()", CodeGraphEdge.EdgeType.CALLS, "line 20"),
                new CodeGraphEdge("com.test.D#d()", "com.test.B#b()", CodeGraphEdge.EdgeType.CALLS, "line 30"),
                new CodeGraphEdge("com.test.B", "com.test.InterfaceB", CodeGraphEdge.EdgeType.IMPLEMENTS, "line 5"));

        return new CodeGraph(nodes, edges);
    }

    @Test
    void findByName_returnsMatchingNodes() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        List<CodeGraphNode> results = index.findByName("b");
        // Should match B#b() method and possibly class B
        assertThat(results).isNotEmpty();
        assertThat(results).extracting(CodeGraphNode::getName).contains("b");
    }

    @Test
    void findByName_caseInsensitive() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        List<CodeGraphNode> results = index.findByName("INTERFACEB");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getName()).isEqualTo("InterfaceB");
    }

    @Test
    void findByName_emptyPattern_returnsEmpty() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        assertThat(index.findByName("")).isEmpty();
        assertThat(index.findByName(null)).isEmpty();
    }

    @Test
    void findCallChain_returnsForwardPath() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        // A#a() calls B#b() which calls C#c()
        List<CodeGraphNode> chain = index.findCallChain("com.test.A#a()", 5);
        assertThat(chain).hasSize(2); // B#b() and C#c()
        assertThat(chain.get(0).getId()).isEqualTo("com.test.B#b()");
        assertThat(chain.get(1).getId()).isEqualTo("com.test.C#c()");
    }

    @Test
    void findCallChain_respectsMaxDepth() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        // A#a() → B#b() → C#c() — depth 1 should only return B#b()
        List<CodeGraphNode> chain = index.findCallChain("com.test.A#a()", 1);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).getId()).isEqualTo("com.test.B#b()");
    }

    @Test
    void findReverseCallChain_returnsCallers() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        // B#b() is called by A#a() and D#d()
        List<CodeGraphNode> callers = index.findReverseCallChain("com.test.B#b()", 5);
        assertThat(callers).hasSize(2);
        assertThat(callers).extracting(CodeGraphNode::getId)
                .contains("com.test.A#a()", "com.test.D#d()");
    }

    @Test
    void findImpactScope_returnsAffectedNodes() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        // If B#b() changes, A#a() and D#d() are affected
        List<CodeGraphNode> impacted = index.findImpactScope("com.test.B#b()", 3);
        assertThat(impacted).isNotEmpty();
        assertThat(impacted).extracting(CodeGraphNode::getId)
                .contains("com.test.A#a()", "com.test.D#d()");
    }

    @Test
    void findCallChain_unknownMethod_returnsEmpty() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        assertThat(index.findCallChain("com.test.NonExistent#method()", 5)).isEmpty();
    }

    @Test
    void getOutgoingEdges_returnsAllOutgoing() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        List<CodeGraphEdge> edges = index.getOutgoingEdges("com.test.A#a()");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getType()).isEqualTo(CodeGraphEdge.EdgeType.CALLS);
    }

    @Test
    void getIncomingEdges_returnsAllIncoming() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        List<CodeGraphEdge> edges = index.getIncomingEdges("com.test.B#b()");
        assertThat(edges).hasSize(2); // from A#a() and D#d()
    }

    @Test
    void getNode_returnsNodeById() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        CodeGraphNode node = index.getNode("com.test.C#c()");
        assertThat(node).isNotNull();
        assertThat(node.getName()).isEqualTo("c");
    }

    @Test
    void getNode_unknownId_returnsNull() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        assertThat(index.getNode("nonexistent")).isNull();
    }

    @Test
    void nodeCount_returnsTotalNodes() {
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(buildTestGraph());
        assertThat(index.nodeCount()).isEqualTo(9);
    }

    @Test
    void findCallChain_handlesCycles() {
        // A#a() → B#b() → A#a() (cycle)
        CodeGraphNode a = methodNode("A#a()", "a", "A");
        CodeGraphNode b = methodNode("B#b()", "b", "B");
        CodeGraph graph = new CodeGraph(
                Arrays.asList(a, b),
                Arrays.asList(
                        new CodeGraphEdge("A#a()", "B#b()", CodeGraphEdge.EdgeType.CALLS, ""),
                        new CodeGraphEdge("B#b()", "A#a()", CodeGraphEdge.EdgeType.CALLS, "")));
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(graph);
        List<CodeGraphNode> chain = index.findCallChain("A#a()", 10);
        // Should not loop infinitely, should return B#b()
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).getId()).isEqualTo("B#b()");
    }
}
