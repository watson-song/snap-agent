package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link ChatMemoryRepository} for testing and
 * single-node development. Not durable across restarts.
 */
public class InMemoryChatMemoryRepository implements ChatMemoryRepository {

    private final ConcurrentHashMap<String, List<Message>> store = new ConcurrentHashMap<>();

    @Override
    public void save(String conversationId, List<Message> messages) {
        if (conversationId == null) {
            return;
        }
        store.put(conversationId, new CopyOnWriteArrayList<>(messages));
    }

    @Override
    public List<Message> load(String conversationId) {
        if (conversationId == null) {
            return Collections.emptyList();
        }
        List<Message> messages = store.get(conversationId);
        return messages != null ? new ArrayList<>(messages) : Collections.<Message>emptyList();
    }

    @Override
    public void delete(String conversationId) {
        if (conversationId != null) {
            store.remove(conversationId);
        }
    }

    /**
     * Returns the number of stored conversations (for testing).
     */
    public int size() {
        return store.size();
    }
}
