# 04 — 工具与 MCP

## 1. ToolProvider SPI

```java
public interface ToolProvider {
    /** 工具名，skill frontmatter tools 字段引用此名 */
    String name();

    /** JSON Schema，注入 LLM tools 定义（Anthropic format） */
    String schema();

    /** 执行；ctx 提供 userId/tenantId/审计回调 */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}

public record ToolResult(
    String content,        // 回填 LLM 的文本（JSON 或表格）
    int rowCount,          // 行数（SQL 用）
    boolean truncated,     // 是否截断
    long durationMs,
    String error           // 非 null 表示失败
) {}
```

`ToolDispatcher` 持有所有已装配 `ToolProvider`（按 `name()` 建 map），路由 `tool_use.name`。skill 加载时用 `ToolDispatcher.availableToolNames()` 做 tools 契约校验（见 [02](02-skill-loading.md) §5）。

## 2. JdbcQueryToolProvider

### 2.1 独立只读 DSN（决策 #3）

- 不用宿主业务 DataSource，用 yml `snap-agent.jdbc.datasource-bean-name` 指向的独立 DataSource bean。
- 集成指南要求运维建**只读受限 DB 用户**（见 [09](09-integration-guide.md) §3）。爆炸半径限制在该 DB 用户授权范围 —— 这是 prompt injection 的**主防御**。
- 工具名：`mysql_query`。
- schema：
```json
{"name":"mysql_query","description":"Execute a read-only SQL query.","input_schema":{
  "type":"object","properties":{"sql":{"type":"string","description":"SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query"}},
  "required":["sql"]}}
```

### 2.2 SQL guard（只读强制 — 验证项 #3）

执行流程：
1. `sql = args.get("sql").trim()`，去掉首尾分号与注释。
2. **多语句拒绝**：若剥离末尾 `;` 后仍含 `;` → 拒绝。
3. **首关键字白名单**：正则 `^\s*(WITH|SELECT|SHOW|DESCRIBE|EXPLAIN|DESC)\b` 不匹配 → 拒绝。
4. **危险关键字黑名单**（大小写不敏感，跨整个 SQL）：命中任一 → 拒绝。
5. **LIMIT 注入**：若无 `LIMIT`，追加 `LIMIT {max-result-rows}`；若有 `LIMIT n` 且 `n > max-result-rows`，改写为 `LIMIT {max-result-rows}`。
6. 执行 `stmt.executeQuery`（Statement 只读，不支持 `executeUpdate`）。
7. 行数截断到 `max-result-rows`（默认 1000），字符截断到 `max-tool-result-chars`（默认 50000）。

### 2.3 危险关键字黑名单（正则，大小写不敏感）

```
\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|RENAME|GRANT|REVOKE|
   REPLACE|MERGE|CALL|HANDLER|LOCK|UNLOCK|FLUSH|RESET|SHUTDOWN|KILL|
   LOAD|LOAD_FILE|INTO\s+OUTFILE|INTO\s+DUMPFILE|
   SLEEP|BENCHMARK)\b
```

> 注：`LOAD_FILE`/`INTO OUTFILE`/`INTO DUMPFILE` 单列在黑名单，且 `INTO\s+OUTFILE` 也被 `INTO` ... 实际 `INTO` 本身在 `INSERT ... INTO` 语境已被 `INSERT` 拦。这里显式列 OUTFILE/DUMPFILE 是为防 `SELECT ... INTO OUTFILE`（首关键字是 SELECT，会过白名单，必须靠黑名单拦）。

### 2.4 拒绝用例（验证项 #3 证据）

| 输入 | 拒绝原因 | 命中规则 |
|------|---------|---------|
| `DELETE FROM users WHERE id=1` | 首关键字 DELETE | §2.2 步骤3 |
| `UPDATE sep_wh_replenish SET status=0` | 首关键字 UPDATE | §2.2 步骤3 |
| `DROP TABLE sep_wh_replenish` | 首关键字 DROP | §2.2 步骤3 |
| `SELECT * FROM users; DROP TABLE users; --` | 多语句（含 `;`） | §2.2 步骤2 |
| `SELECT * FROM users INTO OUTFILE '/tmp/x'` | 含 `INTO OUTFILE` | §2.2 步骤4 黑名单 |
| `SELECT LOAD_FILE('/etc/passwd')` | 含 `LOAD_FILE` | §2.2 步骤4 黑名单 |
| `SELECT SLEEP(60)` | 含 `SLEEP` | §2.2 步骤4 黑名单 |
| `SELECT * FROM users` | ✅ 通过，追加 `LIMIT 1000` | §2.2 步骤5 |
| `WITH essd_agg AS (...) SELECT ...` | ✅ 通过（CTE，首关键字 WITH） | §2.2 步骤3 |

### 2.5 SQL guard 的诚实局限（风险声明）

guard 是**语法层**而非**语义层**，可被合法语法绕过，例如：
```sql
SELECT password FROM drp_sys_user WHERE user_name = 'admin'
```
这条 SQL 首关键字是 `SELECT`、无黑名单词、会被通过 —— 它能读出用户表密码字段（即使是哈希）。

**这不是 guard 的职责，是 DB 用户授权的职责。** 独立只读 DB 用户应只授业务诊断所需表的 SELECT 权限，**不授** `drp_sys_user` / 权限表 / 密码列。见 [09-integration-guide.md](09-integration-guide.md) §3 授权示例。文档诚实承认：**prompt injection 不可完全防，只读工具 + 只读 DB 用户限制爆炸半径，审计是检测而非预防。**

### 2.6 审计（决策 #14）

每次 `mysql_query` 调用追加 `AuditRecord`：
```
{ taskId, userId, toolName:"mysql_query", args:{sql}, rowCount, truncated, timestamp, durationMs }
```
- 存 `AgentTask.transcript`（SSE 实时推）+ ring-buffer/Redis list（`GET /runs/{id}/transcript` 可查）。
- 审计开关：`snap-agent.security.audit-log`（默认 true）。

## 3. RedisReadToolProvider

- 用 `redis-template-bean-name` 指向的 `RedisTemplate`（按名注入，见 [01](01-architecture.md) §5）。
- 工具名：`redis_get`。
- schema：
```json
{"name":"redis_get","description":"Read a Redis key.","input_schema":{
  "type":"object","properties":{"key":{"type":"string"}},"required":["key"]}}
```
- 仅支持 `get` / `exists`。**无 set/del/incr 等写命令。**
- `KEYS *` 拒绝（决策 #10）：要求 pattern 必须有业务前缀（至少一个 `:`），且命中数 ≤ `redis.max-key-count`（默认 100）。
  - 工具不暴露 `keys` 给 LLM；若需要列键，用受限的 `redis_scan`（Phase 2 再考虑），Phase 1 只 `get`/`exists`。
  - 即便 Phase 2 加 `keys`，pattern 必须匹配 `^[^:*\?]+:.+`（含冒号前缀），`KEYS *` 直接拒。

## 4. MCP（Phase 2，SSE/HTTP only — 决策 #16）

### 为什么不 stdio
Web 容器不应 spawn 子进程；K8s pod 无 Node。stdio MCP 需要 long-lived 子进程，与容器模型冲突。因此 Phase 2 仅接 **SSE/HTTP transport** 的 MCP server。

### 配置
```yaml
snap-agent:
  mcp:
    enabled: false              # Phase 2 默认关
    servers:
      bdp-data-map:
        transport: sse
        url: https://bdp-mcp.sit/mcp/sse
        auth-header: "X-Bdp-Token"      # header 名
        auth-header-value: ${BDP_TOKEN:} # 值（环境变量注入）
```

### McpToolProvider
- 启动时按 `mcp.servers` 配置，对每个 server 调 `initialize` 握手，拉取其 `tools/list`。
- 把远端工具以 `mcp__{server}__{tool}` 名注册到 `ToolDispatcher`（与 Claude Code MCP 工具命名一致）。
- skill frontmatter `tools` 可声明 `mcp__bdp-data-map__search_table` 等。
- 远端工具的执行 = OkHttp POST 到 MCP server 的 endpoint，透传结果。
- **安全**：MCP server 的只读性由该 server 自身保证，本库不保证。文档标注：接 MCP 等于信任该 server 的安全边界。

## 5. 工具装配条件矩阵

| ToolProvider | 装配条件 | 缺失时 |
|--------------|---------|--------|
| `JdbcQueryToolProvider` | `jdbc.enabled=true` 且 bean 名指向的 DataSource 存在 | 静默不装配；声明 `mysql_query` 的 skill 标 UNAVAILABLE |
| `RedisReadToolProvider` | `redis.enabled=true` 且 RedisTemplate bean 存在 | 同上，`redis_get` 不可用 |
| `McpToolProvider` | `mcp.enabled=true`（Phase 2） | 不装配 |

## 6. 验证（验证项 #3 只读强制证明）

- SQL guard 正则 + 拒绝用例：见 §2.4 表，9 个用例覆盖 DELETE/UPDATE/DROP/多语句/INTO OUTFILE/LOAD_FILE/SLEEP/正常 SELECT/CTE。
- 独立只读 DB 用户授权示例：见 [09-integration-guide.md](09-integration-guide.md) §3。
- Redis `KEYS *` 拒绝：见 §3。
