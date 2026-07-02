package com.watsontech.snapagent.core.skill;

/**
 * Three-state availability of a skill.
 *
 * <ul>
 *   <li>{@link #AVAILABLE} — frontmatter valid and all declared tools are registered.</li>
 *   <li>{@link #UNAVAILABLE} — frontmatter valid but one or more declared tools are missing.</li>
 *   <li>{@link #INVALID} — frontmatter is malformed (missing required fields or invalid inputs).</li>
 * </ul>
 */
public enum SkillAvailability {
    AVAILABLE,
    UNAVAILABLE,
    INVALID
}
