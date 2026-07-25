package cn.watsontech.snapagent.core.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ConditionalEdge {
    private final String from;
    private final EdgeCondition condition;
    private final Map<String, String> routing;

    public ConditionalEdge(String from, EdgeCondition condition, Map<String, String> routing) {
        this.from = from;
        this.condition = condition;
        this.routing = Collections.unmodifiableMap(new HashMap<>(routing));
    }

    public String getFrom() { return from; }
    public EdgeCondition getCondition() { return condition; }
    public Map<String, String> getRouting() { return routing; }
}
