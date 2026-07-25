package cn.watsontech.snapagent.core.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class CompiledGraph {
    private final Map<String, Node> nodes;
    private final String entryPoint;
    private final Map<String, List<EdgeTarget>> adjacency;

    CompiledGraph(Map<String, Node> nodes, String entryPoint, Map<String, List<EdgeTarget>> adjacency) {
        this.nodes = Collections.unmodifiableMap(new HashMap<>(nodes));
        this.entryPoint = entryPoint;
        this.adjacency = Collections.unmodifiableMap(adjacency);
    }

    public String getEntryPoint() { return entryPoint; }
    public Map<String, Node> getNodes() { return nodes; }
    public List<EdgeTarget> getEdgesFrom(String node) {
        return adjacency.getOrDefault(node, Collections.emptyList());
    }
}
