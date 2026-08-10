---
name: snap-agent-tool-system
description: Tool 系统 — @Tool/@ToolParam 注解、ToolCallback SPI、ToolCallbackRegistry、插件系统、内置工具、安全守卫
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Tool 系统

## 1. 架构概述

2.x 从 1.x 的 `ToolProvider` 接口迁移到 `@Tool` 注解声明式工具定义。

```
@Tool 方法 → ToolCallbacks.from() → ToolCallback[] → ToolCallbackRegistry
                                                           ↓
                                              AgentNode 构建 tool defs → LLM
                                              ToolsNode 执行 tool calls
```

## 2. 注解

### 2.1 @Tool（3 属性）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/Tool.java -->
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    String name() default "";
    String description();
    boolean returnDirect() default false;
}
```

### 2.2 @ToolParam（2 属性）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolParam.java -->
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    String description();
    boolean required() default true;
}
```

### 2.3 @ToolPlugin（6 属性）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolPlugin.java -->
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPlugin {
    String id();
    String toolType() default "";
    String displayName() default "";
    String version() default "1.0.0";
    String description() default "";
    boolean isDefault() default false;
}
```

## 3. 核心 SPI

### 3.1 ToolCallback（7 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallback.java -->
```java
public interface ToolCallback {
    ToolResult execute(Map<String, Object> input, Object context);
    String getName();
    default String getDescription() { return ""; }
    default String getJsonSchema() { return "{}"; }
    default boolean isReturnDirect() { return false; }
    default boolean isSystem() { return false; }
    default boolean isApprovalRequired() { return false; }
}
```

### 3.2 ToolCallbackRegistry（6 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistry.java -->
```java
public interface ToolCallbackRegistry {
    ToolCallback find(String name);
    default void register(ToolCallback callback) { throw new UnsupportedOperationException("register not implemented"); }
    default void unregister(String toolName) { throw new UnsupportedOperationException("unregister not implemented"); }
    default List<ToolCallback> getAll() { return Collections.emptyList(); }
    default String toToolDefinitionsJson() { return "[]"; }
    default ToolCallbackRegistry subset(Map<String, String> pluginOverrides) { return this; }
}
```

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbackRegistryImpl.java -->
`ToolCallbackRegistryImpl` — ConcurrentHashMap 默认实现。

### 3.3 ToolCallbacks 反射工厂（2 static 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolCallbacks.java -->
```java
public class ToolCallbacks {
    public static ToolCallback[] from(Object target)
    public static ToolCallback[] from(Class<?> clazz)
}
```

扫描 `@Tool` 方法 → 自动生成 JSON Schema → 构建 ToolCallback。`@ToolParam` 参数映射为 Schema properties。

### 3.4 ToolResult

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolResult.java -->
```java
public final class ToolResult {
    public static ToolResult success(String content, int rowCount, long durationMs)
    public static ToolResult success(String content, int rowCount, long durationMs, String toolUseId)
    public static ToolResult truncated(String content, int rowCount, long durationMs)
    public static ToolResult truncated(String content, int rowCount, long durationMs, int originalLength)
    public static ToolResult error(String message, long durationMs)
    public boolean isSuccess()
    public boolean isError()
    // getters: getContent, getRowCount, isTruncated, getDurationMs, getError, getOriginalLength, getToolUseId
}
```

### 3.5 ToolContext

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolContext.java -->
```java
public final class ToolContext {
    public ToolContext(String taskId, String userId)
    public ToolContext(String taskId, String userId, Map<String, String> pluginOverrides)
    public String getTaskId()
    public String getUserId()
    public Map<String, String> getPluginOverrides()
}
```

## 4. 插件系统

### 4.1 PluginRegistry（9 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/PluginRegistry.java -->
```java
public interface PluginRegistry {
    void register(PluginDescriptor descriptor);
    PluginDescriptor getPlugin(String pluginId);
    List<PluginDescriptor> listPlugins();
    void unregister(String pluginId);
    Boolean toggleEnabled(String pluginId);
    void enable(String pluginId);
    void disable(String pluginId);
    void setDefault(String toolType, String pluginId);
    PluginDescriptor getDefault(String toolType);
}
```

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistry.java -->
`InMemoryPluginRegistry` — 默认内存实现。

### 4.2 ToolPlugin SPI

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolPlugin.java -->
```java
public interface ToolPlugin {
    default String name() { return ""; }
    default String version() { return ""; }
    default String description() { return ""; }
    default List<String> toolNames() { return Collections.emptyList(); }
}
```

## 5. 内置工具（boot2x/tool/）

| 类 | 工具 | 说明 |
|----|------|------|
| `JdbcQueryTools` | jdbc_query | SQL 查询（SqlGuard 保护） |
| `RedisReadTools` | redis_get, redis_scan | Redis 只读 |
| `LogReadTools` | log_read | 日志文件读取 |
| `LogSearchTools` | log_search | Loki LogQL 搜索 |
| `MetricsTools` | metrics_query | Prometheus PromQL 查询 |
| `TraceSearchTools` | trace_search | Jaeger 链路追踪 |
| `ConfigReadTools` | config_read | Spring Environment / Nacos |
| `CodeReaderTools` | code_read | 代码读取（CodePathGuard 保护）|
| `ProjectStructureTools` | project_structure | 项目目录树 |
| `GitLogTools` | git_log | Git 日志 |
| `CodeGraphTools` | code_graph_tools | 代码图谱查询 |

## 6. 安全守卫

| 类 | 保护对象 |
|----|---------|
| `SqlGuard` | SQL：禁 DDL/DML/危险函数 |
| `CodePathGuard` | 代码路径：限制 projectRoot + 扩展名白名单 |
| `LogPathGuard` | 日志路径：限制 allowed-paths |
