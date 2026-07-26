package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Locale;

/**
 * 2.x read-only Redis tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code RedisReadToolProvider}. Supports only
 * {@code get} and {@code exists} commands. Write commands are rejected.</p>
 */
public class RedisReadTools {

    private static final Logger log = LoggerFactory.getLogger(RedisReadTools.class);

    private final RedisTemplate<String, ?> redisTemplate;

    public RedisReadTools(RedisTemplate<String, ?> redisTemplate) {
        if (redisTemplate == null) throw new IllegalArgumentException("redisTemplate must not be null");
        this.redisTemplate = redisTemplate;
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "redis_get", description = "Read a Redis key. Supports get and exists commands only (read-only).")
    public String get(
            @ToolParam(description = "Redis key to read") String key,
            @ToolParam(description = "Command: get or exists (default get)", required = false) String command) {
        String cmd = (command == null || command.isEmpty()) ? "get" : command.toLowerCase(Locale.ROOT);

        if (!"get".equals(cmd) && !"exists".equals(cmd)) {
            log.warn("Redis command rejected (read-only): {}", cmd);
            return "Error: Redis command rejected (read-only): " + cmd;
        }

        try {
            if ("get".equals(cmd)) {
                Object value = redisTemplate.opsForValue().get(key);
                return value != null ? String.valueOf(value) : "(nil)";
            } else {
                Boolean exists = redisTemplate.hasKey(key);
                boolean present = exists != null && exists;
                return String.valueOf(present);
            }
        } catch (RuntimeException e) {
            log.error("Redis operation failed: {}", e.getMessage());
            return "Error: Redis operation failed: " + e.getMessage();
        }
    }
}
