package cn.watsontech.snapagent.core.codegraph;

import java.util.List;

/**
 * Code graph index SPI.
 *
 * <p>Provides retrieval interfaces over a {@link CodeGraph}. The default
 * starter implementation ({@code InMemoryCodeGraphIndex}) uses in-memory
 * adjacency lists. Custom implementations can use SQLite/H2 for persistence
 * or external graph databases.</p>
 */
public interface CodeGraphIndex {

    /**
     * Find nodes by name pattern (substring match, case-insensitive).
     *
     * @param namePattern name pattern to search for
     * @return list of matching nodes (may be empty, never null)
     */
    List<CodeGraphNode> findByName(String namePattern);

    /**
     * Get all outgoing edges from a node (this node points to others).
     *
     * @param nodeId the source node ID
     * @return list of outgoing edges (may be empty)
     */
    List<CodeGraphEdge> getOutgoingEdges(String nodeId);

    /**
     * Get all incoming edges to a node (others point to this node).
     *
     * @param nodeId the target node ID
     * @return list of incoming edges (may be empty)
     */
    List<CodeGraphEdge> getIncomingEdges(String nodeId);

    /**
     * Forward call chain: BFS from the given method along CALLS edges.
     *
     * @param methodId the starting method node ID
     * @param maxDepth maximum BFS depth
     * @return ordered list of reachable method nodes
     */
    List<CodeGraphNode> findCallChain(String methodId, int maxDepth);

    /**
     * Reverse call chain: who calls this method (BFS along incoming CALLS edges).
     *
     * @param methodId the target method node ID
     * @param maxDepth maximum BFS depth
     * @return ordered list of caller method nodes
     */
    List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth);

    /**
     * Impact analysis: what nodes are affected if the given node changes
     * (BFS along all incoming edges — callers, dependents, etc.).
     *
     * @param nodeId the changed node ID
     * @param maxDepth maximum BFS depth
     * @return ordered list of affected nodes (excluding the starting node)
     */
    List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth);

    /**
     * Get a node by its ID.
     *
     * @param id node ID
     * @return the node, or null if not found
     */
    CodeGraphNode getNode(String id);

    /**
     * Total number of nodes in the index.
     */
    int nodeCount();
}
