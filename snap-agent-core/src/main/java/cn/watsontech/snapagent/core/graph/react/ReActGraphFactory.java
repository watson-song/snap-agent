package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.graph.CompiledGraph;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.StateGraph;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.advisor.AdvisorNode;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillUnavailableException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the standard ReAct graph: entry→agent↔tools→END.
 * Advisors are wrapped as AdvisorNode around entry/agent/tools.
 *
 * <p>UC-21: When {@code skill.getAvailability() != AVAILABLE},
 * {@code build()} throws {@link SkillUnavailableException} and does
 * not construct the graph.</p>
 */
public class ReActGraphFactory {

    @SuppressWarnings("unchecked")
    public CompiledGraph build(SkillMeta skill, AgentTask task, List<Advisor> advisors) {
        // UC-21: UNAVAILABLE or INVALID skill blocks graph compilation
        if (skill.getAvailability() == null
                || skill.getAvailability() != SkillAvailability.AVAILABLE) {
            throw new SkillUnavailableException(
                skill.getName(),
                skill.getAvailability(),
                skill.getUnavailableReason()
            );
        }

        // AgentTask.getInputs() returns Map<String, String>, EntryNode expects Map<String, Object>.
        // Safe cast: String is assignable to Object.
        Map<String, Object> inputs = (Map<String, Object>) (Map<?, ?>) task.getInputs();

        // Create nodes
        EntryNode entryNode = new EntryNode(skill, inputs);
        AgentNode agentNode = new AgentNode(skill, task);
        ToolsNode toolsNode = new ToolsNode(4000);
        ShouldContinue shouldContinue = new ShouldContinue();

        // Wrap with AdvisorNode
        Node wrappedEntry = new AdvisorNode(entryNode, advisors);
        Node wrappedAgent = new AdvisorNode(agentNode, advisors);
        Node wrappedTools = new AdvisorNode(toolsNode, advisors);

        // END node (noop)
        Node endNode = new Node() {
            @Override public String getName() { return "END"; }
            @Override public GraphState execute(GraphState s, ExecutionContext ctx) { return s; }
        };

        // Build graph
        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("tools", "tools");

        StateGraph g = new StateGraph();
        g.addNode("entry", wrappedEntry)
            .addNode("agent", wrappedAgent)
            .addNode("tools", wrappedTools)
            .addNode("END", endNode)
            .addEdge("entry", "agent")
            .addConditionalEdges("agent", shouldContinue, routing)
            .addEdge("tools", "agent")
            .setEntryPoint("entry");

        return g.compile();
    }
}
