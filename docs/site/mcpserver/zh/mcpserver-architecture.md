# SnapAgent 宿主 MCP Server 架构

> 版本：v1.0-draft | 更新日期：2026-07-30

## 1. 概述

SnapAgent 宿主 MCP Server 让宿主应用通过 SnapAgent 暴露自身 `@Tool` 业务能力为 MCP Server，外部 AI Agent（Claude Code / Cursor / Windsurf）可直接发现和调用，无需人工翻译 API 语义。

### 核心定位

**不是给 REST API 套壳** — 核心是把 SnapAgent 已积累的 `@Tool` 工具体系（宿主应用的真实业务能力，非 REST）暴露给外部 AI Agent。

### 对称设计

```
McpSseClient (已有)  →  连接外部 MCP Server，消费工具
McpServerController (新增) →  暴露内部工具，供外部消费
```

两者共用 `ToolCallback` SPI，实现零重复的工具定义和调用逻辑。

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **零侵入** | `mcp.server.enabled=false`（默认）时，不创建任何 MCP Server 相关 Bean |
| **显式暴露** | `@SnapAgentTools` 标注的 Service 才会被注册，非自动扫描所有 @Service |
| **allowlist 优先** | 默认 allowlist 模式，防止意外暴露敏感工具（如 `mysql_query`） |
| **四层权限** | 连接→可见→参数→返回，每层独立拦截 |

---

## 2. 架构拓扑

```
┌──────────────────────────────────────────────────────────────┐
│  外部 AI Agent (Claude Code / Cursor / Windsurf)              │
│    headers: { X-Mcp-Token: "dev-team-xxx" }                     │
└──────────────────┬───────────────────────────────────────────┘
                   │ SSE + JSON-RPC
                   ▼
┌──────────────────────────────────────────────────────────────┐
│  ① 连接层 — McpServerAuthFilter                                │
│    → 校验 Token / IP 白名单                                    │
│    → 解析为 McpCallerContext { callerId, roles, warehouses }   │
│    → 绑定到 McpSession                                         │
└──────────────────┬───────────────────────────────────────────┘
                   │
     ② 可见层       ▼
┌──────────────────────────────────────────────────────────────┐
│  McpServerController                                           │
│    GET  /snap-agent/mcp/sse      → SSE 连接, 返回 POST 端点    │
│    POST /snap-agent/mcp/messages → JSON-RPC 请求处理           │
│                                                                │
│  McpServerHandler                                              │
│    • initialize  → 协议握手                                    │
│    • tools/list  → 按 McpCallerContext 过滤后返回工具列表      │
│    • tools/call  → 调用 ToolCallback.execute()                │
└──────────────────┬───────────────────────────────────────────┘
                   │
     ③ 参数层       ▼
┌──────────────────────────────────────────────────────────────┐
│  ToolCallbackRegistry (统一注册表)                              │
│    ① 内置 @Tool 方法 (JdbcQueryTools, RedisReadTools, ...)    │
│    ② MCP Client 工具 (mcp__{server}__{tool}, 中继外部)        │
│    ③ JAR 插件工具 (PluginRegistry 动态加载)                    │
│    ④ 宿主 @SnapAgentTools (InventoryService.queryStock, ...)  │
│                                                                │
│  ToolExecutionContext (框架注入, LLM 不可见)                    │
│    → caller 仓库范围校验                                        │
│    → 环境隔离 (sit/uat/prod)                                   │
│    → 读写限制                                                    │
└──────────────────┬───────────────────────────────────────────┘
                   │
     ④ 返回层       ▼
┌──────────────────────────────────────────────────────────────┐
│  ResultPostProcessor                                           │
│    → @ToolResultMask 注解处理 (字段脱敏)                        │
│    → 速率限制检查                                               │
│    → 审计日志记录 (caller + tool + args + result 摘要)         │
│    → 返回最终 ToolResult                                        │
└──────────────────────────────────────────────────────────────┘
```

### 层级说明

| 层 | 组件 | 职责 |
|-----|------|------|
| 连接层 | McpServerAuthFilter | Token 认证、IP 白名单、解析 McpCallerContext |
| 可见层 | McpServerHandler.handleToolsList() | allowlist/denylist 过滤、@ToolVisibility 角色匹配 |
| 参数层 | ToolExecutionContext | 框架注入到 @Tool 方法，做数据范围校验（仓库/环境/读写） |
| 返回层 | ResultPostProcessor | @ToolResultMask 字段脱敏、审计日志、速率限制 |

---

## 3. @SnapAgentTools 自动注册

### 标记注解

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SnapAgentTools {
    // 标记此类含 @Tool 方法，需注册到 ToolCallbackRegistry
}
```

### 宿主使用示例

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
}
```

### 为什么不用 @Service 自动扫描

- 并非所有 `@Service` 都应该暴露给 AI Agent。
- `@SnapAgentTools` 让开发者**显式选择**哪些 Service 的能力要暴露。
- 遵循"零影响"原则：不标注 = 不注册 = 不影响。

---

## 4. 四层权限模型

权限不是单个点，是一条从连接到返回的完整链路：

| 层 | 机制 | 说明 |
|----|------|------|
| ① 连接层 | Token 认证 + IP 白名单 | 校验 `X-Mcp-Token`，解析为 `McpCallerContext`（callerId / roles / 数据范围） |
| ② 可见层 | allowlist/denylist + @ToolVisibility | `tools/list` 返回前过滤：排除 `external=false` 的工具、排除角色不匹配的工具 |
| ③ 参数层 | ToolExecutionContext 注入 | 方法内用 `context.getCaller()` 做数据范围校验（仓库范围/环境隔离/读写限制） |
| ④ 返回层 | @ToolResultMask 字段脱敏 | 非 finance 角色看不到 `unit_cost` / `supplier` 字段 |

### ToolExecutionContext 注入

```java
@Tool(name = "query_stock", description = "查询库存")
public StockInfo queryStock(
        @ToolParam(description = "SKU编号") String sku,
        @ToolParam(description = "仓库名称") String warehouse,
        ToolExecutionContext context) {           // ← 框架注入, 不暴露给 LLM
    McpCallerContext caller = context.getCaller();
    if (caller.getWarehouses() != null
        && !caller.getWarehouses().contains(warehouse)) {
        throw new PermissionException("无权访问仓库: " + warehouse);
    }
    return stockMapper.selectBySkuAndWarehouse(sku, warehouse);
}
```

`ToolExecutionContext` 是框架注入的，不出现在 LLM 的 tool schema 里，LLM 看不到也改不了。

---

## 5. 配置

```yaml
snap-agent:
  mcp:
    # 客户端模式 (已有)
    enabled: false
    servers:
      bdp-data-map:
        transport: sse
        url: https://bdp-mcp.sit/mcp/sse

    # 服务端模式 (新增)
    server:
      enabled: false                    # 默认关闭
      path: /snap-agent/mcp             # 端点路径前缀
      auth:
        type: token                     # token | mTLS | oauth
        token-header: X-Mcp-Token
        tokens:
          dev-team: ${MCP_TOKEN_DEV:}
          ops-team: ${MCP_TOKEN_OPS:}
        ip-whitelist:                   # 可选
          - 10.0.0.0/8
      exposed-tools:
        mode: allowlist                 # allowlist | denylist | role-based
        includes:
          - query_stock
          - query_transfers
        excludes:
          - mysql_query
          - redis_get
      max-sessions: 10                  # 最大并发 SSE 会话
      session-timeout-seconds: 300      # 会话超时
      audit-log: true                   # 调用审计日志
```

---

## 6. JSON-RPC 协议

### initialize

```json
// 请求
{ "jsonrpc": "2.0", "id": "1", "method": "initialize",
  "params": { "protocolVersion": "2024-11-05", "capabilities": {} } }

// 响应
{ "jsonrpc": "2.0", "id": "1", "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": { "listChanged": true } },
    "serverInfo": { "name": "snap-agent-mcp-server", "version": "1.0" } } }
```

### tools/list

```json
{ "jsonrpc": "2.0", "id": "2", "result": {
    "tools": [
      { "name": "query_stock",
        "description": "根据SKU查询库存详情",
        "inputSchema": { "type": "object",
          "properties": {
            "sku": { "type": "string", "description": "SKU编号" },
            "warehouse": { "type": "string", "description": "仓库名称" } },
          "required": ["sku", "warehouse"] } }
    ] } }
```

### tools/call

```json
// 请求
{ "jsonrpc": "2.0", "id": "3", "method": "tools/call",
  "params": { "name": "query_stock",
              "arguments": { "sku": "SKU001", "warehouse": "华东" } } }

// 响应
{ "jsonrpc": "2.0", "id": "3", "result": {
    "content": [ { "type": "text", "text": "SKU001 华东仓: 库存 500 件..." } ] } }
```

---

## 7. 使用场景

### 场景 1：开发者在 IDE 调试库存系统

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
  → MCP tools/list 自动发现: query_stock, query_replenishment_history
  → 自动调用 query_replenishment_history(sku="SKU001")
  → "SKU001 上次补货是 2026-07-25, 补了 500 件, 来源仓库: 华北总仓"
```

### 场景 2：运维用 AI 排查线上问题

```
运维 (Claude Code 连接宿主 MCP):
  "华东仓今天有没有异常调拨？"
  → MCP 自动发现: query_transfers, query_stock_alerts
  → AI 自主编排多步调用: 先查 alerts, 再查对应 transfers
  → 给出分析结论
```

### 场景 3：跨系统联动

外部 AI Agent 同时连接多个 MCP Server：
```
  - 库存系统 MCP → query_stock
  - 物流系统 MCP → query_shipment
  - 财务系统 MCP → query_cost

  "SKU001 从华东仓调到华南仓的总成本是多少？"
  → AI 自主组合三个系统的工具完成跨域查询
```

### 场景 4：工具链中继

宿主 A 既是 MCP Server（对外），又是 MCP Client（对内连接其他 MCP Server）：
```
外部 Agent → MCP Server (宿主A) → tools/call →
  宿主A 的 ToolCallbackRegistry 中的 mcp__external__* →
  MCP Client → 宿主B 的 MCP Server
```

---

## 8. 实现分期

| 阶段 | 交付项 |
|------|--------|
| Phase 1 (MVP) | `@SnapAgentTools` 注解 + 自动扫描、`McpServerController` + `McpServerHandler`、Token 认证、allowlist/denylist 配置、审计日志 |
| Phase 2 (生产可用) | `@ToolVisibility` 注解、`ToolExecutionContext` 注入、`@ToolResultMask` 字段脱敏、会话管理 |
| Phase 3 (企业级) | OAuth2 / Spring Security 集成、多租户数据范围隔离、速率限制 + 配额管理、`notifications/tools/list_changed`、Prometheus 指标 |

---

## 9. 零影响证明

- `mcp.server.enabled=false`（默认）→ 不创建 `McpServerController` / `McpServerHandler` / `McpServerAuthFilter`，端点不存在。
- `@SnapAgentTools` 不标注 → 宿主 Service 不被扫描，不影响已有行为。
- 内嵌 LLM 调用 `@Tool` 方法时，`ToolExecutionContext` 为 null，方法内需做 null check。
- MCP Server 和 MCP Client 独立开关，可同时启用（双向 MCP）或单独启用。

---

## 10. 与现有组件的关系

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

三个消费方共用同一套 `ToolCallbackRegistry`，实现零重复的工具定义和调用逻辑。
