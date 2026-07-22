package cn.watsontech.snapagent.boot2x.anchor;

/**
 * Request parameters for anchor content injection.
 *
 * <p>Carries the anchor name, page URL, skill/workflow identifier,
 * and cache TTL from the frontend to the backend orchestrator.</p>
 */
public class InjectionRequest {

    private final String anchorName;
    private final String pageUrl;
    private final String skillId;
    private final String workflowId;
    private final int cacheTtl;

    public InjectionRequest(String anchorName, String pageUrl,
                           String skillId, String workflowId, int cacheTtl) {
        this.anchorName = anchorName;
        this.pageUrl = pageUrl;
        this.skillId = skillId;
        this.workflowId = workflowId;
        this.cacheTtl = cacheTtl;
    }

    /** Parse from a Map (typically deserialized from JSON request body). */
    public static InjectionRequest fromMap(java.util.Map<String, Object> map) {
        if (map == null) return null;
        String anchorName = str(map.get("anchorName"));
        String pageUrl = str(map.get("pageUrl"));
        String skillId = str(map.get("skillId"));
        String workflowId = str(map.get("workflowId"));
        int cacheTtl = 3600;
        Object ttlObj = map.get("cacheTtl");
        if (ttlObj instanceof Number) {
            cacheTtl = ((Number) ttlObj).intValue();
        }
        return new InjectionRequest(anchorName, pageUrl, skillId, workflowId, cacheTtl);
    }

    private static String str(Object obj) {
        return obj instanceof String ? (String) obj : null;
    }

    public String getAnchorName() { return anchorName; }
    public String getPageUrl() { return pageUrl; }
    public String getSkillId() { return skillId; }
    public String getWorkflowId() { return workflowId; }
    public int getCacheTtl() { return cacheTtl; }

    /** Returns true if at least one source (skillId or workflowId) is specified. */
    public boolean hasSource() {
        return (skillId != null && !skillId.isEmpty())
                || (workflowId != null && !workflowId.isEmpty());
    }

    /** Returns the non-null source identifier (skillId preferred). */
    public String getSourceId() {
        return skillId != null && !skillId.isEmpty() ? skillId : workflowId;
    }
}
