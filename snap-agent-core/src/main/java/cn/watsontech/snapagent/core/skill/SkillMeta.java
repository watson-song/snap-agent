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
    private final String outputFormat;
    private final SkillAvailability availability;
    private final String unavailableReason;
    private final String source;
    private final boolean overridesBuiltin;
    private final String requiredPermission;
    private final SkillMode mode;

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, String body,
                     SkillAvailability availability, String unavailableReason) {
        this(name, description, tools, inputs, Collections.<Shortcut>emptyList(), body,
                "", availability, unavailableReason, "custom", false, "", SkillMode.READ_ONLY);
    }

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, List<Shortcut> shortcuts, String body,
                     SkillAvailability availability, String unavailableReason,
                     String source, boolean overridesBuiltin) {
        this(name, description, tools, inputs, shortcuts, body, "",
                availability, unavailableReason, source, overridesBuiltin, "", SkillMode.READ_ONLY);
    }

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, List<Shortcut> shortcuts, String body,
                     SkillAvailability availability, String unavailableReason,
                     String source, boolean overridesBuiltin,
                     String requiredPermission) {
        this(name, description, tools, inputs, shortcuts, body, "",
                availability, unavailableReason, source, overridesBuiltin, requiredPermission, SkillMode.READ_ONLY);
    }

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, List<Shortcut> shortcuts, String body,
                     String outputFormat,
                     SkillAvailability availability, String unavailableReason,
                     String source, boolean overridesBuiltin,
                     String requiredPermission) {
        this(name, description, tools, inputs, shortcuts, body, outputFormat,
                availability, unavailableReason, source, overridesBuiltin, requiredPermission, SkillMode.READ_ONLY);
    }

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, List<Shortcut> shortcuts, String body,
                     String outputFormat,
                     SkillAvailability availability, String unavailableReason,
                     String source, boolean overridesBuiltin,
                     String requiredPermission, SkillMode mode) {
        this.name = name;
        this.description = description;
        this.tools = tools;  // Preserve null to distinguish "all tools" from "no tools"
        this.inputs = inputs == null ? Collections.<InputSpec>emptyList() : inputs;
        this.shortcuts = shortcuts == null ? Collections.<Shortcut>emptyList() : shortcuts;
        this.body = body;
        this.outputFormat = outputFormat != null ? outputFormat : "";
        this.availability = availability;
        this.unavailableReason = unavailableReason;
        this.source = source;
        this.overridesBuiltin = overridesBuiltin;
        this.requiredPermission = requiredPermission != null ? requiredPermission : "";
        this.mode = mode != null ? mode : SkillMode.READ_ONLY;
    }

    /**
     * Builder for constructing SkillMeta instances.
     * <p>Replaces the 5 overloaded constructors with a fluent API.
     * All fields have sensible defaults; only {@code name} is required.</p>
     *
     * <pre>{@code
     * SkillMeta meta = SkillMeta.builder()
     *     .name("my-skill")
     *     .description("Does something useful")
     *     .tools(Arrays.asList("tool1", "tool2"))
     *     .mode(SkillMode.READ_WRITE)
     *     .build();
     * }</pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description;
        private List<String> tools;
        private List<InputSpec> inputs = Collections.emptyList();
        private List<Shortcut> shortcuts = Collections.emptyList();
        private String body;
        private String outputFormat = "";
        private SkillAvailability availability = SkillAvailability.AVAILABLE;
        private String unavailableReason;
        private String source = "custom";
        private boolean overridesBuiltin;
        private String requiredPermission = "";
        private SkillMode mode = SkillMode.READ_ONLY;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder tools(List<String> tools) { this.tools = tools; return this; }
        public Builder inputs(List<InputSpec> inputs) { this.inputs = inputs; return this; }
        public Builder shortcuts(List<Shortcut> shortcuts) { this.shortcuts = shortcuts; return this; }
        public Builder body(String body) { this.body = body; return this; }
        public Builder outputFormat(String outputFormat) { this.outputFormat = outputFormat; return this; }
        public Builder availability(SkillAvailability availability) { this.availability = availability; return this; }
        public Builder unavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder overridesBuiltin(boolean overridesBuiltin) { this.overridesBuiltin = overridesBuiltin; return this; }
        public Builder requiredPermission(String requiredPermission) { this.requiredPermission = requiredPermission; return this; }
        public Builder mode(SkillMode mode) { this.mode = mode; return this; }

        public SkillMeta build() {
            return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                    outputFormat, availability, unavailableReason, source,
                    overridesBuiltin, requiredPermission, mode);
        }
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

    /**
     * Returns the output format directive for this skill, or empty string
     * if none is declared. When non-empty, the prompt builder appends it
     * to the system prompt as an output schema/example section.
     */
    public String getOutputFormat() {
        return outputFormat;
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

    /**
     * Returns the access mode for this skill.
     * {@code READ_ONLY} (default) restricts the agent to diagnostic queries.
     * {@code READ_WRITE} allows file modification, auto-fix, and other write actions.
     */
    public SkillMode getMode() {
        return mode;
    }

    /** Returns a copy with the given source. */
    public SkillMeta withSource(String source) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                outputFormat, availability, unavailableReason, source, overridesBuiltin, requiredPermission, mode);
    }

    /** Returns a copy with overridesBuiltin set. */
    public SkillMeta withOverridesBuiltin(boolean overrides) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                outputFormat, availability, unavailableReason, source, overrides, requiredPermission, mode);
    }

    /** Returns a copy with the given required permission. */
    public SkillMeta withRequiredPermission(String requiredPermission) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                outputFormat, availability, unavailableReason, source, overridesBuiltin, requiredPermission, mode);
    }

    /** Returns a copy with the given mode. */
    public SkillMeta withMode(SkillMode mode) {
        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                outputFormat, availability, unavailableReason, source, overridesBuiltin, requiredPermission, mode);
    }

    @Override
    public String toString() {
        return "SkillMeta{name='" + name + "', source=" + source
                + ", availability=" + availability + "}";
    }
}
