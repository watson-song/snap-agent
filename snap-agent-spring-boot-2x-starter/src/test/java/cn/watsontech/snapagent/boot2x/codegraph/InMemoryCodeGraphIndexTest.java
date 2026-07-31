package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    // --- P0-4 / P2-14 / P2-15: name inverted index + thread safety + result limit ---

    @Test
    void findByName_usesIndex_returnsQuickly() {
        // Build a graph with duplicate names; the inverted index should return all
        // nodes sharing the same name without scanning every node linearly.
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        for (int i = 0; i < 50; i++) {
            nodes.add(methodNode("com.test.Service#handle" + i + "()", "handle", "com.test.Service"));
        }
        // Add some nodes with different names that should not match
        nodes.add(methodNode("com.test.Other#x()", "x", "com.test.Other"));
        nodes.add(classNode("com.test.Unrelated", "Unrelated"));
        CodeGraph graph = new CodeGraph(nodes, Collections.<CodeGraphEdge>emptyList());

        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(graph);
        List<CodeGraphNode> results = index.findByName("handle");

        // All 50 "handle" nodes must be found via the index
        assertThat(results).hasSize(50);
        assertThat(results).extracting(CodeGraphNode::getName).containsOnly("handle");
    }

    @Test
    void findByName_respectsMaxResults() {
        // 250 nodes with the same name prefix must be truncated to maxResults.
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        for (int i = 0; i < 250; i++) {
            nodes.add(methodNode("com.test.Dup#" + i + "()", "dupNode", "com.test.Dup"));
        }
        CodeGraph graph = new CodeGraph(nodes, Collections.<CodeGraphEdge>emptyList());

        // Default limit (100)
        InMemoryCodeGraphIndex defaultIndex = new InMemoryCodeGraphIndex(graph);
        List<CodeGraphNode> defaultResults = defaultIndex.findByName("dupNode");
        assertThat(defaultResults).hasSize(100);

        // Custom limit (50)
        InMemoryCodeGraphIndex limitedIndex = new InMemoryCodeGraphIndex(graph, 50);
        List<CodeGraphNode> limitedResults = limitedIndex.findByName("dupNode");
        assertThat(limitedResults).hasSize(50);

        // Setter can raise the limit above the default
        defaultIndex.setMaxResults(200);
        List<CodeGraphNode> raisedResults = defaultIndex.findByName("dupNode");
        assertThat(raisedResults).hasSize(200);
    }

    @Test
    void findByName_isThreadSafe() throws InterruptedException {
        // Concurrent reads from multiple threads must not throw and must return
        // consistent, correct results once construction is complete.
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        for (int i = 0; i < 200; i++) {
            nodes.add(methodNode("com.test.Concurrent#m" + i + "()", "concurrentMethod", "com.test.Concurrent"));
        }
        CodeGraph graph = new CodeGraph(nodes, Collections.<CodeGraphEdge>emptyList());
        final InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(graph, 100);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>(null);

        for (int t = 0; t < threads; t++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < 200; i++) {
                            List<CodeGraphNode> results = index.findByName("concurrentMethod");
                            // Result limit must always hold, even under contention
                            if (results.size() > 100) {
                                throw new AssertionError("result limit exceeded: " + results.size());
                            }
                            if (results.isEmpty()) {
                                throw new AssertionError("expected non-empty results");
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(error.get()).as("concurrent findByName must not throw").isNull();
    }

    @Test
    void bfsMethods_respectMaxResults() {
        // Build a wide graph: root calls 250 distinct targets.
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        List<CodeGraphEdge> edges = new ArrayList<CodeGraphEdge>();
        nodes.add(methodNode("com.test.Root#r()", "r", "com.test.Root"));
        for (int i = 0; i < 250; i++) {
            String targetId = "com.test.Target#t" + i + "()";
            nodes.add(methodNode(targetId, "t" + i, "com.test.Target"));
            edges.add(new CodeGraphEdge("com.test.Root#r()", targetId, CodeGraphEdge.EdgeType.CALLS, ""));
        }
        CodeGraph graph = new CodeGraph(nodes, edges);
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(graph, 100);

        // findCallChain (forward BFS) must be truncated to maxResults
        List<CodeGraphNode> chain = index.findCallChain("com.test.Root#r()", 5);
        assertThat(chain.size()).isLessThanOrEqualTo(100);
    }
}
