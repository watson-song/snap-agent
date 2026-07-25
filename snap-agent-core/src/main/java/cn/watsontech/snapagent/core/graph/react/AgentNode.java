package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.skill.SkillMeta;

/**
 * Phase 1 stub — returns state unchanged.
 * Full LLM streaming + tool defs + RAG context implementation comes in Task 15 (UC-31~35).
 */
public class AgentNode implements Node {
    private final SkillMeta skill;
    private final AgentTask task;

    public AgentNode(SkillMeta skill, AgentTask task) {
        this.skill = skill;
        this.task = task;
    }

    @Override
    public String getName() { return "agent"; }

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        // Phase 1 stub: no LLM call yet, just pass through.
        // Task 15 will implement: LLM streaming, tool definitions, RAG context.
        return state.with("stop_reason", "end_turn");
    }

    public SkillMeta getSkill() { return skill; }
    public AgentTask getTask() { return task; }
}
