package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisReadToolProvider}.
 *
 * <p>Covers GET existing/missing key, EXISTS, and KEYS rejection
 * (TDD_SPEC §UC-13).</p>
 */
class RedisReadToolProviderTest {

    @SuppressWarnings("unchecked")
    private RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
    private RedisReadToolProvider provider;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        provider = new RedisReadToolProvider(redisTemplate);
    }

    @Test
    void shouldReturnNameRedisGet() {
        assertThat(provider.name()).isEqualTo("redis_get");
    }

    @Test
    void shouldReturnSchemaContainingKeyProperty() {
        String schema = provider.schema();

        assertThat(schema).contains("redis_get");
        assertThat(schema).contains("key");
        assertThat(schema).contains("required");
    }

    @Test
    void shouldReturnValueWhenKeyExists() {
        when(valueOps.get("foo")).thenReturn("bar");

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "foo");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("bar");
    }

    @Test
    void shouldReturnNilWhenKeyDoesNotExist() {
        when(valueOps.get("notexist")).thenReturn(null);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "notexist");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("(nil)");
    }

    @Test
    void shouldReturnTrueWhenExistsCommandAndKeyPresent() {
        when(redisTemplate.hasKey("foo")).thenReturn(true);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "foo");
        args.put("command", "exists");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("true");
    }

    @Test
    void shouldReturnFalseWhenExistsCommandAndKeyAbsent() {
        when(redisTemplate.hasKey("missing")).thenReturn(false);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "missing");
        args.put("command", "exists");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("false");
    }

    @Test
    void shouldRejectWhenCommandIsKeys() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "*");
        args.put("command", "keys");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("keys");
    }

    @Test
    void shouldRejectWhenCommandIsSet() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "foo");
        args.put("command", "set");
        args.put("value", "bar");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("set");
    }

    @Test
    void shouldRejectWhenCommandIsDel() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "foo");
        args.put("command", "del");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldRejectWhenKeyIsMissing() {
        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("key");
    }

    @Test
    void shouldDefaultToGetWhenCommandNotSpecified() {
        when(valueOps.get("foo")).thenReturn("bar");

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("key", "foo");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("bar");
    }

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }
}
