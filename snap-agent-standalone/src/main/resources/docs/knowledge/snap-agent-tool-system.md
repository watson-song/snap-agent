---
name: snap-agent-tool-system
description: Tool 系统详解 — ToolCallback SPI、ToolCallbackRegistry、插件机制、内置工具
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Tool 系统

## 1. 架构概述

Tool 系统是 SnapAgent 与外部世界交互的接口，允许 Agent 执行数据库查询、文件操作、API 调用等动作。

```
┌─────────────────────────────────────────────────────────
│                    AgentNode                             │
│                                                         │
│  LLM 返回 tool_calls: [                                 │
│    {name: "mysql_query", input: {sql: "SELECT ..."}},  │
│    {name: "redis_get", input: {key: "user:123"}}       │
│  ]                                                       │
└────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  ToolsNode                               │
│                                                         │
│  for (ToolCall call : tool_calls) {                    │
│    ToolCallback tool = registry.find(call.getName());  │
│    Object result = tool.execute(call.getInput());      │
│    results.add(result);                                │
│  }                                                       │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              ToolCallbackRegistry                        │
│                                                         │
│  find(name) → ToolCallback                              │
│  getAll() → List<ToolCallback>                          │
│  toToolDefinitionsJson() → "[...]"                      │
└────────────────────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
─────────────┐ ┌─────────────┐ ┌─────────────┐
│ mysql_query │ │  redis_get  │ │ log_search  │
│   (JDBC)    │ │   (Redis)   │ │   (File)    │
└─────────────┘ └─────────────┘ └─────────────┘
```

## 2. ToolCallback SPI

### 2.1 接口定义

```java
public interface ToolCallback {
    /** 工具名称，LLM 通过此名称调用 */
    String getName();

    /** 工具描述，用于 LLM 理解工具功能 */
    String getDescription();

    /** JSON Schema 参数定义 */
    String getParametersJsonSchema();

    /**
     * 执行工具
     * @param input 参数 Map
     * @return 执行结果（自动序列化为 JSON）
     */
    Object execute(Map<String, Object> input);
}
```

### 2.2 示例实现

```java
@Component
public class MySqlQueryTool implements ToolCallback {
    private final DataSource dataSource;

    @Override
    public String getName() { return "mysql_query"; }

    @Override
    public String getDescription() {
        return "Execute read-only SQL query against the application database";
    }

    @Override
    public String getParametersJsonSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "sql": {
              "type": "string",
              "description": "SQL query to execute (SELECT only)"
            }
          },
          "required": ["sql"]
        }
        """;
    }

    @Override
    public Object execute(Map<String, Object> input) {
        String sql = (String) input.get("sql");
        
        // 安全检查：只允许 SELECT
        if (!sql.trim().toUpperCase().startsWith("SELECT")) {
            throw new IllegalArgumentException("Only SELECT queries allowed");
        }

        // 执行查询
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            return resultSetToList(rs);
        }
    }
}
```

## 3. ToolCallbackRegistry

### 3.1 接口定义

```java
public interface ToolCallbackRegistry {
    /** 根据名称查找工具 */
    ToolCallback find(String name);

    /** 注册工具（动态添加） */
    default void register(ToolCallback callback) {
        throw new UnsupportedOperationException();
    }

    /** 注销工具 */
    default void unregister(String toolName) {
        throw new UnsupportedOperationException();
    }

    /** 获取所有工具 */
    default List<ToolCallback> getAll() {
        return Collections.emptyList();
    }

    /** 导出为 JSON Schema（给 LLM 用） */
    default String toToolDefinitionsJson() {
        return "[]";
    }

    /** 创建子集（插件覆盖场景） */
    default ToolCallbackRegistry subset(Map<String, String> pluginOverrides) {
        return this;
    }
}
```

### 3.2 默认实现

```java
public class PluginToolCallbackRegistry implements ToolCallbackRegistry {
    private final Map<String, ToolCallback> tools = new ConcurrentHashMap<>();

    @Override
    public ToolCallback find(String name) {
        return tools.get(name);
    }

    @Override
    public void register(ToolCallback callback) {
        tools.put(callback.getName(), callback);
    }

    @Override
    public List<ToolCallback> getAll() {
        return new ArrayList<>(tools.values());
    }

    @Override
    public String toToolDefinitionsJson() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (ToolCallback tool : tools.values()) {
            defs.add(Map.of(
                "type", "function",
                "function", Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "parameters", parseJson(tool.getParametersJsonSchema())
                )
            ));
        }
        return toJson(defs);
    }
}
```

## 4. 内置工具

### 4.1 数据库工具

| 工具名 | 数据源 | 只读 | 说明 |
|--------|--------|------|------|
| `mysql_query` | DataSource | ✅ | 执行 SELECT 查询 |
| `redis_get` | RedisTemplate | ✅ | 读取 Redis Key |
| `redis_scan` | RedisTemplate | ✅ | 扫描 Redis Keys |

### 4.2 文件工具

| 工具名 | 路径 | 只读 | 说明 |
|--------|------|------|------|
| `log_search` | /logs | ✅ | 搜索日志文件 |
| `log_read` | /logs | ✅ | 读取日志内容 |
| `file_read` | 配置路径 | ✅ | 读取任意文件 |

### 4.3 代码工具

| 工具名 | 范围 | 只读 | 说明 |
|--------|------|------|------|
| `project_structure` | 项目根 | ✅ | 扫描项目结构 |
| `code_read` | src/main | ✅ | 读取源码文件 |
| `git_log` | .git | ✅ | 查看 Git 历史 |

### 4.4 网络工具

| 工具名 | 目标 | 说明 |
|--------|------|------|
| `http_get` | 任意 URL | GET 请求 |
| `http_post` | 任意 URL | POST 请求 |
| `bridge_request` | 内网服务 | 通过浏览器代理 |

## 5. 工具注册流程

### 5.1 自动扫描

```java
@Component
public class ToolAutoConfiguration {
    @Bean
    public ToolCallbackRegistry toolCallbackRegistry(
            ObjectProvider<List<ToolCallback>> toolsProvider) {
        
        PluginToolCallbackRegistry registry = new PluginToolCallbackRegistry();
        
        // 自动注册所有 ToolCallback Bean
        List<ToolCallback> tools = toolsProvider.getIfAvailable();
        if (tools != null) {
            for (ToolCallback tool : tools) {
                registry.register(tool);
            }
        }
        
        return registry;
    }
}
```

### 5.2 插件工具

```java
// 用户上传的插件工具
public class PluginToolLoader {
    public void loadPlugin(String pluginDir) {
        // 1. 加载 JAR
        URLClassLoader loader = new URLClassLoader(...);
        
        // 2. 扫描 ToolCallback 实现
        ServiceLoader<ToolCallback> serviceLoader = 
            ServiceLoader.load(ToolCallback.class, loader);
        
        // 3. 注册到 Registry
        for (ToolCallback tool : serviceLoader) {
            registry.register(tool);
        }
    }
}
```

## 6. 工具执行流程

### 6.1 ToolsNode 执行

```java
public class ToolsNode implements Node {
    private final ToolCallbackRegistry registry;
    private final int maxToolResultChars;

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) {
        List<ToolCall> toolCalls = state.get("tool_calls");
        if (toolCalls == null || toolCalls.isEmpty()) {
            return state;
        }

        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            try {
                // 1. 查找工具
                ToolCallback tool = registry.find(call.getName());
                if (tool == null) {
                    results.add(ToolResult.error(call.getId(), 
                        "Tool not found: " + call.getName()));
                    continue;
                }

                // 2. 执行工具
                Object result = tool.execute(call.getInput());
                
                // 3. 序列化结果
                String resultJson = serialize(result);
                if (resultJson.length() > maxToolResultChars) {
                    resultJson = resultJson.substring(0, maxToolResultChars) + "...";
                }
                
                results.add(ToolResult.success(call.getId(), resultJson));
            } catch (Exception e) {
                results.add(ToolResult.error(call.getId(), e.getMessage()));
            }
        }

        return state.with("tool_results", results);
    }
}
```

### 6.2 结果处理

```
ToolResult.success(id, result):
  ─ 注入到 LLM 下一轮请求
  ─ 格式：{"role": "tool", "content": "...", "tool_call_id": "..."}

ToolResult.error(id, error):
  ─ 返回错误信息给 LLM
  ─ LLM 可决定重试或放弃
```

## 7. 工具定义导出

### 7.1 JSON Schema 格式

```json
[
  {
    "type": "function",
    "function": {
      "name": "mysql_query",
      "description": "Execute read-only SQL query",
      "parameters": {
        "type": "object",
        "properties": {
          "sql": {
            "type": "string",
            "description": "SQL query (SELECT only)"
          }
        },
        "required": ["sql"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "redis_get",
      "description": "Get Redis key value",
      "parameters": {
        "type": "object",
        "properties": {
          "key": {
            "type": "string",
            "description": "Redis key"
          }
        },
        "required": ["key"]
      }
    }
  }
]
```

### 7.2 导出时机

- **AgentNode 执行前**：注入到 LLM 请求的 `tools` 字段
- **Skill 校验时**：验证 skill.tools 声明的工具是否已注册
- **API 查询时**：`GET /snap-agent/tools` 返回工具列表

## 8. 配置属性

```yaml
snap-agent:
  tool:
    max-result-chars: 4000      # 工具结果最大字符数
    timeout-seconds: 30         # 工具执行超时
    jdbc:
      enabled: true
      datasource-bean-name: dataSource
    redis:
      enabled: false
    log:
      enabled: true
      allowed-paths:
        - /logs
        - /tmp
```

## 9. 自定义工具开发

### 9.1 实现接口

```java
@Component
public class MyCustomTool implements ToolCallback {
    @Override
    public String getName() { return "my_custom_tool"; }

    @Override
    public String getDescription() { return "My custom tool description"; }

    @Override
    public String getParametersJsonSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "param1": {"type": "string", "description": "Description"}
          },
          "required": ["param1"]
        }
        """;
    }

    @Override
    public Object execute(Map<String, Object> input) {
        String param1 = (String) input.get("param1");
        // 执行逻辑...
        return Map.of("result", "success");
    }
}
```

### 9.2 声明为 Bean

```java
@Configuration
public class MyToolConfig {
    @Bean
    public ToolCallback myCustomTool() {
        return new MyCustomTool();
    }
}
```

### 9.3 在 Skill 中使用

```markdown
---
name: my-skill
tools:
  - my_custom_tool
---

# My Skill

## Step 1: Use Custom Tool
Use `my_custom_tool` to ...
```

## 10. 常见问题

### Q1: 工具未找到？
```
错误：Tool not found: xxx
原因：工具未注册或名称拼写错误
解决：检查 @Component 扫描或手动注册
```

### Q2: 工具执行超时？
```
错误：Tool execution timeout
原因：工具执行超过 timeout-seconds
解决：优化工具性能或增加超时配置
```

### Q3: 工具结果被截断？
```
现象：结果末尾有 "..."
原因：超过 max-result-chars 限制
解决：增加配置值或优化工具返回结构
```
