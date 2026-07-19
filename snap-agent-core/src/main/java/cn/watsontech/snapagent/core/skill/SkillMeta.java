package cn.watsontech.snapagent.core.skill;

import java.util.Collections;
import java.util.List;

/**
 * In-memory representation of a parsed skill.
 *
 * <p>Each skill has a {@code source} ("builtin" or "custom") indicating whether
 * it came from the classpath (bundled in the JAR) or the upload directory
 * (filesystem). When a custom skill shadows a builtin with the same name,
 * {@code overridesBuiltin} is {@code true}.</p>
 */
public final class SkillMeta {

    private final String name;
    private final String description;
    private final List<String> tools;
    private final List<InputSpec> inputs;
    private final List<Shortcut> shortcuts;
    private final String body;
    private final SkillAvailability availability;
    private final String unavailableReason;
    private final String source;
    private final boolean overridesBuiltin;
    private final String requiredPermission;

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, String body,
                     SkillAvailability availability, String unavailableReason) {
        this(name, description, tools, inputs, Collections.<Shortcut>emptyList(), body,
                availability, unavailableReason, "custom", false, "");
    }

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, List<Shortcut> shortcuts, String body,
                     SkillAvailability availability, String unavailableReason,
                     String source, boolean overridesBuiltin) {
        this(name, description, tools, inputs, shortcuts, body,
                availability, unavailableReason, source, overridesBuiltin, "");
    }

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, List<Shortcut> shortcuts, String body,
                     SkillAvailability availability, String unavailableReason,
                     String source, boolean overridesBuiltin,
                     String requiredPermission) {
        this.name = name;
        this.description = description;
        this.tools = tools == null ? Collections.<String>emptyList() : tools;
        this.inputs = inputs == null ? Collections.<InputSpec>emptyList() : inputs;
        this.shortcuts = shortcuts == null ? Collections.<Shortcut>emptyList() : shortcuts;
        this.body = body;
        this.availability = availability;
        this.unavailableReason = unavailableReason;
        this.source = source;
        this.overridesBuiltin = overridesBuiltin;
        this.requiredPermission = requiredPermission != null ? requiredPermission : "";
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getTools() {
        return tools;
    }

    public List<InputSpec> getInputs() {
        return inputs;
    }

    public List<Shortcut> getShortcuts() {
        return shortcuts;
    }

    public String getBody() {
        return body;
    }

    public SkillAvailability getAvailability() {
        return availability;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public String getSource() {
        return source;
    }

    public boolean isOverridesBuiltin() {
        return overridesBuiltin;
    }

    /**
     * Returns the permission code required to run this skill.
     * Empty string means no skill-level permission is declared;
     * the global {@code snap-agent.security.required-permission} applies instead.
     */
    public String getRequiredPermission() {
        return requiredPermission;
    }

    /** Returns a copy with the given source. */
    public SkillMeta withSource(String source) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                availability, unavailableReason, source, overridesBuiltin, requiredPermission);
    }

    /** Returns a copy with overridesBuiltin set. */
    public SkillMeta withOverridesBuiltin(boolean overrides) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                availability, unavailableReason, source, overrides, requiredPermission);
    }

    /** Returns a copy with the given required permission. */
    public SkillMeta withRequiredPermission(String requiredPermission) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                availability, unavailableReason, source, overridesBuiltin, requiredPermission);
    }

    @Override
    public String toString() {
        return "SkillMeta{name='" + name + "', source=" + source
                + ", availability=" + availability + "}";
    }
}
