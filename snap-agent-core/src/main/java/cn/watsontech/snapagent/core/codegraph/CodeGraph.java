package cn.watsontech.snapagent.core.codegraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable code graph containing a set of nodes and edges.
 *
 * <p>Built by a {@link CodeGraphBuilder} and indexed by a
 * {@link CodeGraphIndex} for retrieval.</p>
 */
public class CodeGraph {

    private final List<CodeGraphNode> nodes;
    private final List<CodeGraphEdge> edges;

    public CodeGraph(List<CodeGraphNode> nodes, List<CodeGraphEdge> edges) {
        this.nodes = new ArrayList<CodeGraphNode>(nodes);
        this.edges = new ArrayList<CodeGraphEdge>(edges);
    }

    public List<CodeGraphNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<CodeGraphEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }

    @Override
    public String toString() {
        return "CodeGraph{nodes=" + nodes.size() + ", edges=" + edges.size() + "}";
    }
}
