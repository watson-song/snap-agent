package cn.watsontech.snapagent.core.patrol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An anomaly event detected during a patrol run.
 */
public class AnomalyEvent {

    private String type;
    private String source;
    private String message;
    private long timestamp;
    private Map<String, Object> metadata;
    private String skillName;
    private Map<String, String> inputs;

    public AnomalyEvent() {
        this.timestamp = System.currentTimeMillis();
        this.metadata = new LinkedHashMap<String, Object>();
        this.inputs = new LinkedHashMap<String, String>();
    }

    public AnomalyEvent(String type, String source, String message, String skillName,
                        Map<String, Object> metadata, Map<String, String> inputs) {
        this.type = type;
        this.source = source;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
        this.skillName = skillName;
        this.metadata = metadata == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(metadata);
        this.inputs = inputs == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(inputs);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(metadata);
    }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public Map<String, String> getInputs() { return inputs; }
    public void setInputs(Map<String, String> inputs) {
        this.inputs = inputs == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(inputs);
    }

    @Override
    public String toString() {
        return "AnomalyEvent{type='" + type + "', source='" + source
                + "', message='" + message + "'}";
    }
}
