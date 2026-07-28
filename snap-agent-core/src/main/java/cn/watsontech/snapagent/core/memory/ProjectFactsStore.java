package cn.watsontech.snapagent.core.memory;

import java.util.List;

/**
 * SPI for storing and retrieving project-level stable facts
 * (Layer 6 — Long-term Memory).
 *
 * <p>Implementations persist {@link ProjectFact} lists per project ID.
 * The default {@link InMemoryProjectFactsStore} keeps data in a
 * {@code ConcurrentHashMap}.</p>
 */
public interface ProjectFactsStore {

    /**
     * Load project facts for the given project ID.
     *
     * @param projectId the project identifier
     * @return list of facts (empty if not found)
     */
    List<ProjectFact> load(String projectId);

    /**
     * Save (or overwrite) the project facts.
     *
     * @param projectId the project identifier
     * @param facts     the facts to save
     */
    void save(String projectId, List<ProjectFact> facts);
}
