package cn.watsontech.snapagent.core.agent;

import cn.watsontech.snapagent.core.skill.SkillMeta;

/**
 * System prompt extension point.
 *
 * <p>Called by {@link AgentExecutor} while assembling the system prompt, allowing
 * implementations to append contextual information (e.g. project structure summary).</p>
 *
 * <p>This is the forward-compatible foundation for v0.7's KnowledgeInjector:
 * v0.7 will extend it to per-question dynamic retrieval, while v0.3 does
 * startup-time static injection only.</p>
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
