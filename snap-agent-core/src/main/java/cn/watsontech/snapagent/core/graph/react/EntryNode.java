package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

/**
 * EntryNode builds the system prompt and user message from skill body + task inputs.
 *
 * <p>The system prompt follows a layered structure aligned with the 7-layer
 * Context Stack pattern:</p>
 * <ol>
 *   <li><b>Instructions</b> — read-only guardrail + skill body (wrapped in
 *       {@code <skill_body>} tags for prompt injection defense)</li>
 *   <li><b>Output Format</b> — if the skill declares an {@code output-format}
 *       in frontmatter, it is appended as an explicit output schema section</li>
 * </ol>
 *
 * <p>The user message is built from task inputs wrapped in {@code <user_inputs>}
 * tags. Retrieved facts (RAG) and conversation history are injected by
 * advisors and the AgentNode respectively, keeping each concern separated.</p>
 */
public class EntryNode implements Node {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String READ_ONLY_PREFIX =
        "你是只读诊断 agent。你只能执行只读查询，不能修改任何数据。\n" +
        "请基于以下 skill 指令进行诊断分析。\n\n";

    private static final String OUTPUT_FORMAT_HEADER =
        "\n\n<output_format>\n";

    private static final String OUTPUT_FORMAT_FOOTER =
        "\n</output_format>\n";

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
        String systemPrompt = buildSystemPrompt(skill);
        String userMessage = buildUserMessage(inputs);

        return state
            .with(StateKeys.SYSTEM_PROMPT, systemPrompt)
            .with(StateKeys.USER_MESSAGE, userMessage);
    }

    /**
     * Build the system prompt: read-only guardrail (when mode=READ_ONLY) +
     * skill body + output format directive (if declared).
     */
    private String buildSystemPrompt(SkillMeta skill) {
        StringBuilder sb = new StringBuilder();
        if (skill.getMode() != SkillMode.READ_WRITE) {
            sb.append(READ_ONLY_PREFIX);
        }
        sb.append(buildSkillSection(skill));
        // Layer 7: Output Format — lock the answer structure
        String fmt = skill.getOutputFormat();
        if (fmt != null && !fmt.isEmpty()) {
            sb.append(OUTPUT_FORMAT_HEADER);
            sb.append(fmt);
            sb.append(OUTPUT_FORMAT_FOOTER);
        }
        return sb.toString();
    }

    private String buildSkillSection(SkillMeta skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(skill.getName()).append("\n\n");
        sb.append("<skill_body>\n");
        sb.append(skill.getBody());
        sb.append("\n</skill_body>\n");
        return sb.toString();
    }

    /**
     * Build the user message from task inputs as JSON inside
     * {@code <user_inputs>} tags. JSON format supports nested objects,
     * lists, and typed values (Layer 2 — User Input).
     */
    private String buildUserMessage(Map<String, Object> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<user_inputs>\n");
        try {
            sb.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(inputs));
        } catch (Exception e) {
            // Fallback: simple key=value format if JSON serialization fails
            inputs.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        }
        sb.append("\n</user_inputs>");
        return sb.toString();
    }
}
