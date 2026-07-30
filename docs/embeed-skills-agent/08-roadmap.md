# 08 — 路线图

> **注意**：此文档为设计阶段路线图快照。项目实际交付状态以 [`docs/ROADMAP.md`](../ROADMAP.md) 为准（已交付至 v0.7+）。

## MVP（Phase 1）— ✅ 已交付

**范围**：让一个 Spring Boot 2.x 宿主引入 starter 即可加载并执行标准 skill，只读 SQL/Redis + SSE 推送。

| 交付项 | 说明 | 文档 |
|--------|------|------|
| `snap-agent-core` | skill 解析 / graph 运行时 / LlmClient SPI / `@Tool` + `ToolCallback` SPI，servlet 无关 | 01 §1 |
| `snap-agent-spring-boot-2x-starter` | `javax.servlet` Filter + AutoConfig (thin) + 8 domain `@Configuration` + `spring.factories` + 静态 UI | 01 §1 |
| SkillRegistry + 标准 frontmatter | `name`/`description`/`tools`/`inputs`，启动缓存 + 手动刷新 | 02 |
| GraphExecutor 流式循环 | LLM 流式 + tool_use 分发（ToolsNode + ToolCallbackRegistry） + max-turns + TaskStore | 03 |
| JdbcQueryTools | 独立只读 DSN + SQL guard + LIMIT + 审计 | 04 §2 |
| RedisReadTools | get/exists，`KEYS *` 拒绝 | 04 §3 |
| AnthropicLlmClient (extends AbstractStreamingLlmClient) | OkHttp 流式 + per-run model + 服务端白名单 | 05 |
| REST + SSE + 单页 SPA | skills/tools/models/runs + stream + transcript + 原生 JS UI | 06 |
| SecurityGateway 双 Adapter | SpringSecurityAdapter + ShiroAdapter | 07 §3 |
| PrincipalResolver SPI | 默认实现 + 可自定义 | 07 §5 |
| 限流 + 专用线程池 | 每用户并发 1 / 每小时 20 / core2-max4-queue10 | 03 §6-7 |
| 审计 | 每次 tool 调用记录 | 04 §2.6 / 07 §6 |

**MVP 完成定义**：
- 验证项 #2 可行性走查跑通（`sep-wh-replenish-diagnose` 端到端）。
- 验证项 #3 只读强制证明（SQL guard 拒绝用例 + 只读 DB 用户）。
- 验证项 #4 零影响证明（`enabled=false` 无 bean/Filter/线程池）。
- 验证项 #5 双框架鉴权走查。

## Phase 2 — ✅ 已交付（v0.2）

| 项 | 说明 |
|----|------|
| MCP SSE/HTTP 接入 | `mcp.servers` 配置，SSE transport only，远端工具以 `mcp__{server}__{tool}` 注册（决策 #16） |
| Run 取消 | `POST /runs/{id}/cancel`，配合 OkHttp `Call.cancel()` 中断 LLM 流式 |
| Redis `redis_scan`（可选） | 受限 SCAN 替代 KEYS，仍强制前缀 pattern + max-key-count |
| 报告导出 | `GET /runs/{id}/report?format=md` 下载诊断报告 markdown |
| 审计持久化 | ring-buffer → 可选 DB 表（`skills_agent_audit`）便于长期追溯 |

## Phase 3 — ✅ 已交付（v0.3–v0.7）

| 项 | 说明 | 状态 |
|----|------|------|
| `snap-agent-spring-boot-3x-starter` | `jakarta.servlet` 版本，controller/filter 重写 | 待定 |
| OpenAI 适配器 | `OpenAiLlmClient`（extends `AbstractStreamingLlmClient`），`llm.api-type: openai` | 待定 |
| skill 热重载 | WatchService 监听 `upload-skills-dir`，文件变更自动 refresh（仍线程安全） | ✅ 已交付 (v0.2) |
| 报告富文本渲染 | markdown 报告 + 表格 + 折叠 SQL + 复制按钮（前端增强） | ✅ 已交付 |
| OkHttp shade/relocate | `llm.shade-okhttp=true`，解决与宿主 OkHttp 大版本冲突 | 待定 |
| 多 DSN（多环境） | `jdbc.datasources: {sit: ..., uat: ...}`，skill `inputs.env` 选 DSN | ✅ 已交付 (v0.6) |
| 任务历史列表 | `GET /runs?userId=&skillId=&page=`，跨 session 查历史诊断 | ✅ 已交付 |

## Phase 4 — 宿主 MCP Server

> 完整设计见 [11-mcp-server.md](11-mcp-server.md)。

**目标**：让宿主应用通过 SnapAgent 暴露自身 `@Tool` 业务能力为 MCP Server，外部 AI Agent（Claude Code / Cursor / Windsurf）可直接发现和调用。

### Phase 4.1 (MVP)

| 项 | 说明 |
|----|------|
| `@SnapAgentTools` 注解 + 自动扫描 | 宿主 Service 标注即注册到 ToolCallbackRegistry（11 §4） |
| `McpServerController` + `McpServerHandler` | MCP 协议端点：`initialize` + `tools/list` + `tools/call`，SSE+POST 传输（11 §5） |
| Token 认证 | 连接层校验，解析为 `McpCallerContext`（callerId / roles / 数据范围）（11 §6.2） |
| allowlist/denylist 配置 | 工具可见层过滤，防止意外暴露敏感工具（11 §6.3） |
| 审计日志 | 所有 MCP 调用记录 caller + tool + args + result 摘要 |

### Phase 4.2 (生产可用)

| 项 | 说明 |
|----|------|
| `@ToolVisibility` 注解 | 细粒度工具可见性控制（按角色过滤）（11 §6.3） |
| `ToolExecutionContext` 注入 | 参数约束层，方法内做数据范围校验（11 §6.4） |
| `@ToolResultMask` 注解 | 数据返回层字段脱敏（11 §6.5） |
| 会话管理 | max-sessions 限制 + 超时清理 |

### Phase 4.3 (企业级)

| 项 | 说明 |
|----|------|
| OAuth2 / Spring Security 集成 | 连接层企业认证 |
| 多租户数据范围隔离 | 每个会话独立工具可见性 |
| 速率限制 + 配额管理 | 按 caller 限流 |
| `notifications/tools/list_changed` | 动态工具注册/注销通知 |
| Prometheus 指标 | MCP 调用量 / 延迟 / 错误率 |

## 不在路线图（明确拒绝）

- **stdio MCP**：Web 容器不 spawn 子进程，K8s 无 Node（决策 #16）。
- **写工具**：默认只读（`SkillMode.READ_ONLY`），`READ_WRITE` 模式仅用于 auto-fix 工作流（核心约束）。
- **语义级 SQL 安全分析**：不可靠，靠 DB 用户授权（诚实声明）。
- **session 级 model 持久**：per-run 覆盖，不存用户偏好到服务端（决策 #7）。

## 验证里程碑

| 阶段 | 验证 |
|------|------|
| MVP | 验证项 #1-#5（见 README §8） |
| Phase 2 | MCP server 桥接 e2e；cancel 能中断进行中的 LLM 流式 |
| Phase 3 | 3.x 宿主集成 e2e；`OpenAiLlmClient`（extends `AbstractStreamingLlmClient`）工具协议映射正确；热重载无竞态 |
| Phase 4 | 验证项 #6 宿主 MCP Server 端到端（工具发现/调用/认证/过滤/零影响/双向共存）；验证项 #7 权限链路（allowlist/@ToolVisibility/数据范围/字段脱敏） |
