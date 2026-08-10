---
name: snap-agent-tool-system
description: Tool 系统 — @Tool 注解、ToolCallback SPI、ToolCallbackRegistry、ToolCallbacks 反射工厂、内置工具
version: 2.0.0
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

## 2. 核心 SPI

### 2.1 @Tool + @ToolParam 注解

```java
// core/tool/Tool.java
@Retention(RUNTIME) @Target(METHOD)
public @interface Tool {
    String name() default "";        // 空=用方法名
    String description();            // LLM 理解用
    boolean returnDirect() default false;
}

// core/tool/ToolParam.java
@Retention(RUNTIME) @Target(PARAMETER)
public @interface ToolParam {
    String description();
    boolean required() default true;
}
```

### 2.2 ToolCallback SPI

```java
// core/tool/ToolCallback.java
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

### 2.3 ToolCallbacks 反射工厂

```java
// core/tool/ToolCallbacks.java
ToolCallback[] callbacks = ToolCallbacks.from(myToolObject);
// 扫描 @Tool 方法 → 自动生成 JSON Schema → 构建 ToolCallback
// @ToolParam 参数 → JSON Schema properties
// @ToolApproval → isApprovalRequired()
```

### 2.4 ToolCallbackRegistry

```java
// core/tool/ToolCallbackRegistry.java
public interface ToolCallbackRegistry {
    ToolCallback find(String name);
    List<ToolCallback> getAll();
    String toToolDefinitionsJson();
}

// core/tool/ToolCallbackRegistryImpl.java — ConcurrentHashMap 实现
```

### 2.5 ToolResult

```java
// core/tool/ToolResult.java
ToolResult.success(content, inputTokens, outputTokens, toolUseId)
ToolResult.error(message, originalLength)
```

### 2.6 ToolContext

```java
// core/tool/ToolContext.java — 工具执行上下文
```

## 3. 插件系统

```java
// core/tool/ToolPlugin.java — 插件元数据 SPI
public interface ToolPlugin {
    default String name() { return ""; }
    default String version() { return ""; }
    default String description() { return ""; }
    default List<String> toolNames() { return Collections.emptyList(); }
}

// core/tool/PluginDescriptor.java — 插件描述
// core/tool/PluginRegistry.java — 插件注册表 SPI
// core/tool/InMemoryPluginRegistry.java — 默认实现

// boot2x/tool/ToolPluginRegistry.java — 收集所有 ToolPlugin bean
```

## 4. 内置工具（boot2x/tool/）

| 类 | 工具 | 说明 |
|----|------|------|
| `JdbcQueryTools` | jdbc_query | SQL 查询（SqlGuard 保护） |
| `RedisReadTools` | redis_get, redis_scan | Redis 只读 |
| `LogReadTools` | log_read | 日志文件读取 |
| `LogSearchTools` | log_search | Loki LogQL 搜索 |
| `MetricsTools` | metrics_query | Prometheus PromQL 查询 |
| `TraceSearchTools` | trace_search | Jaeger 链路追踪 |
| `ConfigReadTools` | config_read | Spring Environment / Nacos |
| `CodeReadTool` | code_read | 源码读取（CodePathGuard 保护）|
| `CodeReaderTools` | code_read 等 | 代码读取工具集 |
| `ProjectStructureTools` | project_structure | 项目目录树 |
| `GitLogTools` | git_log | Git 日志 |
| `CodeGraphTools` | code_graph_tools | 代码图谱查询 |
| `ModuleArchitectureTools` | generate_module_arch | 模块架构图 |
| `DomainKnowledgeTools` | domain_knowledge_tools | 领域知识工具 |

## 5. 安全守卫

| 类 | 保护对象 |
|----|---------|
| `SqlGuard` | SQL：禁 DDL/DML/危险函数 |
| `CodePathGuard` | 代码路径：限制 projectRoot + 扩展名白名单 |
| `LogPathGuard` | 日志路径：限制 allowed-paths |
