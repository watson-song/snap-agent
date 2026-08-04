package cn.watsontech.snapagent.core.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a domain concept parsed from a Markdown file with YAML frontmatter.
 *
 * <p>Example frontmatter:</p>
 * <pre>
 * ---
 * name: 调拨计划
 * tables: [drp_allocation_plan, drp_allocation_detail]
 * services: [AllocationPlanService]
 * entry_points: [ReplenishmentPlanTask.generate()]
 * related_concepts: [补货策略, 安全库存]
 * tags: [replenishment, allocation]
 * ---
 * </pre>
 *
 * <p>Immutable. All list/map fields return unmodifiable views.</p>
 */
public class DomainKnowledge {

    private final String name;
    private final String content;
    private final List<String> tables;
    private final List<String> services;
    private final List<String> entryPoints;
    private final List<String> relatedConcepts;
    private final List<String> tags;
    private final Map<String, Object> rawMetadata;

    public DomainKnowledge(String name, String content, List<String> tables,
                           List<String> services, List<String> entryPoints,
                           List<String> relatedConcepts, List<String> tags,
                           Map<String, Object> rawMetadata) {
        this.name = name;
        this.content = content;
        this.tables = tables == null ? new ArrayList<String>() : new ArrayList<String>(tables);
        this.services = services == null ? new ArrayList<String>() : new ArrayList<String>(services);
        this.entryPoints = entryPoints == null ? new ArrayList<String>() : new ArrayList<String>(entryPoints);
        this.relatedConcepts = relatedConcepts == null ? new ArrayList<String>() : new ArrayList<String>(relatedConcepts);
        this.tags = tags == null ? new ArrayList<String>() : new ArrayList<String>(tags);
        this.rawMetadata = rawMetadata == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(rawMetadata);
    }

    public String getName() { return name; }
    public String getContent() { return content; }
    public List<String> getTables() { return Collections.unmodifiableList(tables); }
    public List<String> getServices() { return Collections.unmodifiableList(services); }
    public List<String> getEntryPoints() { return Collections.unmodifiableList(entryPoints); }
    public List<String> getRelatedConcepts() { return Collections.unmodifiableList(relatedConcepts); }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public Map<String, Object> getRawMetadata() { return Collections.unmodifiableMap(rawMetadata); }

    /**
     * Convert to a summary string suitable for LLM context injection.
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("概念: ").append(name).append("\n");
        if (!tables.isEmpty()) {
            sb.append("涉及表: ").append(join(tables)).append("\n");
        }
        if (!services.isEmpty()) {
            sb.append("涉及类: ").append(join(services)).append("\n");
        }
        if (!entryPoints.isEmpty()) {
            sb.append("入口方法: ").append(join(entryPoints)).append("\n");
        }
        if (!relatedConcepts.isEmpty()) {
            sb.append("关联概念: ").append(join(relatedConcepts)).append("\n");
        }
        if (content != null && !content.isEmpty()) {
            sb.append("\n").append(content);
        }
        return sb.toString();
    }

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "DomainKnowledge{name='" + name + "', tables=" + tables.size()
                + ", services=" + services.size() + "}";
    }
}
