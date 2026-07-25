package cn.watsontech.snapagent.core.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public CompiledGraph compile() {
        if (entryPoint == null) {
            throw new IllegalStateException("entry point not set");
        }
        if (!nodes.containsKey(entryPoint)) {
            throw new IllegalStateException("entry point not found in nodes: " + entryPoint);
        }

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

        return new CompiledGraph(nodes, entryPoint, adjacency);
    }
}
