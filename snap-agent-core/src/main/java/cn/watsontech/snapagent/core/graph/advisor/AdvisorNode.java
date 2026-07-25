package cn.watsontech.snapagent.core.graph.advisor;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decorator that wraps a Node, running advisor before/after chains.
 * Advisors are sorted by order ascending.
 */
public class AdvisorNode implements Node {
    private static final Logger log = LoggerFactory.getLogger(AdvisorNode.class);
    private final Node delegate;
    private final List<Advisor> advisors;

    public AdvisorNode(Node delegate, List<Advisor> advisors) {
        this.delegate = delegate;
        this.advisors = new ArrayList<>(advisors);
        this.advisors.sort(Comparator.comparingInt(Advisor::getOrder));
    }

    @Override
    public String getName() { return delegate.getName(); }

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        GraphState current = state;
        for (Advisor advisor : advisors) {
            try {
                current = advisor.beforeNode(delegate.getName(), current, ctx);
            } catch (RuntimeException e) {
                log.warn("advisor {} beforeNode failed", advisor.getName(), e);
            }
        }
        current = delegate.execute(current, ctx);
        for (int i = advisors.size() - 1; i >= 0; i--) {
            try {
                current = advisors.get(i).afterNode(delegate.getName(), current, ctx);
            } catch (RuntimeException e) {
                log.warn("advisor {} afterNode failed", advisors.get(i).getName(), e);
            }
        }
        return current;
    }

    public Node getDelegate() { return delegate; }
    public List<Advisor> getAdvisors() { return advisors; }
}
