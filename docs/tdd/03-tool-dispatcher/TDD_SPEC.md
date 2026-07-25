# TDD需求规格说明书 — @Tool 声明式工具 + ToolCallback SPI + ToolsNode

> 版本: 3.0 | 模块: `snap-agent-core/tool` + `snap-agent-spring-boot-2x-starter/tool`

---

## 1. 需求元信息

```yaml
需求ID: REQ-03-TOOL
需求名称: @Tool/@ToolParam 注解 + ToolCallback SPI + ToolCallbackRegistry + ToolsNode + @ToolApproval HITL
优先级: P0 | 迭代: 2.x | 状态: 重构中
```

**背景**: v1.x 的 `ToolDispatcher` 持有不可变 Map，`ToolProvider` SPI 仅为元数据层，无法热插拔。2.x 重构删除 `ToolDispatcher`/`ToolProvider`/`@ToolPluginAnnotation`/`PluginRegistry`，替换为声明式 `@Tool` 注解 + 统一 `ToolCallback` SPI + 图节点 `ToolsNode`。工具定义从"接口实现 + YAML 清单"变为"注解声明 + 反射发现"，减少 80% 样板代码。

**目标**: LLM 看到的工具定义由 `@Tool` + `@ToolParam` 注解自动生成 JSON Schema；工具执行从 `ToolDispatcher.dispatch()` 变为 `ToolsNode` 图节点接收 `tool_use` → 调用 `ToolCallback.execute()` → 结果截断 → SSE 事件 → 回传 LLM。Plugin 热插拔通过 `ToolCallbacks.from()` 反射发现 + `ToolCallbackRegistry.register()` 实现。

**范围**: `@Tool`/`@ToolParam` 注解、`ToolCallback` SPI、`ToolCallbackRegistry` SPI、`ToolCallbacks.from()` 反射工厂、`ToolsNode` 图节点、`@ToolApproval` HITL 注解、`ToolResult` 值对象、JSON Schema 自动生成、Plugin 热加载产出 `ToolCallback[]`。**不含**: MCP 远程工具适配（见 09-plugin-mcp）、JAR 上传/ClassLoader 隔离（见 09-plugin-mcp）。

| 风险 | 描述 | 缓解 |
|------|------|------|
| R1 | `@Tool` 方法签名复杂（泛型/可变参数）反射失败 | `ToolCallbacks.from()` 抛明确异常，文档约束支持类型 |
| R2 | `ToolCallbackRegistry` 并发 register/unregister 与 ToolsNode 执行竞争 | `ConcurrentHashMap` + volatile |
| R3 | `@ToolApproval` 的 HITL 中断后 checkpoint 丢失 | `InterruptException` → `GraphExecutor` 存 checkpoint → `PAUSED` |
| R4 | 工具输出超长撑爆 LLM context | `ToolsNode` 截断 `maxToolResultChars`（默认 4000） |

---

## 2. 用户故事 (User Stories)

### US-1: @Tool 注解声明工具
```gherkin
作为 工具开发者
我希望 用 @Tool(name="mysql_query", description="执行只读SQL查询") 标注方法
以便 无需实现 ToolProvider 接口即可声明工具，减少 80% 样板代码
```
**AC:**
```gherkin
AC1: Given 类 MySqlQueryTool 含方法 @Tool(name="mysql_query", description="执行SQL") String query(@ToolParam("SQL语句") String sql)
  When ToolCallbacks.from(mySqlQueryTool)
  Then 返回 ToolCallback[] 含1个 callback
  And callback.getName() == "mysql_query"
  And callback.getDescription() == "执行SQL"
AC2: Given @Tool 无 name() 默认用方法名
  When ToolCallbacks.from(target)
  Then callback.getName() == 方法名
AC3: Given @Tool(returnDirect=true)
  When ToolCallback.execute 返回结果
  Then ToolResult.isReturnDirect() == true（结果直接回传用户，不回传 LLM）
```

### US-2: @ToolParam 参数元数据
```gherkin
作为 工具开发者
我希望 用 @ToolParam(description="SQL语句", required=true) 标注参数
  以便 LLM 知道每个参数的用途和是否必填
```
**AC:**
```gherkin
AC1: Given 方法含 @ToolParam(description="SQL语句") String sql
  When 生成 JSON Schema
  Then schema 含 parameters.sql.description == "SQL语句"
  And schema 含 parameters.sql.type == "string"
AC2: Given @ToolParam(required=false) String mode
  When 生成 JSON Schema
  Then schema 含 parameters.mode.required == false（默认 true）
AC3: Given 参数无 @ToolParam 注解
  When ToolCallbacks.from(target)
  Then 抛 IllegalStateException 含 "parameter missing @ToolParam: parameter 'sql'"
```

### US-3: ToolCallback 统一 SPI
```gherkin
作为 平台开发者
我希望 所有工具（内置 @Tool、Plugin、MCP）实现统一 ToolCallback 接口
  以便 ToolsNode 无差别调用，消除工具类型歧视
```
**AC:**
```gherkin
AC1: Given 内置 @Tool 方法 和 Plugin 产出 ToolCallback
  When ToolsNode 执行 tool_use
  Then 两者 execute() 调用方式完全一致，无 instanceof 分支
AC2: Given ToolCallback.execute(args, ctx) 返回 ToolResult.success
  When ToolsNode 处理
  Then ToolResult.content 回传 LLM 作为 tool_result
AC3: Given ToolCallback.execute 抛 RuntimeException
  When ToolsNode 处理
  Then 返回 ToolResult.error 含异常消息，LLM 收到错误自纠
```

### US-4: ToolCallbackRegistry 注册与查询
```gherkin
作为 平台开发者
我希望 ToolCallbackRegistry 提供 register/unregister/getAll/find/toToolDefinitionsJson
  以便 运行时动态管理工具集
```
**AC:**
```gherkin
AC1: Given registry 为空
  When register(callback)
  Then getAll() 返回 size==1 且 find(callback.getName()) 命中
AC2: Given registry 已注册 "mysql_query"
  When register(同名 callback)
  Then 抛 IllegalArgumentException "tool already registered: mysql_query"
AC3: Given registry 已注册 "mysql_query"(system=true)
  When unregister("mysql_query")
  Then 抛 UnsupportedOperationException "cannot unregister system tool"
AC4: Given registry 含3个 ToolCallback
  When toToolDefinitionsJson()
  Then 返回 JSON 数组含3个 {name, description, input_schema} 对象
```

### US-5: ToolCallbacks.from() 反射自动发现
```gherkin
作为 工具开发者
我希望 ToolCallbacks.from(target) 自动扫描 @Tool 方法并生成 ToolCallback[]
  以便 零配置注册工具
```
**AC:**
```gherkin
AC1: Given 类含3个 @Tool 方法
  When ToolCallbacks.from(target)
  Then 返回 ToolCallback[3]，每个含 name/description/jsonSchema
AC2: Given 类无 @Tool 方法
  When ToolCallbacks.from(target)
  Then 返回 ToolCallback[0]（空数组，不抛异常）
AC3: Given @Tool 方法参数无 @ToolParam 注解
  When ToolCallbacks.from(target)
  Then 抛 IllegalStateException 含 "parameter missing @ToolParam"
AC4: Given @Tool 方法返回类型非 String
  When ToolCallbacks.from(target)
  Then ToolCallback.execute 返回 ToolResult.success(content=String.valueOf(result))
```

### US-6: ToolsNode 图节点执行
```gherkin
作为 Agent 执行循环
我希望 ToolsNode 接收 state["tool_calls"] 批量执行工具并回传结果
  以便 ReAct 循环中工具执行作为图节点，可被 Advisor 增强
```
**AC:**
```gherkin
AC1: Given state["tool_calls"] 含2个 tool_use (mysql_query, log_read)
  When ToolsNode.execute(state, ctx)
  Then 2个 ToolCallback.execute 被调用
  And state["tool_results"] 含2个 ToolResult
  And SSE 推送2个 tool_result 事件
AC2: Given tool_use 含 unknown tool name
  When ToolsNode.execute
  Then ToolResult.error 含 "tool not found: {name}"，回传 LLM
AC3: Given ToolCallback.execute 返回 content 长度 > maxToolResultChars
  When ToolsNode 处理
  Then ToolResult.isTruncated()==true 且 content 被截断
```

### US-7: @ToolApproval HITL 人工审批
```gherkin
作为 安全审计员
我希望 用 @ToolApproval(required=true) 标注高危工具，执行前需人工确认
  以便 SQL 执行/文件删除等危险操作不自动触发
```
**AC:**
```gherkin
AC1: Given @Tool(name="execute_ddl") + @ToolApproval(required=true)
  When ToolsNode 检测 tool_use["execute_ddl"]
  Then 抛 InterruptException 含 toolName + args
  And GraphExecutor 存 checkpoint → TaskStatus.PAUSED → SSE 推送 "paused" 事件
AC2: Given 已 PAUSED 的任务
  When POST /runs/{id}/resume {humanInput: "approved"}
  Then GraphExecutor.resume() 注入 state["human.input"]="approved"
  And ToolsNode 重新执行 execute_ddl，正常返回 ToolResult
AC3: Given POST /runs/{id}/resume {humanInput: "rejected"}
  When ToolsNode 处理
  Then 返回 ToolResult.error 含 "human rejected"，LLM 收到拒绝消息
AC4: Given @ToolApproval(required=false) 或无 @ToolApproval
  When ToolsNode 执行
  Then 不触发中断，直接执行
```

### US-8: ToolResult 值对象
```gherkin
作为 系统开发者
我希望 ToolResult 是不可变值对象，含 success/error/truncated 三种状态
  以便 工具执行结果语义明确，不可篡改
```
**AC:**
```gherkin
AC1: Given ToolResult.success("content", "toolId", 10)
  When 检查
  Then isSuccess()==true 且 isTruncated()==false 且 getContent()=="content"
AC2: Given ToolResult.error("error message")
  When 检查
  Then isError()==true 且 getErrorMessage()=="error message"
AC3: Given ToolResult.truncated("long...", 4000, 10000)
  When 检查
  Then isTruncated()==true 且 getOriginalLength()==10000
```

### US-9: per-request 工具子集路由
```gherkin
作为 skill 编写者
我希望 POST /runs 传 pluginOverrides 选择本次执行的 ToolCallback 子集
  以便 同一工具类型可有多实现，按场景选择
```
**AC:**
```gherkin
AC1: Given registry 含 "local-log" 和 "remote-log" 两个 ToolCallback
  When ToolCallbackRegistry.subset({"log_read":"remote-log"})
  Then 返回子集 registry 含 "remote-log"，不含 "local-log"
AC2: Given 无 pluginOverrides
  When subset(null)
  Then 返回全部 ToolCallback
AC3: Given override 指向不存在的 tool name
  When subset({"log_read":"nonexistent"})
  Then 抛 IllegalArgumentException 含 "tool not found in registry: nonexistent"
```

### US-10: Plugin 热加载产出 ToolCallback
```gherkin
作为 运维管理员
我希望 Plugin JAR 上传后自动用 ToolCallbacks.from() 发现 @Tool 方法并注册
  以便 运行时扩展工具能力，无需重启宿主
```
**AC:**
```gherkin
AC1: Given Plugin JAR 含类 LogTool with @Tool methods
  When PluginUploader 加载 JAR → ToolCallbacks.from(logToolInstance)
  Then 产出 ToolCallback[] 并注册到 ToolCallbackRegistry
  And toToolDefinitionsJson() 含新工具
AC2: Given Plugin 卸载
  When ToolCallbackRegistry.unregister(pluginToolNames)
  Then toToolDefinitionsJson() 不再含该 Plugin 的工具
AC3: Given 内置 @Tool 和 Plugin @Tool 同名
  When Plugin 注册
  Then 抛 IllegalArgumentException "tool already registered: {name}"
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 声明 | US-1 @Tool 注解 | 零接口工具声明 | 样板减少 80% | - |
| 元数据 | US-2 @ToolParam | LLM 知晓参数 | Schema 100% 覆盖 | US-1 |
| 统一 | US-3 ToolCallback | 无差别调用 | instanceof 0 | US-1 |
| 注册 | US-4 Registry | 运行时管理 | CRUD 完整 | US-3 |
| 发现 | US-5 ToolCallbacks.from | 零配置注册 | 反射发现 100% | US-1,2 |
| 执行 | US-6 ToolsNode | 图节点工具执行 | ReAct 循环集成 | US-3,4 |
| 审批 | US-7 @ToolApproval | HITL 危险操作 | 中断+恢复 100% | US-6 |
| 结果 | US-8 ToolResult | 语义明确结果 | 三状态覆盖 | US-3 |
| 路由 | US-9 subset | per-request 选择 | 子集正确 100% | US-4 |
| 热加载 | US-10 Plugin | 运行时扩展 | JAR→注册 < 2s | US-5,4 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | @Tool 注解基础属性 | P0 | US-1 AC1/AC2 | 单元 |
| UC-02 | @Tool returnDirect | P0 | US-1 AC3 | 单元 |
| UC-03 | @ToolParam description+required | P0 | US-2 AC1/AC2 | 单元 |
| UC-04 | 缺少 @ToolParam 抛异常 | P0 | US-2 AC3 | 单元 |
| UC-05 | ToolCallback execute 成功 | P0 | US-3 AC1/AC2 | 单元 |
| UC-06 | ToolCallback execute 异常 | P0 | US-3 AC3 | 单元 |
| UC-07 | Registry register/getAll/find | P0 | US-4 AC1 | 单元 |
| UC-08 | Registry 重复注册冲突 | P0 | US-4 AC2 | 单元 |
| UC-09 | Registry system 不可 unregister | P0 | US-4 AC3 | 单元 |
| UC-10 | Registry toToolDefinitionsJson | P0 | US-4 AC4 | 单元 |
| UC-11 | ToolCallbacks.from 多方法 | P0 | US-5 AC1 | 单元 |
| UC-12 | ToolCallbacks.from 无 @Tool | P1 | US-5 AC2 | 单元 |
| UC-13 | ToolCallbacks.from 缺 @ToolParam | P0 | US-5 AC3 | 单元 |
| UC-14 | ToolCallbacks.from 非String 返回 | P1 | US-5 AC4 | 单元 |
| UC-15 | ToolsNode 批量执行 | P0 | US-6 AC1 | 单元 |
| UC-16 | ToolsNode 未知工具 | P0 | US-6 AC2 | 单元 |
| UC-17 | ToolsNode 结果截断 | P0 | US-6 AC3 | 单元 |
| UC-18 | @ToolApproval 中断 | P0 | US-7 AC1 | 单元 |
| UC-19 | @ToolApproval resume 批准 | P0 | US-7 AC2 | 单元 |
| UC-20 | @ToolApproval resume 拒绝 | P0 | US-7 AC3 | 单元 |
| UC-21 | @ToolApproval not required | P1 | US-7 AC4 | 单元 |
| UC-22 | ToolResult success/error/truncated | P0 | US-8 AC1/2/3 | 单元 |
| UC-23 | Registry subset per-request | P0 | US-9 AC1/2/3 | 单元 |
| UC-24 | Plugin 热加载注册 | P0 | US-10 AC1 | 单元 |
| UC-25 | Plugin 卸载反注册 | P0 | US-10 AC2 | 单元 |
| UC-26 | Plugin 同名冲突 | P1 | US-10 AC3 | 单元 |
| UC-R1 | GET /tools 工具列表 | P1 | - | 集成 |
| UC-R2 | GET /tools/{name} 工具详情 | P1 | - | 集成 |

### 3.2 详细用例 (Gherkin)

#### UC-01: @Tool 注解基础属性
```gherkin
@priority:high @type:unit
功能: @Tool 注解声明工具元数据
  场景: 显式 name + description
    Given 类 MySqlQueryTool 含方法:
      @Tool(name="mysql_query", description="执行只读SQL查询")
      public String query(@ToolParam("SQL语句") String sql) { return "result"; }
    When ToolCallbacks.from(new MySqlQueryTool())
    Then 返回 ToolCallback[1]
    And callback.getName() == "mysql_query"
    And callback.getDescription() == "执行只读SQL查询"
  场景: 默认 name 用方法名
    Given @Tool(description="查询") 无 name() 标注于方法 "query"
    When ToolCallbacks.from(target)
    Then callback.getName() == "query"
```

#### UC-02: @Tool returnDirect
```gherkin
@priority:high @type:unit
功能: @Tool returnDirect 直接回传用户
  场景: returnDirect=true 不回传 LLM
    Given @Tool(name="render_html", returnDirect=true) String render()
    When ToolCallbacks.from(target)
    Then callback.isReturnDirect() == true
    And ToolsNode 将 ToolResult 直接写入 state["direct_output"]
```

#### UC-03: @ToolParam 参数元数据
```gherkin
@priority:high @type:unit
功能: @ToolParam 描述参数用途和必填性
  场景: description + required=true
    Given 方法含 @ToolParam(description="SQL语句", required=true) String sql
    When 生成 JSON Schema
    Then schema.parameters.properties.sql.description == "SQL语句"
    And schema.parameters.properties.sql.type == "string"
    And schema.parameters.required 含 "sql"
  场景: required=false
    Given @ToolParam(description="模式", required=false) String mode
    When 生成 JSON Schema
    Then schema.parameters.required 不含 "mode"
```

#### UC-04: 缺少 @ToolParam 抛异常
```gherkin
@priority:high @type:unit
功能: 参数无 @ToolParam 注解时反射失败
  场景: 参数无注解
    Given 方法 query(String sql) 参数 sql 无 @ToolParam
    When ToolCallbacks.from(target)
    Then 抛 IllegalStateException 含 "parameter missing @ToolParam: parameter 'sql' in method 'query'"
```

#### UC-05: ToolCallback execute 成功
```gherkin
@priority:high @type:unit
功能: ToolCallback.execute 正常执行返回成功
  场景: 内置 @Tool 和 Plugin ToolCallback 无差别调用
    Given 内置 MySqlQueryTool @Tool 方法产出 callbackA
    And Plugin 产出 callbackB (name="log_read")
    When ToolsNode 分别执行 tool_use["mysql_query"] 和 tool_use["log_read"]
    Then callbackA.execute 和 callbackB.execute 调用方式一致
    And 两者返回 ToolResult.success
```

#### UC-06: ToolCallback execute 异常
```gherkin
@priority:high @type:unit
功能: ToolCallback.execute 抛异常时返回 error
  场景: RuntimeException 不中断循环
    Given ToolCallback.execute 抛 RuntimeException("DB connection failed")
    When ToolsNode 处理
    Then 返回 ToolResult.error("tool execution failed: DB connection failed")
    And 不向上抛出异常
    And state["tool_results"] 含 error 结果回传 LLM
```

#### UC-07: Registry register/getAll/find
```gherkin
@priority:high @type:unit
功能: ToolCallbackRegistry CRUD
  场景: 注册并查询
    Given registry 为空
    When register(callback("mysql_query"))
    Then getAll().size() == 1
    And find("mysql_query") == callback
    And find("nonexistent") == null
```

#### UC-08: Registry 重复注册冲突
```gherkin
@priority:high @type:unit
功能: 同名 ToolCallback 不可重复注册
  场景: 冲突抛异常
    Given registry 已注册 "mysql_query"
    When register(callback("mysql_query"))
    Then 抛 IllegalArgumentException "tool already registered: mysql_query"
```

#### UC-09: Registry system 不可 unregister
```gherkin
@priority:high @type:unit
功能: system ToolCallback 不可反注册
  场景: system 保护
    Given registry 含 "mysql_query" (system=true)
    When unregister("mysql_query")
    Then 抛 UnsupportedOperationException "cannot unregister system tool: mysql_query"
  场景: 非 system 可反注册
    Given registry 含 "custom_tool" (system=false)
    When unregister("custom_tool")
    Then getAll() 不含 "custom_tool"
```

#### UC-10: Registry toToolDefinitionsJson
```gherkin
@priority:high @type:unit
功能: 生成 LLM 工具定义 JSON
  场景: 3个工具生成3个定义
    Given registry 含 mysql_query, log_read, metrics_query
    When toToolDefinitionsJson()
    Then 返回 JSON 数组 size==3
    And 每个对象含 name, description, input_schema
    And input_schema 含 type=object, properties, required 数组
```

#### UC-11: ToolCallbacks.from 多方法
```gherkin
@priority:high @type:unit
功能: 反射发现多个 @Tool 方法
  场景: 3个方法
    Given 类含3个 @Tool 方法: query, insert, delete
    When ToolCallbacks.from(target)
    Then 返回 ToolCallback[3]
    And names == ["query", "insert", "delete"]
```

#### UC-12: ToolCallbacks.from 无 @Tool
```gherkin
@priority:medium @type:unit
功能: 无 @Tool 方法返回空数组
  场景: 空类
    Given 类无任何 @Tool 方法
    When ToolCallbacks.from(target)
    Then 返回 ToolCallback[0] 不抛异常
```

#### UC-13: ToolCallbacks.from 缺 @ToolParam
```gherkin
@priority:high @type:unit
功能: 参数缺少 @ToolParam 时抛异常
  场景: 参数无注解
    Given @Tool 方法 query(String sql) 参数无 @ToolParam
    When ToolCallbacks.from(target)
    Then 抛 IllegalStateException 含 "parameter missing @ToolParam"
```

#### UC-14: ToolCallbacks.from 非String 返回
```gherkin
@priority:medium @type:unit
功能: 非 String 返回类型自动转换
  场景: 返回 int/boolean/POJO
    Given @Tool 方法返回 int
    When ToolCallbacks.from(target)
    Then ToolCallback.execute 返回 ToolResult.success(content=String.valueOf(result))
    Given @Tool 方法返回 POJO
    When ToolCallback.execute
    Then ToolResult.success(content=JSON.toJSONString(result))
```

#### UC-15: ToolsNode 批量执行
```gherkin
@priority:high @type:unit
功能: ToolsNode 批量执行 tool_use
  场景: 2个 tool_use 并行执行
    Given state["tool_calls"] = [{id:"t1", name:"mysql_query", input:{sql:"SELECT 1"}}, {id:"t2", name:"log_read", input:{file:"app.log"}}]
    And registry 含 mysql_query 和 log_read ToolCallback
    When ToolsNode.execute(state, ctx)
    Then 2个 ToolCallback.execute 被调用
    And state["tool_results"] 含2个 ToolResult (id="t1", id="t2")
    And ctx.emit 被调用2次 (tool_result 事件)
```

#### UC-16: ToolsNode 未知工具
```gherkin
@priority:high @type:unit
功能: tool_use 引用不存在的工具名
  场景: 返回 error 回传 LLM
    Given state["tool_calls"] 含 name="nonexistent"
    And registry 不含 "nonexistent"
    When ToolsNode.execute
    Then ToolResult.error("tool not found: nonexistent")
    And state["tool_results"] 含 error 结果
```

#### UC-17: ToolsNode 结果截断
```gherkin
@priority:high @type:unit
功能: 工具输出超长截断
  场景: content 超过 maxToolResultChars
    Given maxToolResultChars=50
    And ToolCallback.execute 返回 content 长度 100
    When ToolsNode 处理
    Then ToolResult.isTruncated() == true
    And content 长度 <= 50
    And content 含截断标记 "...[truncated]"
  场景: error 结果不截断
    Given ToolCallback.execute 抛异常，error message 长度 100
    When ToolsNode 处理
    Then ToolResult.isError()==true 且 isTruncated()==false
```

#### UC-18: @ToolApproval 中断
```gherkin
@priority:high @type:unit
功能: 高危工具触发 HITL 中断
  场景: @ToolApproval(required=true) 抛 InterruptException
    Given @Tool(name="execute_ddl") + @ToolApproval(required=true)
    And state["tool_calls"] 含 execute_ddl
    When ToolsNode.execute
    Then 抛 InterruptException
    And exception 含 toolName="execute_ddl" + args
    And GraphExecutor 存 checkpoint → TaskStatus.PAUSED
    And SSE 推送 "paused" 事件含 toolName + args
```

#### UC-19: @ToolApproval resume 批准
```gherkin
@priority:high @type:unit
功能: 人工批准后继续执行
  场景: resume approved
    Given Task 已 PAUSED (因 @ToolApproval)
    When POST /runs/{id}/resume {humanInput: "approved"}
    Then GraphExecutor.resume() 注入 state["human.input"]="approved"
    And ToolsNode 重新执行 execute_ddl
    And ToolResult.success 正常回传
```

#### UC-20: @ToolApproval resume 拒绝
```gherkin
@priority:high @type:unit
功能: 人工拒绝后回传 LLM
  场景: resume rejected
    Given Task 已 PAUSED
    When POST /runs/{id}/resume {humanInput: "rejected"}
    Then ToolsNode 返回 ToolResult.error("human rejected: execute_ddl")
    And state["tool_results"] 含拒绝结果
    And LLM 在下一轮收到拒绝消息
```

#### UC-21: @ToolApproval not required
```gherkin
@priority:medium @type:unit
功能: 无 @ToolApproval 或 required=false 不中断
  场景: 普通工具直接执行
    Given @Tool(name="mysql_query") 无 @ToolApproval
    When ToolsNode.execute
    Then 不抛 InterruptException
    And 正常返回 ToolResult
```

#### UC-22: ToolResult 值对象
```gherkin
@priority:high @type:unit
功能: ToolResult 不可变值对象三状态
  场景: success
    When ToolResult.success("content", "tool-1", 10)
    Then isSuccess()==true 且 isTruncated()==false
    And getContent()=="content" 且 getToolUseId()=="tool-1"
  场景: error
    When ToolResult.error("error msg")
    Then isError()==true 且 getErrorMessage()=="error msg"
  场景: truncated
    When ToolResult.truncated("short...", 4000, 10000)
    Then isTruncated()==true 且 getOriginalLength()==10000
```

#### UC-23: Registry subset per-request
```gherkin
@priority:high @type:unit
功能: per-request 工具子集
  场景: override 选择
    Given registry 含 "local-log" 和 "remote-log"
    When subset({"log_read":"remote-log"})
    Then 返回子集含 "remote-log" 不含 "local-log"
  场景: null 返回全部
    Given registry 含3个 ToolCallback
    When subset(null)
    Then 返回全部3个
  场景: 不存在的 override
    Given subset({"log_read":"nonexistent"})
    Then 抛 IllegalArgumentException "tool not found in registry: nonexistent"
```

#### UC-24: Plugin 热加载注册
```gherkin
@priority:high @type:unit
功能: Plugin JAR → ToolCallbacks.from() → Registry 注册
  场景: 加载并注册
    Given Plugin JAR 含 LogTool with @Tool methods
    When PluginUploader 加载 JAR
    Then ToolCallbacks.from(logToolInstance) 产出 ToolCallback[]
    And ToolCallbackRegistry.register(each)
    And toToolDefinitionsJson() 含新工具
```

#### UC-25: Plugin 卸载反注册
```gherkin
@priority:high @type:unit
功能: Plugin 卸载移除 ToolCallback
  场景: 反注册
    Given Plugin "log-plugin" 已注册 "log_read" ToolCallback
    When Plugin 卸载
    Then ToolCallbackRegistry.unregister("log_read")
    And toToolDefinitionsJson() 不含 "log_read"
```

#### UC-26: Plugin 同名冲突
```gherkin
@priority:medium @type:unit
功能: 内置与 Plugin 同名冲突
  场景: 冲突抛异常
    Given registry 含 "mysql_query" (内置)
    When Plugin 注册同名 "mysql_query"
    Then 抛 IllegalArgumentException "tool already registered: mysql_query"
```

---

## 4. 接口规格 (API Specs)

### 4.1 @Tool 注解
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    String name() default "";           // 默认用方法名
    String description();               // 必填
    boolean returnDirect() default false; // true=结果直接回传用户
}
```

### 4.2 @ToolParam 注解
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    String description();              // 必填
    boolean required() default true;   // 默认必填
}
```

### 4.3 @ToolApproval 注解
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolApproval {
    boolean required() default false;  // true=执行前需人工确认
    String prompt() default "";  // 自定义审批提示
}
```

### 4.4 ToolCallback SPI
```java
public interface ToolCallback {
    String getName();
    String getDescription();
    String getJsonSchema();            // JSON Schema for LLM tool definitions
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
    boolean isReturnDirect();
    boolean isSystem();                // system=true 不可 unregister
}
```

### 4.5 ToolCallbackRegistry SPI
```java
public interface ToolCallbackRegistry {
    void register(ToolCallback callback);    // 冲突→IllegalArgumentException, system不可unregister
    void unregister(String toolName);         // system→UnsupportedOperationException
    List<ToolCallback> getAll();
    ToolCallback find(String toolName);       // null=不存在
    String toToolDefinitionsJson();           // LLM 工具定义 JSON 数组
    ToolCallbackRegistry subset(Map<String, String> pluginOverrides); // per-request 子集
}
```

### 4.6 ToolCallbacks 反射工厂
```java
public class ToolCallbacks {
    public static ToolCallback[] from(Object target);  // 扫描 @Tool 方法
    public static ToolCallback[] from(Class<?> clazz);  // 静态方法扫描
    // 反射: @Tool name/description/returnDirect → ToolCallback
    //        @ToolParam description/required → JSON Schema
    //        参数类型 → schema type (String→string, int→integer, boolean→boolean, POJO→object)
    //        返回类型: String→直接, int/boolean→String.valueOf, POJO→JSON序列化
}
```

### 4.7 ToolsNode 图节点
```java
public class ToolsNode implements Node {
    private final ToolCallbackRegistry registry;
    private final int maxToolResultChars;  // 默认 4000

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) {
        // 1. 从 state["tool_calls"] 获取 tool_use 列表
        // 2. 对每个 tool_use:
        //    a. registry.find(name) → 未知 → ToolResult.error
        //    b. 检查 @ToolApproval(required=true) → 抛 InterruptException
        //    c. ToolCallback.execute(args, ctx)
        //    d. 结果截断 (content > maxToolResultChars)
        //    e. ctx.emit(TranscriptEvent.toolResult(...))
        // 3. 写入 state["tool_results"]
        // 4. return state
    }
}
```

### 4.8 ToolResult 值对象
```java
public final class ToolResult {
    private final boolean success;
    private final boolean truncated;
    private final String content;
    private final String errorMessage;
    private final String toolUseId;
    private final int originalLength;   // truncated 时原始长度

    // 工厂方法
    public static ToolResult success(String content, String toolUseId, int durationMs);
    public static ToolResult error(String errorMessage);
    public static ToolResult truncated(String content, int maxLen, int originalLen);

    // 查询
    public boolean isSuccess();
    public boolean isError();
    public boolean isTruncated();
    public boolean isReturnDirect();
    public String getContent();
    public String getErrorMessage();
    public String getToolUseId();
    public int getOriginalLength();
}
```

---

## 5. 数据规格 (Data Specs)

```yaml
@Tool 注解:
  name: String, 默认 "" (用方法名)
  description: String, 必填
  returnDirect: boolean, 默认 false

@ToolParam 注解:
  description: String, 必填
  required: boolean, 默认 true

@ToolApproval 注解:
  required: boolean, 默认 false

ToolCallback:
  name: String, 非 null 唯一
  description: String
  jsonSchema: String, JSON 格式
  returnDirect: boolean
  system: boolean, 不可变

ToolResult:
  不可变值对象, 工厂方法构造
  success | error | truncated 三状态互斥

JSON Schema 格式:
  {
    "name": "mysql_query",
    "description": "执行只读SQL查询",
    "input_schema": {
      "type": "object",
      "properties": {
        "sql": { "type": "string", "description": "SQL语句" }
      },
      "required": ["sql"]
    }
  }

参数类型映射:
  String → "string"
  int/Integer/long/Long → "integer"
  boolean/Boolean → "boolean"
  float/Float/double/Double → "number"
  POJO → "object" (反射属性)
  List<?> → "array"

返回类型处理:
  String → 直接 content
  int/boolean → String.valueOf
  POJO → JSON.toJSONString (Jackson)
  void → content="void"

截断规则:
  maxToolResultChars: 默认 4000
  content > max → 截断 + "...[truncated]" 标记
  error 结果不截断
```

---

## 6. 错误处理规格

| 错误码 | 描述 | 用户提示 | 行为 |
|--------|------|----------|------|
| E301 | tool not found | "tool not found: {name}" | ToolResult.error 回传 LLM |
| E302 | tool execution failed | "tool execution failed: {msg}" | ToolResult.error 回传 LLM |
| E303 | tool already registered | "tool already registered: {name}" | 抛 IllegalArgumentException |
| E304 | cannot unregister system tool | "cannot unregister system tool: {name}" | 抛 UnsupportedOperationException |
| E305 | parameter missing @ToolParam | "parameter missing @ToolParam: {param}" | 抛 IllegalStateException |
| E306 | human rejected | "human rejected: {toolName}" | ToolResult.error 回传 LLM |
| E307 | tool not found in registry (subset) | "tool not found in registry: {name}" | 抛 IllegalArgumentException |

```gherkin
场景: ToolCallback.execute 抛 RuntimeException
  When execute 抛 RuntimeException("DB connection failed")
  Then ToolResult.error("tool execution failed: DB connection failed")
  And 不向上抛出异常
  And LLM 在下一轮收到错误描述

场景: @ToolApproval required=true 未 resume 超时
  Given Task PAUSED 超过 24h
  When Task 超时
  Then TaskStatus → TIMEOUT, errorMessage 含 "awaiting human approval timeout"
```

---

## 7. 非功能需求 (NFR)

```yaml
性能:
  - ToolCallbacks.from() 反射扫描 P95 < 100ms (10个 @Tool 方法)
  - ToolCallbackRegistry.find() P99 < 1ms
  - ToolsNode 批量执行 5 个工具 P95 < 500ms (不含工具自身耗时)
  - toToolDefinitionsJson() 100个工具 P95 < 10ms
  - subset() P99 < 5ms

安全:
  - system ToolCallback 不可 unregister
  - @ToolApproval 高危工具强制 HITL
  - 工具输出截断防 LLM context 溢出
  - pluginId 正则 ^[a-zA-Z0-9_-]+$ 防路径穿越

可测试性:
  - 核心逻辑单元覆盖率 > 80%
  - @Tool/@ToolParam/@ToolApproval 注解全路径测试
  - ToolCallbackRegistry 并发测试 (register/unregister/find)
  - ToolsNode 批量/异常/截断/中断全分支覆盖
  - ToolResult 三状态 + 不可变性测试
```

---

## 8. 测试策略 (Test Strategy)

### 8.1 已有测试覆盖

| 测试文件 | 类型 | 覆盖用例 |
|----------|------|----------|
| (2.x 重构后旧测试已废弃，新测试待编写) | - | - |

**总结**: 2.x 重构删除旧 `ToolDispatcher`/`ToolProvider`/`PluginRegistry`/`@ToolPluginAnnotation`，所有 2.x 测试为新增。优先实现 UC-01~26 (单元) 和 UC-R1~2 (集成)。

### 8.2 E2E 关键路径

| 路径ID | 关键路径 | 端点/组件 | 状态 |
|--------|----------|-----------|------|
| E2E-1 | 工具列表: GET /tools → 200 (toToolDefinitionsJson) | GET /tools | ⚠未实现 (GAP-9) |
| E2E-2 | 工具详情: GET /tools/{name} → 200 (ToolCallback 详情) / 404 | GET /tools/{name} | ⚠未实现 (GAP-10) |
| E2E-3 | ReAct 工具执行: POST /runs → 图执行 → ToolsNode → tool_result → SSE | POST /runs, ToolsNode | ⚠未实现 (GAP-11 P0) |
| E2E-4 | HITL 审批: POST /runs → @ToolApproval 中断 → PAUSED → POST /runs/{id}/resume → 继续 | POST /runs, POST /runs/{id}/resume | ⚠未实现 (GAP-12 P0) |
| E2E-5 | Plugin 热加载: POST /tools/plugins/upload (JAR) → ToolCallbacks.from() → 注册 → GET /tools 含新工具 | POST /tools/plugins/upload, GET /tools | ⚠未实现 (GAP-13) |

### 8.3 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | `@Tool` 注解 name 默认方法名 无单测 | P0 | UC-01 |
| GAP-2 | `@Tool` returnDirect=true 无单测 | P0 | UC-02 |
| GAP-3 | `@ToolParam` description/required 生成 JSON Schema 无单测 | P0 | UC-03 |
| GAP-4 | 缺少 `@ToolParam` 抛异常 无单测 | P0 | UC-04 |
| GAP-5 | `ToolCallback.execute` 成功/异常 无单测 | P0 | UC-05/06 |
| GAP-6 | `ToolCallbackRegistry` register/unregister/find/toToolDefinitionsJson 无单测 | P0 | UC-07~10 |
| GAP-7 | `ToolCallbackRegistry` 重复注册冲突 无单测 | P0 | UC-08 |
| GAP-8 | `ToolCallbackRegistry` system 不可 unregister 无单测 | P0 | UC-09 |
| GAP-9 | E2E缺失: GET /tools REST 端点无 E2E 覆盖 — 见 E2E-1 | P1 | 需 E2E 集成测试 |
| GAP-10 | E2E缺失: GET /tools/{name} REST 端点无 E2E 覆盖 — 见 E2E-2 | P1 | 需 E2E 集成测试 |
| GAP-11 | E2E缺失: ReAct 工具执行 ReAct 循环 → ToolsNode → tool_result → SSE 无 E2E — 见 E2E-3 | P0 | 需 E2E 集成测试 |
| GAP-12 | E2E缺失: @ToolApproval HITL 中断→resume→继续 无 E2E — 见 E2E-4 | P0 | 需 E2E 集成测试 |
| GAP-13 | E2E缺失: Plugin JAR 热加载→注册→GET /tools 含新工具 无 E2E — 见 E2E-5 | P1 | 需 E2E 集成测试 |
| GAP-14 | `ToolCallbacks.from()` 反射多方法/无方法/缺注解/非String返回 无单测 | P0 | UC-11~14 |
| GAP-15 | `ToolsNode` 批量执行/未知工具/截断 无单测 | P0 | UC-15~17 |
| GAP-16 | `@ToolApproval` 中断/resume 批准/拒绝/不中断 无单测 | P0 | UC-18~21 |
| GAP-17 | `ToolResult` success/error/truncated 不可变 无单测 | P0 | UC-22 |
| GAP-18 | `ToolCallbackRegistry.subset()` per-request 路由 无单测 | P0 | UC-23 |
| GAP-19 | Plugin 热加载/卸载/冲突 无单测 | P0 | UC-24~26 |
| GAP-20 | `ToolsNode` 并发执行 + Registry 并发 register/unregister 竞争 无单测 | P1 | 并发测试 |

### 8.4 Mock 策略
```yaml
Mock: ToolCallback(匿名实现), ToolCallbackRegistry(ConcurrentHashMap实现), ExecutionContext(Mockito)
反射: 真实 @Tool 注解类 (MySqlQueryTool, LogTool 等)
Plugin: @TempDir + 真实 JAR (JarOutputStream) + URLClassLoader
HITL: InterruptException 捕获 + checkpoint mock
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| snap-agent-core graph SPI (Node, GraphState, ExecutionContext) | 2.x 新增 | 无 |
| snap-agent-core execution (InterruptException) | 2.x 新增 | 无 |
| Jackson ObjectMapper (JSON 序列化) | 已就绪 | 缺失时 POJO 返回 toString |
| JDK 反射 API (Java 8+) | 已就绪 | 无 |
| Plugin JAR 加载 (URLClassLoader) | 09-plugin-mcp 提供 | 无 Plugin 时仅内置 @Tool |

---

## 10. 可观测性设计

```yaml
日志:
  INFO "tool registered: {name} (system={system}, returnDirect={returnDirect})"
  INFO "tool unregistered: {name}"
  INFO "tool executed: name={name} duration={ms}ms truncated={truncated}"
  WARN "tool not found: {name}"
  WARN "tool execution failed: name={name} error={msg}"
  INFO "HITL approval required: tool={name} pausing execution"
  INFO "HITL approval resolved: tool={name} decision={approved|rejected}"

指标:
  tool_callback_count
  tool_execute_count{tool_name,result}
  tool_execute_duration_seconds{tool_name}
  tool_registry_size
  tool_truncated_total{tool_name}
  hitl_approval_required_total
  hitl_approval_resolved_total{decision}

追踪:
  MicrometerObservationAdvisor span "snap-agent.tool.execute" (ToolsNode 执行)
  span tag: tool.name, tool.success, tool.truncated
```

---

## 11. 原型与交互参考

| 操作 | 成功 | 错误 |
|------|------|------|
| GET /tools | 200 `[{name, description, input_schema}]` | - |
| GET /tools/{name} | 200 `{name, description, input_schema, system}` | 404 |
| POST /runs (含 @ToolApproval) | 202 → PAUSED → "paused" SSE 事件 | - |
| POST /runs/{id}/resume | 202 → RUNNING → 继续执行 | 404 |

---

## 12. 附录

### 12.1 变更历史

| 版本 | 日期 | 作者 | 变更 |
|------|------|------|------|
| 2.0 | 2026-07-23 | snap-agent team | 初始 TDD 规格 (ToolDispatcher + ToolProvider + PluginRegistry) |
| 3.0 | 2026-07-25 | snap-agent team | 2.x 重构: @Tool/@ToolParam 声明式注解 + ToolCallback 统一 SPI + ToolCallbacks.from() 反射工厂 + ToolsNode 图节点 + @ToolApproval HITL + ToolResult 值对象 + per-request subset 路由，删除旧 ToolDispatcher/ToolProvider/PluginRegistry/@ToolPluginAnnotation |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` (Section 2 @Tool+ToolCallback)
- `docs/embeed-skills-agent/04-tools-and-mcp.md`
- `docs/tdd/TEMPLATE.md`

### 12.3 术语表

| 术语 | 定义 |
|------|------|
| @Tool | 方法级注解，声明工具元数据 (name, description, returnDirect) |
| @ToolParam | 参数级注解，声明参数元数据 (description, required) |
| @ToolApproval | 方法级注解，标记高危工具需人工审批 (required=true) |
| ToolCallback | 2.x 统一工具 SPI，内置 @Tool 和 Plugin 共用 |
| ToolCallbackRegistry | 工具注册表 SPI，register/unregister/find/toToolDefinitionsJson/subset |
| ToolCallbacks.from() | 反射工厂，自动发现 @Tool 方法生成 ToolCallback[] |
| ToolsNode | 图节点，接收 state["tool_calls"] 执行工具并回传结果 |
| ToolResult | 不可变值对象，success/error/truncated 三状态 |
| returnDirect | @Tool 属性，true=结果直接回传用户不回传 LLM |
| system ToolCallback | 内置不可删除的工具，isSystem()==true |
| subset | per-request 工具子集，根据 pluginOverrides 路由 |
