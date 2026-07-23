package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolDispatcherTest {

    private ToolProvider mysqlProvider;
    private ToolProvider redisProvider;

    @BeforeEach
    void setUp() {
        mysqlProvider = mock(ToolProvider.class);
        when(mysqlProvider.name()).thenReturn("mysql_query");
        when(mysqlProvider.schema()).thenReturn("{\"name\":\"mysql_query\"}");

        redisProvider = mock(ToolProvider.class);
        when(redisProvider.name()).thenReturn("redis_get");
        when(redisProvider.schema()).thenReturn("{\"name\":\"redis_get\"}");
    }

    @Test
    void shouldReturnAvailableToolNamesWhenAllProvidersRegistered() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider, redisProvider), 50000);

        Set<String> names = dispatcher.availableToolNames();

        assertThat(names).containsExactlyInAnyOrder("mysql_query", "redis_get");
    }

    @Test
    void shouldReturnEmptySetWhenNoProvidersRegistered() {
        ToolDispatcher dispatcher = new ToolDispatcher(Collections.<ToolProvider>emptyList(), 50000);

        assertThat(dispatcher.availableToolNames()).isEmpty();
    }

    @Test
    void shouldRouteToRegisteredProviderWhenDispatchCalled() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), 50000);
        Map<String, Object> args = new HashMap<>();
        args.put("sql", "SELECT 1");
        ToolContext ctx = new ToolContext("t1", "u1", null);
        ToolResult expected = ToolResult.success("1", 1, 10L);
        when(mysqlProvider.execute(eq(args), eq(ctx))).thenReturn(expected);

        ToolResult result = dispatcher.dispatch("mysql_query", args, ctx);

        assertThat(result).isSameAs(expected);
        verify(mysqlProvider, times(1)).execute(args, ctx);
    }

    @Test
    void shouldReturnErrorWhenToolNameNotFound() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);

        ToolResult result = dispatcher.dispatch("foo", Collections.<String, Object>emptyMap(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("no plugin registered for: foo");
        verify(mysqlProvider, never()).execute(any(), any());
    }

    @Test
    void shouldTruncateContentWhenResultExceedsMaxChars() {
        int maxChars = 50;
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), maxChars);
        String longContent = "0123456789ABCDEFGHIJ0123456789ABCDEFGHIJ0123456789ABCDEFGHIJ0123456789ABCDEFGHIJ"; // 80 chars
        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(any(), any())).thenReturn(ToolResult.success(longContent, 5, 1L));

        ToolResult result = dispatcher.dispatch("mysql_query", Collections.<String, Object>emptyMap(), ctx);

        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getContent()).contains("truncated");
        assertThat(result.getContent().length()).isLessThan(longContent.length());
        assertThat(result.getRowCount()).isEqualTo(5);
    }

    @Test
    void shouldNotTruncateWhenContentWithinLimit() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(any(), any())).thenReturn(ToolResult.success("short", 1, 1L));

        ToolResult result = dispatcher.dispatch("mysql_query", Collections.<String, Object>emptyMap(), ctx);

        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getContent()).isEqualTo("short");
    }

    @Test
    void shouldNotTruncateErrorResults() {
        int maxChars = 5;
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), maxChars);
        ToolContext ctx = new ToolContext("t1", "u1", null);
        String longError = "a-very-long-error-message";
        when(mysqlProvider.execute(any(), any())).thenReturn(ToolResult.error(longError, 1L));

        ToolResult result = dispatcher.dispatch("mysql_query", Collections.<String, Object>emptyMap(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo(longError);
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldInvokeAuditCallbackWhenPresent() {
        AuditCallback callback = mock(AuditCallback.class);
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), 50000);
        ToolContext ctx = new ToolContext("t1", "u1", callback);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", "SELECT 1");
        ToolResult returned = ToolResult.success("1", 1, 10L);
        when(mysqlProvider.execute(eq(args), eq(ctx))).thenReturn(returned);

        ToolResult result = dispatcher.dispatch("mysql_query", args, ctx);

        verify(callback, times(1)).onToolExecuted("mysql_query", args, result);
    }

    @Test
    void shouldNotInvokeAuditCallbackWhenAbsent() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(any(), any())).thenReturn(ToolResult.success("1", 1, 1L));

        dispatcher.dispatch("mysql_query", Collections.<String, Object>emptyMap(), ctx);

        // no exception thrown — null callback handled gracefully
    }

    @Test
    void shouldInvokeAuditCallbackEvenForUnknownTool() {
        AuditCallback callback = mock(AuditCallback.class);
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider), 50000);
        ToolContext ctx = new ToolContext("t1", "u1", callback);

        ToolResult result = dispatcher.dispatch("foo", Collections.<String, Object>emptyMap(), ctx);

        assertThat(result.isError()).isTrue();
        verify(callback, times(1)).onToolExecuted(eq("foo"), any(), eq(result));
    }

    @Test
    void shouldHandleNullProviderList() {
        ToolDispatcher dispatcher = new ToolDispatcher((java.util.Collection<ToolProvider>) null, 50000);

        assertThat(dispatcher.availableToolNames()).isEmpty();
    }

    @Test
    void shouldBuildToolDefinitionsForRegisteredProviders() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider, redisProvider), 50000);

        String defs = dispatcher.buildToolDefinitions();

        assertThat(defs).contains("mysql_query");
        assertThat(defs).contains("redis_get");
    }

    @Test
    void shouldRouteToDefaultPluginWhenNoOverride() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor desc = new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null);
        registry.register(desc);
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        Map<String, Object> args = new HashMap<>();
        args.put("sql", "SELECT 1");
        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(eq(args), any(ToolContext.class)))
                .thenReturn(ToolResult.success("1", 1, 10L));

        ToolResult result = dispatcher.dispatch("mysql_query", args, ctx);

        assertThat(result.isSuccess()).isTrue();
        verify(mysqlProvider, times(1)).execute(eq(args), any(ToolContext.class));
    }

    @Test
    void shouldRouteToOverridePluginWhenSpecified() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor defaultDesc = new PluginDescriptor(
                "local-log", "log_read", "Local Log", "", "1.0",
                true, true, true, mysqlProvider, null, null, null);
        PluginDescriptor overrideDesc = new PluginDescriptor(
                "remote-log", "log_read", "Remote Log", "", "1.0",
                false, true, false, redisProvider, null, null, null);
        registry.register(defaultDesc);
        registry.register(overrideDesc);
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides);

        when(redisProvider.execute(any(), any(ToolContext.class)))
                .thenReturn(ToolResult.success("remote", 1, 5L));

        ToolResult result = dispatcher.dispatch("log_read", new HashMap<>(), ctx);

        assertThat(result.isSuccess()).isTrue();
        verify(redisProvider, times(1)).execute(any(), any(ToolContext.class));
        verify(mysqlProvider, never()).execute(any(), any());
    }

    @Test
    void shouldReturnErrorWhenNoPluginForToolType() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);

        ToolResult result = dispatcher.dispatch("unknown_type", new HashMap<>(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("no plugin registered for: unknown_type");
    }

    @Test
    void shouldReturnErrorWhenPluginDisabled() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor desc = new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null);
        registry.register(desc);
        registry.disable("mysql");
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);

        ToolResult result = dispatcher.dispatch("mysql_query", new HashMap<>(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("plugin disabled: mysql");
        verify(mysqlProvider, never()).execute(any(), any());
    }

    @Test
    void shouldInjectPluginContextWhenPresent() {
        PluginContext pluginCtx = org.mockito.Mockito.mock(PluginContext.class);
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor desc = new PluginDescriptor(
                "custom", "log_read", "Custom", "", "1.0",
                true, true, false, mysqlProvider, null, null, pluginCtx);
        registry.register(desc);
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(any(), any(ToolContext.class)))
                .thenAnswer(invocation -> {
                    ToolContext passedCtx = invocation.getArgument(1);
                    return ToolResult.success("ctx=" + (passedCtx.getPluginContext() != null), 1, 1L);
                });

        ToolResult result = dispatcher.dispatch("log_read", new HashMap<>(), ctx);

        assertThat(result.getContent()).isEqualTo("ctx=true");
    }

    @Test
    void shouldReturnActivePluginsDedupedByToolType() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "local-log", "log_read", "Local", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        registry.register(new PluginDescriptor(
                "remote-log", "log_read", "Remote", "", "1.0",
                false, true, false, redisProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        java.util.Collection<PluginDescriptor> active = dispatcher.activePlugins();

        assertThat(active).hasSize(1);
        assertThat(active.iterator().next().getPluginId()).isEqualTo("local-log");
    }

    @Test
    void shouldApplyOverridesInActivePlugins() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "local-log", "log_read", "Local", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        registry.register(new PluginDescriptor(
                "remote-log", "log_read", "Remote", "", "1.0",
                false, true, false, redisProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote-log");

        java.util.Collection<PluginDescriptor> active = dispatcher.activePlugins(overrides);

        assertThat(active).hasSize(1);
        assertThat(active.iterator().next().getPluginId()).isEqualTo("remote-log");
    }

    @Test
    void shouldSkipDisabledPluginsInActivePlugins() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        registry.disable("mysql");
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        assertThat(dispatcher.activePlugins()).isEmpty();
    }

    @Test
    void shouldSupportBackwardCompatOldConstructor() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider, redisProvider), 50000);

        java.util.Set<String> types = dispatcher.availableToolTypes();

        assertThat(types).containsExactlyInAnyOrder("mysql_query", "redis_get");
    }

    @Test
    void shouldInvokeAuditWithToolTypeNotPluginId() {
        AuditCallback callback = org.mockito.Mockito.mock(AuditCallback.class);
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", callback);
        when(mysqlProvider.execute(any(), any())).thenReturn(ToolResult.success("1", 1, 1L));

        dispatcher.dispatch("mysql_query", new HashMap<>(), ctx);

        verify(callback, times(1)).onToolExecuted(eq("mysql_query"), any(), any());
    }

    // --- G-301: dispatch when provider.execute returns null ---

    @Test
    void shouldReturnErrorWhenProviderReturnsNullResult() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(any(), any())).thenReturn(null);

        ToolResult result = dispatcher.dispatch("mysql_query", new HashMap<>(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("tool returned null result");
        assertThat(result.getContent()).isNull();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldInvokeAuditWhenProviderReturnsNullResult() {
        AuditCallback callback = mock(AuditCallback.class);
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", callback);
        when(mysqlProvider.execute(any(), any())).thenReturn(null);

        ToolResult result = dispatcher.dispatch("mysql_query", new HashMap<>(), ctx);

        verify(callback, times(1)).onToolExecuted(eq("mysql_query"), any(), eq(result));
    }

    // --- G-302: dispatch when provider.execute throws RuntimeException ---

    @Test
    void shouldReturnErrorWhenProviderThrowsRuntimeException() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", null);
        when(mysqlProvider.execute(any(), any())).thenThrow(new RuntimeException("boom"));

        ToolResult result = dispatcher.dispatch("mysql_query", new HashMap<>(), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("plugin execution failed: boom");
        assertThat(result.getContent()).isNull();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldInvokeAuditWhenProviderThrowsRuntimeException() {
        AuditCallback callback = mock(AuditCallback.class);
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);
        ToolContext ctx = new ToolContext("t1", "u1", callback);
        when(mysqlProvider.execute(any(), any())).thenThrow(new RuntimeException("boom"));

        ToolResult result = dispatcher.dispatch("mysql_query", new HashMap<>(), ctx);

        assertThat(result.isError()).isTrue();
        verify(callback, times(1)).onToolExecuted(eq("mysql_query"), any(), eq(result));
    }

    // --- G-305: activePlugins when override points to disabled plugin (fallback to default) ---

    @Test
    void shouldFallbackToDefaultWhenOverridePointsToDisabledPlugin() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "local", "log_read", "Local", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        PluginDescriptor remote = new PluginDescriptor(
                "remote", "log_read", "Remote", "", "1.0",
                false, true, false, redisProvider, null, null, null);
        registry.register(remote);
        registry.disable("remote");
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote");

        java.util.Collection<PluginDescriptor> active = dispatcher.activePlugins(overrides);

        assertThat(active).hasSize(1);
        assertThat(active.iterator().next().getPluginId()).isEqualTo("local");
    }

    // --- G-306: Concurrent register/disable + dispatch (thread safety) ---

    @Test
    void shouldHandleConcurrentRegisterDisableAndDispatchWithoutExceptions() throws InterruptedException {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
                "mysql", "mysql_query", "MySQL", "", "1.0",
                true, true, true, mysqlProvider, null, null, null));
        when(mysqlProvider.execute(any(), any())).thenReturn(ToolResult.success("1", 1, 1L));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        int threadCount = 30;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (idx % 3 == 0) {
                        // register a new plugin with a unique id
                        String id = "plugin-" + idx;
                        ToolProvider p = mock(ToolProvider.class);
                        when(p.name()).thenReturn("log_read");
                        registry.register(new PluginDescriptor(
                                id, "log_read", "Log", "", "1.0",
                                false, true, false, p, null, null, null));
                    } else if (idx % 3 == 1) {
                        // disable then re-enable the mysql plugin
                        registry.disable("mysql");
                        registry.enable("mysql");
                    } else {
                        // dispatch a tool call
                        dispatcher.dispatch("mysql_query", new HashMap<>(),
                                new ToolContext("t1", "u1", null));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errors).isEmpty();
    }

    // --- G-309: buildToolDefinitions @Deprecated compatibility ---

    @Test
    void shouldBuildToolDefinitionsWithSchemaForDeprecatedCompatibility() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider, redisProvider), 50000);

        String defs = dispatcher.buildToolDefinitions();

        assertThat(defs).startsWith("Available tools:");
        assertThat(defs).contains("mysql_query");
        assertThat(defs).contains("{\"name\":\"mysql_query\"}");
        assertThat(defs).contains("redis_get");
        assertThat(defs).contains("{\"name\":\"redis_get\"}");
    }

    @Test
    void shouldBuildEmptyToolDefinitionsWhenNoPluginsRegistered() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                Collections.<ToolProvider>emptyList(), 50000);

        String defs = dispatcher.buildToolDefinitions();

        assertThat(defs).startsWith("Available tools:");
        // no tool entries — just the header
        assertThat(defs).doesNotContain("- ");
    }

    // --- G-310: availableToolTypes vs availableToolNames equivalence ---

    @Test
    void shouldReturnEquivalentResultsForAvailableToolTypesAndNames() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                java.util.Arrays.asList(mysqlProvider, redisProvider), 50000);

        Set<String> types = dispatcher.availableToolTypes();
        Set<String> names = dispatcher.availableToolNames();

        assertThat(types).isEqualTo(names);
        assertThat(types).containsExactlyInAnyOrder("mysql_query", "redis_get");
    }

    @Test
    void shouldReturnEquivalentEmptyResultsForTypesAndNamesWhenNoPlugins() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                Collections.<ToolProvider>emptyList(), 50000);

        Set<String> types = dispatcher.availableToolTypes();
        Set<String> names = dispatcher.availableToolNames();

        assertThat(types).isEqualTo(names);
        assertThat(types).isEmpty();
    }
}
