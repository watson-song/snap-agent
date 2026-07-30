# 11 — 宿主 MCP Server

> 状态：设计阶段
> 版本：1.0-draft
> 日期：2026-07-30

## 1. 背景与动机

### 1.1 当前状态：SnapAgent 是 MCP 客户端

SnapAgent 已实现 MCP 客户端能力（见 [04-tools-and-mcp.md](04-tools-and-mcp.md) §4）：

```
宿主应用 → SnapAgent (MCP Client) → 外部 MCP Server (如 BDP)
                                    initialize → tools/list → tools/call
```

- `McpSseClient` 连接外部 MCP Server，发现工具，代理调用。
- `McpTools` 把外部工具包装为 `ToolCallback`，注册到 `ToolCallbackRegistry`。
- 工具命名 `mcp__{server}__{tool}`，与 Claude Code 约定一致。

### 1.2 缺失能力：没有 MCP Server 端

当前实现**没有** MCP Server 端。宿主应用已通过 `@Tool` 注解积累了大量业务能力（库存查询、补货计划、调拨记录等），这些能力：

- 不走 HTTP，外部无法调用。
- 只能被 SnapAgent 内嵌 LLM 使用。
- 开发者在 IDE 用 Claude Code / Cursor 时完全访问不到。

### 1.3 MCP Server 的核心价值

**不是给 REST API 套壳，而是把 SnapAgent 已积累的 `@Tool` 工具体系暴露给外部 AI Agent。**

```
                          ┌──────────────────┐
                          │  外部 AI Agent    │
                          │  (Claude Code)   │
                          └────────┬─────────┘
                                   │ MCP
                                   ▼
┌──────────────────────────────────────────────────┐
│  宿主应用 + SnapAgent                              │
│                                                   │
│  McpServerController                              │
│    └── tools/list → ToolCallbackRegistry          │
│                                                   │
│  ToolCallbackRegistry 里有什么:                    │
│    ① @Tool 方法 (宿主业务能力, 非 REST)             │ ← 核心价值
│    ② JDBC/Redis 工具 (直接数据访问, 非 REST)        │ ← 核心价值
│    ③ MCP Client 工具 (中继其他 MCP Server)          │ ← 链式联动
│    ④ REST API 能力 (如果需要也可以包装)             │ ← 补充, 非必须
│                                                   │
│  内嵌 LLM 也能用同一套工具 (ReAct 循环)             │
└──────────────────────────────────────────────────┘
```

一句话总结：**MCP Server 不是给 REST API 套壳，而是把 SnapAgent 已经积累的 `@Tool` 工具体系（宿主应用的真实业务能力）暴露给外部 AI Agent 使用。**

### 1.4 REST API vs MCP — 为什么不是套壳

| 维度 | REST API | MCP Tool |
|------|----------|----------|
| 面向对象 | 人/程序 | AI Agent |
| 发现方式 | 需要人读 Swagger 文档 | `tools/list` 自动发现 |
| 参数语义 | 需要人理解字段含义 | `@ToolParam description` 天然给 LLM |
| 编排能力 | 需要人组合多个端点 | LLM 自主编排多步调用 |
| 非 REST 能力 | 无法暴露 | `@Tool` 方法直接暴露（查 DB、读缓存、调内部 Service） |

## 2. 对称设计

```
McpSseClient (已有)  →  连接外部 MCP Server，消费工具
McpServerController (新增) →  暴露内部工具，供外部消费
```

两者共用 `ToolCallback` SPI，实现零重复的工具定义和调用逻辑。

```
@Service + @Tool → ToolCallbackRegistry →
    ├── 内嵌 LLM (ReAct 循环内调用)
    └── McpServerController → 外部 AI Agent (Claude Code / Cursor)
```

## 3. @Tool 机制回顾

### 3.1 注解定义

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    String name() default "";         // empty = use method name
    String description();             // required — shown to the LLM
    boolean returnDirect() default false;
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    String description();
    boolean required() default true;
}
```

### 3.2 反射注册

`ToolCallbacks.from(bean)` 扫描 `getMethods()` 找 `@Tool` 注解 → 每个方法生成一个匿名 `ToolCallback`（含自动生成的 JSON Schema）→ 注册到 `ToolCallbackRegistry`。

### 3.3 运行时调用

LLM 返回 `tool_use { name, input }` → `registry.find(name)` → `callback.execute(args)` → `Method.invoke(beanInstance, args)` → 返回 `result.toString()`。

**方法体可以执行任意逻辑**：查 MySQL、读 Redis、调其他 Service、发 HTTP 请求。`JdbcQueryTools` 直接操作 JDBC Connection，`RedisReadTools` 直接调 `RedisTemplate`。

### 3.4 当前问题：宿主 Service 未自动注册

`ToolAutoConfiguration` 里是硬编码的 `ObjectProvider<JdbcQueryTools>` 等内置工具。宿主应用的 `@Service` + `@Tool` 方法不会被自动发现。

## 4. 宿主工具自动注册

### 4.1 方案：`@SnapAgentTools` 标记注解

新增标记注解，宿主在需要暴露的 Service 类上标注，SnapAgent 启动时自动扫描并注册。

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SnapAgentTools {
    // 标记此类含 @Tool 方法，需注册到 ToolCallbackRegistry
}
```

### 4.2 自动扫描

```java
// ToolAutoConfiguration.pluginRegistry() 中新增
List<Object> toolsBeans = new ArrayList<>();
addIfAvailable(toolsBeans, jdbcTools);
addIfAvailable(toolsBeans, redisTools);
// ... 内置工具 ...

// 自动扫描宿主 @SnapAgentTools 标注的 Bean
String[] beanNames = applicationContext.getBeanNamesForAnnotation(SnapAgentTools.class);
for (String name : beanNames) {
    toolsBeans.add(applicationContext.getBean(name));
}

for (Object tools : toolsBeans) {
    ToolCallback[] callbacks = ToolCallbacks.from(tools);
    for (ToolCallback cb : callbacks) {
        registry.register(cb);
    }
}
```

### 4.3 宿主使用示例

```java
@Service
@SnapAgentTools
public class InventoryService {

    @Tool(name = "query_stock", description = "根据SKU查询库存详情")
    public StockInfo queryStock(
            @ToolParam(description = "SKU编号") String sku,
            @ToolParam(description = "仓库名称") String warehouse) {
        // 里面调 Mapper、查 MySQL、调其他 Service — 都行
        return stockMapper.selectBySkuAndWarehouse(sku, warehouse);
    }

    @Tool(name = "generate_replenishment", description = "生成补货计划")
    public ReplenishmentPlan generateReplenishment(
            @ToolParam(description = "SKU编号") String sku,
            @ToolParam(description = "预测天数") int days) {
        return replenishmentEngine.generate(sku, days);
    }
}
```

### 4.4 为什么不用 `@Service` 自动扫描

- 并非所有 `@Service` 都应该暴露给 AI Agent。
- `@SnapAgentTools` 让开发者**显式选择**哪些 Service 的能力要暴露。
- 遵循"零影响"原则：不标注 = 不注册 = 不影响。

## 5. MCP Server 架构

### 5.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  外部 AI 客户端 (Claude Code, Cursor, Windsurf...)      │
└──────────────────┬──────────────────────────────────────┘
                   │ SSE + JSON-RPC
                   ▼
┌─────────────────────────────────────────────────────────┐
│  McpServerController                                     │
│  GET  /snap-agent/mcp/sse      → SSE 连接, 返回 POST 端点 │
│  POST /snap-agent/mcp/messages → JSON-RPC 请求处理       │
└──────────────────┬──────────────────────────────────────┘
                   │
         ┌─────────▼──────────┐
         │ McpServerHandler    │
         │ • initialize        │
         │ • tools/list        │ → 从 ToolCallbackRegistry 获取工具
         │ • tools/call        │ → 调用 ToolCallback.execute()
         └─────────┬──────────┘
                   │
         ┌─────────▼──────────┐
         │ ToolCallbackRegistry│ (已有)
         │  • built-in @Tools  │
         │  • MCP client tools │
         │  • JAR plugin tools │
         │  • 宿主 @SnapAgentTools │
         └─────────────────────┘
```

### 5.2 核心组件

#### McpServerController

Spring MVC 暴露 MCP 协议端点：

```java
@RestController
@RequestMapping("/snap-agent/mcp")
@ConditionalOnProperty(prefix = "snap-agent.mcp.server", name = "enabled", havingValue = "true")
public class McpServerController {

    private final McpServerHandler handler;

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        return handler.handleConnect();
    }

    @PostMapping("/messages")
    public ResponseEntity<String> handleMessage(
            @RequestParam("sessionId") String sessionId,
            @RequestBody String jsonRpcRequest) {
        String result = handler.handleRequest(sessionId, jsonRpcRequest);
        return ResponseEntity.ok(result);
    }
}
```

#### McpServerHandler

处理 JSON-RPC 协议，复用已有 `ToolCallbackRegistry`：

```java
public class McpServerHandler {

    private final ToolCallbackRegistry toolRegistry;
    private final McpServerProperties config;
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    // SSE 连接 — 生成 sessionId, 创建 session, 返回 POST endpoint
    public SseEmitter handleConnect() {
        String sessionId = UUID.randomUUID().toString();
        McpSession session = new McpSession(sessionId);
        sessions.put(sessionId, session);

        SseEmitter emitter = new SseEmitter(0L); // 无超时
        session.setEmitter(emitter);
        session.send("endpoint",
            "/snap-agent/mcp/messages?sessionId=" + sessionId);
        return emitter;
    }

    // JSON-RPC 请求路由
    public String handleRequest(String sessionId, String body) {
        JsonNode req = objectMapper.readTree(body);
        String method = req.get("method").asText();
        String id = req.get("id").asText();

        switch (method) {
            case "initialize":
                return handleInitialize(id, sessionId);
            case "tools/list":
                return handleToolsList(id, session);
            case "tools/call":
                return handleToolsCall(id, req, session);
            default:
                return errorResponse(id, -32601, "Method not found");
        }
    }
}
```

### 5.3 JSON-RPC 协议实现

#### initialize

```json
// 请求
{ "jsonrpc": "2.0", "id": "1", "method": "initialize",
  "params": { "protocolVersion": "2024-11-05",
              "capabilities": {}, "clientInfo": {"name": "claude-code", "version": "1.0"} } }

// 响应
{ "jsonrpc": "2.0", "id": "1", "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": { "listChanged": true } },
    "serverInfo": { "name": "snap-agent-mcp-server", "version": "1.0" } } }
```

#### tools/list

从 `ToolCallbackRegistry.getAll()` 获取工具，按 caller 上下文过滤后返回：

```json
{ "jsonrpc": "2.0", "id": "2", "result": {
    "tools": [
      { "name": "query_stock",
        "description": "根据SKU查询库存详情",
        "inputSchema": { "type": "object",
          "properties": {
            "sku": { "type": "string", "description": "SKU编号" },
            "warehouse": { "type": "string", "description": "仓库名称" } },
          "required": ["sku", "warehouse"] } },
      { "name": "mysql_query",
        "description": "Execute a read-only SQL query",
        "inputSchema": { ... } }
    ] } }
```

#### tools/call

```json
// 请求
{ "jsonrpc": "2.0", "id": "3", "method": "tools/call",
  "params": { "name": "query_stock",
              "arguments": { "sku": "SKU001", "warehouse": "华东" } } }

// 响应
{ "jsonrpc": "2.0", "id": "3", "result": {
    "content": [ { "type": "text", "text": "SKU001 华东仓: 库存 500 件..." } ] } }
```

### 5.4 协议版本

与 `McpSseClient` 一致：`2024-11-05`，兼容现有 MCP 生态（Claude Code、Cursor、Windsurf）。

## 6. 权限设计

### 6.1 四层权限模型

权限不是单个点，是一条从连接到返回的完整链路：

```
外部 AI Agent
    │
    │ ① 连接层：谁能连进来？
    ▼
MCP Server (SSE 连接)
    │
    │ ② 工具可见层：连进来后能看到哪些工具？
    ▼
tools/list (过滤后的工具列表)
    │
    │ ③ 参数约束层：能用什么参数调？
    ▼
tools/call (执行)
    │
    │ ④ 数据返回层：返回结果要不要过滤？
    ▼
ToolResult → 外部 Agent
```

### 6.2 ① 连接层 — Token 认证

```yaml
snap-agent:
  mcp:
    server:
      auth:
        type: token              # token | mTLS | oauth
        token-header: X-Mcp-Token
        tokens:
          dev-team: ${MCP_TOKEN_DEV:}      # 开发团队 token
          ops-team: ${MCP_TOKEN_OPS:}      # 运维团队 token
        ip-whitelist:             # 可选
          - 10.0.0.0/8
```

连接时校验 token → 解析为 `McpCallerContext` → 绑定到 `McpSession` → 后续所有操作都带着这个 caller 身份。

```java
public class McpCallerContext {
    private String callerId;           // "dev-team" 或 "ops-team"
    private List<String> roles;         // ["reader"]
    private List<String> warehouses;   // ["华东"]  ← 数据范围
    private String envScope;           // "sit" | "uat" | "prod"
    private boolean readOnly;          // true = 只能调只读工具
}
```

### 6.3 ② 工具可见层 — 注解式过滤

#### 配置式 allowlist（简单，适合 MVP）

```yaml
snap-agent:
  mcp:
    server:
      exposed-tools:
        mode: allowlist           # allowlist | denylist | role-based
        includes:
          - query_stock
          - query_transfers
        excludes:
          - mysql_query           # 数据库直查不对外暴露
```

#### 注解式权限标记（细粒度）

```java
@Tool(name = "query_stock", description = "查询库存")
@ToolVisibility(roles = {"reader", "manager", "admin"})
public StockInfo queryStock(...) { ... }

@Tool(name = "generate_replenishment", description = "生成补货计划")
@ToolVisibility(roles = {"manager", "admin"})
@ToolApproval(required = true)                             // 需要人工审批
public ReplenishmentPlan generateReplenishment(...) { ... }

@Tool(name = "mysql_query", description = "执行SQL查询")
@ToolVisibility(external = false)                          // 不对外暴露
public String query(...) { ... }
```

`McpServerHandler.handleToolsList()` 过滤逻辑：

```
ToolCallbackRegistry.getAll()
  → 排除 @ToolVisibility(external=false) 的工具
  → 排除 caller roles 不匹配的工具
  → 排除 allowlist/denylist 配置过滤的工具
  → 返回过滤后的 tools/list
```

### 6.4 ③ 参数约束层 — 数据范围上下文

```java
@Tool(name = "query_stock", description = "查询库存")
public StockInfo queryStock(
        @ToolParam(description = "SKU编号") String sku,
        @ToolParam(description = "仓库名称") String warehouse,
        ToolExecutionContext context) {           // ← 框架注入, 不暴露给 LLM
    McpCallerContext caller = context.getCaller();

    // 数据范围校验
    if (caller.getWarehouses() != null
        && !caller.getWarehouses().contains(warehouse)) {
        throw new PermissionException("无权访问仓库: " + warehouse);
    }

    // 环境隔离
    if (!"prod".equals(caller.getEnvScope())) {
        // 开发团队只能看非生产数据
    }

    return stockMapper.selectBySkuAndWarehouse(sku, warehouse);
}
```

关键点：`ToolExecutionContext` 是**框架注入**的，不出现在 LLM 的 tool schema 里，LLM 看不到也改不了。

### 6.5 ④ 数据返回层 — 字段脱敏

#### 在方法体内处理（最灵活）

```java
@Tool(name = "query_stock", description = "查询库存")
public String queryStock(String sku, String warehouse, ToolExecutionContext ctx) {
    StockInfo info = stockMapper.select(sku, warehouse);
    if (!ctx.getCaller().getRoles().contains("finance")) {
        info.setUnitCost(null);    // 非财务角色看不到成本
        info.setSupplier(null);    // 看不到供应商
    }
    return info.toString();
}
```

#### 注解式字段脱敏（声明式）

```java
@Tool(name = "query_stock", description = "查询库存")
@ToolResultMask(
    fields = {"unit_cost", "supplier"},
    requireRoles = {"finance"}
)
public StockInfo queryStock(...) { ... }
```

框架在 `ToolCallback.execute()` 返回后自动做字段脱敏。

## 7. 配置

### 7.1 完整配置树

```yaml
snap-agent:
  mcp:
    # 客户端模式 (已有, 见 04-tools-and-mcp.md §4)
    enabled: false
    servers:
      bdp-data-map:
        transport: sse
        url: https://bdp-mcp.sit/mcp/sse
        auth-header: "X-Bdp-Token"
        auth-header-value: ${BDP_TOKEN:}

    # 服务端模式 (新增)
    server:
      enabled: false                    # 默认关闭
      path: /snap-agent/mcp             # 端点路径前缀
      auth:
        type: token                     # token | mTLS | oauth (Phase 1 仅 token)
        token-header: X-Mcp-Token
        tokens:
          dev-team: ${MCP_TOKEN_DEV:}
          ops-team: ${MCP_TOKEN_OPS:}
        ip-whitelist:                   # 可选
          - 10.0.0.0/8
      exposed-tools:
        mode: allowlist                 # allowlist | denylist | role-based
        includes:                       # mode=allowlist 时生效
          - query_stock
          - query_transfers
          - query_replenishment_history
        excludes:                       # 任何 mode 下都排除
          - mysql_query
          - redis_get
      max-sessions: 10                  # 最大并发 SSE 会话
      session-timeout-seconds: 300      # 会话超时
      audit-log: true                   # 调用审计日志
```

### 7.2 配置属性类

```java
public static class McpServer {
    private boolean enabled = false;
    private String path = "/snap-agent/mcp";
    private Auth auth = new Auth();
    private ExposedTools exposedTools = new ExposedTools();
    private int maxSessions = 10;
    private int sessionTimeoutSeconds = 300;
    private boolean auditLog = true;

    public static class Auth {
        private String type = "token";
        private String tokenHeader = "X-Mcp-Token";
        private Map<String, String> tokens = new HashMap<>();
        private List<String> ipWhitelist = new ArrayList<>();
    }

    public static class ExposedTools {
        private String mode = "allowlist";  // allowlist | denylist | role-based
        private List<String> includes = new ArrayList<>();
        private List<String> excludes = new ArrayList<>();
    }
}
```

## 8. 安全架构

```
┌──────────────────────────────────────────────────────────────┐
│ McpServerAuthFilter                                            │
│   → 校验 token / IP 白名单                                    │
│   → 解析为 McpCallerContext {                                  │
│       callerId, roles, warehouses, envScope, readOnly         │
│     }                                                         │
│   → 绑定到 McpSession                                          │
└──────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────┐
│ McpServerHandler.handleToolsList()                             │
│   → ToolCallbackRegistry.getAll()                              │
│   → 按 McpCallerContext 过滤:                                  │
│     • @ToolVisibility(external=false) 的排除                   │
│     • caller roles 不匹配的排除                                │
│     • allowlist/denylist 配置过滤                              │
│   → 返回过滤后的 tools/list                                    │
└──────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────┐
│ McpServerHandler.handleToolsCall()                             │
│   → registry.find(toolName)                                   │
│   → 构建 ToolExecutionContext { caller, sessionId }            │
│   → callback.execute(args, context)                           │
│   → 方法内部用 context 做参数校验 (仓库范围/环境隔离/读写限制) │
└──────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────┐
│ ResultPostProcessor                                           │
│   → @ToolResultMask 注解处理 (字段脱敏)                        │
│   → 速率限制检查                                               │
│   → 审计日志记录 (caller + tool + args + result 摘要)         │
│   → 返回最终 ToolResult                                        │
└──────────────────────────────────────────────────────────────┘
```

## 9. 使用场景

### 9.1 场景 1：开发者在 IDE 调试库存系统

```yaml
snap-agent:
  mcp:
    server:
      enabled: true
      auth:
        type: token
        tokens:
          dev-team: ${MCP_TOKEN_DEV:secret}
      exposed-tools:
        mode: allowlist
        includes:
          - query_stock
          - query_replenishment_history
```

在 Claude Code 的 MCP 配置中：
```json
{
  "mcpServers": {
    "inventory-app": {
      "url": "http://inv-app:8080/snap-agent/mcp/sse",
      "headers": { "X-Mcp-Token": "secret" }
    }
  }
}
```

```
开发者: "SKU001 上次补货是什么时候？补了多少？"
Claude Code:
  → MCP tools/list 发现有: query_stock, query_replenishment_history
  → 自动调用 query_replenishment_history(sku="SKU001")
  → "SKU001 上次补货是 2026-07-25, 补了 500 件, 来源仓库: 华北总仓"
```

没有 MCP，开发者要么去看数据库，要么去用 Postman 调 API，要么去看日志。

### 9.2 场景 2：运维用 AI 排查线上问题

```
运维 (Claude Code 连接宿主 MCP):
  "华东仓今天有没有异常调拨？"

  → MCP 自动发现: query_transfers, query_stock_alerts
  → 调用组合: 先查 alerts, 再查对应 transfers
  → AI 自主编排多步调用, 给出分析结论
```

REST API 做不到这种自主编排 — AI 需要理解 API 语义才能组合调用，而 MCP 的 tool description 天然为此设计。

### 9.3 场景 3：跨系统联动

外部 AI Agent 同时连接多个 MCP Server：

```
  - 库存系统 MCP → query_stock
  - 物流系统 MCP → query_shipment
  - 财务系统 MCP → query_cost

  "SKU001 从华东仓调到华南仓的总成本是多少？"
  → AI 自主组合三个系统的工具完成跨域查询
```

### 9.4 场景 4：工具链中继

宿主 A 既是 MCP Server（对外），又是 MCP Client（对内连接其他 MCP Server）：

```
外部 Agent → MCP Server (宿主A) → tools/call →
  宿主A 的 ToolCallbackRegistry 中的 mcp__external__* →
  MCP Client → 宿主B 的 MCP Server
```

## 10. 装配条件矩阵

| 组件 | 装配条件 | 缺失时 |
|------|---------|--------|
| `McpServerController` | `mcp.server.enabled=true` | 不装配，端点不存在 |
| `McpServerHandler` | `mcp.server.enabled=true` | 不装配 |
| `McpServerAuthFilter` | `mcp.server.enabled=true` 且 `auth.type != none` | 不装配，无认证 |
| `@SnapAgentTools` 扫描 | `snap-agent.enabled=true`（始终，不依赖 mcp.server） | 宿主工具不注册 |
| `ToolExecutionContext` 注入 | MCP caller 存在时自动注入 | 内嵌 LLM 调用时 context 为 null |

## 11. 零影响证明

- `mcp.server.enabled=false`（默认）→ 不创建 `McpServerController` / `McpServerHandler` / `McpServerAuthFilter`，端点不存在。
- `@SnapAgentTools` 不标注 → 宿主 Service 不被扫描，不影响已有行为。
- 内嵌 LLM 调用 `@Tool` 方法时，`ToolExecutionContext` 为 null，方法内需做 null check（或使用 `Optional`）。
- MCP Server 和 MCP Client 独立开关，可同时启用（双向 MCP）或单独启用。

## 12. 与现有组件的关系

```
                    ┌─────────────────────┐
                    │ ToolCallbackRegistry │
                    │  (统一注册表)        │
                    └──────┬──────────────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
  │ 内嵌 LLM     │  │ MCP Server  │  │ MCP Client  │
  │ (ReAct 循环) │  │ (新增, 对外) │  │ (已有, 对内) │
  │ ToolsNode   │  │ Handler     │  │ McpSseClient │
  └─────────────┘  └─────────────┘  └─────────────┘
```

三个消费方共用同一套 `ToolCallbackRegistry`：
- **内嵌 LLM**：`ToolsNode` 在 ReAct 循环中调用 `registry.find(name).execute(args, null)`。
- **MCP Server**（新增）：`McpServerHandler` 处理外部 `tools/call` → `registry.find(name).execute(args, context)`。
- **MCP Client**（已有）：外部 MCP Server 的工具通过 `McpTools` 注册到 registry，可被内嵌 LLM 和 MCP Server 调用。

## 13. 决策记录

| # | 决策 | 理由 |
|---|------|------|
| #17 | MCP Server 仅 SSE+POST 传输 | 与 MCP Client 对称，容器友好，无子进程 |
| #18 | 复用 ToolCallbackRegistry | 已有 @Tools + MCP Client 工具 + JAR 插件工具，零重复 |
| #19 | 协议版本 2024-11-05 | 与 McpSseClient 一致，兼容现有 MCP 生态 |
| #20 | `@SnapAgentTools` 标记注解 | 显式选择暴露哪些 Service，非自动扫描所有 @Service |
| #21 | 四层权限模型 | 连接→可见→参数→返回，每层独立拦截 |
| #22 | `ToolExecutionContext` 框架注入 | 不暴露给 LLM，方法内可做数据范围校验 |
| #23 | allowlist 默认模式 | 防止意外暴露敏感工具（如 mysql_query） |

## 14. 实现分期

### Phase 1 (MVP)

| 项 | 说明 |
|----|------|
| `@SnapAgentTools` 注解 + 自动扫描 | 宿主 Service 标注即注册到 ToolCallbackRegistry |
| `McpServerController` + `McpServerHandler` | initialize + tools/list + tools/call |
| Token 认证 | 连接层校验，解析 McpCallerContext |
| allowlist/denylist 配置 | 工具可见层过滤 |
| 审计日志 | 所有 MCP 调用记录 caller + tool + args + result 摘要 |

### Phase 2 (生产可用)

| 项 | 说明 |
|----|------|
| `@ToolVisibility` 注解 | 细粒度工具可见性控制 |
| `ToolExecutionContext` 注入 | 参数约束层，方法内做数据范围校验 |
| `@ToolResultMask` 注解 | 数据返回层字段脱敏 |
| 会话管理 | max-sessions 限制 + 超时清理 |

### Phase 3 (企业级)

| 项 | 说明 |
|----|------|
| OAuth2 / Spring Security 集成 | 连接层企业认证 |
| 多租户数据范围隔离 | 每个会话独立工具可见性 |
| 速率限制 + 配额管理 | 按 caller 限流 |
| `notifications/tools/list_changed` | 动态工具注册/注销通知 |
| Prometheus 指标 | MCP 调用量/延迟/错误率 |

## 15. 验证

### 验证项 #6: 宿主 MCP Server 端到端

1. **工具发现**：外部 MCP 客户端连接 → `tools/list` 返回 `@SnapAgentTools` 标注的工具。
2. **工具调用**：`tools/call` → 宿主 Service 方法执行 → 返回 `result.toString()`。
3. **认证拦截**：无 token / 错误 token → 连接拒绝。
4. **工具过滤**：`exposed-tools.excludes` 中的工具不出现在 `tools/list`。
5. **零影响**：`mcp.server.enabled=false` → 端点不存在，宿主无感知。
6. **双向共存**：同时启用 MCP Client + MCP Server，工具互通无误。

### 验证项 #7: 权限链路

1. **allowlist 过滤**：`exposed-tools.includes` 外的工具不可见。
2. **`@ToolVisibility(external=false)`**：标记的工具不出现在 `tools/list`。
3. **数据范围校验**：caller 仓库范围外的参数 → 抛 `PermissionException`。
4. **字段脱敏**：非 finance 角色 → `unit_cost` / `supplier` 字段为 null。

## 16. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| **Prompt injection 跨边界** | 外部 AI 可能被注入恶意指令 | allowlist 默认模式 + Token 认证 + 审计日志 |
| **`@Tool` 方法副作用** | 宿主 Service 方法可能有写操作 | `@ToolVisibility(external=false)` 排除写工具；文档标注风险 |
| **Token 泄露** | 持有 token 的客户端可访问所有暴露工具 | Phase 2 细粒度角色 + 数据范围上下文 |
| **SSE 连接泄漏** | 客户端断开但 session 未清理 | session-timeout-seconds + max-sessions 上限 |
| **工具数量爆炸** | 大量 `@Tool` 方法导致 `tools/list` 过大 | allowlist 过滤 + 分组（Phase 3） |
| **内嵌 LLM 与外部 caller 竞争** | 同一工具被内嵌 LLM 和外部 Agent 同时调用 | 无状态设计，`ToolExecutionContext` 可选参数 |
