# 嵌入式 SnapAgent 库 — 设计文档

> 状态：设计阶段（交付物 = 设计文档，不含代码实现）
> 版本：1.0-draft
> 日期：2026-06-29

## 1. 这是什么

一个**完全独立的第三方 Spring Boot starter 库**，让任何 Spring Boot 应用「加 pom + 配 yml」即可获得一个内嵌的 LLM 诊断 Agent：像 Claude Code 一样加载并执行任意标准格式 skill，由 LLM 按流程自动执行**只读** SQL / Redis 查询，输出诊断报告，并通过 SSE 实时推送 agent 的思考与工具调用过程。

源动机：本仓库 `docs/skills/` 下的诊断类 skill（`allocation-plan-diagnose`、`sep-wh-replenish-diagnose`、`replenishment-strategy-diagnose`）是 LLM 原生 markdown 文档，描述「收集信息 → 选查询方式 → 逐层 SQL 排查 → 出报告」的完整流程，内嵌 `{skuCode}` 占位 SQL 与 `mcp__mysql-sit__query` 工具引用。目前只能被 Claude Code CLI 消费，运营人员在线排查无法直接复用。本方案把这种能力从 CLI 搬到 Web。

## 2. 目标 / 非目标

### 目标
- **完全独立**：库不依赖宿主业务 jar，仅依赖标准 Spring Boot bean。集成 = 加 pom + 配 yml，默认关闭，对宿主零影响。
- **执行任意标准格式 skill**：不局限本项目 3 个；加载用户编写的、frontmatter 合规的 skill。
- **严格只读**：JDBC 仅 `SELECT/SHOW/DESCRIBE/EXPLAIN`；Redis 仅 `get/keys/exists`。无写工具。
- **权限在 yml，兼容 Spring Security 与 Shiro**：默认复用宿主已分配权限；Phase 1 两种框架都支持。
- **模型页面临时修改 + 本地缓存**：运维页面切模型，浏览器 localStorage 缓存，覆盖 yml；服务端强制 `allowed-models` 白名单。
- **SSE 流式推送**：agent 思考 / 工具调用 / 最终报告实时推到运维 SPA。

### 非目标
- 不做认证本身（只读已认证 principal，鉴权委托宿主）。
- 不做 skill 运行时上传（skill 来源 = 服务端目录，admin 控制）。
- Phase 1 不接 MCP（Phase 2 才接，且仅 SSE/HTTP transport）。
- Phase 1 不支持 Spring Boot 3.x（javax/jakarta 二进制不兼容，3.x 单独 starter 后续）。
- 不做语义级 SQL 安全分析（靠独立只读 DB 用户授权限制爆炸半径，文档显式承认局限）。
- 不写任何代码、不改 pom、不改现有源码 —— 本交付物仅为设计文档。

## 3. 术语

| 术语 | 含义 |
|------|------|
| **宿主** | 引入本 starter 的 Spring Boot 应用（如 your-app） |
| **skill** | 一份标准格式 markdown 文档，含 frontmatter + 诊断流程正文 + 占位 SQL |
| **frontmatter** | skill 顶部的 YAML 块，声明 `name` / `description` / `tools` / `inputs` |
| **inputs** | skill 声明的入参契约（如 `skuCode` / `warehouseCode` / `env`），用于渲染表单 |
| **tool_use** | LLM 输出的「调用某工具」结构化指令（Anthropic Messages 协议） |
| **ToolProvider** | 工具后端 SPI，实现某工具名的实际执行（如 JDBC 查询） |
| **transcript** | 一次 run 的完整过程记录（思考 + 工具调用 + 参数 + 结果 + 审计） |
| **principal** | 当前已认证用户标识，由宿主安全框架提供 |
| **只读 DSN** | 独立的只读 DB 账号 DataSource，本库所有 SQL 走它，不走宿主业务 DataSource |

## 4. 架构总览

```
[运维 SPA] ──SSE/HTTP──> SnapAgentController(autoconfig, /snap-agent/**)
                            │
  ┌─────────────────────────┼──────────────────────────┐
  ▼                         ▼                          ▼
SkillRegistry            AgentExecutor             ToolDispatcher
(启动扫 *.md,            (LLM流式循环:             (分发 tool_use)
 手动刷新)                system=只读前缀+
  │                       skill正文+工具清单;       ├─► JdbcQueryToolProvider(只读DSN, SQL guard)
  │                       tool_use→dispatch→        ├─► RedisReadToolProvider(只读)
  │                       回填→直到end_turn)        └─► McpToolProvider(Phase2, SSE)
  │                         │
  ▼                         ▼
SkillMeta/frontmatter    LlmClient(OkHttp流式,
  + tools 契约校验        Anthropic Messages,
  + inputs 表单           model per-run覆盖)
  │                         │
  └─▶ unavailable 灰显     ▼
                       TaskStore ── AgentTask(status+transcript+审计)
                            │
                  SecurityGateway ─► SpringSecurityAdapter / ShiroAdapter
                            │
                  PrincipalResolver ─► (默认/自定义)
```

详见 [01-architecture.md](01-architecture.md)。

## 5. 模块

| 模块 | 包名 | 职责 | 阶段 |
|------|------|------|------|
| `snap-agent-core` | `cn.watsontech.snapagent.core` | skill 解析 / agent 循环 / LLM 客户端 / tool SPI，**无 servlet 依赖** | Phase 1 |
| `snap-agent-spring-boot-2x-starter` | `cn.watsontech.snapagent.boot2x` | `javax.servlet` Filter + AutoConfig + `spring.factories` + 静态 UI 资源 | Phase 1 |
| `snap-agent-spring-boot-3x-starter` | `cn.watsontech.snapagent.boot3x` | `jakarta.servlet` 版本 | Phase 3 |

理由：`javax.servlet` 与 `jakarta.servlet` 二进制不兼容，单 artifact 不能同时服务 2.x 与 3.x 宿主。core 模块保持 servlet 无关，两个 starter 各自做容器适配。

## 6. 5 步快速集成（摘要）

完整版见 [09-integration-guide.md](09-integration-guide.md)。

1. **加 pom**：引入 `snap-agent-spring-boot-2x-starter`。
2. **配 yml**：`snap-agent.enabled=true` + LLM api-key + 独立只读 DataSource bean。
3. **建只读 DB 用户**：仅授 SELECT，限制 schema/表。
4. **放行 `/snap-agent/**`**：在宿主安全配置里允许已认证用户访问。
5. **（可选）PrincipalResolver**：若默认实现取不到 userId，宿主写 ~5 行自定义实现。

## 7. 文档目录

| 文档 | 内容 |
|------|------|
| [01-architecture.md](01-architecture.md) | 模块拆分、组件、依赖、AutoConfig 默认关闭零影响证明 |
| [02-skill-loading.md](02-skill-loading.md) | 标准 skill 格式、frontmatter 解析、`tools`/`inputs` 契约、缓存与刷新、unavailable 标记 |
| [03-agent-engine.md](03-agent-engine.md) | LLM 流式循环、system prompt、tool_use 分发、停止条件、TaskStore、限流、线程池 |
| [04-tools-and-mcp.md](04-tools-and-mcp.md) | ToolProvider SPI、JDBC（只读 DSN + SQL guard + LIMIT + 审计）、Redis（KEYS* 拒绝）、MCP Phase2 |
| [05-llm-client.md](05-llm-client.md) | Anthropic Messages 流式客户端、per-run model 覆盖、服务端白名单、localStorage UX、OpenAI 适配器(Phase3) |
| [06-api-and-ui.md](06-api-and-ui.md) | REST 与 SSE 接口、单页 SPA、localStorage 缓存、实时渲染 |
| [07-config-security.md](07-config-security.md) | 完整 `snap-agent.*` 配置树、SecurityGateway 双 Adapter、PrincipalResolver SPI、Filter 可配序、只读 DSN、审计、限流、租户绕过风险 |
| [08-roadmap.md](08-roadmap.md) | MVP → Phase2（MCP SSE）→ Phase3（3.x / OpenAI / 热重载 / 报告渲染） |
| [09-integration-guide.md](09-integration-guide.md) | 宿主集成 5 步详述 + 故障排查 |

## 8. 验证方式

设计文档完成后，按以下 5 项自检（详见各文档末尾的「验证」小节与 [08-roadmap.md](08-roadmap.md)）：

1. **文档自检**：交叉引用一致；配置树每个字段在组件里有落点；架构图与组件描述吻合。
2. **可行性走查**：以 `sep-wh-replenish-diagnose` 为样本，模拟 frontmatter 解析 → tools 契约校验 → system prompt → LLM 流式 → JdbcQueryToolProvider 执行 Layer 0 SQL → SQL guard 拒一条 UPDATE → SSE 推 transcript → 出报告。
3. **只读强制证明**：SQL guard 正则 + 拒绝用例 + 独立只读 DB 用户授权示例。
4. **零影响证明**：`enabled=false` 时无 bean 装配、无 Filter 注册、无线程池。
5. **双框架鉴权走查**：Spring Security 与 Shiro 两条路径分别模拟 principal 解析 + hasPermission。

## 9. 风险提示（诚实声明）

- **Prompt injection 不可完全防**：只读工具 + 独立只读 DB 用户限制爆炸半径，审计是检测而非预防。
- **SQL guard 可被子查询绕过**：靠 DB 用户授权限制，而非语义分析。
- **租户绕过**：raw JDBC 绕过 MyBatis Plus 租户拦截器；靠只读 DSN + skill 自带 `tenant_id` 占位 + 审计兜底。
- **SSE 代理兼容**：反向代理需支持 SSE（无缓冲）。
- **依赖冲突**：OkHttp / snakeyaml 版本交由 Spring Boot BOM 管控。
- **线程池饥饿**：agent 长任务用专用有界线程池，不用 servlet 请求线程。

详见各文档「风险」小节。
