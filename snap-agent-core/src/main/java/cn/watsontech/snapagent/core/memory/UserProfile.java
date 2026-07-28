package cn.watsontech.snapagent.core.memory;

import java.util.Collections;
import java.util.List;

/**
 * Immutable user profile for long-term memory (Layer 6).
 *
 * <p>Stores stable user preferences: preferred language, output style,
 * and frequently-queried services.</p>
 */
public final class UserProfile {

    private final String userId;
    private final String language;
    private final String outputStyle;
    private final List<String> frequentServices;

    public UserProfile(String userId, String language, String outputStyle,
                       List<String> frequentServices) {
        this.userId = userId;
        this.language = language;
        this.outputStyle = outputStyle;
        this.frequentServices = frequentServices == null || frequentServices.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.<String>unmodifiableList(frequentServices);
    }

    public String getUserId() { return userId; }
    public String getLanguage() { return language; }
    public String getOutputStyle() { return outputStyle; }
    public List<String> getFrequentServices() { return frequentServices; }
}
