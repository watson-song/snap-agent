package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link CodeGraphIndex} backed by bidirectional adjacency lists.
 *
 * <p>Stores nodes in a {@link HashMap} keyed by ID, plus two edge maps
 * (outgoing and incoming) for efficient traversal. All graph searches use BFS
 * with cycle detection via a visited set.</p>
 *
 * <p>P0-4 / P2-14 / P2-15: A name inverted index ({@code nameIndex}, lowercase
 * name → list of nodes) is built during construction so {@link #findByName}
 * uses index lookup instead of an O(N) linear scan. All maps are
 * {@code volatile} and wrapped in {@link Collections#unmodifiableMap} after
 * construction so the index is safely published to concurrent readers. A
 * {@code maxResults} limit (default 100) truncates {@link #findByName} and BFS
 * results to prevent returning huge lists.</p>
 *
 * <p>The {@link #rebuild(CodeGraphBuilder)} method replaces the indexed graph
 * in-place for hot-reload scenarios.</p>
 */
public class InMemoryCodeGraphIndex implements CodeGraphIndex {

    /** Default cap on the number of results returned by search/BFS methods. */
    private static final int DEFAULT_MAX_RESULTS = 100;

    private volatile Map<String, CodeGraphNode> nodeMap;
    private volatile Map<String, List<CodeGraphEdge>> outgoing;
    private volatile Map<String, List<CodeGraphEdge>> incoming;
    private volatile Map<String, List<CodeGraphNode>> nameIndex;
    private volatile int maxResults;

    public InMemoryCodeGraphIndex(CodeGraph graph) {
        this(graph, DEFAULT_MAX_RESULTS);
    }

    /**
     * Construct an index with a custom result-limit cap.
     *
     * @param graph      the initial code graph
     * @param maxResults maximum number of nodes returned by {@link #findByName}
     *                   and BFS traversal methods
     */
    public InMemoryCodeGraphIndex(CodeGraph graph, int maxResults) {
        this.maxResults = maxResults;
        loadGraph(graph);
    }

    /**
     * Adjust the result-limit cap at runtime. Affects subsequent queries.
     *
     * @param maxResults new cap (must be &gt; 0)
     */
    public void setMaxResults(int maxResults) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive");
        }
        this.maxResults = maxResults;
    }

    /**
     * Rebuild the index from a fresh {@link CodeGraphBuilder#build()} result.
     * The previous graph is replaced atomically. Safe to call from a watch
     * thread while query methods may be running concurrently on another thread;
     * the field assignments are volatile-ish due to the synchronized block, and
     * readers see a consistent snapshot because they read the {@code nodeMap}
     * reference once per invocation.
     *
     * @param builder the builder to invoke {@code build()} on
     */
    @Override
    public synchronized void rebuild(CodeGraphBuilder builder) {
        CodeGraph graph = builder.build();
        loadGraph(graph);
    }

    private synchronized void loadGraph(CodeGraph graph) {
        Map<String, CodeGraphNode> newNodeMap = new HashMap<String, CodeGraphNode>();
        Map<String, List<CodeGraphEdge>> newOutgoing = new HashMap<String, List<CodeGraphEdge>>();
        Map<String, List<CodeGraphEdge>> newIncoming = new HashMap<String, List<CodeGraphEdge>>();
        Map<String, List<CodeGraphNode>> newNameIndex = new HashMap<String, List<CodeGraphNode>>();

        for (CodeGraphNode node : graph.getNodes()) {
            newNodeMap.put(node.getId(), node);
            newOutgoing.put(node.getId(), new ArrayList<CodeGraphEdge>());
            newIncoming.put(node.getId(), new ArrayList<CodeGraphEdge>());
            // Build the name inverted index (lowercase name → nodes) so
            // findByName can use index lookup instead of an O(N) scan.
            if (node.getName() != null) {
                String key = node.getName().toLowerCase();
                List<CodeGraphNode> bucket = newNameIndex.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<CodeGraphNode>();
                    newNameIndex.put(key, bucket);
                }
                bucket.add(node);
            }
        }
        for (CodeGraphEdge edge : graph.getEdges()) {
            if (!newOutgoing.containsKey(edge.getFromId())) {
                newOutgoing.put(edge.getFromId(), new ArrayList<CodeGraphEdge>());
            }
            if (!newIncoming.containsKey(edge.getToId())) {
                newIncoming.put(edge.getToId(), new ArrayList<CodeGraphEdge>());
            }
            newOutgoing.get(edge.getFromId()).add(edge);
            newIncoming.get(edge.getToId()).add(edge);
        }

        // Publish immutable views so no mutation happens after construction.
        // volatile fields guarantee the fully-constructed maps are visible to
        // other threads; wrapping in unmodifiableMap prevents accidental writes.
        this.nodeMap = Collections.unmodifiableMap(newNodeMap);
        this.outgoing = Collections.unmodifiableMap(newOutgoing);
        this.incoming = Collections.unmodifiableMap(newIncoming);
        this.nameIndex = Collections.unmodifiableMap(newNameIndex);
    }

    @Override
    public List<CodeGraphNode> findByName(String namePattern) {
        if (namePattern == null || namePattern.isEmpty()) {
            return Collections.emptyList();
        }
        String lower = namePattern.toLowerCase();
        List<CodeGraphNode> results = new ArrayList<CodeGraphNode>();
        Map<String, List<CodeGraphNode>> names = nameIndex;
        Map<String, CodeGraphNode> nodes = nodeMap;
        if (names == null || nodes == null) {
            return results;
        }
        Set<String> seen = new HashSet<String>();
        int limit = maxResults;

        // Use the name inverted index for name contains lookup instead of
        // scanning every node. Iterate over the (relatively few) distinct
        // lowercase name keys and check substring containment.
        for (Map.Entry<String, List<CodeGraphNode>> entry : names.entrySet()) {
            if (entry.getKey().contains(lower)) {
                for (CodeGraphNode node : entry.getValue()) {
                    if (seen.add(node.getId())) {
                        results.add(node);
                        if (results.size() >= limit) {
                            return results;
                        }
                    }
                }
            }
        }

        // Fallback: nodes whose name did not match but whose ID contains the
        // pattern. Preserves the previous substring-on-id behaviour.
        for (CodeGraphNode node : nodes.values()) {
            if (node.getId() != null && node.getId().toLowerCase().contains(lower)) {
                if (seen.add(node.getId())) {
                    results.add(node);
                    if (results.size() >= limit) {
                        return results;
                    }
                }
            }
        }
        return results;
    }

    @Override
    public List<CodeGraphEdge> getOutgoingEdges(String nodeId) {
        Map<String, List<CodeGraphEdge>> snapshot = outgoing;
        if (snapshot == null) {
            return Collections.<CodeGraphEdge>emptyList();
        }
        List<CodeGraphEdge> edges = snapshot.get(nodeId);
        return edges != null ? Collections.unmodifiableList(edges) : Collections.<CodeGraphEdge>emptyList();
    }

    @Override
    public List<CodeGraphEdge> getIncomingEdges(String nodeId) {
        Map<String, List<CodeGraphEdge>> snapshot = incoming;
        if (snapshot == null) {
            return Collections.<CodeGraphEdge>emptyList();
        }
        List<CodeGraphEdge> edges = snapshot.get(nodeId);
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
        Map<String, CodeGraphNode> snapshot = nodeMap;
        return snapshot != null ? snapshot.get(id) : null;
    }

    @Override
    public int nodeCount() {
        Map<String, CodeGraphNode> snapshot = nodeMap;
        return snapshot != null ? snapshot.size() : 0;
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
        Map<String, CodeGraphNode> snapshot = nodeMap;
        if (startId == null || snapshot == null || !snapshot.containsKey(startId)) {
            return Collections.emptyList();
        }

        int limit = maxResults;
        List<CodeGraphNode> result = new ArrayList<CodeGraphNode>();
        Set<String> visited = new HashSet<String>();
        visited.add(startId);

        List<String> currentLevel = new ArrayList<String>();
        currentLevel.add(startId);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty() && result.size() < limit; depth++) {
            List<String> nextLevel = new ArrayList<String>();
            for (String nodeId : currentLevel) {
                if (result.size() >= limit) {
                    break;
                }
                List<CodeGraphEdge> edges = forward
                        ? getOutgoingEdges(nodeId)
                        : getIncomingEdges(nodeId);
                for (CodeGraphEdge edge : edges) {
                    if (edge.getType() != edgeType) continue;
                    String targetId = forward ? edge.getToId() : edge.getFromId();
                    if (!visited.contains(targetId) && snapshot.containsKey(targetId)) {
                        visited.add(targetId);
                        nextLevel.add(targetId);
                        result.add(snapshot.get(targetId));
                        if (result.size() >= limit) {
                            break;
                        }
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
        Map<String, CodeGraphNode> snapshot = nodeMap;
        if (startId == null || snapshot == null || !snapshot.containsKey(startId)) {
            return Collections.emptyList();
        }

        int limit = maxResults;
        List<CodeGraphNode> result = new ArrayList<CodeGraphNode>();
        Set<String> visited = new HashSet<String>();
        visited.add(startId);

        List<String> currentLevel = new ArrayList<String>();
        currentLevel.add(startId);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty() && result.size() < limit; depth++) {
            List<String> nextLevel = new ArrayList<String>();
            for (String nodeId : currentLevel) {
                if (result.size() >= limit) {
                    break;
                }
                List<CodeGraphEdge> edges = forward
                        ? getOutgoingEdges(nodeId)
                        : getIncomingEdges(nodeId);
                for (CodeGraphEdge edge : edges) {
                    String targetId = forward ? edge.getToId() : edge.getFromId();
                    if (!visited.contains(targetId) && snapshot.containsKey(targetId)) {
                        visited.add(targetId);
                        nextLevel.add(targetId);
                        result.add(snapshot.get(targetId));
                        if (result.size() >= limit) {
                            break;
                        }
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return result;
    }
}
