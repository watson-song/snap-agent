package cn.watsontech.snapagent.core.memory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link UserProfileStore} for testing and
 * single-node development. Not durable across restarts.
 */
public class InMemoryUserProfileStore implements UserProfileStore {

    private final ConcurrentHashMap<String, UserProfile> store = new ConcurrentHashMap<>();

    @Override
    public UserProfile load(String userId) {
        if (userId == null) {
            return null;
        }
        return store.get(userId);
    }

    @Override
    public void save(String userId, UserProfile profile) {
        if (userId != null && profile != null) {
            store.put(userId, profile);
        }
    }
}
