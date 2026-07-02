# 路线图 — SnapAgent

> 本文档描述 SnapAgent 框架从 v0.1-alpha 到 v0.6 的演进规划。
> 核心定位：**不是查库工具，而是通用 AI 技能框架** — 技能驱动 + 工具可扩展 + 嵌入式。

---

## v0.1-alpha（当前 — 已交付）

**目标**：嵌入式框架核心 + 数据库诊断验证场景

### 已完成

| 能力 | 说明 |
|------|------|
| AgentExecutor | 多轮循环：LLM 思考 → tool_use 分发 → 结果反馈 → 继续思考 → end_turn |
| LLM 流式 | OkHttp SSE → `LlmEventSink` → `TranscriptEvent` → `SseEmitter` token 级推送 |
| Skill Markdown | YAML frontmatter (name/description/inputs) + 步骤式 body，启动加载 + 手动刷新 |
| JDBC 工具 | `JdbcQueryToolProvider` + `SqlGuard` (白名单+黑名单+LIMIT 注入+多语句拒绝) |
| Redis 工具 | `RedisReadToolProvider` (get/exists，KEYS 拒绝) |
| 安全适配 | SpringSecurityAdapter + ShiroAdapter + DefaultPrincipalResolver |
| 限流 | 每用户并发 1 / 每小时 20 / 专用线程池 core2-max4-queue10 |
| 跨 Pod 中继 | K8s API → Headless DNS → Static → None 降级链 |
| Chat SPA | index.html + app.js + md.js + style.css，无构建工具 |
| 审计 | 每次 tool 调用写入 transcript，API 可回溯 |

### 验证场景

- 补货策略诊断 Skill：用户用自然语言排查 SKU 未生成补货策略的根因
- 多轮对话：Agent 查库 → 分析 → 补充查询 → 输出结构化报告

---

## v0.2 — 框架增强

**目标**：补全框架基础能力，为后续扩展打基础

| 项 | 说明 | 价值 |
|----|------|------|
| MCP 协议接入 | `mcp.servers` 配置，SSE transport，远端工具以 `mcp__{server}__{tool}` 注册 | 无需写 Java 即可接入外部工具 |
| 任务取消 | `POST /runs/{id}/cancel`，OkHttp `Call.cancel()` 中断流式 | 用户可随时停止长时间运行的任务 |
| 报告导出 | `GET /runs/{id}/report?format=md` | 诊断报告可下载、可分享 |
| Skill 热重载 | WatchService 监听 `skills-dir`，文件变更自动 refresh | 开发 Skill 时无需重启应用 |
| 审计持久化 | ring-buffer → 可选 DB 表 (`skills_agent_audit`) | 长期审计追溯 |
| 对话历史 | `GET /runs?userId=&skillId=&page=` | 跨 session 查历史任务 |

---

## v0.3 — 代码理解能力

**目标**：Agent 不止能查数据，还能读懂项目代码，回答更精准

### 新增工具

| 工具 | 能力 |
|------|------|
| `CodeReaderToolProvider` | 读取项目源码文件，Agent 可回答"这个接口在哪实现""这段逻辑为什么这样写" |
| `ProjectStructureToolProvider` | 扫描项目结构（模块、包、关键类、接口列表），Agent 理解项目全貌 |
| `GitLogToolProvider` | 读取 git log / blame，Agent 可回答"这段代码是谁改的、什么时候改的、改了什么" |

### 增强

| 项 | 说明 |
|----|------|
| 项目上下文注入 | Agent 启动时扫描项目结构，生成摘要作为系统提示注入 LLM，让回答自带项目背景 |
| Skill 自动生成 | Agent 分析项目代码 + 数据库结构，自动生成候选 Skill Markdown 供开发者确认 |
| 代码引用渲染 | Agent 回答中引用代码文件时，前端渲染为可点击链接，跳转到源码 |

### 场景示例

```
用户: "OrderService.createOrder 这个方法为什么会跳过库存校验？"
Agent:
  1. 调用 CodeReaderToolProvider 读取 OrderService.java
  2. 分析 createOrder 方法逻辑
  3. 发现 if (skipValidation) 分支
  4. 调用 GitLogToolProvider 查看 skipValidation 字段的变更历史
  5. 发现是 3 个月前为了紧急上线临时加的
  6. 输出: "该分支由 @zhangsan 在 2025-10-15 添加，commit message: 紧急上线跳过校验。
           建议移除该分支或增加前置条件检查。"
```

---

## v0.4 — 运营诊断能力

**目标**：Agent 对接可观测性平台，从"被动问答"升级为"主动运营诊断"

### 新增工具

| 工具 | 能力 |
|------|------|
| `MetricsToolProvider` | 对接 Prometheus API，Agent 实时查询指标（QPS、延迟、错误率、CPU/Mem） |
| `LogAnalysisToolProvider` | 对接 ELK / Loki API，Agent 搜索日志、分析错误模式、统计频率 |
| `TraceAnalysisToolProvider` | 对接 Jaeger / SkyWalking，Agent 分析调用链、定位慢节点 |
| `ConfigToolProvider` | 读取应用配置（Apollo / Nacos / 本地 yml），Agent 对比环境配置差异 |

### 内置 Skill 模板

| Skill | 流程 |
|-------|------|
| `health-check` | 全面健康检查：CPU/Mem/延迟/错误率 → 异常指标 → 根因分析 |
| `slow-query-analysis` | 慢查询排查：查慢日志 → 分析执行计划 → 索引建议 |
| `error-spike-investigation` | 错误率突增：定位时间窗口 → 查日志 → 关联部署 → 根因 |
| `config-diff` | 环境配置对比：sit vs prod 配置差异 → 识别风险项 |

### 场景示例

```
用户: "线上订单创建接口今天为什么这么慢？"
Agent:
  1. 调用 MetricsToolProvider 查 /api/orders POST 的 P99 延迟趋势
  2. 发现 14:00 开始延迟从 200ms 飙升到 3s
  3. 调用 TraceAnalysisToolProvider 查该时段的调用链
  4. 发现 InventoryService.check 库存校验耗时 2.8s
  5. 调用 MetricsToolProvider 查库存服务的 CPU 和 DB 连接池
  6. 发现 DB 连接池打满
  7. 调用 LogAnalysisToolProvider 查库存服务日志
  8. 发现大量 "Connection timeout" 日志
  9. 输出: "根因: 库存服务 DB 连接池打满 (14:00 开始)，导致订单接口级联超时。
           可能原因: 14:00 有定时任务批量查库存，占满连接池。
           建议: 1. 调大库存服务连接池 2. 定时任务错峰执行 3. 加熔断降级"
```

---

## v0.5 — 主动监控 & 智能推送

**目标**：从"用户提问才诊断"升级为"Agent 主动发现问题并推送"

### 核心能力

| 能力 | 说明 |
|------|------|
| 异常监听 | 对接消息队列 (Kafka/RabbitMQ)，消费异常事件，自动触发诊断 Skill |
| 健康巡检 | 定时执行健康检查 Skill，发现异常自动生成诊断报告 |
| Bugfix 推送 | Agent 诊断后结合代码分析，生成修复建议 (diff 级别)，推送到 Issue/工单系统 |
| 告警收敛 | 同类异常聚合，减少噪声告警，附带根因分析 |
| 趋势预测 | 基于历史指标趋势，提前预警 (如"磁盘 3 天后满""连接池 2 小时后打满") |

### 架构变化

```
                    ┌─────────────────┐
                    │  异常事件源      │
                    │  (Kafka/MQ)     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  EventListener  │  ← 新增
                    │  (消费异常)      │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
     │ Auto-Diagnose │ │ Push    │ │ Alert      │
     │ (触发Skill)   │ │ Bugfix  │ │ Convergence│
     └───────────────┘ └──────────┘ └─────────────┘
```

### 场景示例

```
[自动触发，无需用户提问]
事件: NullPointerException 在 OrderService.createOrder 频繁出现 (10次/分钟)

Agent 自动执行:
  1. 调用 LogAnalysisToolProvider 拉取异常堆栈
  2. 调用 CodeReaderToolProvider 读取 OrderService.java 对应行
  3. 分析: order.getItem().getPrice() NPE，因为 getItem() 返回 null
  4. 调用 GitLogToolProvider 查该行最近变更
  5. 发现: @lisi 昨天 18:00 重构，把 item 从构造函数参数改成了 setter 注入
  6. 生成 Bugfix 建议:
     "根因: OrderService 第 87 行 order.getItem() 返回 null
      原因: @lisi 2025-07-01 重构，setter 注入未在构造后保证非 null
      修复: 加 null 检查 或 改回构造函数注入"
  7. 推送到 Jira / 钉钉 / 邮件
```

---

## v0.6 — 平台化

**目标**：从框架升级为平台，支持更多语言生态和社区协作

| 项 | 说明 |
|----|------|
| Spring Boot 3.x | `jakarta.servlet` 版本 starter |
| 多 LLM 适配 | OpenAI / 国产大模型 (通义/文心/智谱) |
| 多环境数据源 | `jdbc.datasources: {sit: ..., uat: ...}`，Skill `inputs.env` 选 DSN |
| Skill 市场 | 社区共享 Skill 模板，一行配置导入 |
| 工具市场 | 开箱即用的 ToolProvider 扩展包 (Kafka/MQ/Apollo/Nacos/...) |
| REST API 增强 | SDK 化，非 Web 场景也能调用 Agent |
| 多租户 | Agent 对话隔离，Skill/工具按租户配置 |
| 权限细化 | Skill 级别权限控制，不同角色可用不同 Skill |

---

## 设计原则（贯穿所有版本）

1. **嵌入式优先** — 永远是库，不是独立服务。不增加运维负担。
2. **只读优先** — 内置工具默认只读。写操作需要自定义 ToolProvider 且明确标注风险。
3. **零影响** — `enabled=false` 时不创建任何 Bean。宿主不感知。
4. **Skill 驱动** — 新场景 = 新 Markdown 文件，不需要写代码（除非需要新工具）。
5. **工具可扩展** — `ToolProvider` SPI + `@Component`，零配置自动发现。
6. **安全内建** — SqlGuard、限流、审计、SecurityGateway，安全不是后加的。

---

## 不在路线图（明确拒绝）

- **stdio MCP** — Web 容器不 spawn 子进程
- **写工具** — 永远只读（核心约束，自定义工具除外但需自行承担风险）
- **独立部署形态** — 永远是嵌入式库，不做 SaaS
- **前端框架** — 永远 vanilla JS，不引入 React/Vue 构建链
