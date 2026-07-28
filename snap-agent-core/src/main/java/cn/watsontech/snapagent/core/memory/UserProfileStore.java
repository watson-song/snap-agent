package cn.watsontech.snapagent.core.memory;

/**
 * SPI for storing and retrieving user profiles (Layer 6 — Long-term Memory).
 *
 * <p>Implementations persist {@link UserProfile} per user ID.
 * The default {@link InMemoryUserProfileStore} keeps data in a
 * {@code ConcurrentHashMap}.</p>
 */
public interface UserProfileStore {

    /**
     * Load the user profile for the given user ID.
     *
     * @param userId the user identifier
     * @return the profile, or {@code null} if not found
     */
    UserProfile load(String userId);

    /**
     * Save (or overwrite) the user profile.
     *
     * @param userId  the user identifier
     * @param profile the profile to save
     */
    void save(String userId, UserProfile profile);
}
