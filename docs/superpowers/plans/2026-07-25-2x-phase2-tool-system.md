# Phase 2: Tool System — @Tool/@ToolParam + ToolCallbacks.from() Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 1.x ToolDispatcher/ToolProvider/PluginRegistry with declarative @Tool/@ToolParam annotations + ToolCallback SPI upgrade + ToolCallbacks.from() reflection factory + ToolCallbackRegistryImpl.

**Architecture:** @Tool annotation on methods declares tool metadata; @ToolParam on parameters generates JSON Schema; ToolCallbacks.from() scans a target object via reflection, produces ToolCallback[] with name/description/jsonSchema/execute; ToolCallbackRegistryImpl provides register/unregister/getAll/find/toToolDefinitionsJson/subset with ConcurrentHashMap. ToolsNode updated to use new ToolCallback methods.

**Tech Stack:** Java 8, JUnit 5, AssertJ 3.19.0, Mockito, JDK Reflection API

**Build command:**
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=TestName -DfailIfNoTests=false
```

**Base package:** `cn.watsontech.snapagent.core.tool`

---

## File Structure

### Create
| File | Responsibility |
|------|---------------|
| `snap-agent-core/.../tool/Tool.java` | @Tool method annotation (name, description, returnDirect) |
| `snap-agent-core/.../tool/ToolParam.java` | @ToolParam parameter annotation (description, required) |
| `snap-agent-core/.../tool/ToolCallbacks.java` | Reflection factory: scans @Tool methods → ToolCallback[] |
| `snap-agent-core/.../tool/ToolCallbackRegistryImpl.java` | ConcurrentHashMap-based ToolCallbackRegistry implementation |
| `snap-agent-core/src/test/.../tool/ToolTest.java` | @Tool annotation tests (UC-01, UC-02) |
| `snap-agent-core/src/test/.../tool/ToolParamTest.java` | @ToolParam annotation tests (UC-03, UC-04) |
| `snap-agent-core/src/test/.../tool/ToolCallbacksTest.java` | ToolCallbacks.from() reflection tests (UC-11~14) |
| `snap-agent-core/src/test/.../tool/ToolCallbackRegistryImplTest.java` | Registry implementation tests (UC-07~10, UC-23) |

### Modify
| File | Changes |
|------|--------|
| `snap-agent-core/.../tool/ToolCallback.java` | Add getDescription(), getJsonSchema() defaults |
| `snap-agent-core/.../tool/ToolCallbackRegistry.java` | Add register/unregister/getAll/toToolDefinitionsJson/subset defaults |
| `snap-agent-core/.../tool/ToolResult.java` | Add originalLength field + truncated() factory |
| `snap-agent-core/.../graph/react/ToolsNode.java` | Use new ToolCallback methods, fix truncation |
| `snap-agent-core/.../graph/react/AgentNode.java` | Build tool defs from registry.getAll() |

### Delete (1.x clean break)
| File | Reason |
|------|--------|
| `snap-agent-core/.../tool/ToolDispatcher.java` | Replaced by ToolsNode graph node |
| `snap-agent-core/.../tool/ToolProvider.java` | Replaced by @Tool annotation |
| `snap-agent-core/.../tool/ToolPluginAnnotation.java` | Replaced by @Tool annotation |
| `snap-agent-core/.../tool/PluginRegistry.java` | Replaced by ToolCallbackRegistry |
| `snap-agent-core/.../tool/InMemoryPluginRegistry.java` | Replaced by ToolCallbackRegistryImpl |
| `snap-agent-core/.../tool/ToolPlugin.java` | Replaced by ToolCallback |
| `snap-agent-core/.../tool/PluginContext.java` | No longer needed |
| `snap-agent-core/.../tool/PluginDescriptor.java` | Deferred to 09-plugin-mcp |
| `snap-agent-core/.../tool/AuditCallback.java` | No longer needed |
| `snap-agent-core/.../tool/ToolContext.java` | Simplify (remove PluginContext/AuditCallback deps) |

---

## Task 1: @Tool + @ToolParam Annotations (UC-01~04)

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/Tool.java`
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolParam.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolAnnotationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@Tool / @ToolParam 注解")
class ToolAnnotationTest {

    static class TestTool {
        @Tool(name = "mysql_query", description = "执行SQL查询")
        public String query(@ToolParam(description = "SQL语句") String sql) {
            return "result: " + sql;
        }

        @Tool(description = "默认name用方法名")
        public String insert(@ToolParam(description = "插入数据") String data) {
            return data;
        }

        @Tool(name = "render_html", description = "渲染HTML", returnDirect = true)
        public String render() {
            return "<html></html>";
        }
    }

    @Test
    @DisplayName("@Tool 显式 name + description")
    void toolWithNameAndDescription() throws Exception {
        Method method = TestTool.class.getMethod("query", String.class);
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool.name()).isEqualTo("mysql_query");
        assertThat(tool.description()).isEqualTo("执行SQL查询");
        assertThat(tool.returnDirect()).isFalse();
    }

    @Test
    @DisplayName("@Tool 默认 name 为空 (ToolCallbacks.from 用方法名)")
    void toolDefaultNameIsEmpty() throws Exception {
        Method method = TestTool.class.getMethod("insert", String.class);
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool.name()).isEmpty();
        assertThat(tool.description()).isEqualTo("默认name用方法名");
    }

    @Test
    @DisplayName("@Tool returnDirect=true")
    void toolReturnDirect() throws Exception {
        Method method = TestTool.class.getMethod("render");
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool.returnDirect()).isTrue();
    }

    @Test
    @DisplayName("@ToolParam description + required 默认 true")
    void toolParamDefaults() throws Exception {
        Method method = TestTool.class.getMethod("query", String.class);
        ToolParam param = method.getParameterAnnotations()[0][0];
        assertThat(param.description()).isEqualTo("SQL语句");
        assertThat(param.required()).isTrue();
    }

    @Test
    @DisplayName("@Tool @Retention RUNTIME — 可反射读取")
    void retentionRuntime() {
        assertThat(Tool.class.getRetention()).isNotNull();
        // Verify it's actually RUNTIME by checking we can read it
        Tool tool = TestTool.class.getAnnotation(Tool.class);
        // @Inherited on class level doesn't apply to method annotations,
        // but we can still check the annotation is present on methods
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolAnnotationTest -DfailIfNoTests=false
```
Expected: FAIL — `Tool` and `ToolParam` annotations not found

- [ ] **Step 3: Write minimal implementation**

`Tool.java`:
```java
package cn.watsontech.snapagent.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as an LLM-callable tool.
 * ToolCallbacks.from() scans for this annotation and builds ToolCallback instances.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    /** Tool name. Empty string = use method name. */
    String name() default "";
    /** Human-readable description for the LLM. Required. */
    String description();
    /** If true, result goes directly to user (not back to LLM). */
    boolean returnDirect() default false;
}
```

`ToolParam.java`:
```java
package cn.watsontech.snapagent.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares parameter metadata for a @Tool method.
 * Used by ToolCallbacks.from() to generate JSON Schema.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    /** Human-readable description of the parameter. Required. */
    String description();
    /** Whether the parameter is required. Default true. */
    boolean required() default true;
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolAnnotationTest -DfailIfNoTests=false
```
Expected: PASS — 5 tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/Tool.java snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolParam.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolAnnotationTest.java
git commit -m "feat(tool): add @Tool + @ToolParam annotations (UC-01~04)"
```

---

## Task 2: Upgrade ToolResult — Add originalLength (UC-22)

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolResult.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolResultTest.java` (existing — add tests)

- [ ] **Step 1: Write the failing test (append to existing ToolResultTest)**

Add these tests to the existing `ToolResultTest.java`:

```java
@Test
@DisplayName("truncated 含 originalLength")
void truncatedWithOriginalLength() {
    String longContent = "abcdefghij"; // 10 chars
    ToolResult result = ToolResult.truncated(longContent, 5, 10, 0);
    assertThat(result.isTruncated()).isTrue();
    assertThat(result.getOriginalLength()).isEqualTo(10);
    assertThat(result.getContent()).hasSize(5);
}

@Test
@DisplayName("success 含 toolUseId")
void successWithToolUseId() {
    ToolResult result = ToolResult.success("content", 5, 100, "tu-1");
    assertThat(result.getToolUseId()).isEqualTo("tu-1");
    assertThat(result.isSuccess()).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolResultTest -DfailIfNoTests=false
```
Expected: FAIL — `getOriginalLength()` and `getToolUseId()` not found

- [ ] **Step 3: Modify ToolResult to add originalLength and toolUseId**

Add `originalLength` and `toolUseId` fields. Add new constructors and factory methods. Keep existing methods for backward compatibility.

```java
// Add fields:
private final int originalLength;
private final String toolUseId;

// Add new constructor (6+7 args):
public ToolResult(String content, int rowCount, boolean truncated, long durationMs,
                   String error, int originalLength, String toolUseId) {
    this.content = content;
    this.rowCount = rowCount;
    this.truncated = truncated;
    this.durationMs = durationMs;
    this.error = error;
    this.originalLength = originalLength;
    this.toolUseId = toolUseId;
}

// Update existing factory methods to delegate to new constructor with defaults:
// success(content, rowCount, durationMs) → originalLength=0, toolUseId=null
// truncated(content, rowCount, durationMs) → originalLength=content.length(), toolUseId=null
// error(message, durationMs) → originalLength=0, toolUseId=null

// Add new factory methods:
public static ToolResult success(String content, int rowCount, long durationMs, String toolUseId) {
    return new ToolResult(content, rowCount, false, durationMs, null, 0, toolUseId);
}

public static ToolResult truncated(String content, int rowCount, long durationMs, int originalLength) {
    return new ToolResult(content, rowCount, true, durationMs, null, originalLength, null);
}

// Add getters:
public int getOriginalLength() { return originalLength; }
public String getToolUseId() { return toolUseId; }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolResultTest -DfailIfNoTests=false
```
Expected: PASS — all ToolResult tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolResult.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolResultTest.java
git commit -m "feat(tool): add originalLength + toolUseId to ToolResult (UC-22)"
```

---

## Task 3: Upgrade ToolCallback — Add getDescription() + getJsonSchema() (UC-05)

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallback.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCallback SPI 升级")
class ToolCallbackSpiTest {

    @Test
    @DisplayName("getDescription 默认空字符串")
    void defaultDescription() {
        ToolCallback callback = new ToolCallback() {
            @Override public ToolResult execute(java.util.Map<String, Object> input, Object context) { return null; }
            @Override public String getName() { return "test"; }
        };
        assertThat(callback.getDescription()).isEmpty();
    }

    @Test
    @DisplayName("getJsonSchema 默认空对象")
    void defaultJsonSchema() {
        ToolCallback callback = new ToolCallback() {
            @Override public ToolResult execute(java.util.Map<String, Object> input, Object context) { return null; }
            @Override public String getName() { return "test"; }
        };
        assertThat(callback.getJsonSchema()).isEqualTo("{}");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbackSpiTest -DfailIfNoTests=false
```
Expected: FAIL — `getDescription()` and `getJsonSchema()` not found

- [ ] **Step 3: Add default methods to ToolCallback interface**

```java
// Add to ToolCallback interface:
default String getDescription() { return ""; }
default String getJsonSchema() { return "{}"; }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbackSpiTest -DfailIfNoTests=false
```
Expected: PASS — 2 tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallback.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolCallbackSpiTest.java
git commit -m "feat(tool): add getDescription() + getJsonSchema() to ToolCallback SPI (UC-05)"
```

---

## Task 4: Upgrade ToolCallbackRegistry — Add register/unregister/getAll/toToolDefinitionsJson/subset (UC-07~10, UC-23)

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistry.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ToolCallbackRegistry SPI 升级")
class ToolCallbackRegistrySpiTest {

    @Test
    @DisplayName("default getAll 返回空列表")
    void defaultGetAll() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    @DisplayName("default register 抛 UnsupportedOperationException")
    void defaultRegisterThrows() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThatThrownBy(() -> registry.register(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("default unregister 抛 UnsupportedOperationException")
    void defaultUnregisterThrows() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThatThrownBy(() -> registry.unregister("test"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("default toToolDefinitionsJson 返回空数组")
    void defaultToJson() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThat(registry.toToolDefinitionsJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("default subset 返回自身")
    void defaultSubset() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThat(registry.subset(null)).isSameAs(registry);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbackRegistrySpiTest -DfailIfNoTests=false
```
Expected: FAIL — methods not found

- [ ] **Step 3: Add default methods to ToolCallbackRegistry**

```java
// Add to ToolCallbackRegistry interface:
import java.util.Collections;
import java.util.List;
import java.util.Map;

default void register(ToolCallback callback) { throw new UnsupportedOperationException("register not implemented"); }
default void unregister(String toolName) { throw new UnsupportedOperationException("unregister not implemented"); }
default List<ToolCallback> getAll() { return Collections.emptyList(); }
default String toToolDefinitionsJson() { return "[]"; }
default ToolCallbackRegistry subset(Map<String, String> pluginOverrides) { return this; }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbackRegistrySpiTest -DfailIfNoTests=false
```
Expected: PASS — 5 tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistry.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistrySpiTest.java
git commit -m "feat(tool): upgrade ToolCallbackRegistry SPI with register/unregister/getAll/toJson/subset (UC-07~10, UC-23)"
```

---

## Task 5: ToolCallbackRegistryImpl — ConcurrentHashMap Implementation (UC-07~10, UC-23)

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistryImpl.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistryImplTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ToolCallbackRegistryImpl")
class ToolCallbackRegistryImplTest {

    private ToolCallback callback(String name, boolean system) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getName()).thenReturn(name);
        when(cb.isSystem()).thenReturn(system);
        when(cb.getDescription()).thenReturn("desc-" + name);
        when(cb.getJsonSchema()).thenReturn("{\"type\":\"object\"}");
        return cb;
    }

    @Test
    @DisplayName("register + find + getAll (UC-07)")
    void registerFindGetAll() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        ToolCallback cb = callback("mysql_query", false);
        registry.register(cb);
        assertThat(registry.getAll()).hasSize(1);
        assertThat(registry.find("mysql_query")).isSameAs(cb);
        assertThat(registry.find("nonexistent")).isNull();
    }

    @Test
    @DisplayName("重复注册抛 IllegalArgumentException (UC-08)")
    void duplicateRegisterThrows() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("mysql_query", false));
        assertThatThrownBy(() -> registry.register(callback("mysql_query", false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tool already registered: mysql_query");
    }

    @Test
    @DisplayName("system 不可 unregister (UC-09)")
    void systemCannotUnregister() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("mysql_query", true));
        assertThatThrownBy(() -> registry.unregister("mysql_query"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("cannot unregister system tool");
    }

    @Test
    @DisplayName("非 system 可 unregister (UC-09)")
    void nonSystemCanUnregister() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("custom_tool", false));
        registry.unregister("custom_tool");
        assertThat(registry.getAll()).isEmpty();
        assertThat(registry.find("custom_tool")).isNull();
    }

    @Test
    @DisplayName("toToolDefinitionsJson 生成 JSON 数组 (UC-10)")
    void toToolDefinitionsJson() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("mysql_query", false));
        registry.register(callback("log_read", false));
        String json = registry.toToolDefinitionsJson();
        assertThat(json).startsWith("[").endsWith("]");
        assertThat(json).contains("mysql_query");
        assertThat(json).contains("log_read");
        assertThat(json).contains("desc-mysql_query");
        assertThat(json).contains("input_schema");
    }

    @Test
    @DisplayName("subset null 返回全部 (UC-23)")
    void subsetNullReturnsAll() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("tool_a", false));
        registry.register(callback("tool_b", false));
        ToolCallbackRegistry sub = registry.subset(null);
        assertThat(sub.getAll()).hasSize(2);
    }

    @Test
    @DisplayName("subset 选子集 (UC-23)")
    void subsetSelects() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("local-log", false));
        registry.register(callback("remote-log", false));
        Map<String, String> overrides = new HashMap<>();
        overrides.put("log_read", "remote-log");
        ToolCallbackRegistry sub = registry.subset(overrides);
        assertThat(sub.find("remote-log")).isNotNull();
        assertThat(sub.find("local-log")).isNull();
    }

    @Test
    @DisplayName("subset 指向不存在的工具抛异常 (UC-23)")
    void subsetNonExistentThrows() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("tool_a", false));
        Map<String, String> overrides = new HashMap<>();
        overrides.put("log_read", "nonexistent");
        assertThatThrownBy(() -> registry.subset(overrides))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tool not found in registry: nonexistent");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbackRegistryImplTest -DfailIfNoTests=false
```
Expected: FAIL — `ToolCallbackRegistryImpl` not found

- [ ] **Step 3: Write implementation**

```java
package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
        callbacks.remove(toolName);
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbackRegistryImplTest -DfailIfNoTests=false
```
Expected: PASS — 8 tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistryImpl.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistryImplTest.java
git commit -m "feat(tool): add ToolCallbackRegistryImpl with ConcurrentHashMap (UC-07~10, UC-23)"
```

---

## Task 6: ToolCallbacks.from() — Reflection Factory (UC-11~14, UC-01~04)

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbacks.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolCallbacksTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ToolCallbacks.from() 反射工厂")
class ToolCallbacksTest {

    static class MultiTool {
        @Tool(name = "query", description = "查询数据")
        public String query(@ToolParam(description = "SQL语句") String sql) {
            return "result: " + sql;
        }

        @Tool(name = "insert", description = "插入数据")
        public String insert(@ToolParam(description = "数据") String data) {
            return "inserted: " + data;
        }

        @Tool(name = "delete", description = "删除数据")
        public String delete(@ToolParam(description = "条件") String condition) {
            return "deleted: " + condition;
        }
    }

    static class DefaultNameTool {
        @Tool(description = "默认name用方法名")
        public String ping(@ToolParam(description = "消息") String msg) {
            return "pong: " + msg;
        }
    }

    static class NoToolClass {
        public String regularMethod() { return "hello"; }
    }

    static class MissingToolParam {
        @Tool(description = "缺少ToolParam")
        public String query(String sql) { return sql; }
    }

    static class NonStringReturn {
        @Tool(name = "count", description = "计数")
        public int count(@ToolParam(description = "表名") String table) {
            return 42;
        }
    }

    static class ReturnDirectTool {
        @Tool(name = "render", description = "渲染", returnDirect = true)
        public String render(@ToolParam(description = "模板") String template) {
            return "<html>" + template + "</html>";
        }
    }

    @Test
    @DisplayName("多个 @Tool 方法 → ToolCallback[3] (UC-11)")
    void multipleToolMethods() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        assertThat(callbacks).hasSize(3);
        assertThat(callbacks[0].getName()).isEqualTo("query");
        assertThat(callbacks[1].getName()).isEqualTo("insert");
        assertThat(callbacks[2].getName()).isEqualTo("delete");
    }

    @Test
    @DisplayName("description 正确提取")
    void descriptionExtracted() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        assertThat(callbacks[0].getDescription()).isEqualTo("查询数据");
    }

    @Test
    @DisplayName("默认 name 用方法名 (UC-01)")
    void defaultNameIsMethodName() {
        ToolCallback[] callbacks = ToolCallbacks.from(new DefaultNameTool());
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getName()).isEqualTo("ping");
    }

    @Test
    @DisplayName("无 @Tool 方法返回空数组 (UC-12)")
    void noToolMethodsReturnsEmpty() {
        ToolCallback[] callbacks = ToolCallbacks.from(new NoToolClass());
        assertThat(callbacks).isEmpty();
    }

    @Test
    @DisplayName("缺少 @ToolParam 抛 IllegalStateException (UC-13)")
    void missingToolParamThrows() {
        assertThatThrownBy(() -> ToolCallbacks.from(new MissingToolParam()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("parameter missing @ToolParam");
    }

    @Test
    @DisplayName("非 String 返回 → String.valueOf (UC-14)")
    void nonStringReturn() {
        ToolCallback[] callbacks = ToolCallbacks.from(new NonStringReturn());
        assertThat(callbacks).hasSize(1);
        ToolResult result = callbacks[0].execute(
            new java.util.HashMap<String, Object>() {{ put("table", "users"); }}, null);
        assertThat(result.getContent()).isEqualTo("42");
    }

    @Test
    @DisplayName("returnDirect=true 正确传递 (UC-02)")
    void returnDirectPropagated() {
        ToolCallback[] callbacks = ToolCallbacks.from(new ReturnDirectTool());
        assertThat(callbacks[0].isReturnDirect()).isTrue();
    }

    @Test
    @DisplayName("execute 调用真实方法")
    void executeCallsRealMethod() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("sql", "SELECT 1");
        ToolResult result = callbacks[0].execute(args, null);
        assertThat(result.getContent()).isEqualTo("result: SELECT 1");
    }

    @Test
    @DisplayName("getJsonSchema 生成正确 JSON")
    void jsonSchemaGenerated() {
        ToolCallback[] callbacks = ToolCallbacks.from(new MultiTool());
        String schema = callbacks[0].getJsonSchema();
        assertThat(schema).contains("\"type\":\"object\"");
        assertThat(schema).contains("\"sql\"");
        assertThat(schema).contains("\"string\"");
        assertThat(schema).contains("\"SQL语句\"");
        assertThat(schema).contains("\"required\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbacksTest -DfailIfNoTests=false
```
Expected: FAIL — `ToolCallbacks` not found

- [ ] **Step 3: Write implementation**

```java
package cn.watsontech.snapagent.core.tool;

import cn.watsontech.snapagent.core.graph.hitl.ToolApproval;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection factory that scans a target object for @Tool methods
 * and produces ToolCallback[] with auto-generated JSON Schema.
 */
public class ToolCallbacks {

    public static ToolCallback[] from(Object target) {
        if (target == null) {
            return new ToolCallback[0];
        }
        return from(target.getClass(), target);
    }

    public static ToolCallback[] from(Class<?> clazz) {
        return from(clazz, null);
    }

    private static ToolCallback[] from(Class<?> clazz, Object instance) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }
            callbacks.add(buildCallback(method, toolAnnotation, instance));
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    private static ToolCallback buildCallback(Method method, Tool toolAnno, Object instance) {
        // Validate parameters have @ToolParam
        Parameter[] params = method.getParameters();
        String[] paramNames = new String[params.length];
        ToolParam[] paramAnnotations = new ToolParam[params.length];

        for (int i = 0; i < params.length; i++) {
            ToolParam tp = params[i].getAnnotation(ToolParam.class);
            if (tp == null) {
                throw new IllegalStateException(
                    "parameter missing @ToolParam: parameter '" + params[i].getName()
                    + "' in method '" + method.getName() + "'");
            }
            paramAnnotations[i] = tp;
            paramNames[i] = params[i].getName();
        }

        // Determine tool name
        String name = toolAnno.name();
        if (name == null || name.isEmpty()) {
            name = method.getName();
        }

        // Check @ToolApproval
        ToolApproval approval = method.getAnnotation(ToolApproval.class);
        boolean approvalRequired = approval != null && approval.required();

        // Build JSON Schema
        String jsonSchema = buildJsonSchema(paramNames, paramAnnotations, params);
        String description = toolAnno.description();
        boolean returnDirect = toolAnno.returnDirect();

        final String toolName = name;
        final Method toolMethod = method;
        final Object toolInstance = instance;
        final boolean isApprovalRequired = approvalRequired;

        return new ToolCallback() {
            @Override
            public String getName() { return toolName; }
            @Override
            public String getDescription() { return description; }
            @Override
            public String getJsonSchema() { return jsonSchema; }
            @Override
            public boolean isReturnDirect() { return returnDirect; }
            @Override
            public boolean isSystem() { return false; }
            @Override
            public boolean isApprovalRequired() { return isApprovalRequired; }

            @Override
            public ToolResult execute(Map<String, Object> args, Object context) {
                try {
                    toolMethod.setAccessible(true);
                    Object[] invokeArgs = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Object val = args != null ? args.get(paramNames[i]) : null;
                        invokeArgs[i] = convertType(val, params[i].getType());
                    }
                    Object result = toolMethod.invoke(toolInstance, invokeArgs);
                    String content = result == null ? "void" : result.toString();
                    return ToolResult.success(content, 0, 0, null);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return ToolResult.error("tool execution failed: " + cause.getMessage(), 0);
                }
            }
        };
    }

    private static String buildJsonSchema(String[] paramNames, ToolParam[] paramAnnotations, Parameter[] params) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        List<String> required = new ArrayList<>();
        for (int i = 0; i < paramNames.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(paramNames[i]).append("\":{");
            sb.append("\"type\":\"").append(schemaType(params[i].getType())).append("\"");
            sb.append(",\"description\":\"").append(escapeJson(paramAnnotations[i].description())).append("\"");
            sb.append("}");
            if (paramAnnotations[i].required()) {
                required.add(paramNames[i]);
            }
        }
        sb.append("},\"required\":[");
        for (int i = 0; i < required.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(required.get(i)).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String schemaType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type == float.class || type == Float.class) return "number";
        if (type == double.class || type == Double.class) return "number";
        if (List.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    @SuppressWarnings("unchecked")
    private static Object convertType(Object value, Class<?> targetType) {
        if (value == null) {
            return defaultValue(targetType);
        }
        if (targetType == String.class) return value.toString();
        if (targetType == int.class || targetType == Integer.class) return Integer.valueOf(value.toString());
        if (targetType == long.class || targetType == Long.class) return Long.valueOf(value.toString());
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.valueOf(value.toString());
        if (targetType == float.class || targetType == Float.class) return Float.valueOf(value.toString());
        if (targetType == double.class || targetType == Double.class) return Double.valueOf(value.toString());
        return value;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ToolCallbacksTest -DfailIfNoTests=false
```
Expected: PASS — 9 tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbacks.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolCallbacksTest.java
git commit -m "feat(tool): add ToolCallbacks.from() reflection factory with JSON Schema generation (UC-11~14, UC-01~04)"
```

---

## Task 7: Update AgentNode — Build Tool Defs from registry.getAll() (UC-31~35)

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/react/AgentNode.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/graph/react/AgentNodeTest.java` (existing — add test)

- [ ] **Step 1: Write the failing test (add to existing AgentNodeTest)**

```java
@Test
@DisplayName("tool defs 从 registry.getAll() 构建")
void toolDefsFromRegistry() throws InterruptException {
    LlmClient llmClient = mock(LlmClient.class);
    LlmRequest[] capturedReq = new LlmRequest[1];
    doAnswer(inv -> {
        capturedReq[0] = inv.getArgument(0);
        LlmEventSink sink = inv.getArgument(1);
        sink.onStop("end_turn");
        return null;
    }).when(llmClient).stream(any(), any(), any());

    // Create a real ToolCallback with description + jsonSchema
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getName()).thenReturn("mysql_query");
    when(callback.getDescription()).thenReturn("执行SQL查询");
    when(callback.getJsonSchema()).thenReturn("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}");

    ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
    when(registry.getAll()).thenReturn(java.util.Collections.singletonList(callback));

    ExecutionContext ctx = mock(ExecutionContext.class);
    when(ctx.getLlmClient()).thenReturn(llmClient);
    when(ctx.getTools()).thenReturn(registry);
    when(ctx.getTaskId()).thenReturn("t-1");

    AgentNode node = new AgentNode(testSkill(), testTask());
    GraphState state = GraphState.empty("t1")
            .with("system.prompt", "你是诊断 agent")
            .with("user.message", "分析问题");

    node.execute(state, ctx);

    // Verify tool defs were passed to LlmRequest
    assertThat(capturedReq[0].getTools()).hasSize(1);
    assertThat(capturedReq[0].getTools().get(0).getName()).isEqualTo("mysql_query");
    assertThat(capturedReq[0].getTools().get(0).getDescription()).isEqualTo("执行SQL查询");
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=AgentNodeTest -DfailIfNoTests=false
```
Expected: FAIL — AgentNode passes empty toolDefs, not from registry

- [ ] **Step 3: Modify AgentNode to build tool defs from registry**

Replace the tool defs line in AgentNode.execute():

```java
// OLD:
// List<ToolDef> toolDefs = Collections.emptyList();

// NEW:
List<ToolDef> toolDefs = new ArrayList<>();
if (toolRegistry != null) {
    for (ToolCallback callback : toolRegistry.getAll()) {
        toolDefs.add(new ToolDef(
            callback.getName(),
            callback.getDescription(),
            callback.getJsonSchema()
        ));
    }
}
```

Add imports:
```java
import cn.watsontech.snapagent.core.tool.ToolCallback;
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=AgentNodeTest -DfailIfNoTests=false
```
Expected: PASS — 6 tests

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/react/AgentNode.java snap-agent-core/src/test/java/cn/watsontech/snapagent/core/graph/react/AgentNodeTest.java
git commit -m "feat(graph): AgentNode builds tool defs from registry.getAll() (UC-31~35)"
```

---

## Task 8: Delete 1.x Tool Classes — Clean Break

**Files:**
- Delete: 10 files from `snap-agent-core/.../tool/`
- Modify: `snap-agent-core/.../tool/ToolContext.java` (simplify)

- [ ] **Step 1: Run existing tests to get baseline**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -DfailIfNoTests=false 2>&1 | tail -3
```
Expected: Current tests pass

- [ ] **Step 2: Delete 1.x files**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolDispatcher.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolProvider.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolPluginAnnotation.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginRegistry.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistry.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolPlugin.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginContext.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginDescriptor.java
git rm snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/AuditCallback.java
```

- [ ] **Step 3: Simplify ToolContext (remove PluginContext/AuditCallback deps)**

Replace `ToolContext.java` with simplified version:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context object passed to ToolCallback.execute carrying request-scoped info.
 * Immutable — all fields are final.
 */
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final Map<String, String> pluginOverrides;

    public ToolContext(String taskId, String userId) {
        this(taskId, userId, null);
    }

    public ToolContext(String taskId, String userId, Map<String, String> pluginOverrides) {
        this.taskId = taskId;
        this.userId = userId;
        this.pluginOverrides = pluginOverrides != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverrides))
                : Collections.<String, String>emptyMap();
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public Map<String, String> getPluginOverrides() { return pluginOverrides; }
}
```

- [ ] **Step 4: Fix compilation errors**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml 2>&1 | grep "找不到符号\|cannot find symbol" | head -20
```

Fix each broken reference. Most will be in test files or starter module (transitional — OK to fail for Phase 2).

- [ ] **Step 5: Run all core tests to verify**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -DfailIfNoTests=false 2>&1 | tail -5
```
Expected: Core module tests pass (starter module may have transitional errors)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(tool): delete 1.x ToolDispatcher/ToolProvider/PluginRegistry — clean break"
```

---

## Task 9: Delete Old Tool Tests

**Files:**
- Delete: old test files that reference deleted 1.x classes

- [ ] **Step 1: Find broken test files**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
find snap-agent-core/src/test -name "*.java" -exec grep -l "ToolDispatcher\|ToolProvider\|PluginRegistry\|InMemoryPluginRegistry\|ToolPlugin\|PluginContext\|PluginDescriptor\|AuditCallback\|ToolPluginAnnotation" {} \;
```

- [ ] **Step 2: Delete broken test files**

```bash
git rm <list of broken test files from Step 1>
```

- [ ] **Step 3: Run all core tests to verify**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test(tool): delete 1.x tool tests referencing deleted classes"
```

---

## Phase 2 Summary

### Coverage Matrix

| UC ID | Task | Status |
|-------|------|--------|
| UC-01~02 | Task 1: @Tool annotation | - |
| UC-03~04 | Task 1: @ToolParam annotation | - |
| UC-05 | Task 3: ToolCallback getDescription/getJsonSchema | - |
| UC-06 | Task 6: ToolCallbacks.from() error handling | - |
| UC-07~10 | Task 5: ToolCallbackRegistryImpl | - |
| UC-11~14 | Task 6: ToolCallbacks.from() reflection | - |
| UC-15~17 | (Phase 1: ToolsNode already implemented) | - |
| UC-18~21 | (Phase 1: @ToolApproval already implemented) | - |
| UC-22 | Task 2: ToolResult originalLength | - |
| UC-23 | Task 5: subset per-request | - |
| UC-24~26 | (Deferred to Phase 10: Plugin/MCP) | deferred |
| UC-R1~R2 | (Deferred to Phase 12: Host Integration) | deferred |
