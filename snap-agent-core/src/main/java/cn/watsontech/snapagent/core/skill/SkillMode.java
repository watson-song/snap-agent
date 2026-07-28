package cn.watsontech.snapagent.core.skill;

/**
 * Declares whether a skill is restricted to read-only operations
 * or can perform write actions.
 *
 * <p>When {@code READ_ONLY}, the {@code EntryNode} prepends a read-only
 * guardrail to the system prompt, instructing the LLM not to modify data.
 * When {@code READ_WRITE}, the guardrail is omitted — the skill may
 * use write-capable tools (file edit, auto-fix, etc.).</p>
 */
public enum SkillMode {
    /** Read-only diagnostic skill (default). Cannot modify data. */
    READ_ONLY,
    /** Write-capable skill. May use file edit, auto-fix, or other write tools. */
    READ_WRITE
}
