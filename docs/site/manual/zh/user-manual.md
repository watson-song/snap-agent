# SnapAgent 用户手册

> 版本：v1.0 | 更新日期：2026-07-17

本手册面向两类用户：

- **集成者（Integrator）** — 把 SnapAgent 嵌入自家 Spring Boot 2.x 应用的开发人员
- **操作者/最终用户（Operator / End User）** — 通过浏览器 Web UI 或 REST API 运行诊断 Skill 的工程师、SRE、值班人员

技术装配细节（Maven 坐标、自动装配顺序、SecurityGateway 自定义、跨 Pod 路由等）请查阅 [宿主集成指南](../integration/zh/host-integration-guide.md)；架构总览请查阅 [系统架构总览](../architecture/zh/system-architecture.md)。本手册只关注"如何使用"。

---

## 1. 快速开始

### 1.1 集成者路径（让宿主应用获得 SnapAgent 能力）

最小三步即可让一个 Spring Boot 2.x 应用具备 LLM 诊断能力：

1. **添加 Maven 依赖**

   ```xml
   <dependency>
       <groupId>cn.watsontech.snapagent</groupId>
       <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
       <version>1.0-SNAPSHOT</version>
   </dependency>
   <!-- OkHttp（Starter 标记为 optional，宿主必须显式引入） -->
   <dependency>
       <groupId>com.squareup.okhttp3</groupId>
       <artifactId>okhttp</artifactId>
   </dependency>
   ```

2. **在 `application.yml` 中开启并配置 LLM**

   ```yaml
   snap-agent:
     enabled: true                              # 总开关，默认 false
     base-path: /snap-agent                    # Controller 路径前缀，默认 /snap-agent
     llm:
       base-url: https://api.anthropic.com
       auth-token: ${ANTHROPIC_AUTH_TOKEN}      # 推荐用环境变量注入
       model: claude-sonnet-4-6
     security:
       required-permission: snap-agent:access   # 设为空字符串允许匿名
     jdbc:
       enabled: true
       datasources:
         sit:
           url: jdbc:mysql://sit-db:3306/mydb
           username: readonly
           password: ${SIT_DB_PASSWORD}
           driver-class-name: com.mysql.cj.jdbc.Driver
       default-env: sit
   ```

3. **启动宿主应用**

   Spring Boot 通过 `META-INF/spring.factories` 自动装配 `SnapAgentAutoConfiguration`。激活后，`{base-path}/**` 下挂载完整的 Agent Web UI 与 REST API，宿主的 `DataSource`、`RedisTemplate`、Spring Security 认证信息被自动复用。

集成完整清单（依赖、可选依赖、自动装配顺序、SecurityGateway 自定义、basePath 冲突检查、冒烟测试）详见 [宿主集成指南 §2–§4](../integration/zh/host-integration-guide.md)。

### 1.2 操作者路径（用浏览器使用 Web UI）

1. 在浏览器打开 `http://<host>:<port>/snap-agent/`（`base-path` 可被宿主覆盖）。
2. 浏览器复用宿主应用的安全上下文（Basic Auth / Session Cookie / 自定义 Token Header），未登录时页面会显示"请先登录系统后再访问 SnapAgent"。
3. 登录后，左侧栏列出所有可用 Skill（内置 + 宿主自研），每张卡片显示 Skill 名称、描述与图标。
4. 点击任意 Skill → 中间区域出现输入表单 → 填写参数 → 点 "➤ 运行" → 流式输出开始。

### 1.3 最终用户路径（用 REST API 集成）

REST API 全部挂在 `{base-path}` 下，默认 `/snap-agent`。最简调用链：

```bash
# 1) 列出所有 Skill
curl -u user:pass http://localhost:8080/snap-agent/skills

# 2) 发起一次诊断（返回 taskId + streamUrl）
curl -u user:pass -X POST http://localhost:8080/snap-agent/runs \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"health-check","inputs":{},"model":"claude-sonnet-4-6"}'

# 3) 订阅 SSE 流（见 §10）
curl -N -u user:pass http://localhost:8080/snap-agent/runs/<taskId>/stream
```

完整端点表与请求/响应示例见 [§10 REST API 参考](#10-rest-api-参考)。

---

## 2. Web UI 使用

SnapAgent 自带一个嵌入式单页应用（`{base-path}/`），布局如下：

```
┌──────────────────────────────────────────────────────────────────────┐
│ ⚡ SnapAgent         [模型 ▼]  👤 user                  │
├──────────┬───────────────────────────────────────────────────────────┤
│ 📄 文件   │  当前 Skill: <name>                                          │
│ 📁 文件夹 │  🌍 当前环境: sit    📄 应用日志: /var/log/app.log            │
│ 🔄 刷新   │  ─────────────────────────────────────────────────────────  │
│ 🔍 显隐   │                                                             │
│          │  ┌─ 流式思考 ─────────────────────────────────────────────┐ │
│ ▾ 宿主   │  │ Agentic: 我先验证数据库连通性 ...                        │ │
│   Skills │  └────────────────────────────────────────────────────────┘ │
│  • ...   │  ┌─ 工具调用: mysql_query ────────────────────────────────┐ │
│ ▸ 内置   │  │ args: {"sql":"SELECT 1 AS ok"}                          │ │
│   Skills │  │ result: ok=1 (1 row, 12ms)                              │ │
│          │  └────────────────────────────────────────────────────────┘ │
│ [🔧 📋 💰 │  ┌─ 最终回复 ─────────────────────────────────────────────┐ │
│  🐛 🛡️ 🔔 │  │ ✅ 系统健康：数据库连通、共 47 张表...                  │ │
│  📚]     │  └────────────────────────────────────────────────────────┘ │
│          │                                                             │
│ ‹ 收起   │  📜  [输入消息或参数...]                              [➤]  │
└──────────┴───────────────────────────────────────────────────────────┘
```

### 2.1 主要交互元素

| 元素 | 位置 | 作用 |
|------|------|------|
| 📄 文件 / 📁 文件夹 | 侧栏顶部 | 上传单个 `.md` Skill 文件或整个目录到 `snap-agent.upload-skills-dir` |
| 🔄 刷新 | 侧栏顶部 | 触发 `POST /skills/refresh` 重新扫描上传目录 |
| 🔍 显隐 | 侧栏顶部 | 显示/隐藏 unavailable Skills（依赖未装配的 Tool） |
| 🌍 当前环境 | 顶栏 | 显示宿主 Spring `activeProfiles`，所有运行自动注入 `_app_profile` |
| 📜 历史 | 输入栏左侧 | 打开当前 Skill 的历史会话 modal（见 §4） |
| ➤ 发送 | 输入栏右侧 | 提交输入并启动 Skill（见 §3） |
| ‹ 收起 | 侧栏底部 | 折叠侧栏为首字母图标栏，hover 显示完整 Skill 名 |

### 2.2 多 Skill 并行流

Web UI 为每个 Skill 维护独立的 `skillChatState`。**切换侧栏 Skill 不会取消前一个 Skill 的流**——后台流继续接收事件并更新 transcript；切回时从内存直接重建 DOM，包含思考、工具调用、时间戳。只有当同一 Skill 再次点 "➤ 运行" 时，前一个流才会被 `cancelSkillStream()` 保存部分结果并取消。

侧栏中运行中的 Skill 会显示 "运行中" badge，方便定位后台未完成的流。

### 2.3 功能导航栏（侧栏底部 7 个按钮）

| 按钮 | 标题 | 调用端点 | 内容 |
|------|------|---------|------|
| 🔧 | 工具 & 插件 | `GET /tools` + `GET /tools/plugins` | 已注册工具名表 + 已声明 `ToolPlugin` 元数据表 |
| 📋 | 工作流 | `GET /workflows` + `GET /workflows/{name}` | 工作流列表 + 每步详情；行内 "运行" 按钮触发 `POST /workflows/{name}/run` |
| 💰 | 成本看板 | `GET /cost/summary?from=&to=` | 总成本、总 Tokens、总请求数、预算利用率（见 §9） |
| 🐛 | 问题闭环 | `GET /runs` | 最近运行列表 + 行内 "建议方案" 按钮（见 §8） |
| 🛡️ | 巡检任务 | `GET /patrol/tasks` + `GET /patrol/reports` | 巡检任务与报告列表（主动监控子系统） |
| 🔔 | 告警 | `GET /alerts` | 活跃告警列表 + "解决" 按钮（主动监控子系统） |
| 📚 | 知识库 | `GET /knowledge/status` + `GET /knowledge/search?q=` | 知识片段统计 + 检索测试（见 §5） |

### 2.4 前端版本

`index.html` 通过 `app.js?v=25` 的查询字符串标记前端版本（v25 = 知识检索使用配置的 minScore + 显示相关度百分比）。如需确认线上版本，浏览器查看页面源代码搜索 `app.js?v=`。

---

## 3. 运行 Skill

### 3.1 Skill 是什么

Skill 是一份 Markdown 文件，YAML frontmatter 声明元数据（`name` / `description` / `tools` / `inputs` / `shortcuts` / `required-permission`），正文是给 LLM 的阶段化诊断指令。Skill 不是代码，而是诊断剧本——LLM 按剧本调用工具，逐步产出诊断报告。

### 3.2 输入表单

Skill 的 `inputs` 字段在 Web UI 自动渲染为表单：

```yaml
inputs:
  - key: service
    label: 服务名
    required: true
    type: text            # text / number / boolean / enum / date
    options:              # 仅 enum
      - sit
      - uat
      - prod
  - key: time_window
    label: 时间窗口
    type: text
    default: 1h
```

| `type` | HTML 控件 | 校验 |
|--------|----------|------|
| `text` | `<input type="text">` | 必填非空（若 `required: true`） |
| `number` | `<input type="number">` | `Double.parseDouble()` |
| `boolean` | `<input type="text">` | 必须是 `true` / `false` |
| `enum` | `<select>` | 值必须在 `options` 列表内 |
| `date` | `<input type="date">` | `LocalDate.parse()` |

输入栏还会自动注入环境字段：当 `key` 名匹配 `profile`/`profiles`/`environment`/`env` 之一时，自动用宿主当前 `activeProfiles` 填充。

### 3.3 流式输出生命周期

点击 "➤ 运行" 后：

1. 前端先在 transcript 追加 `user` 消息并 `POST /conversations` 立即落盘（确保刷新不丢）。
2. `POST /runs` 返回 `{taskId, status, streamUrl}`。
3. 前端用 `EventSource` 订阅 `streamUrl`（SSE 流），按事件类型渲染：

| SSE event 名 | 含义 |
|--------------|------|
| `thought` | LLM 思考流式增量（增量 token，前缀 `+`） |
| `tool_call` | 工具被调用，含 `tool` + `args` |
| `tool_result` | 工具返回，含 `content`（截断 500 字符）+ `error` |
| `task_error` | 任务级错误（不使用 `error` 以免触发 `EventSource` 内置错误处理器） |
| `done` | 终端事件，`data.status` 为最终状态，可选 `data.report` |
| `comment` | 心跳（每 15s） |

4. 用户可随时点 "取消"（或前端代码调 `POST /runs/{id}/cancel`），后端将 `task.status` 置为 `CANCELLED`，调用 `LlmClient.cancel(taskId)` 中断在途 HTTP 调用，并发送 `done` SSE 事件。
5. 任务结束（`SUCCEEDED` / `FAILED` / `TIMEOUT` / `CANCELLED`）后，最终回复自动保存为 `assistant` 消息到当前会话。

### 3.4 多环境数据源

如果宿主配置了 `snap-agent.jdbc.datasources`（Map 形式），`mysql_query` 工具 schema 新增 `env` 参数，空值代表默认环境。Skill frontmatter 中若有 `env` 输入，用户可在表单里选目标环境（如 `prod`），LLM 会把该值透传到 `mysql_query` 的 `env` 参数。详见 [宿主集成指南 §3.3](../integration/zh/host-integration-guide.md)。

### 3.5 Skill 运行页 ASCII 布局

```
┌─ 当前 Skill: slow-query-analysis ──────────────────────────────────┐
│ 描述: 慢查询排查：查慢日志 → 分析执行计划 → 索引建议                  │
│ ⚠️ 必须输入: service                                               │
│ 🌍 当前环境: sit                                                   │
├───────────────────────────────────────────────────────────────────┤
│ [service: order-service] [time_window: 1h]                        │
│ [输入消息或参数...]                                         [➤]   │
├─ 14:32:01 user ────────────────────────────────────────────────────┤
│ order-service 最近有慢查询吗？                                       │
├─ 14:32:02 thought ─────────────────────────────────────────────────┤
│ 我先查 mysql_slow_queries 指标...                                  │
├─ 14:32:03 tool_call: metrics_query ───────────────────────────────┤
│ args: {"query":"rate(mysql_slow_queries_total[5m])"}              │
├─ 14:32:04 tool_result ─────────────────────────────────────────────┤
│ 0.05 q/s (1 row, 84ms)                                             │
├─ 14:32:10 response ─────────────────────────────────────────────────┤
│ 过去 5 分钟慢查询 0.05 q/s，发现 3 条 SQL 全表扫描...建议加索引      │
└───────────────────────────────────────────────────────────────────┘
```

---

## 4. 会话历史

### 4.1 自动保存

每次发起 `POST /runs` 后，前端立即把 `user` 消息 `POST /conversations` 落盘；流结束时再追加 `assistant` 消息。会话标题由首条 `user` 消息截断生成，ID 由服务端分配。会话 JSON 文件存于 `{upload-skills-dir}/conversations/{userId}/{conversationId}.json`。

### 4.2 列表与操作

点输入栏左侧 "📜" 按钮打开 modal：

```
┌─ 📜 历史会话 — slow-query-analysis ──────────────┐
│                                                   │
│  order-service 最近有慢查询吗？                     │
│  2026-07-17 14:32  · 5 条消息                      │
│  [📂 加载]  [⬇ 下载]  [🗑 删除]                     │
│                                                   │
│  为什么订单状态不一致？                              │
│  2026-07-16 09:15  · 8 条消息                      │
│  [📂 加载]  [⬇ 下载]  [🗑 删除]                     │
└───────────────────────────────────────────────────┘
```

- **加载**：从后端 `GET /conversations/{id}` 拉回消息，重建 transcript（含 user/assistant 消息）。**不会重新执行**，思考与工具调用不恢复——若需要重跑，请在加载后再次点 "➤"。
- **下载**：`GET /conversations/{id}/download` 返回 Markdown 格式（YAML 元数据 + 按 role 分节），文件名为 `{conversationId}.md`。
- **删除**：`DELETE /conversations/{id}`，归属校验通过后删除 JSON 文件。

### 4.3 Skill 切换的会话保存

切换侧栏 Skill 时，当前 Skill 的内存 transcript 被保留（不写回后端），新 Skill 加载自己最近一次会话。若用户在切换前编辑了输入未点 "➤"，未落盘的内容会丢失——已发送的消息总是已持久化。

---

## 5. 业务知识库

知识库（v0.7）让 LLM 在诊断时自动获得业务上下文，避免"凭空猜表名"或"不知道补货策略依赖哪张表"。架构与算法详见 [知识搜索算法](../search/zh/knowledge-search.md)。

### 5.1 检索测试（Web UI）

点侧栏 "📚" 按钮：

```
┌─ 知识库 ──────────────────────────────────────────────────┐
│  5      3      0.1                                        │
│ 知识片段  注入上限  最低分数                                │
│                                                           │
│ 数据源 (1)                                                │
│ ┌──────────┬──────────────────────────────────────────┐   │
│ │ markdown │ classpath:/docs/knowledge/                 │   │
│ └──────────┴──────────────────────────────────────────┘   │
│                                                           │
│ 检索测试                                                  │
│ [输入关键词搜索知识片段...]                      [搜索]   │
│                                                           │
│ ┌─ 数据库诊断 ────────────── 相关度 100% ─────────────┐   │
│ │ 来源: business-overview.md:section-2                 │   │
│ │ 数据库诊断基于独立只读数据源连接，仅支持 SELECT...    │   │
│ └───────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

每条结果右侧的 **相关度 N%** badge 来自 `SearchResult.score * 100`（向下取整）。低于 `min-score` 的片段不返回。

### 5.2 自动注入（KnowledgeInjector）

运行 Skill 时，`KnowledgeInjector`（实现 `SystemPromptExtender`）会从 `task.inputs` 拼接查询串 → `knowledgeBase.search(query, maxFragments, minScore)` → 把匹配片段格式化后注入 LLM 的 system prompt。注入上限由 `snap-agent.knowledge.max-fragments`（默认 3）控制，避免 token 爆炸。用户无需手动操作。

### 5.3 REST API

| 方法 | 端点 | 说明 |
|------|------|------|
| `GET` | `/snap-agent/knowledge/status` | 知识库状态：片段数、注入上限、minScore、数据源列表 |
| `GET` | `/snap-agent/knowledge/search?q={query}` | 检索测试（topK = max-fragments × 3，最少 10） |

示例：

```bash
curl -u user:pass 'http://localhost:8080/snap-agent/knowledge/search?q=%E6%95%B0%E6%8D%AE%E5%BA%93'
```

```json
{
  "query": "数据库",
  "totalFragments": 5,
  "matched": 2,
  "fragments": [
    {
      "title": "数据库诊断",
      "content": "数据库诊断基于独立只读数据源连接...",
      "source": "business-overview.md:section-2",
      "metadata": { "category": "SnapAgent 业务知识示例" },
      "score": 1.0
    }
  ]
}
```

### 5.4 添加知识

把 `.md` 文件放到 `snap-agent.knowledge.sources[].dir`（默认 `classpath:/docs/knowledge/`）。每个 `##` 标题下的内容成为一个 `KnowledgeFragment`，H1 标题作为 `metadata.category`；无 `##` 的文件整文件作为一个片段。修改后调 `KnowledgeBase.reload()` 或重启生效。

```yaml
snap-agent:
  knowledge:
    enabled: true
    max-fragments: 3
    min-score: 0.1
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
      - type: markdown
        dir: /opt/myapp/knowledge/
```

---

## 6. 工具与 Skill 全景

### 6.1 内置 Skill

SnapAgent 打包内置 13 个 Skill（位于 `docs/skills/`），按功能域分组：

| Skill | 版本 | 一句话描述 | 依赖工具 |
|-------|------|------------|----------|
| `health-check` | v0.1 | 基础健康检查：数据库连通性 + 表计数 | `mysql_query` |
| `database-query` | v0.1 | 自然语言转只读 SQL 诊断查询 | `mysql_query` |
| `redis-query` | v0.1 | Redis Key 检查（`exists` / `get`） | `redis_get` |
| `log-analysis` | v0.1 | 应用日志文件分析（异常/用户操作） | `log_read` |
| `code-analysis` | v0.3 | 代码结构 + 调用链分析 | `project_structure`, `code_read`, `git_log` |
| `ops-health-check` | v0.4 | 全面运营健康检查（指标快照 → 异常 → 根因） | `metrics_query`, `log_search`, `mysql_query` |
| `slow-query-analysis` | v0.4 | 慢查询排查（慢日志 → EXPLAIN → 索引建议） | `mysql_query`, `log_search`, `metrics_query` |
| `error-spike-investigation` | v0.4 | 错误率突增根因（指标 → 日志 → 变更 → 链路 → 代码） | `metrics_query`, `log_search`, `trace_search`, `code_read`, `git_log` |
| `config-diff` | v0.4 | 环境配置对比（差异 + 风险评估） | `config_read`, `metrics_query` |
| `trend-prediction` | v0.5 | 7 天指标趋势预测与容量预警 | `metrics_query` |
| `health-patrol` | v0.5 | 巡检用综合健康检查（CPU/内存/错误率/延迟） | `metrics_query` |
| `solution-suggest` | v0.9 | 基于根因生成 2-3 个候选方案 + 推荐度 | 无（纯 LLM 推理） |
| `verify-fix` | v0.9 | 修复后复查：重跑诊断确认问题已解决 | 依赖原诊断 skill 声明的工具 |

> `solution-suggest` 与 `verify-fix` 通常不直接被用户运行，而是由问题闭环服务（§8）自动调用。

### 6.2 内置工具

按功能域分组的 `ToolProvider` 实现（详见 [工具插件架构 §3](../plugins/zh/tool-plugin-architecture.md)）：

| 工具名 | 提供者 | 启用配置 |
|--------|--------|----------|
| `mysql_query` | `JdbcQueryToolProvider` | `snap-agent.jdbc.*` |
| `redis_get` | `RedisReadToolProvider` | `snap-agent.redis.*` |
| `code_read` | `CodeReaderToolProvider` | `snap-agent.code.enabled=true` + `project-root` |
| `project_structure` | `ProjectStructureToolProvider` | 同上 |
| `git_log` | `GitLogToolProvider` | 同上 |
| `log_read` | `LogReadToolProvider` | `snap-agent.logs.allowed-paths` |
| `metrics_query` | `MetricsToolProvider` | `snap-agent.metrics.enabled=true` + `base-url` |
| `log_search` | `LogSearchToolProvider` | `snap-agent.log-search.enabled=true` + `base-url` |
| `trace_search` | `TraceSearchToolProvider` | `snap-agent.trace.enabled=true` + `base-url` |
| `config_read` | `ConfigReadToolProvider` | `snap-agent.config-read.enabled=true` |
| `code_graph_tools` | `CodeGraphToolProvider` | `snap-agent.code-graph.enabled=true` |

`code_graph_tools` 是单一工具含 4 个子工具：`call_chain` / `reverse_chain` / `impact_analysis` / `find`。

### 6.3 内置工作流

| 工作流 | 步骤 | 描述 |
|--------|------|------|
| `full-diagnose` | 4 步 | 健康检查 → 错误排查（条件） → 代码分析（条件） → 方案建议（条件） |

详见 §7 与 [工作流引擎架构](../workflow/zh/workflow-engine-architecture.md)。

### 6.4 自定义 Skill

两种方式上传：

1. **Web UI**：点侧栏 "📄 文件"（上传单个 `.md`）或 "📁 文件夹"（上传整个目录，含 `SKILL.md` + 附属资源）。文件落入 `snap-agent.upload-skills-dir`（默认 `/tmp/snap-agent-skills`）。
2. **REST API**：`POST /snap-agent/skills/upload`（multipart 单文件）或 `POST /snap-agent/skills/upload-folder`（multipart 多文件 + 相对路径）。

自定义 Skill 按 name 覆盖同名内置 Skill；删除自定义后内置版本自动恢复。详见 [宿主集成指南 §3.13](../integration/zh/host-integration-guide.md)。

---

## 7. 工作流执行

### 7.1 查看与运行

侧栏 "📋" 按钮打开工作流 modal，列出所有已加载的 `.yml` 工作流（来自 `snap-agent.workflows.dir`）。每行有 "运行" 按钮，点击后前端调 `POST /workflows/{name}/run`：

```bash
curl -u user:pass -X POST \
  http://localhost:8080/snap-agent/workflows/full-diagnose/run \
  -H 'Content-Type: application/json' \
  -d '{"service":"order-service"}'
```

### 7.2 执行语义

`SimpleWorkflowEngine` 顺序执行 steps：

- 无 `condition` 的 step 总是执行；有 `condition` 的 step 先解析表达式（`${step.result != null}` / `.contains('error')` / `.size > 0` / `${trigger.xxx}`）
- `onFailure: STOP` → 失败立即终止整个工作流
- `onFailure: SKIP` → 失败跳过本步继续
- `onFailure: RETRY` → 立即重试本步

结果 DTO 含 `success`、`status`、`failedStep`、`errorMessage`、`stepResults`（每步的 `taskId` / `status` / `report`）和 `durationMs`。

### 7.3 内置 `full-diagnose` 示例

```yaml
name: full-diagnose
description: "全链路诊断工作流 — 健康检查 → 错误排查 → 代码分析 → 解决方案建议"
steps:
  - name: health-check
    skill: health-check
    inputs: { service: "${trigger.service}" }
    onFailure: STOP
  - name: find-root-cause
    skill: error-spike-investigation
    condition: "${health-check.result.contains('error')}"
    inputs: { timeWindow: "1h", service: "${trigger.service}" }
    onFailure: STOP
  - name: code-analysis
    skill: code-analysis
    condition: "${find-root-cause.result != null}"
    inputs: { rootCause: "${find-root-cause.result}" }
    onFailure: SKIP
  - name: solution-suggest
    skill: solution-suggest
    condition: "${code-analysis.result.size > 0}"
    inputs: { rootCause: "${find-root-cause.result}", codeAnalysis: "${code-analysis.result}" }
    onFailure: SKIP
```

引擎内部、YAML 加载、条件表达式 DSL、`StepResult` 结构详见 [工作流引擎架构](../workflow/zh/workflow-engine-architecture.md)。

---

## 8. 问题闭环使用

问题闭环（v0.9）把"诊断 → 方案建议 → 外部 Issue → 修复验证 → 经验沉淀"串成一个完整闭环。架构详见 [问题闭环架构](../issue/zh/issue-closure-architecture.md)。

### 8.1 操作流程

```
  诊断完成 (POST /runs → SSE done)
        │
        ▼
  ① 建议方案: POST /runs/{taskId}/solution
     → IssueClosureService 跑 solution-suggest skill
     → 返回 2-3 个候选方案 + 推荐度
        │
        ▼
  ② 创建外部 Issue: POST /runs/{taskId}/issue
     body: {"selected_solution": "方案1 描述"}
     → (若 IssueTracker 配置) 创建 Jira/GitHub Issue
     → 返回 issue 含 externalIssueId
        │
        ▼
  ③ 在代码库实施修复 (人工)
        │
        ▼
  ④ 验证修复: POST /issues/{issueId}/verify
     → 跑 verify-fix skill 重新检查
     → IssueClosure.verificationResult 填充
        │
        ▼
  ⑤ 关闭问题: POST /issues/{issueId}/close
     → KnowledgeSedimentationExtractor 从 IssueClosure 抽取知识片段
     → 写入 KnowledgeBase 供未来诊断复用
     → IssueClosure.status = CLOSED
```

### 8.2 REST 端点

| 方法 | 端点 | 触发动作 |
|------|------|---------|
| `POST` | `/snap-agent/runs/{taskId}/solution` | 跑 `solution-suggest` skill，返回候选方案 |
| `POST` | `/snap-agent/runs/{taskId}/issue` | 选中方案后创建外部 Issue |
| `GET` | `/snap-agent/issues/{issueId}` | 加载 Issue 详情 |
| `POST` | `/snap-agent/issues/{issueId}/verify` | 跑 `verify-fix` skill 复查 |
| `POST` | `/snap-agent/issues/{issueId}/close` | 关闭并沉淀知识 |

未启用时（`snap-agent.issue-closure.enabled=false`）所有端点返回 `503 ISSUE_CLOSURE_DISABLED`。

### 8.3 Web UI

侧栏 "🐛 问题闭环" 按钮打开 modal，列出最近运行的任务。每个已完成的任务行有 "建议方案" 按钮，点击后行内展开方案列表。后续的创建 Issue / 验证 / 关闭操作通过 REST API 或宿主自研面板完成。

---

## 9. 成本与预算

成本核算（v1.0）通过 `CostTrackingLlmClient` 装饰原始 `LlmClient`，从 SSE `message_start` / `message_delta` 的 `usage` 字段捕获 token 用量并落盘。架构详见 [系统架构总览 §6 成本核算](../architecture/zh/system-architecture.md)。

### 9.1 配置

```yaml
snap-agent:
  cost:
    enabled: true                          # 默认 false
    pricing:
      input: 3.00                          # 每 1M input tokens（CNY）
      output: 15.00                        # 每 1M output tokens
      cache-read: 0.30                     # 每 1M cache-read tokens
      currency: CNY
    budgets:
      per-user-daily: 50.00                # null = 不限
      per-skill-daily: 20.00
      global-daily: 500.00
    storage-dir: /var/lib/snap-agent/cost  # 空 = {upload-skills-dir}/cost/
    warn-threshold: 0.8                    # 预算利用率到 80% 告警
```

成本记录以 JSON 存于 `{storage-dir}/{yyyy-MM-dd}/{recordId}.json`，按天分目录。

### 9.2 预算强制

`BudgetEnforcer` 在每次 `POST /runs` 前检查：

- 用户当日累计成本是否超 `per-user-daily`
- 该 Skill 当日累计成本是否超 `per-skill-daily`
- 全局当日累计成本是否超 `global-daily`

超限时任务被拒绝（`403 BUDGET_EXCEEDED` 或等价错误），LLM 不被调用，不产生新成本。`warn-threshold` 触发时记 WARN 日志，不阻断。

### 9.3 查询接口

| 方法 | 端点 | 维度 |
|------|------|------|
| `GET` | `/snap-agent/cost/summary?from=&to=&groupBy=` | 全局（`groupBy=user`/`skill` 也返回全局汇总） |
| `GET` | `/snap-agent/cost/users/{userId}/summary?from=&to=` | 单用户 |
| `GET` | `/snap-agent/cost/skills/{skillName}/summary?from=&to=` | 单 Skill |

`from` / `to` 为 epoch 秒。示例响应：

```json
{
  "dimension": "global",
  "dimensionValue": "global",
  "from": 1721184000,
  "to": 1721788800,
  "totalCost": 12.34,
  "totalInputTokens": 2340000,
  "totalOutputTokens": 560000,
  "requestCount": 87,
  "budget": 500.00,
  "utilization": 0.0247
}
```

### 9.4 Web UI 看板

侧栏 "💰 成本看板" 按钮默认查最近 7 天 `GET /cost/summary`，展示三个统计卡（总成本 / 总 Tokens / 总请求数）+ 按维度拆分明细表（含预算利用率列）。

---

## 10. REST API 参考

所有端点挂载在 `${snap-agent.base-path:/snap-agent}` 下。除 `GET /auth-config` 公开外，其余端点要求宿主安全框架已认证用户，并通过 `SecurityGateway.hasPermission(required-permission)` 权限校验。

### 10.1 端点速查表

| # | 方法 | 路径 | 用途 |
|---|------|------|------|
| 1 | `GET` | `/auth-config` | 前端读取 token 来源（header/cookie/localStorage key） |
| 2 | `GET` | `/user-info` | 当前用户 + `activeProfiles` + 授权状态 |
| 3 | `GET` | `/skills` | 列出所有 Skill（builtin + custom） |
| 4 | `GET` | `/skills/{name}` | （`/skills` 列表内嵌）获取 Skill 定义 |
| 5 | `POST` | `/skills/refresh` | 重新扫描 upload 目录 |
| 6 | `DELETE` | `/skills/{name}` | 删除自定义 Skill（builtin 返回 403） |
| 7 | `POST` | `/skills/upload` | multipart 上传单文件 |
| 8 | `POST` | `/skills/upload-folder` | multipart 上传目录 |
| 9 | `GET` | `/tools` | 已注册工具名列表 |
| 10 | `GET` | `/tools/plugins` | 已声明 `ToolPlugin` 元数据 |
| 11 | `GET` | `/models` | LLM 允许的模型列表（`default` + `allowed`） |
| 12 | `POST` | `/runs` | 发起 Skill 运行 |
| 13 | `GET` | `/runs?status=&skillId=&page=&size=` | 分页列出当前用户任务 |
| 14 | `GET` | `/runs/{id}` | 任务详情 |
| 15 | `GET` | `/runs/{id}/transcript` | 完整 transcript（所有事件） |
| 16 | `GET` | `/runs/{id}/report` | 诊断报告纯文本 |
| 17 | `GET` | `/runs/{id}/stream?token=` | SSE 流（token 用于 EventSource 鉴权） |
| 18 | `POST` | `/runs/{id}/cancel` | 取消运行中的任务 |
| 19 | `GET` | `/audit?action=&page=&size=` | 审计日志（需配置 AuditStore） |
| 20 | `POST` | `/conversations` | 保存/更新会话 |
| 21 | `GET` | `/conversations?skillId=` | 列出当前用户会话 |
| 22 | `GET` | `/conversations/{id}` | 加载会话全量消息 |
| 23 | `GET` | `/conversations/{id}/download` | 下载会话为 Markdown |
| 24 | `DELETE` | `/conversations/{id}` | 删除会话 |
| 25 | `GET` | `/knowledge/status` | 知识库状态 |
| 26 | `GET` | `/knowledge/search?q=` | 知识检索 |
| 27 | `GET` | `/workflows` | 工作流列表 |
| 28 | `GET` | `/workflows/{name}` | 工作流详情（含 steps） |
| 29 | `POST` | `/workflows/{name}/run` | 触发工作流执行 |
| 30 | `POST` | `/runs/{taskId}/solution` | 问题闭环：建议方案 |
| 31 | `POST` | `/runs/{taskId}/issue` | 问题闭环：创建外部 Issue |
| 32 | `GET` | `/issues/{issueId}` | 问题闭环：Issue 详情 |
| 33 | `POST` | `/issues/{issueId}/verify` | 问题闭环：验证修复 |
| 34 | `POST` | `/issues/{issueId}/close` | 问题闭环：关闭并沉淀 |
| 35 | `POST` | `/runs/{id}/bugfix-suggestion` | 生成模板化修复建议（若 `TemplateBugfixSuggester` 装配） |
| 36 | `GET` | `/cost/summary?from=&to=&groupBy=` | 全局成本汇总 |
| 37 | `GET` | `/cost/users/{userId}/summary?from=&to=` | 用户维度成本 |
| 38 | `GET` | `/cost/skills/{skillName}/summary?from=&to=` | Skill 维度成本 |
| 39 | `POST` | `/patrol/tasks` | 创建巡检任务（主动监控） |
| 40 | `GET` | `/patrol/tasks` | 巡检任务列表 |
| 41 | `DELETE` | `/patrol/tasks/{id}` | 删除巡检任务 |
| 42 | `GET` | `/patrol/reports` | 巡检报告列表 |
| 43 | `GET` | `/patrol/reports/{id}` | 巡检报告详情 |
| 44 | `GET` | `/alerts` | 活跃告警列表 |
| 45 | `POST` | `/alerts/{id}/resolve` | 解决告警 |

### 10.2 端到端示例：用 curl 跑一次 health-check

```bash
# (1) 列出 Skill，确认 health-check 可用
curl -s -u alice:secret http://localhost:8080/snap-agent/skills | jq '.skills[] | select(.name=="health-check")'
# {
#   "name": "health-check",
#   "description": "Performs a basic health check of the application — ...",
#   "availability": "AVAILABLE",
#   "tools": ["mysql_query"],
#   "source": "builtin"
# }

# (2) 发起运行
curl -s -u alice:secret -X POST http://localhost:8080/snap-agent/runs \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"health-check","inputs":{},"model":"claude-sonnet-4-6"}'
# {
#   "taskId": "8f7c2e1a-3b4d-4e5f-9a6b-7c8d9e0f1a2b",
#   "status": "PENDING",
#   "streamUrl": "/snap-agent/runs/8f7c2e1a-3b4d-4e5f-9a6b-7c8d9e0f1a2b/stream"
# }

# (3) 订阅 SSE 流（-N 禁用 curl 缓冲，实时看到事件）
curl -N -u alice:secret \
  "http://localhost:8080/snap-agent/runs/8f7c2e1a-3b4d-4e5f-9a6b-7c8d9e0f1a2b/stream"
# event: thought
# data: {"text":"我先验证数据库连通性..."}
#
# event: tool_call
# data: {"tool":"mysql_query","args":{"sql":"SELECT 1 AS ok"}}
#
# event: tool_result
# data: {"content":"ok=1\n1 row (12ms)"}
#
# event: done
# data: {"status":"SUCCEEDED","report":"数据库连通 OK；共 47 张表..."}
```

### 10.3 EventSource 鉴权（SSE token 参数）

浏览器 `EventSource` 不支持自定义 header，SSE 端点 `permitAll` + 接受 `?token=base64(user:pass)` query 参数。Controller 解码后跳过 ownership check。前端启动时从 `/user-info` 拿到 `userId`，再以 `base64(userId:password)` 拼 token。

### 10.4 常见错误码

| HTTP | `error` 字段 | 含义 |
|------|-------------|------|
| 401 | `UNAUTHORIZED` | 未认证 |
| 403 | `FORBIDDEN` | 无 `required-permission` 权限 |
| 404 | `SKILL_NOT_FOUND` / `TASK_NOT_FOUND` / `CONVERSATION_NOT_FOUND` / `ISSUE_NOT_FOUND` / `WORKFLOW_NOT_FOUND` | 资源不存在 |
| 400 | `INVALID_INPUT` / `INVALID_STATUS` / `MODEL_NOT_ALLOWED` | 入参错误 |
| 409 | `SKILL_UNAVAILABLE` | Skill 依赖的 Tool 未装配 |
| 429 | `RATE_LIMITED` | 限流（`Retry-After: 30`） |
| 503 | `*_DISABLED` | 对应子系统未启用（conversation / cost / workflow / issue-closure / audit） |

---

## 相关文档

- [系统架构总览](../architecture/zh/system-architecture.md)
- [宿主集成指南](../integration/zh/host-integration-guide.md)
- [知识搜索算法](../search/zh/knowledge-search.md)
- [工具插件架构](../plugins/zh/tool-plugin-architecture.md)
- [工作流引擎架构](../workflow/zh/workflow-engine-architecture.md)
- [问题闭环架构](../issue/zh/issue-closure-architecture.md)
- [主动监控架构](../proactive/zh/proactive-monitoring-architecture.md)
- [多集群部署架构](../deployment/zh/multi-cluster-architecture.md)
