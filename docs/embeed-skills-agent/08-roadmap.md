# 08 — 路线图

## MVP（Phase 1）

**范围**：让一个 Spring Boot 2.x 宿主引入 starter 即可加载并执行标准 skill，只读 SQL/Redis + SSE 推送。

| 交付项 | 说明 | 文档 |
|--------|------|------|
| `snap-agent-core` | skill 解析 / agent 循环 / LlmClient SPI / ToolProvider SPI，servlet 无关 | 01 §1 |
| `snap-agent-spring-boot-2x-starter` | `javax.servlet` Filter + AutoConfig + `spring.factories` + 静态 UI | 01 §1 |
| SkillRegistry + 标准 frontmatter | `name`/`description`/`tools`/`inputs`，启动缓存 + 手动刷新 | 02 |
| AgentExecutor 流式循环 | LLM 流式 + tool_use 分发 + max-turns + TaskStore | 03 |
| JdbcQueryToolProvider | 独立只读 DSN + SQL guard + LIMIT + 审计 | 04 §2 |
| RedisReadToolProvider | get/exists，`KEYS *` 拒绝 | 04 §3 |
| AnthropicLlmClient | OkHttp 流式 + per-run model + 服务端白名单 | 05 |
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

## Phase 2

| 项 | 说明 |
|----|------|
| MCP SSE/HTTP 接入 | `mcp.servers` 配置，SSE transport only，远端工具以 `mcp__{server}__{tool}` 注册（决策 #16） |
| Run 取消 | `POST /runs/{id}/cancel`，配合 OkHttp `Call.cancel()` 中断 LLM 流式 |
| Redis `redis_scan`（可选） | 受限 SCAN 替代 KEYS，仍强制前缀 pattern + max-key-count |
| 报告导出 | `GET /runs/{id}/report?format=md` 下载诊断报告 markdown |
| 审计持久化 | ring-buffer → 可选 DB 表（`skills_agent_audit`）便于长期追溯 |

## Phase 3

| 项 | 说明 |
|----|------|
| `snap-agent-spring-boot-3x-starter` | `jakarta.servlet` 版本，controller/filter 重写 |
| OpenAI 适配器 | `OpenAiLlmClient`，`llm.provider: openai` |
| skill 热重载 | WatchService 监听 `upload-skills-dir`，文件变更自动 refresh（仍线程安全） |
| 报告富文本渲染 | markdown 报告 + 表格 + 折叠 SQL + 复制按钮（前端增强） |
| OkHttp shade/relocate | `llm.shade-okhttp=true`，解决与宿主 OkHttp 大版本冲突 |
| 多 DSN（多环境） | `jdbc.datasources: {sit: ..., uat: ...}`，skill `inputs.env` 选 DSN |
| 任务历史列表 | `GET /runs?userId=&skillId=&page=`，跨 session 查历史诊断 |

## 不在路线图（明确拒绝）

- **stdio MCP**：Web 容器不 spawn 子进程，K8s 无 Node（决策 #16）。
- **skill 运行时上传**：安全考虑，skill 来源仅服务端目录（决策 #13）。
- **写工具**：永远只读（核心约束）。
- **语义级 SQL 安全分析**：不可靠，靠 DB 用户授权（诚实声明）。
- **session 级 model 持久**：per-run 覆盖，不存用户偏好到服务端（决策 #7）。

## 验证里程碑

| 阶段 | 验证 |
|------|------|
| MVP | 验证项 #1-#5（见 README §8） |
| Phase 2 | MCP server 桥接 e2e；cancel 能中断进行中的 LLM 流式 |
| Phase 3 | 3.x 宿主集成 e2e；OpenAI 适配器工具协议映射正确；热重载无竞态 |
