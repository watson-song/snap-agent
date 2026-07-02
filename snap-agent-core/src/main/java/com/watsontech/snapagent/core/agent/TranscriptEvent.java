package com.watsontech.snapagent.core.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single event in the task transcript (thought / tool_call / tool_result / done / error).
 *
 * <p>Immutable; created via static factory methods.</p>
 */
public final class TranscriptEvent {

    public static final String TYPE_THOUGHT = "thought";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_DONE = "done";
    public static final String TYPE_ERROR = "error";

    private final String type;
    private final String text;
    private final Map<String, Object> data;
    private final long timestamp;

    private TranscriptEvent(String type, String text, Map<String, Object> data, long timestamp) {
        this.type = type;
        this.text = text;
        this.data = data == null ? Collections.<String, Object>emptyMap() : data;
        this.timestamp = timestamp;
    }

    public static TranscriptEvent thought(String text) {
        return new TranscriptEvent(TYPE_THOUGHT, text, null, System.currentTimeMillis());
    }

    public static TranscriptEvent thought(String text, long timestamp) {
        return new TranscriptEvent(TYPE_THOUGHT, text, null, timestamp);
    }

    public static TranscriptEvent toolCall(String id, String name, Map<String, Object> args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put("args", args);
        return new TranscriptEvent(TYPE_TOOL_CALL, null, data, System.currentTimeMillis());
    }

    public static TranscriptEvent toolResult(String id, int rowCount, boolean truncated, long durationMs) {
        return toolResult(id, rowCount, truncated, durationMs, null, null);
    }

    public static TranscriptEvent toolResult(String id, int rowCount, boolean truncated,
                                             long durationMs, String contentPreview, String error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("rowCount", rowCount);
        data.put("truncated", truncated);
        data.put("durationMs", durationMs);
        if (contentPreview != null) {
            data.put("content", contentPreview);
        }
        if (error != null) {
            data.put("error", error);
        }
        return new TranscriptEvent(TYPE_TOOL_RESULT, null, data, System.currentTimeMillis());
    }

    public static TranscriptEvent done(String status, String report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("report", report);
        return new TranscriptEvent(TYPE_DONE, null, data, System.currentTimeMillis());
    }

    public static TranscriptEvent error(String message) {
        return new TranscriptEvent(TYPE_ERROR, message, null, System.currentTimeMillis());
    }

    public String getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "TranscriptEvent{type='" + type + "', text='" + text + "'}";
    }
}
