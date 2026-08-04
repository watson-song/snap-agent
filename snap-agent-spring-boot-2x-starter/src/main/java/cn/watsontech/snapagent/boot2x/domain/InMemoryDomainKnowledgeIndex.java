package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link DomainKnowledgeIndex}.
 *
 * <p>Maintains three maps for fast lookup:</p>
 * <ul>
 *   <li>name → DomainKnowledge (exact match)</li>
 *   <li>tableName → list of DomainKnowledge (reverse lookup)</li>
 *   <li>serviceName → list of DomainKnowledge (reverse lookup)</li>
 * </ul>
 */
public class InMemoryDomainKnowledgeIndex implements DomainKnowledgeIndex {

    private final Map<String, DomainKnowledge> byName = new ConcurrentHashMap<String, DomainKnowledge>();
    private final Map<String, List<DomainKnowledge>> byTable = new ConcurrentHashMap<String, List<DomainKnowledge>>();
    private final Map<String, List<DomainKnowledge>> byService = new ConcurrentHashMap<String, List<DomainKnowledge>>();

    @Override
    public DomainKnowledge findByName(String conceptName) {
        if (conceptName == null || conceptName.isEmpty()) return null;
        // Exact match first
        DomainKnowledge exact = byName.get(conceptName);
        if (exact != null) return exact;
        // Case-insensitive fallback
        for (Map.Entry<String, DomainKnowledge> entry : byName.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(conceptName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public List<DomainKnowledge> findByTable(String tableName) {
        if (tableName == null || tableName.isEmpty()) return Collections.emptyList();
        List<DomainKnowledge> result = byTable.get(tableName.toLowerCase());
        return result != null ? Collections.unmodifiableList(result) : Collections.<DomainKnowledge>emptyList();
    }

    @Override
    public List<DomainKnowledge> findByService(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) return Collections.emptyList();
        List<DomainKnowledge> result = byService.get(serviceName.toLowerCase());
        return result != null ? Collections.unmodifiableList(result) : Collections.<DomainKnowledge>emptyList();
    }

    @Override
    public List<DomainKnowledge> findAll() {
        return Collections.unmodifiableList(new ArrayList<DomainKnowledge>(byName.values()));
    }

    @Override
    public int size() {
        return byName.size();
    }

    @Override
    public void put(DomainKnowledge knowledge) {
        if (knowledge == null || knowledge.getName() == null) return;
        byName.put(knowledge.getName(), knowledge);

        // Build reverse index for tables
        for (String table : knowledge.getTables()) {
            addToMultiMap(byTable, table.toLowerCase(), knowledge);
        }

        // Build reverse index for services
        for (String service : knowledge.getServices()) {
            addToMultiMap(byService, service.toLowerCase(), knowledge);
        }
    }

    @Override
    public void clear() {
        byName.clear();
        byTable.clear();
        byService.clear();
    }

    private void addToMultiMap(Map<String, List<DomainKnowledge>> map, String key, DomainKnowledge value) {
        List<DomainKnowledge> list = map.get(key);
        if (list == null) {
            list = new ArrayList<DomainKnowledge>();
            map.put(key, list);
        }
        // Avoid duplicates
        if (!list.contains(value)) {
            list.add(value);
        }
    }
}
