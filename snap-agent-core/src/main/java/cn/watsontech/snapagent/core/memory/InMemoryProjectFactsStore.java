package cn.watsontech.snapagent.core.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link ProjectFactsStore} for testing and
 * single-node development. Not durable across restarts.
 */
public class InMemoryProjectFactsStore implements ProjectFactsStore {

    private final ConcurrentHashMap<String, List<ProjectFact>> store = new ConcurrentHashMap<>();

    @Override
    public List<ProjectFact> load(String projectId) {
        if (projectId == null) {
            return Collections.emptyList();
        }
        List<ProjectFact> facts = store.get(projectId);
        return facts != null ? new ArrayList<>(facts) : Collections.<ProjectFact>emptyList();
    }

    @Override
    public void save(String projectId, List<ProjectFact> facts) {
        if (projectId != null && facts != null) {
            store.put(projectId, new CopyOnWriteArrayList<>(facts));
        }
    }
}
