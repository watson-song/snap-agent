package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConcurrentHashMap-based ToolCallbackRegistry implementation.
 * Thread-safe: register/unregister/find use ConcurrentHashMap.
 * System tools (isSystem()==true) cannot be unregistered.
 */
public class ToolCallbackRegistryImpl implements ToolCallbackRegistry {
    private final ConcurrentHashMap<String, ToolCallback> callbacks = new ConcurrentHashMap<>();

    @Override
    public void register(ToolCallback callback) {
        if (callback == null || callback.getName() == null) {
            throw new IllegalArgumentException("callback and name must not be null");
        }
        String name = callback.getName();
        ToolCallback existing = callbacks.putIfAbsent(name, callback);
        if (existing != null) {
            throw new IllegalArgumentException("tool already registered: " + name);
        }
    }

    @Override
    public void unregister(String toolName) {
        ToolCallback callback = callbacks.get(toolName);
        if (callback == null) {
            return;
        }
        if (callback.isSystem()) {
            throw new UnsupportedOperationException("cannot unregister system tool: " + toolName);
        }
        // Atomic check-and-remove: only removes if the value is still the same callback
        callbacks.remove(toolName, callback);
    }

    @Override
    public List<ToolCallback> getAll() {
        return new ArrayList<>(callbacks.values());
    }

    @Override
    public ToolCallback find(String toolName) {
        return callbacks.get(toolName);
    }

    @Override
    public String toToolDefinitionsJson() {
        StringBuilder sb = new StringBuilder("[");
        List<ToolCallback> all = getAll();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append(",");
            ToolCallback cb = all.get(i);
            sb.append("{\"name\":\"").append(escapeJson(cb.getName())).append("\"");
            sb.append(",\"description\":\"").append(escapeJson(cb.getDescription())).append("\"");
            sb.append(",\"input_schema\":").append(cb.getJsonSchema());
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public ToolCallbackRegistry subset(Map<String, String> pluginOverrides) {
        if (pluginOverrides == null || pluginOverrides.isEmpty()) {
            return this;
        }
        ToolCallbackRegistryImpl sub = new ToolCallbackRegistryImpl();
        for (Map.Entry<String, String> entry : pluginOverrides.entrySet()) {
            String toolName = entry.getValue();
            ToolCallback cb = callbacks.get(toolName);
            if (cb == null) {
                throw new IllegalArgumentException("tool not found in registry: " + toolName);
            }
            sub.callbacks.put(toolName, cb);
        }
        return sub;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
