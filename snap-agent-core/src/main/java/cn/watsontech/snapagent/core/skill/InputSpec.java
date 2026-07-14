package cn.watsontech.snapagent.core.skill;

import java.util.Collections;
import java.util.List;

/**
 * Specification of a single skill input parameter.
 *
 * <p>Valid types: {@code string}, {@code enum}, {@code date}, {@code number}, {@code boolean}.
 * For {@code enum} type, {@link #options} must be non-empty.</p>
 */
public final class InputSpec {

    private final String key;
    private final String label;
    private final boolean required;
    private final String type;
    private final List<String> options;
    private final String defaultValue;

    public InputSpec(String key, String label, boolean required, String type,
                     List<String> options, String defaultValue) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.type = type;
        this.options = options == null ? Collections.<String>emptyList() : options;
        this.defaultValue = defaultValue;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }

    public String getType() {
        return type;
    }

    public List<String> getOptions() {
        return options;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    @Override
    public String toString() {
        return "InputSpec{key='" + key + "', label='" + label + "', required=" + required
                + ", type='" + type + "'}";
    }
}
