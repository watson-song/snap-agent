package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import java.util.HashMap;
import java.util.Map;

/**
 * EntryNode builds system prompt + user message from skill body + task inputs.
 * Read-only prefix is always first. Skill body is wrapped in <skill_body> tags
 * for prompt injection defense.
 */
public class EntryNode implements Node {
    private static final String READ_ONLY_PREFIX =
        "你是只读诊断 agent。你只能执行只读查询，不能修改任何数据。\n" +
        "请基于以下 skill 指令进行诊断分析。\n\n";

    private final SkillMeta skill;
    private final Map<String, Object> inputs;

    public EntryNode(SkillMeta skill, Map<String, Object> inputs) {
        this.skill = skill;
        this.inputs = inputs != null ? inputs : new HashMap<>();
    }

    @Override
    public String getName() { return "entry"; }

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        String systemPrompt = READ_ONLY_PREFIX + buildSkillSection(skill);
        String userMessage = buildUserMessage(inputs);

        return state
            .with("system.prompt", systemPrompt)
            .with("user.message", userMessage);
    }

    private String buildSkillSection(SkillMeta skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(skill.getName()).append("\n\n");
        sb.append("<skill_body>\n");
        sb.append(skill.getBody());
        sb.append("\n</skill_body>\n");
        return sb.toString();
    }

    private String buildUserMessage(Map<String, Object> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<user_inputs>\n");
        inputs.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        sb.append("</user_inputs>");
        return sb.toString();
    }
}
