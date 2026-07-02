package com.watsontech.snapagent.boot2x.tool;

import com.watsontech.snapagent.core.tool.ToolContext;
import com.watsontech.snapagent.core.tool.ToolProvider;
import com.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Locale;
import java.util.Map;

/**
 * {@link ToolProvider} implementation for read-only Redis operations.
 *
 * <p>Tool name: {@code redis_get}. Supports only {@code get} and {@code exists}
 * commands. Write commands (set, del, incr, etc.) and {@code keys} are rejected
 * (TDD_SPEC §UC-13, design doc 04 §3).</p>
 */
public class RedisReadToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisReadToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"redis_get\","
            + "\"description\":\"Read a Redis key.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{\"key\":{\"type\":\"string\"},"
            + "\"command\":{\"type\":\"string\",\"enum\":[\"get\",\"exists\"],\"default\":\"get\"}},"
            + "\"required\":[\"key\"]}}";

    private final RedisTemplate<String, ?> redisTemplate;

    public RedisReadToolProvider(RedisTemplate<String, ?> redisTemplate) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate must not be null");
        }
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String name() {
        return "redis_get";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String key = extractString(args, "key");
        if (key == null || key.isEmpty()) {
            return ToolResult.error("missing required parameter: key", elapsed(start));
        }

        String command = extractString(args, "command");
        if (command == null || command.isEmpty()) {
            command = "get";
        }
        command = command.toLowerCase(Locale.ROOT);

        // Reject write/scan commands
        if (!"get".equals(command) && !"exists".equals(command)) {
            log.warn("Redis command rejected (read-only): {}", command);
            return ToolResult.error(
                    "Redis command rejected (read-only): " + command, elapsed(start));
        }

        try {
            if ("get".equals(command)) {
                Object value = redisTemplate.opsForValue().get(key);
                String content = value != null ? String.valueOf(value) : "(nil)";
                return ToolResult.success(content, 1, elapsed(start));
            } else {
                // exists
                Boolean exists = redisTemplate.hasKey(key);
                boolean present = exists != null && exists;
                return ToolResult.success(String.valueOf(present), 1, elapsed(start));
            }
        } catch (RuntimeException e) {
            log.error("Redis operation failed: {}", e.getMessage());
            return ToolResult.error("Redis operation failed: " + e.getMessage(), elapsed(start));
        }
    }

    private String extractString(Map<String, Object> args, String fieldName) {
        if (args == null) {
            return null;
        }
        Object value = args.get(fieldName);
        if (value instanceof String) {
            return (String) value;
        }
        return value != null ? String.valueOf(value) : null;
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
