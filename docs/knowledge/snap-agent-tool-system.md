---
name: 工具系统
description: SnapAgent 工具系统 — 工具注册、工具回调、内置工具（代码读取、JDBC 查询）
version: 1.0.0
tables: []
services: [ToolCallbackRegistry, ToolCallback, CodeReadTool, JdbcQueryTool]
entry_points: [ToolCallbackRegistry.register(), ToolCallback.execute()]
related_concepts: [Agent 执行，技能系统，LLM 工具调用]
tags: [tool, callback, registry, code-read, jdbc-query]
version: 1.0.0
module: snap-agent-core
author: SnapAgent
---

# 工具系统

## 1. 模块概述

工具系统提供 Agent 可调用的工具注册和执行机制，支持代码读取、JDBC 查询等内置工具，支持扩展自定义工具。

## 2. 核心类

### 2.1 ToolCallbackRegistry (工具回调注册表)

| 方法 | 说明 |
|------|------|
| register(ToolCallback callback) | 注册工具 |
| unregister(String toolName) | 注销工具 |
| get(String toolName) | 获取工具 |
| getAll() | 获取所有工具 |
| getToolDefs() | 获取工具定义列表（给 LLM） |

### 2.2 ToolCallback (工具回调接口)

```java
public interface ToolCallback {
    /** 工具名称 */
    String getName();
    
    /** 工具描述 */
    String getDescription();
    
    /** 工具参数定义 */
    Map<String, Object> getParameters();
    
    /** 执行工具 */
    String execute(Map<String, Object> input);
}
```

### 2.3 CodeReadTool (代码读取工具)

| 参数 | 类型 | 说明 |
|------|------|------|
| className | String | 类名 |
| packageName | String | 包名（可选） |
| sourceDir | String | 源码目录（可选） |

**功能**：
- 按类名查找 Java 文件
- 提取关键代码（类注释、字段、public 方法签名）
- 返回代码片段给 Agent

### 2.4 JdbcQueryTool (JDBC 查询工具)

| 参数 | 类型 | 说明 |
|------|------|------|
| sql | String | SQL 语句 |
| params | List\<Object\> | 参数列表 |

**功能**：
- 执行 SQL 查询
- 返回结果集（JSON 格式）
- 支持参数化查询防止 SQL 注入

## 3. 内置工具

| 工具名 | 类名 | 说明 |
|--------|------|------|
| code_read | CodeReadTool | 读取源码文件 |
| jdbc_query | JdbcQueryTool | 执行 SQL 查询 |
| codegraph_find_class | CodeGraphFindClassTool | 查找类信息 |
| codegraph_call_chain | CodeGraphCallChainTool | 查询调用链 |
| codegraph_reverse_call_chain | CodeGraphReverseCallChainTool | 查询反向调用链 |
| codegraph_impact_analysis | CodeGraphImpactAnalysisTool | 影响范围分析 |

## 4. 工具注册流程

```
1. 启动时自动扫描
2. 通过 @Component 注解的工具类自动注册
3. 手动注册：toolCallbackRegistry.register(new MyTool())
4. 从配置文件加载工具定义
```

## 5. 工具执行流程

```
1. Agent 解析 LLM 响应，识别工具调用
2. 从 ToolCallbackRegistry 获取工具
3. 调用 ToolCallback.execute(input)
4. 获取执行结果
5. 将结果封装为 tool_result 消息
6. 继续下一轮 LLM 调用
```

## 6. 扩展自定义工具

### 6.1 实现 ToolCallback 接口

```java
@Component
public class MyCustomTool implements ToolCallback {
    @Override
    public String getName() {
        return "my_custom_tool";
    }
    
    @Override
    public String getDescription() {
        return "我的自定义工具";
    }
    
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", Map.of(
            "type", "string",
            "description", "参数 1"
        ));
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> input) {
        String param1 = (String) input.get("param1");
        // 执行工具逻辑
        return "结果：" + param1;
    }
}
```

### 6.2 在技能中引用工具

```markdown
---
name: 我的技能
tables: [my_table]
services: [MyService]
entry_points: [POST /api/my-endpoint]
related_concepts: [相关概念]
tags: [my-tag]
---

# 我的技能

## 技能描述
使用 `my_custom_tool` 工具执行...

## 使用示例
调用 `my_custom_tool` 工具，传入参数...
```

## 7. 工具安全

### 7.1 SQL 注入防护

JdbcQueryTool 使用参数化查询：
```java
// 安全：参数化查询
String sql = "SELECT * FROM users WHERE id = ?";
List<Object> params = List.of(123);

// 不安全：字符串拼接（禁止）
String sql = "SELECT * FROM users WHERE id = " + userId;
```

### 7.2 文件访问控制

CodeReadTool 限制源码目录：
```yaml
snap-agent:
  tool:
    code-read:
      source-dirs:
        - ./src/main/java
        - ./src/test/java
      max-file-size: 50000  # 最大文件大小（字节）
```

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
