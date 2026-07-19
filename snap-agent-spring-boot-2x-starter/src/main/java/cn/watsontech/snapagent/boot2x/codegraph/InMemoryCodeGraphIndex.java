package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link CodeGraphIndex} backed by bidirectional adjacency lists.
 *
 * <p>Stores nodes in a {@link LinkedHashMap} keyed by ID, plus two edge maps
 * (outgoing and incoming) for efficient traversal. All graph searches use BFS
 * with cycle detection via a visited set.</p>
 */
public class InMemoryCodeGraphIndex implements CodeGraphIndex {

    private final Map<String, CodeGraphNode> nodeMap;
    private final Map<String, List<CodeGraphEdge>> outgoing;
    private final Map<String, List<CodeGraphEdge>> incoming;

    public InMemoryCodeGraphIndex(cn.watsontech.snapagent.core.codegraph.CodeGraph graph) {
        this.nodeMap = new LinkedHashMap<String, CodeGraphNode>();
        this.outgoing = new LinkedHashMap<String, List<CodeGraphEdge>>();
        this.incoming = new LinkedHashMap<String, List<CodeGraphEdge>>();

        for (CodeGraphNode node : graph.getNodes()) {
            nodeMap.put(node.getId(), node);
            outgoing.put(node.getId(), new ArrayList<CodeGraphEdge>());
            incoming.put(node.getId(), new ArrayList<CodeGraphEdge>());
        }
        for (CodeGraphEdge edge : graph.getEdges()) {
            if (!outgoing.containsKey(edge.getFromId())) {
                outgoing.put(edge.getFromId(), new ArrayList<CodeGraphEdge>());
            }
            if (!incoming.containsKey(edge.getToId())) {
                incoming.put(edge.getToId(), new ArrayList<CodeGraphEdge>());
            }
            outgoing.get(edge.getFromId()).add(edge);
            incoming.get(edge.getToId()).add(edge);
        }
    }

    @Override
    public List<CodeGraphNode> findByName(String namePattern) {
        if (namePattern == null || namePattern.isEmpty()) {
            return Collections.emptyList();
        }
        String lower = namePattern.toLowerCase();
        List<CodeGraphNode> results = new ArrayList<CodeGraphNode>();
        for (CodeGraphNode node : nodeMap.values()) {
            if (node.getName() != null && node.getName().toLowerCase().contains(lower)) {
                results.add(node);
            } else if (node.getId().toLowerCase().contains(lower)) {
                results.add(node);
            }
        }
        return results;
    }

    @Override
    public List<CodeGraphEdge> getOutgoingEdges(String nodeId) {
        List<CodeGraphEdge> edges = outgoing.get(nodeId);
        return edges != null ? Collections.unmodifiableList(edges) : Collections.<CodeGraphEdge>emptyList();
    }

    @Override
    public List<CodeGraphEdge> getIncomingEdges(String nodeId) {
        List<CodeGraphEdge> edges = incoming.get(nodeId);
        return edges != null ? Collections.unmodifiableList(edges) : Collections.<CodeGraphEdge>emptyList();
    }

    @Override
    public List<CodeGraphNode> findCallChain(String methodId, int maxDepth) {
        return bfs(methodId, maxDepth, true, CodeGraphEdge.EdgeType.CALLS);
    }

    @Override
    public List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth) {
        return bfs(methodId, maxDepth, false, CodeGraphEdge.EdgeType.CALLS);
    }

    @Override
    public List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth) {
        // Impact analysis: BFS along ALL incoming edges (callers, dependents, etc.)
        return bfsAllEdges(nodeId, maxDepth, false);
    }

    @Override
    public CodeGraphNode getNode(String id) {
        return nodeMap.get(id);
    }

    @Override
    public int nodeCount() {
        return nodeMap.size();
    }

    /**
     * BFS traversal along edges of a specific type.
     *
     * @param startId    starting node ID
     * @param maxDepth   maximum depth
     * @param forward    true = follow outgoing edges, false = follow incoming edges
     * @param edgeType   filter edges by this type
     * @return ordered list of reachable nodes (excluding the starting node)
     */
    private List<CodeGraphNode> bfs(String startId, int maxDepth, boolean forward,
                                     CodeGraphEdge.EdgeType edgeType) {
        if (startId == null || !nodeMap.containsKey(startId)) {
            return Collections.emptyList();
        }

        List<CodeGraphNode> result = new ArrayList<CodeGraphNode>();
        Set<String> visited = new HashSet<String>();
        visited.add(startId);

        List<String> currentLevel = new ArrayList<String>();
        currentLevel.add(startId);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
            List<String> nextLevel = new ArrayList<String>();
            for (String nodeId : currentLevel) {
                List<CodeGraphEdge> edges = forward
                        ? getOutgoingEdges(nodeId)
                        : getIncomingEdges(nodeId);
                for (CodeGraphEdge edge : edges) {
                    if (edge.getType() != edgeType) continue;
                    String targetId = forward ? edge.getToId() : edge.getFromId();
                    if (!visited.contains(targetId) && nodeMap.containsKey(targetId)) {
                        visited.add(targetId);
                        nextLevel.add(targetId);
                        result.add(nodeMap.get(targetId));
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return result;
    }

    /**
     * BFS traversal along ALL edge types (used for impact analysis).
     */
    private List<CodeGraphNode> bfsAllEdges(String startId, int maxDepth, boolean forward) {
        if (startId == null || !nodeMap.containsKey(startId)) {
            return Collections.emptyList();
        }

        List<CodeGraphNode> result = new ArrayList<CodeGraphNode>();
        Set<String> visited = new HashSet<String>();
        visited.add(startId);

        List<String> currentLevel = new ArrayList<String>();
        currentLevel.add(startId);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
            List<String> nextLevel = new ArrayList<String>();
            for (String nodeId : currentLevel) {
                List<CodeGraphEdge> edges = forward
                        ? getOutgoingEdges(nodeId)
                        : getIncomingEdges(nodeId);
                for (CodeGraphEdge edge : edges) {
                    String targetId = forward ? edge.getToId() : edge.getFromId();
                    if (!visited.contains(targetId) && nodeMap.containsKey(targetId)) {
                        visited.add(targetId);
                        nextLevel.add(targetId);
                        result.add(nodeMap.get(targetId));
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return result;
    }
}
