package cn.watsontech.snapagent.core.codegraph;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CodeGraph} immutability and basic accessors.
 *
 * <p>Covers TDD spec 12-codegraph GAP-1: CodeGraph immutability
 * (getNodes/getEdges return unmodifiable lists).</p>
 */
class CodeGraphTest {

    private CodeGraphNode node(String id) {
        return new CodeGraphNode(id, CodeGraphNode.NodeType.CLASS, id,
                "com.example", "Example", null, "Example.java", 1);
    }

    private CodeGraphEdge edge(String from, String to, CodeGraphEdge.EdgeType type) {
        return new CodeGraphEdge(from, to, type, null);
    }

    // ---- GAP-1: immutability ----

    @Test
    void getNodesShouldReturnUnmodifiableList() {
        List<CodeGraphNode> nodes = Arrays.asList(node("A"), node("B"));
        CodeGraph graph = new CodeGraph(nodes, Collections.<CodeGraphEdge>emptyList());

        List<CodeGraphNode> returned = graph.getNodes();
        assertThat(returned).hasSize(2);
        assertThatThrownBy(() -> returned.add(node("C")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getEdgesShouldReturnUnmodifiableList() {
        List<CodeGraphEdge> edges = Arrays.asList(
                edge("A", "B", CodeGraphEdge.EdgeType.CALLS),
                edge("B", "C", CodeGraphEdge.EdgeType.IMPLEMENTS));
        CodeGraph graph = new CodeGraph(Collections.<CodeGraphNode>emptyList(), edges);

        List<CodeGraphEdge> returned = graph.getEdges();
        assertThat(returned).hasSize(2);
        assertThatThrownBy(() -> returned.add(edge("X", "Y", CodeGraphEdge.EdgeType.REFERENCES)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getNodesShouldThrowOnRemove() {
        List<CodeGraphNode> nodes = Arrays.asList(node("A"), node("B"));
        CodeGraph graph = new CodeGraph(nodes, Collections.<CodeGraphEdge>emptyList());

        List<CodeGraphNode> returned = graph.getNodes();
        assertThatThrownBy(() -> returned.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getNodesShouldThrowOnClear() {
        List<CodeGraphNode> nodes = Arrays.asList(node("A"), node("B"));
        CodeGraph graph = new CodeGraph(nodes, Collections.<CodeGraphEdge>emptyList());

        List<CodeGraphNode> returned = graph.getNodes();
        assertThatThrownBy(returned::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getEdgesShouldThrowOnSet() {
        List<CodeGraphEdge> edges = Arrays.asList(
                edge("A", "B", CodeGraphEdge.EdgeType.CALLS));
        CodeGraph graph = new CodeGraph(Collections.<CodeGraphNode>emptyList(), edges);

        List<CodeGraphEdge> returned = graph.getEdges();
        assertThatThrownBy(() -> returned.set(0, edge("X", "Y", CodeGraphEdge.EdgeType.EXTENDS)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- defensive copy: mutations to source list do not affect graph ----

    @Test
    void shouldNotBeAffectedByMutationsToSourceNodeList() {
        List<CodeGraphNode> source = new java.util.ArrayList<CodeGraphNode>();
        source.add(node("A"));
        CodeGraph graph = new CodeGraph(source, Collections.<CodeGraphEdge>emptyList());

        source.add(node("B"));
        assertThat(graph.getNodes()).hasSize(1);
        assertThat(graph.nodeCount()).isEqualTo(1);
    }

    @Test
    void shouldNotBeAffectedByMutationsToSourceEdgeList() {
        List<CodeGraphEdge> source = new java.util.ArrayList<CodeGraphEdge>();
        source.add(edge("A", "B", CodeGraphEdge.EdgeType.CALLS));
        CodeGraph graph = new CodeGraph(Collections.<CodeGraphNode>emptyList(), source);

        source.add(edge("C", "D", CodeGraphEdge.EdgeType.EXTENDS));
        assertThat(graph.getEdges()).hasSize(1);
        assertThat(graph.edgeCount()).isEqualTo(1);
    }

    // ---- basic accessors ----

    @Test
    void shouldReturnCorrectNodeCount() {
        CodeGraph graph = new CodeGraph(
                Arrays.asList(node("A"), node("B"), node("C")),
                Collections.<CodeGraphEdge>emptyList());
        assertThat(graph.nodeCount()).isEqualTo(3);
    }

    @Test
    void shouldReturnCorrectEdgeCount() {
        CodeGraph graph = new CodeGraph(
                Collections.<CodeGraphNode>emptyList(),
                Arrays.asList(
                        edge("A", "B", CodeGraphEdge.EdgeType.CALLS),
                        edge("B", "C", CodeGraphEdge.EdgeType.DEPENDS_ON)));
        assertThat(graph.edgeCount()).isEqualTo(2);
    }

    @Test
    void shouldHandleEmptyGraph() {
        CodeGraph graph = new CodeGraph(
                Collections.<CodeGraphNode>emptyList(),
                Collections.<CodeGraphEdge>emptyList());
        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.edgeCount()).isZero();
        assertThat(graph.getNodes()).isEmpty();
        assertThat(graph.getEdges()).isEmpty();
    }

    @Test
    void shouldHandleNullNodeList() {
        CodeGraph graph = new CodeGraph(null, Collections.<CodeGraphEdge>emptyList());
        assertThat(graph.getNodes()).isEmpty();
        assertThat(graph.nodeCount()).isZero();
    }

    @Test
    void shouldHandleNullEdgeList() {
        CodeGraph graph = new CodeGraph(Collections.<CodeGraphNode>emptyList(), null);
        assertThat(graph.getEdges()).isEmpty();
        assertThat(graph.edgeCount()).isZero();
    }

    @Test
    void toStringShouldContainNodeAndEdgeCounts() {
        CodeGraph graph = new CodeGraph(
                Arrays.asList(node("A"), node("B")),
                Arrays.asList(edge("A", "B", CodeGraphEdge.EdgeType.CALLS)));
        assertThat(graph.toString()).contains("nodes=2");
        assertThat(graph.toString()).contains("edges=1");
    }
}
