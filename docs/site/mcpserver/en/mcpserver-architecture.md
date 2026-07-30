# SnapAgent Host MCP Server Architecture

> Version: v1.0-draft | Updated: 2026-07-30

## 1. Overview

The SnapAgent Host MCP Server exposes the host application's `@Tool` business capabilities as an MCP Server, allowing external AI Agents (Claude Code / Cursor / Windsurf) to directly discover and invoke them without requiring humans to translate API semantics.

### Core Positioning

**Not a REST API wrapper** — the core value is exposing the `@Tool` tool system accumulated by SnapAgent (the host application's real business capabilities, not REST endpoints) to external AI Agents.

### Symmetric Design

```
McpSseClient (existing)  →  connects to external MCP Servers, consumes tools
McpServerController (new)  →  exposes internal tools for external consumption
```

Both share the `ToolCallback` SPI, achieving zero-duplication tool definition and invocation logic.

### Core Design Principles

| Principle | Description |
|-----------|-------------|
| **Zero Intrusion** | When `mcp.server.enabled=false` (default), no MCP Server beans are created |
| **Explicit Exposure** | Only `@SnapAgentTools`-annotated Services are registered; not auto-scanning all @Service |
| **Allowlist First** | Default allowlist mode prevents accidental exposure of sensitive tools (e.g., `mysql_query`) |
| **Four-Layer Security** | Connection → Visibility → Parameters → Return; each layer intercepts independently |

---

## 2. Architecture Topology

```
┌──────────────────────────────────────────────────────────────┐
│  External AI Agent (Claude Code / Cursor / Windsurf)           │
│    headers: { X-Mcp-Token: "dev-team-xxx" }                     │
└──────────────────┬───────────────────────────────────────────┘
                   │ SSE + JSON-RPC
                   ▼
┌──────────────────────────────────────────────────────────────┐
│  ① Connection Layer — McpServerAuthFilter                      │
│    → Validate Token / IP whitelist                              │
│    → Resolve to McpCallerContext { callerId, roles, warehouses }│
│    → Bind to McpSession                                         │
└──────────────────┬───────────────────────────────────────────┘
                   │
     ② Visibility   ▼
┌──────────────────────────────────────────────────────────────┐
│  McpServerController                                           │
│    GET  /snap-agent/mcp/sse      → SSE connection, returns POST endpoint │
│    POST /snap-agent/mcp/messages → JSON-RPC request handling   │
│                                                                │
│  McpServerHandler                                              │
│    • initialize  → protocol handshake                         │
│    • tools/list   → returns filtered tool list per McpCallerContext │
│    • tools/call   → invokes ToolCallback.execute()            │
└──────────────────┬───────────────────────────────────────────┘
                   │
     ③ Parameters   ▼
┌──────────────────────────────────────────────────────────────┐
│  ToolCallbackRegistry (unified registry)                       │
│    ① Built-in @Tool methods (JdbcQueryTools, RedisReadTools)  │
│    ② MCP Client tools (mcp__{server}__{tool}, relays external) │
│    ③ JAR plugin tools (PluginRegistry dynamic loading)        │
│    ④ Host @SnapAgentTools (InventoryService.queryStock, ...)  │
│                                                                │
│  ToolExecutionContext (framework-injected, invisible to LLM)   │
│    → caller warehouse scope validation                         │
│    → environment isolation (sit/uat/prod)                      │
│    → read/write restrictions                                   │
└──────────────────┬───────────────────────────────────────────┘
                   │
     ④ Return      ▼
┌──────────────────────────────────────────────────────────────┐
│  ResultPostProcessor                                           │
│    → @ToolResultMask annotation processing (field masking)     │
│    → Rate limit check                                          │
│    → Audit log (caller + tool + args + result summary)         │
│    → Return final ToolResult                                   │
└──────────────────────────────────────────────────────────────┘
```

### Layer Description

| Layer | Components | Responsibility |
|-------|-----------|----------------|
| Connection | McpServerAuthFilter | Token auth, IP whitelist, resolve McpCallerContext |
| Visibility | McpServerHandler.handleToolsList() | allowlist/denylist filtering, @ToolVisibility role matching |
| Parameters | ToolExecutionContext | Framework-injected into @Tool methods for data scope validation |
| Return | ResultPostProcessor | @ToolResultMask field masking, audit logging, rate limiting |

---

## 3. @SnapAgentTools Auto-Registration

### Marker Annotation

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SnapAgentTools {
    // Marks this class as containing @Tool methods to register with ToolCallbackRegistry
}
```

### Host Usage Example

```java
@Service
@SnapAgentTools
public class InventoryService {

    @Tool(name = "query_stock", description = "Query stock details by SKU")
    public StockInfo queryStock(
            @ToolParam(description = "SKU code") String sku,
            @ToolParam(description = "Warehouse name") String warehouse) {
        // Inside: call Mapper, query MySQL, invoke other Services — anything works
        return stockMapper.selectBySkuAndWarehouse(sku, warehouse);
    }
}
```

### Why Not Auto-Scan All @Service

- Not all `@Service` beans should be exposed to AI Agents.
- `@SnapAgentTools` lets developers **explicitly choose** which Services to expose.
- Follows the "zero impact" principle: no annotation = no registration = no impact.

---

## 4. Four-Layer Security Model

Security is not a single point; it's a complete chain from connection to return:

| Layer | Mechanism | Description |
|-------|-----------|-------------|
| ① Connection | Token auth + IP whitelist | Validate `X-Mcp-Token`, resolve to `McpCallerContext` (callerId / roles / data scope) |
| ② Visibility | allowlist/denylist + @ToolVisibility | Filter before `tools/list` response: exclude `external=false` tools, exclude role-mismatched tools |
| ③ Parameters | ToolExecutionContext injection | Validate data scope inside method (warehouse scope / environment isolation / read-write limits) |
| ④ Return | @ToolResultMask field masking | Non-finance roles cannot see `unit_cost` / `supplier` fields |

### ToolExecutionContext Injection

```java
@Tool(name = "query_stock", description = "Query stock")
public StockInfo queryStock(
        @ToolParam(description = "SKU code") String sku,
        @ToolParam(description = "Warehouse name") String warehouse,
        ToolExecutionContext context) {           // ← framework-injected, not exposed to LLM
    McpCallerContext caller = context.getCaller();
    if (caller.getWarehouses() != null
        && !caller.getWarehouses().contains(warehouse)) {
        throw new PermissionException("No access to warehouse: " + warehouse);
    }
    return stockMapper.selectBySkuAndWarehouse(sku, warehouse);
}
```

`ToolExecutionContext` is framework-injected — it does not appear in the LLM's tool schema, the LLM cannot see or modify it.

---

## 5. Configuration

```yaml
snap-agent:
  mcp:
    # Client mode (existing)
    enabled: false
    servers:
      bdp-data-map:
        transport: sse
        url: https://bdp-mcp.sit/mcp/sse

    # Server mode (new)
    server:
      enabled: false                    # default off
      path: /snap-agent/mcp             # endpoint path prefix
      auth:
        type: token                     # token | mTLS | oauth
        token-header: X-Mcp-Token
        tokens:
          dev-team: ${MCP_TOKEN_DEV:}
          ops-team: ${MCP_TOKEN_OPS:}
        ip-whitelist:                   # optional
          - 10.0.0.0/8
      exposed-tools:
        mode: allowlist                 # allowlist | denylist | role-based
        includes:
          - query_stock
          - query_transfers
        excludes:
          - mysql_query
          - redis_get
      max-sessions: 10                  # max concurrent SSE sessions
      session-timeout-seconds: 300      # session timeout
      audit-log: true                   # call audit log
```

---

## 6. JSON-RPC Protocol

### initialize

```json
// Request
{ "jsonrpc": "2.0", "id": "1", "method": "initialize",
  "params": { "protocolVersion": "2024-11-05", "capabilities": {} } }

// Response
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
        "description": "Query stock details by SKU",
        "inputSchema": { "type": "object",
          "properties": {
            "sku": { "type": "string", "description": "SKU code" },
            "warehouse": { "type": "string", "description": "Warehouse name" } },
          "required": ["sku", "warehouse"] } }
    ] } }
```

### tools/call

```json
// Request
{ "jsonrpc": "2.0", "id": "3", "method": "tools/call",
  "params": { "name": "query_stock",
              "arguments": { "sku": "SKU001", "warehouse": "East" } } }

// Response
{ "jsonrpc": "2.0", "id": "3", "result": {
    "content": [ { "type": "text", "text": "SKU001 East warehouse: 500 units in stock..." } ] } }
```

---

## 7. Use Cases

### Use Case 1: Developer Debugging in IDE

In Claude Code's MCP config:
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
Developer: "When was SKU001 last replenished? How much?"
Claude Code:
  → MCP tools/list auto-discovers: query_stock, query_replenishment_history
  → Auto-invokes query_replenishment_history(sku="SKU001")
  → "SKU001 was last replenished on 2026-07-25, 500 units, source: North Warehouse"
```

### Use Case 2: Ops Troubleshooting with AI

```
Ops (Claude Code connected to host MCP):
  "Were there any abnormal transfers at the East warehouse today?"
  → MCP auto-discovers: query_transfers, query_stock_alerts
  → AI autonomously orchestrates multi-step calls: query alerts first, then transfers
  → Provides analysis conclusion
```

### Use Case 3: Cross-System Orchestration

External AI Agent connects to multiple MCP Servers simultaneously:
```
  - Inventory MCP → query_stock
  - Logistics MCP → query_shipment
  - Finance MCP → query_cost

  "What's the total cost of transferring SKU001 from East to South warehouse?"
  → AI autonomously combines tools from three systems for cross-domain query
```

### Use Case 4: Tool Chain Relay

Host A is both an MCP Server (externally) and an MCP Client (internally connecting to other MCP Servers):
```
External Agent → MCP Server (Host A) → tools/call →
  Host A's ToolCallbackRegistry mcp__external__* →
  MCP Client → Host B's MCP Server
```

---

## 8. Implementation Phases

| Phase | Deliverables |
|-------|-------------|
| Phase 1 (MVP) | `@SnapAgentTools` annotation + auto-scan, `McpServerController` + `McpServerHandler`, Token auth, allowlist/denylist config, audit log |
| Phase 2 (Production) | `@ToolVisibility` annotation, `ToolExecutionContext` injection, `@ToolResultMask` field masking, session management |
| Phase 3 (Enterprise) | OAuth2 / Spring Security integration, multi-tenant data scope isolation, rate limiting + quota management, `notifications/tools/list_changed`, Prometheus metrics |

---

## 9. Zero-Impact Proof

- `mcp.server.enabled=false` (default) → no `McpServerController` / `McpServerHandler` / `McpServerAuthFilter` created; endpoints don't exist.
- `@SnapAgentTools` not annotated → host Services are not scanned; existing behavior unchanged.
- When embedded LLM calls `@Tool` methods, `ToolExecutionContext` is null; methods should do null check.
- MCP Server and MCP Client have independent switches; can enable both (bidirectional MCP) or individually.

---

## 10. Relationship with Existing Components

```
                    ┌─────────────────────┐
                    │ ToolCallbackRegistry │
                    │  (unified registry)  │
                    └──────┬──────────────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
  │ Embedded LLM │  │ MCP Server  │  │ MCP Client  │
  │ (ReAct loop) │  │ (new, ext.) │  │ (existing)  │
  │ ToolsNode   │  │ Handler     │  │ McpSseClient │
  └─────────────┘  └─────────────┘  └─────────────┘
```

Three consumers share the same `ToolCallbackRegistry`, achieving zero-duplication tool definition and invocation logic.
