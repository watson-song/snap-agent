package com.watsontech.snapagent.core.skill;

/**
 * A quick-action button shown in the UI for a skill.
 *
 * <p>When clicked, the {@code message} is sent to the agent as if the user
 * typed it in the message bar. This provides one-click access to the most
 * common operations for a skill.</p>
 */
public final class Shortcut {

    private final String label;
    private final String message;

    public Shortcut(String label, String message) {
        this.label = label;
        this.message = message;
    }

    public String getLabel() {
        return label;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "Shortcut{label='" + label + "', message='" + message + "'}";
    }
}
