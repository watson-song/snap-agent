package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.SystemPromptExtender;
import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.skill.SkillMeta;

import java.util.List;
import java.util.Map;

/**
 * {@link SystemPromptExtender} that retrieves business knowledge fragments
 * relevant to the user's question and injects them into the system prompt.
 *
 * <p>At runtime, for each task execution, the injector:</p>
 * <ol>
 *   <li>Builds a query from the task's input values (concatenated).</li>
 *   <li>Searches the {@link KnowledgeBase} for the top-K most relevant
 *       fragments above a minimum score threshold.</li>
 *   <li>Formats matching fragments into a Markdown section appended to the
 *       system prompt, so the LLM can ground its answer in business context.</li>
 * </ol>
 *
 * <p>If no fragments match, returns an empty string (no prompt bloat).</p>
 */
public class KnowledgeInjector implements SystemPromptExtender {

    private final KnowledgeBase knowledgeBase;
    private final int maxFragments;
    private final double minScore;

    public KnowledgeInjector(KnowledgeBase knowledgeBase, int maxFragments, double minScore) {
        this.knowledgeBase = knowledgeBase;
        this.maxFragments = maxFragments;
        this.minScore = minScore;
    }

    @Override
    public String extend(SkillMeta skill, AgentTask task) {
        String query = buildQuery(task);
        if (query == null || query.isEmpty()) {
            return "";
        }

        List<KnowledgeFragment> fragments = knowledgeBase.search(query, maxFragments, minScore);
        if (fragments.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n## 业务知识参考 (来自知识库)\n");
        sb.append("以下是与当前问题相关的业务知识片段:\n\n");
        for (int i = 0; i < fragments.size(); i++) {
            KnowledgeFragment f = fragments.get(i);
            sb.append("### 知识片段 ").append(i + 1).append(": ")
              .append(f.getTitle()).append("\n");
            sb.append("> 来源: ").append(f.getSource()).append("\n\n");
            sb.append(f.getContent()).append("\n\n");
        }
        sb.append("请结合以上业务知识回答用户问题。\n");
        return sb.toString();
    }

    /**
     * Builds the search query from the task's input values.
     * Concatenates all input values into a single query string.
     */
    private String buildQuery(AgentTask task) {
        if (task == null) {
            return null;
        }
        Map<String, String> inputs = task.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : inputs.values()) {
            if (value != null && !value.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }
}
