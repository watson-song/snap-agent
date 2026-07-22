# Phase 1: Core SPI Layer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the core SPI layer for plugin management — PluginDescriptor data model, PluginRegistry with in-memory implementation, ToolContext extension for plugin overrides, and ToolDispatcher refactored to route via PluginRegistry.

**Architecture:** All components live in snap-agent-core's `cn.watsontech.snapagent.core.tool` package. PluginDescriptor is an immutable data model with volatile flags for runtime mutation. PluginRegistry manages plugin lifecycle via ConcurrentHashMap. ToolContext gains pluginOverrides and pluginContext fields for per-request routing. ToolDispatcher replaces its static provider map with PluginRegistry-based dynamic routing, keeping a @Deprecated old constructor for backward compatibility.

**Tech Stack:** Java 8, JUnit 5, AssertJ, Mockito, Maven

---

## File Structure

| File | Responsibility |
|------|---------------|
| `PluginContext.java` | SPI interface — plugin config access |
| `PluginDescriptor.java` | Immutable plugin metadata + provider reference |
| `PluginRegistry.java` | SPI interface — register/unregister/enable/disable/setDefault |
| `InMemoryPluginRegistry.java` | Default ConcurrentHashMap-based impl |
| `ToolContext.java` | Request-scoped context (extended with pluginOverrides + pluginContext) |
| `ToolDispatcher.java` | Routes tool_use calls via PluginRegistry (refactored) |

---

### Task 1: PluginContext Interface + PluginDescriptor

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginContext.java`
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginDescriptor.java`
- Create: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/PluginDescriptorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDescriptorTest {

    private ToolProvider mockProvider() {
        return Mockito.mock(ToolProvider.class);
    }

    @Test
    void shouldHoldAllFields() {
        ToolProvider provider = mockProvider();
        PluginContext ctx = Mockito.mock(PluginContext.class);
        PluginDescriptor desc = new PluginDescriptor(
                "remote-log", "log_read", "Remote Log", "Query Loki", "1.2.0",
                true, true, false, provider, null, Paths.get("/data/p.jar"), ctx);

        assertThat(desc.getPluginId()).isEqualTo("remote-log");
        assertThat(desc.getToolType()).isEqualTo("log_read");
        assertThat(desc.getDisplayName()).isEqualTo("Remote Log");
        assertThat(desc.getDescription()).isEqualTo("Query Loki");
        assertThat(desc.getVersion()).isEqualTo("1.2.0");
        assertThat(desc.isDefault()).isTrue();
        assertThat(desc.isEnabled()).isTrue();
        assertThat(desc.isSystem()).isFalse();
        assertThat(desc.getProvider()).isSameAs(provider);
        assertThat(desc.getClassLoader()).isNull();
        assertThat(desc.getJarPath()).isEqualTo(Paths.get("/data/p.jar"));
        assertThat(desc.getPluginContext()).isSameAs(ctx);
    }

    @Test
    void shouldDefaultEnabledToTrue() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                false, true, true, mockProvider(), null, null, null);

        assertThat(desc.isEnabled()).isTrue();
    }

    @Test
    void shouldAllowModifyingDefaultFlag() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                true, true, true, mockProvider(), null, null, null);

        desc.setDefault(false);
        assertThat(desc.isDefault()).isFalse();

        desc.setDefault(true);
        assertThat(desc.isDefault()).isTrue();
    }

    @Test
    void shouldAllowModifyingEnabledFlag() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                true, true, true, mockProvider(), null, null, null);

        desc.setEnabled(false);
        assertThat(desc.isEnabled()).isFalse();

        desc.setEnabled(true);
        assertThat(desc.isEnabled()).isTrue();
    }

    @Test
    void shouldAllowNullableClassLoaderJarPathAndPluginContext() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                false, true, true, mockProvider(), null, null, null);

        assertThat(desc.getClassLoader()).isNull();
        assertThat(desc.getJarPath()).isNull();
        assertThat(desc.getPluginContext()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl snap-agent-core -Dtest=PluginDescriptorTest -q`
Expected: FAIL with "cannot find symbol: class PluginDescriptor" and "cannot find symbol: class PluginContext"

- [ ] **Step 3: Write minimal implementation**

Create `PluginContext.java`:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * SPI for plugin configuration access.
 * Implementations provide configuration extracted from
 * {@code snap-agent.tools.{pluginId}.*} properties.
 */
public interface PluginContext {

    /**
     * Returns all configuration entries for this plugin.
     *
     * @return unmodifiable map of configuration key → value (never null)
     */
    Map<String, Object> getConfiguration();
}
```

Create `PluginDescriptor.java`:

```java
package cn.watsontech.snapagent.core.tool;

import java.nio.file.Path;

/**
 * Immutable descriptor for a registered plugin.
 *
 * <p>Holds metadata (pluginId, toolType, version), runtime state
 * ({@code isDefault}, {@code enabled} — volatile for safe concurrent access),
 * and the actual {@link ToolProvider} implementation. Custom plugins also
 * carry a {@link ClassLoader} (URLClassLoader for JAR isolation),
 * {@code jarPath}, and {@link PluginContext} for config injection.
 * System plugins (built-in {@code @Component ToolProvider} beans) have
 * null for those three fields.</p>
 */
public final class PluginDescriptor {

    private final String pluginId;
    private final String toolType;
    private final String displayName;
    private final String description;
    private final String version;
    private volatile boolean isDefault;
    private volatile boolean enabled;
    private final boolean system;
    private final ToolProvider provider;
    private final ClassLoader classLoader;
    private final Path jarPath;
    private final PluginContext pluginContext;

    public PluginDescriptor(String pluginId, String toolType, String displayName,
                            String description, String version,
                            boolean isDefault, boolean enabled, boolean system,
                            ToolProvider provider, ClassLoader classLoader,
                            Path jarPath, PluginContext pluginContext) {
        this.pluginId = pluginId;
        this.toolType = toolType;
        this.displayName = displayName;
        this.description = description;
        this.version = version;
        this.isDefault = isDefault;
        this.enabled = enabled;
        this.system = system;
        this.provider = provider;
        this.classLoader = classLoader;
        this.jarPath = jarPath;
        this.pluginContext = pluginContext;
    }

    public String getPluginId() { return pluginId; }
    public String getToolType() { return toolType; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public boolean isDefault() { return isDefault; }
    public boolean isEnabled() { return enabled; }
    public boolean isSystem() { return system; }
    public ToolProvider getProvider() { return provider; }
    public ClassLoader getClassLoader() { return classLoader; }
    public Path getJarPath() { return jarPath; }
    public PluginContext getPluginContext() { return pluginContext; }

    void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl snap-agent-core -Dtest=PluginDescriptorTest -q`
Expected: PASS — all 5 tests green

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginContext.java \
        snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginDescriptor.java \
        snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/PluginDescriptorTest.java
git commit -m "feat: add PluginContext SPI and PluginDescriptor data model"
```

---

### Task 2: PluginRegistry SPI + InMemoryPluginRegistry

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginRegistry.java`
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistry.java`
- Create: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryPluginRegistryTest {

    private InMemoryPluginRegistry registry;

    private PluginDescriptor newPlugin(String id, String toolType, boolean system, boolean isDefault) {
        return new PluginDescriptor(
                id, toolType, id, "", "1.0",
                isDefault, true, system,
                Mockito.mock(ToolProvider.class), null, null, null);
    }

    @BeforeEach
    void setUp() {
        registry = new InMemoryPluginRegistry();
    }

    @Test
    void shouldRegisterPluginAndRetrieveById() {
        PluginDescriptor desc = newPlugin("mysql", "mysql_query", true, true);
        registry.register(desc);

        assertThat(registry.getPlugin("mysql")).isSameAs(desc);
    }

    @Test
    void shouldReturnNullForUnknownPluginId() {
        assertThat(registry.getPlugin("nonexistent")).isNull();
    }

    @Test
    void shouldThrowWhenRegisteringDuplicatePluginId() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));

        assertThatThrownBy(() -> registry.register(newPlugin("mysql", "mysql_query", false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plugin already registered: mysql");
    }

    @Test
    void shouldAutoSetFirstPluginAsDefault() {
        PluginDescriptor desc = newPlugin("mysql", "mysql_query", true, false);
        registry.register(desc);

        assertThat(registry.getDefault("mysql_query")).isSameAs(desc);
        assertThat(desc.isDefault()).isTrue();
    }

    @Test
    void shouldThrowWhenUnregisteringSystemPlugin() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));

        assertThatThrownBy(() -> registry.unregister("mysql"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot unregister system plugin");
    }

    @Test
    void shouldUnregisterNonSystemPlugin() {
        registry.register(newPlugin("custom", "log_read", false, true));

        registry.unregister("custom");

        assertThat(registry.getPlugin("custom")).isNull();
    }

    @Test
    void shouldClearDefaultWhenUnregisteringDefaultPlugin() {
        registry.register(newPlugin("custom", "log_read", false, true));

        registry.unregister("custom");

        assertThat(registry.getDefault("log_read")).isNull();
    }

    @Test
    void shouldEnableAndDisablePlugin() {
        registry.register(newPlugin("custom", "log_read", false, true));

        registry.disable("custom");
        assertThat(registry.getPlugin("custom").isEnabled()).isFalse();

        registry.enable("custom");
        assertThat(registry.getPlugin("custom").isEnabled()).isTrue();
    }

    @Test
    void shouldSetDefaultAndClearOthers() {
        PluginDescriptor first = newPlugin("log1", "log_read", false, true);
        PluginDescriptor second = newPlugin("log2", "log_read", false, false);
        registry.register(first);
        registry.register(second);

        registry.setDefault("log_read", "log2");

        assertThat(first.isDefault()).isFalse();
        assertThat(second.isDefault()).isTrue();
        assertThat(registry.getDefault("log_read")).isSameAs(second);
    }

    @Test
    void shouldReturnPluginsForTypeFiltered() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));
        registry.register(newPlugin("redis", "redis_get", true, true));

        List<PluginDescriptor> result = registry.getPluginsForType("mysql_query");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPluginId()).isEqualTo("mysql");
    }

    @Test
    void shouldIncludeDisabledPluginsInGetPluginsForType() {
        PluginDescriptor desc = newPlugin("custom", "log_read", false, true);
        registry.register(desc);
        registry.disable("custom");

        List<PluginDescriptor> result = registry.getPluginsForType("log_read");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPluginId()).isEqualTo("custom");
    }

    @Test
    void shouldReturnAllPluginsFromList() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));
        registry.register(newPlugin("redis", "redis_get", true, true));
        registry.register(newPlugin("custom", "log_read", false, true));

        assertThat(registry.list()).hasSize(3);
    }

    @Test
    void shouldReturnNullDefaultForUnknownToolType() {
        assertThat(registry.getDefault("nonexistent")).isNull();
    }

    @Test
    void shouldAutoPickNewDefaultWhenDefaultUnregistered() {
        PluginDescriptor first = newPlugin("log1", "log_read", false, true);
        PluginDescriptor second = newPlugin("log2", "log_read", false, false);
        registry.register(first);
        registry.register(second);

        registry.unregister("log1");

        assertThat(registry.getDefault("log_read")).isSameAs(second);
        assertThat(second.isDefault()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl snap-agent-core -Dtest=InMemoryPluginRegistryTest -q`
Expected: FAIL with "cannot find symbol: class PluginRegistry" and "cannot find symbol: class InMemoryPluginRegistry"

- [ ] **Step 3: Write minimal implementation**

Create `PluginRegistry.java`:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.List;

/**
 * SPI for managing plugin lifecycle.
 *
 * <p>Implementations handle registration, removal, enable/disable,
 * and default selection. The default {@link InMemoryPluginRegistry}
 * uses {@link java.util.concurrent.ConcurrentHashMap} for thread safety.</p>
 */
public interface PluginRegistry {

    /**
     * Register a plugin. Throws if pluginId already exists.
     */
    void register(PluginDescriptor descriptor);

    /**
     * Unregister a plugin. System plugins cannot be removed.
     */
    void unregister(String pluginId);

    /** Enable a plugin (allow dispatch to route to it). */
    void enable(String pluginId);

    /** Disable a plugin (dispatch returns "plugin disabled" error). */
    void disable(String pluginId);

    /**
     * Set the default plugin for a toolType.
     * Other plugins of the same toolType have isDefault cleared.
     */
    void setDefault(String toolType, String pluginId);

    /** Get a plugin by id, or null if not found. */
    PluginDescriptor getPlugin(String pluginId);

    /** Get the default plugin for a toolType, or null if none. */
    PluginDescriptor getDefault(String toolType);

    /** Get all plugins for a toolType (including disabled ones). */
    List<PluginDescriptor> getPluginsForType(String toolType);

    /** List all registered plugins. */
    List<PluginDescriptor> list();
}
```

Create `InMemoryPluginRegistry.java`:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link PluginRegistry} backed by {@link ConcurrentHashMap}.
 *
 * <p>Thread-safe for all operations. When a default plugin is unregistered,
 * the next available plugin of the same toolType is auto-promoted to default.</p>
 */
public class InMemoryPluginRegistry implements PluginRegistry {

    private final ConcurrentHashMap<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> defaultsByType = new ConcurrentHashMap<>();

    @Override
    public void register(PluginDescriptor descriptor) {
        if (descriptor == null || descriptor.getPluginId() == null) {
            throw new IllegalArgumentException("descriptor and pluginId must not be null");
        }
        PluginDescriptor existing = plugins.putIfAbsent(descriptor.getPluginId(), descriptor);
        if (existing != null) {
            throw new IllegalArgumentException("plugin already registered: " + descriptor.getPluginId());
        }
        // If this plugin declares isDefault, or no default exists for this toolType yet,
        // auto-promote it to default.
        if (descriptor.isDefault() || defaultsByType.putIfAbsent(descriptor.getToolType(), descriptor.getPluginId()) == null) {
            descriptor.setDefault(true);
            defaultsByType.put(descriptor.getToolType(), descriptor.getPluginId());
        } else {
            descriptor.setDefault(false);
        }
    }

    @Override
    public void unregister(String pluginId) {
        if (pluginId == null) return;
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc == null) return;
        if (desc.isSystem()) {
            throw new UnsupportedOperationException("cannot unregister system plugin: " + pluginId);
        }
        plugins.remove(pluginId);
        String toolType = desc.getToolType();
        // If this was the default, auto-pick a new one
        if (defaultsByType.remove(toolType, pluginId)) {
            for (PluginDescriptor remaining : plugins.values()) {
                if (remaining.getToolType().equals(toolType)) {
                    remaining.setDefault(true);
                    defaultsByType.put(toolType, remaining.getPluginId());
                    break;
                }
            }
        }
    }

    @Override
    public void enable(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc != null) desc.setEnabled(true);
    }

    @Override
    public void disable(String pluginId) {
        PluginDescriptor desc = plugins.get(pluginId);
        if (desc != null) desc.setEnabled(false);
    }

    @Override
    public void setDefault(String toolType, String pluginId) {
        PluginDescriptor target = plugins.get(pluginId);
        if (target == null) return;
        for (PluginDescriptor p : plugins.values()) {
            if (p.getToolType().equals(toolType)) {
                p.setDefault(false);
            }
        }
        target.setDefault(true);
        defaultsByType.put(toolType, pluginId);
    }

    @Override
    public PluginDescriptor getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    @Override
    public PluginDescriptor getDefault(String toolType) {
        String pluginId = defaultsByType.get(toolType);
        if (pluginId == null) return null;
        return plugins.get(pluginId);
    }

    @Override
    public List<PluginDescriptor> getPluginsForType(String toolType) {
        List<PluginDescriptor> result = new ArrayList<>();
        for (PluginDescriptor p : plugins.values()) {
            if (p.getToolType().equals(toolType)) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public List<PluginDescriptor> list() {
        return new ArrayList<>(plugins.values());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl snap-agent-core -Dtest=InMemoryPluginRegistryTest -q`
Expected: PASS — all 13 tests green

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginRegistry.java \
        snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistry.java \
        snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistryTest.java
git commit -m "feat: add PluginRegistry SPI and InMemoryPluginRegistry"
```

---

### Task 3: ToolContext Extension

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolContext.java`
- Modify: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolContextTest.java`

- [ ] **Step 1: Write the failing test**

Add these tests to the existing `ToolContextTest.java`:

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolContextTest {

    @Test
    void shouldHoldAllFields() {
        AuditCallback cb = (name, args, result) -> { };
        ToolContext ctx = new ToolContext("task-1", "user-1", cb);

        assertThat(ctx.getTaskId()).isEqualTo("task-1");
        assertThat(ctx.getUserId()).isEqualTo("user-1");
        assertThat(ctx.getAuditCallback()).isSameAs(cb);
    }

    @Test
    void shouldAllowNullableCallback() {
        ToolContext ctx = new ToolContext("task-2", "user-2", null);

        assertThat(ctx.getUserId()).isEqualTo("user-2");
        assertThat(ctx.getAuditCallback()).isNull();
    }

    // ---- New tests for pluginOverrides and pluginContext ----

    @Test
    void shouldReturnEmptyPluginOverridesWhenUsingOldConstructor() {
        ToolContext ctx = new ToolContext("t1", "u1", null);

        assertThat(ctx.getPluginOverrides()).isEmpty();
        assertThat(ctx.getPluginContext()).isNull();
    }

    @Test
    void shouldStorePluginOverridesFromFourArgConstructor() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides);

        assertThat(ctx.getPluginOverrides()).containsEntry("log_read", "remote-log");
    }

    @Test
    void shouldReturnUnmodifiablePluginOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides);

        assertThatThrownBy(() -> ctx.getPluginOverrides().put("foo", "bar"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHandleNullPluginOverridesAsEmptyMap() {
        ToolContext ctx = new ToolContext("t1", "u1", null, (Map<String, String>) null);

        assertThat(ctx.getPluginOverrides()).isEmpty();
    }

    @Test
    void shouldStorePluginContextFromFiveArgConstructor() {
        PluginContext pluginCtx = Mockito.mock(PluginContext.class);
        Map<String, String> overrides = Collections.singletonMap("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides, pluginCtx);

        assertThat(ctx.getPluginContext()).isSameAs(pluginCtx);
        assertThat(ctx.getPluginOverrides()).containsEntry("log_read", "remote-log");
    }

    @Test
    void shouldReturnNewContextFromWithPluginContext() {
        ToolContext original = new ToolContext("t1", "u1", null,
                Collections.singletonMap("log_read", "remote-log"), null);
        PluginContext pluginCtx = Mockito.mock(PluginContext.class);

        ToolContext updated = original.withPluginContext(pluginCtx);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getTaskId()).isEqualTo("t1");
        assertThat(updated.getUserId()).isEqualTo("u1");
        assertThat(updated.getPluginOverrides()).containsEntry("log_read", "remote-log");
        assertThat(updated.getPluginContext()).isSameAs(pluginCtx);
    }

    @Test
    void shouldPreserveAuditCallbackInWithPluginContext() {
        AuditCallback cb = Mockito.mock(AuditCallback.class);
        ToolContext original = new ToolContext("t1", "u1", cb, null, null);

        ToolContext updated = original.withPluginContext(Mockito.mock(PluginContext.class));

        assertThat(updated.getAuditCallback()).isSameAs(cb);
    }

    // keep the import for Mockito at top — add if not present:
    // import org.mockito.Mockito;
}
```

NOTE: If the existing `ToolContextTest.java` already has imports, merge them. Add `import org.mockito.Mockito;` and `import java.util.Collections;` if not present.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl snap-agent-core -Dtest=ToolContextTest -q`
Expected: FAIL — new test methods fail because `getPluginOverrides()`, `getPluginContext()`, `withPluginContext()`, and 4/5-arg constructors don't exist yet.

- [ ] **Step 3: Write minimal implementation**

Replace `ToolContext.java` with:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context object passed to {@link ToolProvider#execute} carrying request-scoped
 * information: task id, user id, audit callback, plugin overrides, and plugin context.
 *
 * <p>Immutable — all fields are final. {@link #withPluginContext(PluginContext)}
 * returns a new instance with an updated plugin context for use by
 * {@link ToolDispatcher} when injecting the selected plugin's configuration.</p>
 */
public final class ToolContext {

    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;
    private final Map<String, String> pluginOverrides;
    private final PluginContext pluginContext;

    public ToolContext(String taskId, String userId, AuditCallback auditCallback) {
        this(taskId, userId, auditCallback, Collections.<String, String>emptyMap(), null);
    }

    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides) {
        this(taskId, userId, auditCallback, pluginOverrides, null);
    }

    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides, PluginContext pluginContext) {
        this.taskId = taskId;
        this.userId = userId;
        this.auditCallback = auditCallback;
        this.pluginOverrides = pluginOverrides != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverrides))
                : Collections.<String, String>emptyMap();
        this.pluginContext = pluginContext;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getUserId() {
        return userId;
    }

    public AuditCallback getAuditCallback() {
        return auditCallback;
    }

    public Map<String, String> getPluginOverrides() {
        return pluginOverrides;
    }

    public PluginContext getPluginContext() {
        return pluginContext;
    }

    /**
     * Returns a new ToolContext with the same fields except pluginContext
     * is replaced with the given value.
     */
    public ToolContext withPluginContext(PluginContext pluginContext) {
        return new ToolContext(taskId, userId, auditCallback, pluginOverrides, pluginContext);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl snap-agent-core -Dtest=ToolContextTest -q`
Expected: PASS — all 9 tests green (2 existing + 7 new)

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolContext.java \
        snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolContextTest.java
git commit -m "feat: extend ToolContext with pluginOverrides and pluginContext"
```

---

### Task 4: ToolDispatcher Refactor

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolDispatcher.java`
- Modify: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolDispatcherTest.java`

- [ ] **Step 1: Write the failing test**

Add these tests to the existing `ToolDispatcherTest.java` (keep all existing tests — they use the @Deprecated old constructor which still works):

```java
// Add these imports at top of file:
// import cn.watsontech.snapagent.core.tool.PluginContext;
// import java.nio.file.Paths;
// import java.util.LinkedHashMap;

// Add these test methods to the existing class:

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl snap-agent-core -Dtest=ToolDispatcherTest -q`
Expected: FAIL — new test methods fail because `new ToolDispatcher(registry, maxChars)` constructor doesn't exist, `activePlugins()` method doesn't exist, `availableToolTypes()` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

Replace `ToolDispatcher.java` with:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes {@code tool_use} calls to the matching plugin via {@link PluginRegistry}.
 *
 * <p>Dispatch routing logic:
 * <ol>
 *   <li>If {@code ctx.pluginOverrides[toolType]} is set, use that plugin</li>
 *   <li>Otherwise use {@code registry.getDefault(toolType)}</li>
 *   <li>If the selected plugin is disabled or not found, return a
 *       {@link ToolResult#error} so the LLM can self-correct</li>
 * </ol></p>
 *
 * <p>The old {@code ToolDispatcher(Collection<ToolProvider>, int)} constructor is
 * retained {@code @Deprecated} for backward compatibility — it creates an
 * internal {@link InMemoryPluginRegistry} and registers each provider as a
 * system plugin.</p>
 */
public class ToolDispatcher {

    private final PluginRegistry registry;
    private final int maxToolResultChars;

    /**
     * New constructor — routes via PluginRegistry.
     */
    public ToolDispatcher(PluginRegistry registry, int maxToolResultChars) {
        this.registry = registry != null ? registry : new InMemoryPluginRegistry();
        this.maxToolResultChars = maxToolResultChars;
    }

    /**
     * @deprecated Use {@link #ToolDispatcher(PluginRegistry, int)}.
     * Creates an internal registry and registers each provider as a system plugin.
     */
    @Deprecated
    public ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars) {
        InMemoryPluginRegistry reg = new InMemoryPluginRegistry();
        if (providerList != null) {
            for (ToolProvider p : providerList) {
                if (p != null && p.name() != null) {
                    PluginDescriptor desc = new PluginDescriptor(
                            p.name(), p.name(), p.name(), "", "built-in",
                            true, true, true, p, null, null, null);
                    reg.register(desc);
                }
            }
        }
        this.registry = reg;
        this.maxToolResultChars = maxToolResultChars;
    }

    /** Returns the set of registered tool types. */
    public Set<String> availableToolTypes() {
        Set<String> types = new HashSet<>();
        for (PluginDescriptor p : registry.list()) {
            types.add(p.getToolType());
        }
        return types;
    }

    /** @deprecated Use {@link #availableToolTypes()}. */
    @Deprecated
    public Set<String> availableToolNames() {
        return availableToolTypes();
    }

    /**
     * Returns the active plugins for this request.
     * Each toolType is represented once — by the override plugin (if specified)
     * or the default plugin (if enabled).
     */
    public Collection<PluginDescriptor> activePlugins(Map<String, String> overrides) {
        Map<String, PluginDescriptor> active = new LinkedHashMap<>();
        for (PluginDescriptor p : registry.list()) {
            if (!p.isEnabled()) continue;
            String toolType = p.getToolType();
            if (active.containsKey(toolType)) continue;
            active.put(toolType, p);
        }
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                PluginDescriptor p = registry.getPlugin(e.getValue());
                if (p != null && p.isEnabled()) {
                    active.put(e.getKey(), p);
                }
            }
        }
        return active.values();
    }

    /** Convenience overload — no overrides. */
    public Collection<PluginDescriptor> activePlugins() {
        return activePlugins(null);
    }

    /** @deprecated Use {@link #activePlugins()}. */
    @Deprecated
    public Collection<ToolProvider> providers() {
        List<ToolProvider> result = new ArrayList<>();
        for (PluginDescriptor desc : activePlugins()) {
            result.add(desc.getProvider());
        }
        return result;
    }

    /**
     * Dispatch a tool call via PluginRegistry routing.
     */
    public ToolResult dispatch(String toolType, Map<String, Object> args, ToolContext ctx) {
        if (toolType == null) {
            ToolResult err = ToolResult.error("no plugin registered for: null", 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }
        String override = ctx != null ? ctx.getPluginOverrides().get(toolType) : null;
        PluginDescriptor plugin = (override != null)
                ? registry.getPlugin(override)
                : registry.getDefault(toolType);

        if (plugin == null) {
            ToolResult err = ToolResult.error("no plugin registered for: " + toolType, 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }
        if (!plugin.isEnabled()) {
            ToolResult err = ToolResult.error("plugin disabled: " + plugin.getPluginId(), 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }

        if (plugin.getPluginContext() != null && ctx != null) {
            ctx = ctx.withPluginContext(plugin.getPluginContext());
        }

        long start = System.currentTimeMillis();
        ToolResult result;
        try {
            result = plugin.getProvider().execute(args, ctx);
            if (result == null) {
                result = ToolResult.error("tool returned null result",
                        System.currentTimeMillis() - start);
            }
        } catch (RuntimeException e) {
            result = ToolResult.error("plugin execution failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
        ToolResult finalResult = truncateIfNeeded(result);
        invokeAudit(ctx, toolType, args, finalResult);
        return finalResult;
    }

    private ToolResult truncateIfNeeded(ToolResult result) {
        if (result == null || result.isError() || result.getContent() == null) {
            return result;
        }
        String content = result.getContent();
        if (content.length() <= maxToolResultChars) {
            return result;
        }
        String suffix = "\n...[truncated, total " + result.getRowCount() + " rows]";
        int keep = Math.max(0, maxToolResultChars - suffix.length());
        String truncated = content.substring(0, keep) + suffix;
        return new ToolResult(truncated, result.getRowCount(), true, result.getDurationMs(), null);
    }

    private void invokeAudit(ToolContext ctx, String toolName, Map<String, Object> args, ToolResult result) {
        if (ctx != null && ctx.getAuditCallback() != null) {
            try {
                ctx.getAuditCallback().onToolExecuted(toolName, args, result);
            } catch (RuntimeException ignored) {
                // audit failure must not break the agent loop
            }
        }
    }

    /** @deprecated Use activePlugins() + provider schemas instead. */
    @Deprecated
    public String buildToolDefinitions() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n");
        for (PluginDescriptor desc : activePlugins()) {
            sb.append("- ").append(desc.getToolType())
              .append(": ").append(desc.getProvider().schema()).append("\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl snap-agent-core -Dtest=ToolDispatcherTest -q`
Expected: PASS — all existing tests (via @Deprecated old constructor) + all new tests (via PluginRegistry) green

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolDispatcher.java \
        snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolDispatcherTest.java
git commit -m "feat: refactor ToolDispatcher to route via PluginRegistry"
```

---

## Post-Phase Verification

After completing all 4 tasks, run the full core module test suite:

```bash
mvn test -pl snap-agent-core -q
```

Expected: All existing tests (ToolContextTest, ToolDispatcherTest, ToolResultTest) + new tests (PluginDescriptorTest, InMemoryPluginRegistryTest) pass with 0 failures.
