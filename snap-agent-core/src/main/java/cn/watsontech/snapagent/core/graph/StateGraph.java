package cn.watsontech.snapagent.core.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable graph builder — add nodes, edges, conditional edges, then compile.
 *
 * <p>{@link #compile()} allows cycles (for ReAct graphs).
 * {@link #compileDag()} rejects cycles with {@link IllegalGraphException}
 * (for workflow DAGs).</p>
 */
public class StateGraph {
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<ConditionalEdge> conditionalEdges = new ArrayList<>();
    private String entryPoint;

    public StateGraph addNode(String name, Node node) {
        nodes.put(name, node);
        return this;
    }

    public StateGraph addEdge(String from, String to) {
        edges.add(new Edge(from, to));
        return this;
    }

    public StateGraph addConditionalEdges(String from, EdgeCondition condition, Map<String, String> routing) {
        conditionalEdges.add(new ConditionalEdge(from, condition, routing));
        return this;
    }

    public StateGraph setEntryPoint(String entry) {
        this.entryPoint = entry;
        return this;
    }

    /**
     * Compile the graph, allowing cycles (for ReAct graphs).
     *
     * @return compiled graph
     * @throws IllegalStateException if entry point not set or edges reference unknown nodes
     */
    public CompiledGraph compile() {
        return compile(false);
    }

    /**
     * Compile the graph as a DAG, rejecting cycles.
     *
     * @return compiled graph
     * @throws IllegalGraphException if the graph contains a cycle
     * @throws IllegalStateException if entry point not set or edges reference unknown nodes
     */
    public CompiledGraph compileDag() {
        return compile(true);
    }

    private CompiledGraph compile(boolean checkCycles) {
        if (entryPoint == null) {
            throw new IllegalStateException("entry point not set");
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("nodes must not be empty");
        }
        if (!nodes.containsKey(entryPoint)) {
            throw new IllegalStateException("entry point not found in nodes: " + entryPoint);
        }

        // Build adjacency list
        Map<String, List<EdgeTarget>> adjacency = new HashMap<>();
        for (String name : nodes.keySet()) {
            adjacency.put(name, new ArrayList<>());
        }

        for (Edge e : edges) {
            if (!nodes.containsKey(e.getFrom())) {
                throw new IllegalStateException("edge references unknown source node: " + e.getFrom());
            }
            if (!nodes.containsKey(e.getTo())) {
                throw new IllegalStateException("edge references unknown node: " + e.getTo());
            }
            adjacency.get(e.getFrom()).add(new EdgeTarget(e.getTo()));
        }

        for (ConditionalEdge ce : conditionalEdges) {
            if (!nodes.containsKey(ce.getFrom())) {
                throw new IllegalStateException("conditional edge source not found: " + ce.getFrom());
            }
            for (Map.Entry<String, String> entry : ce.getRouting().entrySet()) {
                if (!nodes.containsKey(entry.getValue())) {
                    throw new IllegalStateException("conditional routing target not found: " + entry.getValue());
                }
                adjacency.get(ce.getFrom()).add(new EdgeTarget(entry.getValue(), entry.getKey(), ce.getCondition()));
            }
        }

        // Cycle detection for DAG mode
        if (checkCycles) {
            detectCycles(adjacency);
        }

        return new CompiledGraph(nodes, entryPoint, adjacency);
    }

    /**
     * Detect cycles using DFS with coloring.
     * White (unvisited), Gray (in progress), Black (done).
     */
    private void detectCycles(Map<String, List<EdgeTarget>> adjacency) {
        Set<String> white = new HashSet<>(nodes.keySet());
        Set<String> gray = new HashSet<>();
        Set<String> black = new HashSet<>();

        for (String node : nodes.keySet()) {
            if (white.contains(node)) {
                detectCyclesDfs(node, adjacency, white, gray, black, new ArrayList<>());
            }
        }
    }

    private void detectCyclesDfs(String node, Map<String, List<EdgeTarget>> adjacency,
                                   Set<String> white, Set<String> gray, Set<String> black,
                                   List<String> path) {
        white.remove(node);
        gray.add(node);
        path.add(node);

        for (EdgeTarget target : adjacency.getOrDefault(node, new ArrayList<>())) {
            String next = target.getNodeName();
            if (gray.contains(next)) {
                // Cycle found
                path.add(next);
                String cyclePath = String.join("→", path);
                throw new IllegalGraphException("cycle detected: " + cyclePath);
            }
            if (white.contains(next)) {
                detectCyclesDfs(next, adjacency, white, gray, black, path);
            }
        }

        gray.remove(node);
        black.add(node);
        path.remove(path.size() - 1);
    }
}
