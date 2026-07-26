package cn.watsontech.snapagent.core.agent;

import cn.watsontech.snapagent.core.skill.SkillMeta;

/**
 * System prompt extension point.
 *
 * <p>Called by {@link AgentExecutor} while assembling the system prompt, allowing
 * implementations to append contextual information (e.g. project structure summary).</p>
 *
 * <p>Compatibility interface retained from 1.x. The 2.x primary path uses
 * {@code Advisor} chain with {@code AdvisorNode}.</p>
 */
public interface SystemPromptExtender {

    /**
     * Context text to append to the end of the system prompt.
     *
     * @param skill the skill currently being executed
     * @param task  the current task
     * @return context text (may be empty string, never null)
     */
    String extend(SkillMeta skill, AgentTask task);
}
