package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link H2CodeGraphIndex}.
 *
 * <p>Uses in-memory H2 ({@code jdbc:h2:mem}) so tests are fast and leave no
 * files on disk. Each test gets its own index instance via {@link #newIndex()}
 * which creates a fresh in-memory database with a unique name.</p>
 */
class H2CodeGraphIndexTest {

    private H2CodeGraphIndex index;

    @BeforeEach
    void setUp() {
        index = newIndex();
    }

    @AfterEach
    void tearDown() {
        if (index != null) {
            index.close();
        }
    }

    /** Create a fresh in-memory H2 index with a unique DB name per test. */
    private H2CodeGraphIndex newIndex() {
        String url = "jdbc:h2:mem:test-codegraph-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        return new H2CodeGraphIndex(url);
    }

    // ------------------------------------------------------------------
    // Test data helpers
    // ------------------------------------------------------------------

    private CodeGraphNode methodNode(String id, String name, String className) {
        return new CodeGraphNode(id, CodeGraphNode.NodeType.METHOD, name,
                "com.test", className, "void", "Foo.java", 10);
    }

    private CodeGraphNode classNode(String id, String name) {
        return new CodeGraphNode(id, CodeGraphNode.NodeType.CLASS, name,
                "com.test", id, "", "Foo.java", 1);
    }

    /**
     * Build a test graph:
     * <pre>
     *   A#a() → B#b() → C#c()
     *   D#d() → B#b()
     *   B implements InterfaceB
     * </pre>
     */
    private CodeGraph buildTestGraph() {
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

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void shouldStoreAndRetrieveNodes() {
        index.loadGraph(buildTestGraph());

        CodeGraphNode node = index.getNode("com.test.C#c()");
        assertThat(node).isNotNull();
        assertThat(node.getName()).isEqualTo("c");
        assertThat(node.getType()).isEqualTo(CodeGraphNode.NodeType.METHOD);
        assertThat(node.getClassName()).isEqualTo("com.test.C");
        assertThat(node.getReturnType()).isEqualTo("void");
        assertThat(node.getFilePath()).isEqualTo("Foo.java");
        assertThat(node.getLineNumber()).isEqualTo(10);
    }

    @Test
    void shouldFindByName() {
        index.loadGraph(buildTestGraph());

        // Search for "b" — should match method B#b() (name="b") and class B (name="B")
        List<CodeGraphNode> results = index.findByName("b");
        assertThat(results).isNotEmpty();
        assertThat(results).extracting(CodeGraphNode::getName)
                .contains("b", "B");

        // Case-insensitive search
        List<CodeGraphNode> upperResults = index.findByName("INTERFACEB");
        assertThat(upperResults).isNotEmpty();
        assertThat(upperResults.get(0).getName()).isEqualTo("InterfaceB");

        // Empty pattern returns empty
        assertThat(index.findByName("")).isEmpty();
        assertThat(index.findByName(null)).isEmpty();
    }

    @Test
    void shouldFindCallChain() {
        index.loadGraph(buildTestGraph());

        // A#a() → B#b() → C#c()
        List<CodeGraphNode> chain = index.findCallChain("com.test.A#a()", 5);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getId()).isEqualTo("com.test.B#b()");
        assertThat(chain.get(1).getId()).isEqualTo("com.test.C#c()");

        // Max depth 1: only B#b()
        List<CodeGraphNode> limited = index.findCallChain("com.test.A#a()", 1);
        assertThat(limited).hasSize(1);
        assertThat(limited.get(0).getId()).isEqualTo("com.test.B#b()");

        // D#d() → B#b() → C#c()
        List<CodeGraphNode> chainFromD = index.findCallChain("com.test.D#d()", 5);
        assertThat(chainFromD).hasSize(2);
        assertThat(chainFromD.get(0).getId()).isEqualTo("com.test.B#b()");
        assertThat(chainFromD.get(1).getId()).isEqualTo("com.test.C#c()");
    }

    @Test
    void shouldFindReverseCallChain() {
        index.loadGraph(buildTestGraph());

        // B#b() is called by A#a() and D#d()
        List<CodeGraphNode> callers = index.findReverseCallChain("com.test.B#b()", 5);
        assertThat(callers).hasSize(2);
        assertThat(callers).extracting(CodeGraphNode::getId)
                .containsExactlyInAnyOrder("com.test.A#a()", "com.test.D#d()");

        // C#c() with depth 1: only direct caller B#b()
        List<CodeGraphNode> directCallersOfC = index.findReverseCallChain("com.test.C#c()", 1);
        assertThat(directCallersOfC).hasSize(1);
        assertThat(directCallersOfC.get(0).getId()).isEqualTo("com.test.B#b()");

        // C#c() with depth 5: transitive callers — B#b() then A#a(), D#d()
        List<CodeGraphNode> transitiveCallersOfC = index.findReverseCallChain("com.test.C#c()", 5);
        assertThat(transitiveCallersOfC).hasSize(3);
        assertThat(transitiveCallersOfC).extracting(CodeGraphNode::getId)
                .containsExactlyInAnyOrder("com.test.B#b()", "com.test.A#a()", "com.test.D#d()");
    }

    @Test
    void shouldFindImpactScope() {
        index.loadGraph(buildTestGraph());

        // If B#b() changes, A#a() and D#d() are affected (they call B#b())
        List<CodeGraphNode> impacted = index.findImpactScope("com.test.B#b()", 3);
        assertThat(impacted).isNotEmpty();
        assertThat(impacted).extracting(CodeGraphNode::getId)
                .contains("com.test.A#a()", "com.test.D#d()");
    }

    @Test
    void shouldReturnEmptyForNonExistentNode() {
        index.loadGraph(buildTestGraph());

        assertThat(index.getNode("com.test.NonExistent#method()")).isNull();
        assertThat(index.findCallChain("com.test.NonExistent#method()", 5)).isEmpty();
        assertThat(index.findReverseCallChain("com.test.NonExistent#method()", 5)).isEmpty();
        assertThat(index.findImpactScope("com.test.NonExistent#method()", 5)).isEmpty();
        assertThat(index.getOutgoingEdges("com.test.NonExistent#method()")).isEmpty();
        assertThat(index.getIncomingEdges("com.test.NonExistent#method()")).isEmpty();
    }

    @Test
    void shouldRebuildGraph() {
        // Load initial graph with nodes A, B, C, D
        index.loadGraph(buildTestGraph());
        assertThat(index.nodeCount()).isEqualTo(9);
        assertThat(index.getNode("com.test.A#a()")).isNotNull();

        // Rebuild with a completely different graph
        CodeGraphNode x = methodNode("com.test.X#x()", "x", "com.test.X");
        CodeGraphNode y = methodNode("com.test.Y#y()", "y", "com.test.Y");
        CodeGraph newGraph = new CodeGraph(
                Arrays.asList(x, y),
                Collections.singletonList(
                        new CodeGraphEdge("com.test.X#x()", "com.test.Y#y()",
                                CodeGraphEdge.EdgeType.CALLS, "")));

        CodeGraphBuilder builder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() {
                return newGraph;
            }

            @Override
            public String type() {
                return "test";
            }
        };

        index.rebuild(builder);

        // Old nodes should be gone
        assertThat(index.nodeCount()).isEqualTo(2);
        assertThat(index.getNode("com.test.A#a()")).isNull();
        assertThat(index.getNode("com.test.B#b()")).isNull();

        // New nodes should exist
        assertThat(index.getNode("com.test.X#x()")).isNotNull();
        assertThat(index.getNode("com.test.Y#y()")).isNotNull();

        // Call chain should reflect new graph
        List<CodeGraphNode> chain = index.findCallChain("com.test.X#x()", 5);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).getId()).isEqualTo("com.test.Y#y()");
    }

    @Test
    void shouldHandleLargeGraph() {
        // Build a chain: Method0 → Method1 → ... → Method999 (1000 nodes, 999 edges)
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        List<CodeGraphEdge> edges = new ArrayList<CodeGraphEdge>();
        for (int i = 0; i < 1000; i++) {
            String id = "com.test.Large#method" + i + "()";
            nodes.add(new CodeGraphNode(id, CodeGraphNode.NodeType.METHOD,
                    "method" + i, "com.test", "com.test.Large", "void", "Large.java", i));
            if (i > 0) {
                String prevId = "com.test.Large#method" + (i - 1) + "()";
                edges.add(new CodeGraphEdge(prevId, id, CodeGraphEdge.EdgeType.CALLS, "line " + i));
            }
        }
        CodeGraph largeGraph = new CodeGraph(nodes, edges);

        // Load and verify node count
        index.loadGraph(largeGraph);
        assertThat(index.nodeCount()).isEqualTo(1000);

        // findByName should find all methods matching "method"
        List<CodeGraphNode> found = index.findByName("method");
        assertThat(found).hasSize(1000);

        // getNode should return the correct node
        CodeGraphNode node500 = index.getNode("com.test.Large#method500()");
        assertThat(node500).isNotNull();
        assertThat(node500.getName()).isEqualTo("method500");

        // Call chain from method0 with depth 10 should return 10 nodes
        List<CodeGraphNode> chain = index.findCallChain("com.test.Large#method0()", 10);
        assertThat(chain).hasSize(10);
        assertThat(chain.get(0).getId()).isEqualTo("com.test.Large#method1()");
        assertThat(chain.get(9).getId()).isEqualTo("com.test.Large#method10()");

        // Reverse call chain from method999 with depth 5 should return 5 nodes
        List<CodeGraphNode> reverse = index.findReverseCallChain("com.test.Large#method999()", 5);
        assertThat(reverse).hasSize(5);
        assertThat(reverse.get(0).getId()).isEqualTo("com.test.Large#method998()");

        // Impact scope of method500 should include all callers (499 nodes: 0..499)
        List<CodeGraphNode> impacted = index.findImpactScope("com.test.Large#method500()", 1000);
        assertThat(impacted).hasSize(500);
    }

    @Test
    void shouldHandleCycles() {
        // A#a() → B#b() → A#a() (cycle)
        CodeGraphNode a = methodNode("A#a()", "a", "A");
        CodeGraphNode b = methodNode("B#b()", "b", "B");
        CodeGraph graph = new CodeGraph(
                Arrays.asList(a, b),
                Arrays.asList(
                        new CodeGraphEdge("A#a()", "B#b()", CodeGraphEdge.EdgeType.CALLS, ""),
                        new CodeGraphEdge("B#b()", "A#a()", CodeGraphEdge.EdgeType.CALLS, "")));
        index.loadGraph(graph);

        // Should not loop infinitely, should return B#b()
        List<CodeGraphNode> chain = index.findCallChain("A#a()", 10);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).getId()).isEqualTo("B#b()");

        // Reverse: who calls A#a()? — B#b()
        List<CodeGraphNode> reverse = index.findReverseCallChain("A#a()", 10);
        assertThat(reverse).hasSize(1);
        assertThat(reverse.get(0).getId()).isEqualTo("B#b()");
    }

    @Test
    void shouldReturnCorrectNodeCount() {
        index.loadGraph(buildTestGraph());
        assertThat(index.nodeCount()).isEqualTo(9);

        // Empty graph
        H2CodeGraphIndex emptyIndex = newIndex();
        emptyIndex.loadGraph(new CodeGraph(
                Collections.<CodeGraphNode>emptyList(),
                Collections.<CodeGraphEdge>emptyList()));
        assertThat(emptyIndex.nodeCount()).isEqualTo(0);
        emptyIndex.close();
    }

    @Test
    void shouldGetOutgoingAndIncomingEdges() {
        index.loadGraph(buildTestGraph());

        // A#a() has 1 outgoing CALLS edge
        List<CodeGraphEdge> outgoing = index.getOutgoingEdges("com.test.A#a()");
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0).getType()).isEqualTo(CodeGraphEdge.EdgeType.CALLS);
        assertThat(outgoing.get(0).getToId()).isEqualTo("com.test.B#b()");

        // B#b() has 2 incoming edges (from A#a() and D#d())
        List<CodeGraphEdge> incoming = index.getIncomingEdges("com.test.B#b()");
        assertThat(incoming).hasSize(2);
        assertThat(incoming).extracting(CodeGraphEdge::getFromId)
                .containsExactlyInAnyOrder("com.test.A#a()", "com.test.D#d()");

        // B class has 1 outgoing IMPLEMENTS edge
        List<CodeGraphEdge> implEdges = index.getOutgoingEdges("com.test.B");
        assertThat(implEdges).hasSize(1);
        assertThat(implEdges.get(0).getType()).isEqualTo(CodeGraphEdge.EdgeType.IMPLEMENTS);
    }

    @Test
    void shouldHandleEmptyGraph() {
        index.loadGraph(new CodeGraph(
                Collections.<CodeGraphNode>emptyList(),
                Collections.<CodeGraphEdge>emptyList()));

        assertThat(index.nodeCount()).isEqualTo(0);
        assertThat(index.findByName("anything")).isEmpty();
        assertThat(index.getNode("anything")).isNull();
        assertThat(index.findCallChain("anything", 5)).isEmpty();
    }

    @Test
    void shouldPersistAcrossReopen() throws Exception {
        // Use a file-based H2 URL so data survives close + reopen
        String fileUrl = "jdbc:h2:file:" + java.nio.file.Files.createTempDirectory("h2-persist-test")
                .resolve("test-codegraph").toString();
        H2CodeGraphIndex firstIndex = new H2CodeGraphIndex(fileUrl);
        firstIndex.loadGraph(buildTestGraph());
        assertThat(firstIndex.nodeCount()).isEqualTo(9);
        firstIndex.close();

        // Reopen — data should still be there
        H2CodeGraphIndex reopenedIndex = new H2CodeGraphIndex(fileUrl);
        assertThat(reopenedIndex.nodeCount()).isEqualTo(9);
        assertThat(reopenedIndex.getNode("com.test.A#a()")).isNotNull();
        assertThat(reopenedIndex.findByName("InterfaceB")).isNotEmpty();
        assertThat(reopenedIndex.findCallChain("com.test.A#a()", 5)).hasSize(2);
        reopenedIndex.close();
    }

    @Test
    void shouldRebuildWithH2Index() {
        // Verify rebuild() works on H2CodeGraphIndex via the CodeGraphIndex interface
        index.loadGraph(buildTestGraph());
        assertThat(index.nodeCount()).isEqualTo(9);

        CodeGraph newGraph = new CodeGraph(
                Collections.singletonList(methodNode("com.test.X#x()", "x", "com.test.X")),
                Collections.<CodeGraphEdge>emptyList());

        CodeGraphBuilder builder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() { return newGraph; }
            @Override
            public String type() { return "test"; }
        };

        index.rebuild(builder);
        assertThat(index.nodeCount()).isEqualTo(1);
        assertThat(index.getNode("com.test.A#a()")).isNull();
        assertThat(index.getNode("com.test.X#x()")).isNotNull();
    }

    @Test
    void shouldPersistNodeFieldsCorrectly() {
        // Verify all node fields round-trip through H2
        CodeGraphNode node = new CodeGraphNode(
                "com.example.MyClass#myMethod(String,int)",
                CodeGraphNode.NodeType.METHOD,
                "myMethod",
                "com.example",
                "com.example.MyClass",
                "String",
                "src/main/java/com/example/MyClass.java",
                42);
        CodeGraph graph = new CodeGraph(
                Collections.singletonList(node),
                Collections.<CodeGraphEdge>emptyList());
        index.loadGraph(graph);

        CodeGraphNode retrieved = index.getNode("com.example.MyClass#myMethod(String,int)");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("com.example.MyClass#myMethod(String,int)");
        assertThat(retrieved.getType()).isEqualTo(CodeGraphNode.NodeType.METHOD);
        assertThat(retrieved.getName()).isEqualTo("myMethod");
        assertThat(retrieved.getPackageName()).isEqualTo("com.example");
        assertThat(retrieved.getClassName()).isEqualTo("com.example.MyClass");
        assertThat(retrieved.getReturnType()).isEqualTo("String");
        assertThat(retrieved.getFilePath()).isEqualTo("src/main/java/com/example/MyClass.java");
        assertThat(retrieved.getLineNumber()).isEqualTo(42);
    }
}
