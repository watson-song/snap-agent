package cn.watsontech.snapagent.core.domain;

import java.util.List;

/**
 * Domain knowledge index SPI.
 *
 * <p>Provides lookup interfaces over a collection of {@link DomainKnowledge} entries.
 * Supports forward lookup (by concept name) and reverse lookup (by table or service name).</p>
 */
public interface DomainKnowledgeIndex {

    /**
     * Find a domain concept by its exact name.
     *
     * @param conceptName concept name (e.g. "调拨计划")
     * @return the concept, or null if not found
     */
    DomainKnowledge findByName(String conceptName);

    /**
     * Find all concepts that reference the given database table.
     *
     * @param tableName table name (e.g. "drp_allocation_plan")
     * @return list of matching concepts (may be empty, never null)
     */
    List<DomainKnowledge> findByTable(String tableName);

    /**
     * Find all concepts that reference the given Java service class.
     *
     * @param serviceName class simple name (e.g. "AllocationPlanService")
     * @return list of matching concepts (may be empty, never null)
     */
    List<DomainKnowledge> findByService(String serviceName);

    /**
     * Get all registered domain concepts.
     *
     * @return unmodifiable list of all concepts
     */
    List<DomainKnowledge> findAll();

    /**
     * Total number of registered concepts.
     */
    int size();

    /**
     * Add or replace a domain concept in the index.
     *
     * @param knowledge the concept to add
     */
    void put(DomainKnowledge knowledge);

    /**
     * Clear all concepts from the index.
     */
    void clear();
}
