package cn.watsontech.snapagent.client.dto;

import java.util.Map;

public class TranscriptEventDto {
    private String type;
    private String text;
    private Map<String, Object> data;
    private long timestamp;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
