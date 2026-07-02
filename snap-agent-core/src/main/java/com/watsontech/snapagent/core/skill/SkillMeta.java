package com.watsontech.snapagent.core.skill;

import java.util.Collections;
import java.util.List;

/**
 * In-memory representation of a parsed skill.
 */
public final class SkillMeta {

    private final String name;
    private final String description;
    private final List<String> tools;
    private final List<InputSpec> inputs;
    private final String body;
    private final SkillAvailability availability;
    private final String unavailableReason;

    public SkillMeta(String name, String description, List<String> tools,
                     List<InputSpec> inputs, String body,
                     SkillAvailability availability, String unavailableReason) {
        this.name = name;
        this.description = description;
        this.tools = tools == null ? Collections.<String>emptyList() : tools;
        this.inputs = inputs == null ? Collections.<InputSpec>emptyList() : inputs;
        this.body = body;
        this.availability = availability;
        this.unavailableReason = unavailableReason;
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

    public String getBody() {
        return body;
    }

    public SkillAvailability getAvailability() {
        return availability;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    @Override
    public String toString() {
        return "SkillMeta{name='" + name + "', availability=" + availability + "'}";
    }
}
