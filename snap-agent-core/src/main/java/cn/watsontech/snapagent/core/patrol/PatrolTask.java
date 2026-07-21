package cn.watsontech.snapagent.core.patrol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A scheduled patrol task that periodically runs a skill to check for anomalies.
 */
public class PatrolTask {

    private String id;
    private String skillName;
    private String cron;
    private String userId;
    private boolean enabled;
    private Map<String, String> inputs;
    private String alertKeywords;

    public PatrolTask() {
        this.enabled = true;
        this.inputs = new LinkedHashMap<String, String>();
    }

    public PatrolTask(String id, String skillName, String cron, String userId,
                      Map<String, String> inputs) {
        this(id, skillName, cron, userId, inputs, null);
    }

    public PatrolTask(String id, String skillName, String cron, String userId,
                      Map<String, String> inputs, String alertKeywords) {
        this.id = id;
        this.skillName = skillName;
        this.cron = cron;
        this.userId = userId;
        this.enabled = true;
        this.inputs = inputs == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(inputs);
        this.alertKeywords = alertKeywords;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, String> getInputs() { return inputs; }
    public void setInputs(Map<String, String> inputs) {
        this.inputs = inputs == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(inputs);
    }

    /** Comma-separated keywords; if the patrol report contains any, an anomaly is flagged. */
    public String getAlertKeywords() { return alertKeywords; }
    public void setAlertKeywords(String alertKeywords) { this.alertKeywords = alertKeywords; }

    @Override
    public String toString() {
        return "PatrolTask{id='" + id + "', skillName='" + skillName
                + "', cron='" + cron + "', enabled=" + enabled
                + ", alertKeywords='" + alertKeywords + "'}";
    }
}
