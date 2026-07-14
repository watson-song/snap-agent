# 路线图 — SnapAgent

> 本文档描述 SnapAgent 框架从 v0.1-alpha 到 v1.0 的演进规划。
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
| Skill 热重载 | WatchService 监听 `upload-skills-dir`，文件变更自动 refresh | 开发 Skill 时无需重启应用 |
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

## v0.7 — 嵌入式业务知识库

**目标**：让 Agent 具备业务领域知识，从"查数据"升级为"懂业务"，回答不再脱离上下文

### 核心问题

当前 Agent 能查库、能读代码，但不理解业务语义：
- 用户问"补货策略为什么没生成"，Agent 只能机械查表，不理解"补货策略"在业务上是什么、受哪些因素影响
- 答案缺少业务因果链，只是数据层面的"有没有"，不是业务层面的"为什么"

### 方案设计：LLM Wiki 嵌入

借鉴 [Karpathy LLM Wiki](https://github.com/karpathy/llm.c) 概念，在框架内嵌入一个轻量级业务知识库：

```
snap-agent:
  knowledge:
    enabled: true
    sources:
      - type: markdown          # Markdown 文件目录
        dir: classpath:/docs/knowledge/
      - type: file-conversation  # 历史诊断对话中标注为"有价值"的会话
        dir: ${upload-skills-dir}/knowledge/
      - type: external-api       # 外部知识库 API (如 Confluence/语雀)
        url: https://wiki.internal/api/search
        token: ${KNOWLEDGE_TOKEN}
```

### 新增组件

| 组件 | 职责 |
|------|------|
| `KnowledgeBase` (SPI) | 知识源管理 + 检索接口 `search(query, topK)` |
| `MarkdownKnowledgeSource` | 从 Markdown 文件加载知识，自动分段、建索引 |
| `ConversationKnowledgeSource` | 从历史诊断对话中提取有价值的问题→结论对，自动摘要入库 |
| `ExternalApiKnowledgeSource` | 对接外部 Wiki/文档系统，实时检索 |
| `KnowledgeInjector` | Agent 启动时注入知识摘要到系统提示；运行时按用户问题检索相关知识片段，动态注入 |

### 知识结构

```markdown
# 补货策略生成规则

## 业务背景
补货策略是根据 SKU 的历史销量、库存水位、供应商交期等参数，
自动生成建议补货量的一套算法。

## 关键依赖
- `replm_inv_param_sku_wh_input`: 补货参数输入表
- `drp_allocation_plan`: 调拨计划表（下游消费）
- 定时任务: 每日 06:00 执行

## 常见问题
1. 参数表无数据 → 检查 `init_replenishment_param` 任务是否执行
2. 策略生成了但未下发 → 检查 `allocation_score` 评分是否通过
3. 多仓库存未聚合 → 检查 `warehouse_group` 配置
```

### 运行时流程

```
用户: "SKU-001 为什么没生成补货策略？"
  ↓
KnowledgeInjector.search("补货策略 生成规则 依赖表")
  → 命中知识片段: 补货策略生成规则 + 关键依赖表 + 常见问题
  ↓
注入到 LLM 系统提示: "已知业务规则: ..."
  ↓
Agent 思考:
  1. 从知识库知道需要先查 replm_inv_param_sku_wh_input 表
  2. 知道常见原因是 init_replenishment_param 任务未执行
  3. 调用 JdbcQueryToolProvider 查该 SKU 参数 → 确实无数据
  4. 输出: "SKU-001 在补货参数输入表中无记录。常见原因: init_replenishment_param
           定时任务未执行或该 SKU 被过滤。建议检查..."
```

### 知识沉淀闭环

| 能力 | 说明 |
|------|------|
| 对话标注 | 用户在历史对话中点击"标记为知识" → 提取 Q&A 对，存入知识目录 |
| 自动摘要 | Agent 完成诊断后，自动生成结构化摘要（问题→根因→解决方案），待人工确认入库 |
| 知识热重载 | 与 Skill 热重载一致，WatchService 监听知识目录变更 |

---

## v0.8 — 代码知识库（代码图谱）

**目标**：从"按文件名读代码"升级为"按语义关系导航代码"，Agent 理解调用链、依赖关系、业务领域边界

### 核心问题

v0.3 的 `CodeReaderToolProvider` 只能读单文件。用户问"订单创建流程经过哪些服务"，Agent 无法回答——需要理解方法调用链、类依赖关系、模块边界。

### 方案设计：代码图谱 (Code Graph)

构建项目代码的结构化索引，存储为图模型（节点=类/方法/字段，边=调用/继承/实现/依赖）：

```
snap-agent:
  code-graph:
    enabled: true
    scan-packages:
      - com.example.order
      - com.example.inventory
    index-dir: ${upload-skills-dir}/code-graph/
    refresh-on-startup: true
    watch: true               # 源码变更自动增量更新索引
```

### 新增组件

| 组件 | 职责 |
|------|------|
| `CodeGraphBuilder` | 基于 JavaParser (或 Spoon) 解析 AST，构建调用图 |
| `CodeGraphIndex` | 图索引存储（节点表 + 边表 + 全文检索），默认文件存储（SQLite/H2） |
| `CodeGraphToolProvider` | Agent 工具：查询调用链、反向调用链、依赖路径、影响范围 |
| `CodeSemanticSearchToolProvider` | 语义搜索：用自然语言查找代码（如"处理订单超时的逻辑在哪"） |

### 图模型

```
节点类型:
  - ClassNode   (类: name, package, modifiers, type=class/interface/enum)
  - MethodNode  (方法: name, returnType, params, annotations)
  - FieldNode   (字段: name, type, annotations)
  - ModuleNode  (模块: name, path)

边类型:
  - CALLS        (方法A调用方法B)
  - IMPLEMENTS   (类A实现接口B)
  - EXTENDS      (类A继承类B)
  - DEPENDS_ON   (类A依赖类B: 字段类型/方法参数)
  - OVERRIDES    (方法A重写方法B)
  - REFERENCES   (方法A引用字段B)
```

### Agent 工具接口

| 工具 | 输入 | 输出 |
|------|------|------|
| `code_graph_call_chain` | 方法签名 | 正向调用链 (A→B→C→D)，带条件分支标注 |
| `code_graph_reverse_chain` | 方法签名 | 反向调用链 (谁调用了这个方法) |
| `code_graph_impact_analysis` | 类/方法 | 变更影响范围：受影响的方法/类列表 |
| `code_graph_find_by_pattern` | 关键词/注解 | 匹配的类/方法列表 |
| `code_semantic_search` | 自然语言描述 | 相关代码片段 + 文件位置 + 置信度 |

### 场景示例

```
用户: "如果修改 InventoryService.check 的返回值，会影响哪些功能？"
Agent:
  1. 调用 code_graph_impact_analysis("InventoryService.check")
  2. 输出影响范围:
     - OrderService.createOrder (调用方, 依赖返回值做库存校验)
     - AllocationService.generate (调用方, 依赖返回值做分配)
     - InventoryAlertService.monitor (调用方, 依赖返回值做告警)
  3. 调用 code_graph_reverse_chain 查看每个调用方的上下文
  4. 输出: "修改 InventoryService.check 返回值会影响 3 个下游方法。
           建议: 1. 保持向后兼容 2. 同步修改 OrderService 第 45 行..."
```

### 与 v0.3 的关系

- v0.3 `CodeReaderToolProvider` 读取单文件内容（"这个文件里写了什么"）
- v0.8 `CodeGraphToolProvider` 查询代码间关系（"谁调用了谁、改了影响什么"）
- 两者互补：先用图谱定位，再用 Reader 读具体内容

---

## v0.9 — 问题问答闭环

**目标**：从"一次性诊断"升级为"问题→诊断→方案→修复→验证→沉淀"的完整闭环

### 核心问题

当前 Agent 诊断完就结束。用户拿到根因分析后，还要自己：
1. 想解决方案
2. 找谁修复
3. 追踪修复进度
4. 验证修复是否生效
5. 把经验沉淀下来

### 闭环设计

```
      ┌─────────────────────────────────────────────────────────┐
      │                    问题问答闭环                           │
      │                                                         │
      │  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌────────┐│
      │  │ 诊断    │───▶│ 方案    │───▶│ 修复    │───▶│ 验证   ││
      │  │ Agent   │    │ 建议    │    │ 追踪    │    │ 回归   ││
      │  └─────────┘    └─────────┘    └─────────┘    └────┬───┘
      │       │              │              │              │   │
      │       ▼              ▼              ▼              ▼   │
      │  ┌─────────────────────────────────────────────────┐  │
      │  │              经验沉淀 (→ v0.7 知识库)            │  │
      │  └─────────────────────────────────────────────────┘  │
      └─────────────────────────────────────────────────────────┘
```

### 闭环各阶段

| 阶段 | 能力 | 说明 |
|------|------|------|
| 诊断 | 已有 | Agent 多轮查库分析，输出根因 |
| 方案建议 | **新增** `SolutionSuggester` | 基于根因 + 代码图谱 + 历史类似问题，生成候选解决方案（diff 级别） |
| 修复追踪 | **新增** `IssueTracker` | 自动创建 Issue（Jira/工单），关联诊断报告，状态同步 |
| 验证回归 | **新增** `VerificationRunner` | 修复后自动运行验证：查同一指标是否恢复正常、同一查询是否返回预期结果 |
| 经验沉淀 | **新增** → v0.7 知识库 | 自动提取"问题→根因→方案"三元组，待确认后入库 |

### 数据模型

```java
// 问题闭环记录
class IssueClosure {
    String issueId;             // 外部 Issue ID (Jira/工单)
    String conversationId;      // 关联的诊断会话
    String rootCause;           // 根因摘要
    SolutionSuggestion solution; // 方案建议
    IssueStatus status;         // DIAGNOSED → SOLUTION_PROPOSED → FIX_IN_PROGRESS → VERIFIED → CLOSED
    String fixCommitId;        // 修复 commit (如有)
    VerificationResult verification; // 验证结果
    String knowledgeEntryId;   // 沉淀到知识库的条目 ID
}
```

### 新增端点

| 端点 | 说明 |
|------|------|
| `POST /runs/{id}/solution` | 获取/生成解决方案建议 |
| `POST /runs/{id}/issue` | 自动创建 Issue 到外部系统 |
| `GET /issues/{issueId}` | 查看闭环状态 |
| `POST /issues/{issueId}/verify` | 触发验证 |
| `POST /issues/{issueId}/close` | 关闭并沉淀经验 |

### 场景示例

```
用户: "SKU-001 补货策略没生成"
  ↓
[诊断] Agent 查库 → 根因: init_replenishment_param 任务参数过滤了该 SKU
  ↓
[方案] SolutionSuggester:
  "方案1: 修改任务过滤条件，纳入 SKU-001
   方案2: 手动插入参数记录
   方案3: 调整过滤规则 (需 @zhangsan 确认)
   推荐: 方案2 (临时) + 方案1 (长期)
   相关代码: ReplenishmentParamTask.java 第 78 行"
  ↓
[修复追踪] 用户选择方案2 → 自动创建 Jira Issue SKILL-2025-001
  → Issue 关联诊断报告 + 方案建议 + 责任人
  → 状态: FIX_IN_PROGRESS
  ↓
[验证] 修复后用户点击"验证" → Agent:
  1. 查 replm_inv_param_sku_wh_input → SKU-001 有数据了 ✓
  2. 查 drp_allocation_plan → 策略已生成 ✓
  3. 状态: VERIFIED
  ↓
[沉淀] 自动提取:
  问题: "SKU 补货策略未生成"
  根因: "init_replenishment_param 任务过滤条件"
  方案: "修改过滤条件 / 手动插入参数"
  → 存入知识库 (v0.7)
```

---

## v1.0 — 工具插件生态 & 工作流 & 成本核算

**目标**：建立完整生态——工具插件化、Skill 编排工作流、成本透明化

> 三个方向在同一版本交付，共同构成"可运营"的 Agent 平台。

### 1. 工具插件（Tool Plugins）

**目标**：将内置工具从硬编码改为可插拔插件，支持社区/第三方扩展

#### 插件化改造

| 项 | 说明 |
|----|------|
| 工具插件 SDK | 标准化 `ToolPlugin` 接口：`plugin-info.yml` + ToolProvider 实现 + 可选前端组件 |
| MySQL 插件 | `snap-agent-tool-mysql`：独立 jar，`JdbcQueryToolProvider` + MySQL 专用优化（执行计划、索引建议、慢日志） |
| Redis 插件 | `snap-agent-tool-redis`：`RedisReadToolProvider` + Cluster 模式 + Lua 脚本只读执行 |
| 自有应用 MCP 插件 | 宿主应用通过 MCP 协议暴露自身 API 为工具，其他 Agent 可调用（如订单服务暴露"查订单状态"工具） |
| 插件自动发现 | `META-INF/snap-agent/tools/` 目录扫描 + `@ToolPlugin` 注解，零配置注册 |
| 插件配置 | 每个插件独立 YAML 配置段，`snap-agent.tools.{plugin-name}.*` |

#### 插件结构

```
snap-agent-tool-mysql/
├── plugin-info.yml          # 插件元数据
│   name: mysql
│   version: 1.0
│   description: MySQL 诊断工具
│   tools: [query, explain, slow-log, index-advice]
├── src/main/java/.../
│   ├── MysqlQueryToolProvider.java
│   ├── MysqlExplainToolProvider.java
│   └── MysqlSlowLogToolProvider.java
└── src/main/resources/
    └── snap-agent/tools/    # 可选 Skill 模板
        └── mysql-diagnostics.md
```

#### MCP 自有应用插件

```yaml
snap-agent:
  mcp:
    servers:
      - name: order-service
        url: http://order-service:8080/mcp
        tools:
          - name: get_order_status
            description: "查询订单状态"
          - name: get_order_items
            description: "查询订单明细"
```

宿主应用只需实现 MCP server 端点，Agent 自动发现并注册为工具，无需写 Java 客户端代码。

### 2. 工作流（Workflow）

**目标**：从"单 Skill 问答"升级为"多 Skill 编排"，支持复杂运维场景

#### 工作流模型

```yaml
snap-agent:
  workflows:
    - name: full-diagnose
      description: "全链路诊断工作流"
      steps:
        - name: health-check
          skill: health-check
          inputs:
            service: "${trigger.service}"
        - name: find-root-cause
          skill: error-spike-investigation
          condition: "${health-check.result.anomalies.size > 0}"
          inputs:
            timeWindow: "${health-check.result.anomalyWindow}"
        - name: suggest-fix
          skill: code-analysis
          condition: "${find-root-cause.result.rootCause != null}"
          inputs:
            filePath: "${find-root-cause.result.sourceFile}"
        - name: create-issue
          action: create-issue
          inputs:
            summary: "${find-root-cause.result.rootCause}"
            description: "${suggest-fix.result.solution}"
```

#### 工作流能力

| 能力 | 说明 |
|------|------|
| 步骤编排 | 顺序 / 条件分支 / 循环，引用前序步骤结果 `${step.result.field}` |
| 人工审批 | `approval: true` → 暂停等待人工确认后继续 |
| 触发方式 | 手动 / 定时 (cron) / 事件驱动 (MQ 消息) |
| 结果聚合 | 最终输出整合所有步骤的结果，生成综合报告 |
| 失败策略 | 单步失败可配置: 终止 / 跳过 / 重试 / 降级到备选 Skill |

#### 内置工作流模板

| 工作流 | 场景 |
|--------|------|
| `full-diagnose` | 健康检查 → 异常定位 → 代码分析 → 方案建议 → 创建 Issue |
| `deployment-verify` | 部署后验证: 冒烟测试 → 指标检查 → 日志检查 → 告警确认 |
| `capacity-planning` | 容量规划: 历史趋势 → 增长预测 → 阈值预警 → 扩容建议 |

### 3. 成本核算（Cost Accounting）

**目标**：让 Agent 的 LLM 调用成本透明可控，支持按用户/团队/Skill 维度核算

#### 配置

```yaml
snap-agent:
  cost:
    enabled: true
    pricing:                # Token 单价 (每 1M tokens)
      input: 3.00
      output: 15.00
      currency: CNY
    budgets:                # 预算限制
      per-user-daily: 10.00   # 每用户每天 ¥10
      per-skill-daily: 50.00  # 每 Skill 每天 ¥50
      global-daily: 500.00    # 全局每天 ¥500
    storage:
      type: file            # file (默认) / db
```

#### 成本追踪

| 维度 | 说明 |
|------|------|
| 每次 LLM 调用 | 记录 input_tokens / output_tokens / cache_tokens / 费用 |
| 按用户聚合 | `GET /cost/users/{userId}/summary?from=&to=` |
| 按 Skill 聚合 | `GET /cost/skills/{skillName}/summary?from=&to=` |
| 按时间段聚合 | `GET /cost/summary?from=&to=&groupBy=user/skill/day` |
| 实时告警 | 超 80% 预算时通过 SSE 推送提醒，超 100% 拒绝新请求 |

#### 数据模型

```java
class CostRecord {
    String userId;
    String skillName;
    String taskId;
    String model;
    long inputTokens;
    long outputTokens;
    long cacheReadTokens;  // 缓存命中的输入 token (折价)
    BigDecimal cost;       // 计算费用
    Instant timestamp;
}

class CostSummary {
    String dimension;     // user / skill / global
    String dimensionValue;
    BigDecimal totalCost;
    long totalInputTokens;
    long totalOutputTokens;
    int requestCount;
    BigDecimal budget;    // 预算
    double utilization;    // 预算使用率
}
```

#### 成本优化

| 能力 | 说明 |
|------|------|
| 缓存命中统计 | 记录 cache_read_tokens，展示缓存节省的费用 |
| 模型降级建议 | 当用户日费用 > ¥5 时，建议切换到更便宜的模型（如 glm-4-flash） |
| Skill 成本排行 | 展示各 Skill 的平均费用，帮助识别高成本 Skill |
| 对话预算控制 | 单次对话超 ¥1 时提醒用户，超 ¥2 时建议结束 |

#### 场景示例

```
管理员: "本月哪个 Skill 花费最多？"
Agent (内置 cost-analysis Skill):
  1. 调用 GET /cost/summary?groupBy=skill&from=2026-07-01
  2. 输出:
     | Skill              | 请求次数 | 输入 Tokens | 输出 Tokens | 费用(¥) |
     |-------------------|---------|------------|------------|---------|
     | full-diagnose     | 45      | 2.3M       | 850K       | 19.05   |
     | database-query    | 120     | 1.1M       | 320K       | 8.10    |
     | code-analysis     | 38      | 980K       | 410K       | 9.10    |
     | total             | 203     | 4.4M       | 1.6M       | 36.25   |

     建议: full-diagnose 费用占比 52%，主要因为多轮调用。
     可考虑: 1. 减少诊断轮次 (max-turns: 20→15)
            2. 使用更便宜的模型处理简单查询步骤
```

---

## 设计原则（贯穿所有版本）

1. **嵌入式优先** — 永远是库，不是独立服务。不增加运维负担。
2. **只读优先** — 内置工具默认只读。写操作需要自定义 ToolProvider 且明确标注风险。
3. **零影响** — `enabled=false` 时不创建任何 Bean。宿主不感知。
4. **Skill 驱动** — 新场景 = 新 Markdown 文件，不需要写代码（除非需要新工具）。
5. **工具可扩展** — `ToolProvider` SPI + `@Component`，零配置自动发现。
6. **安全内建** — SqlGuard、限流、审计、SecurityGateway，安全不是后加的。
7. **成本透明** — 从 v1.0 起，每次 LLM 调用的成本可追溯、可预算、可控制。
8. **知识沉淀** — 诊断不是一次性的，经验自动提取、人工确认、反哺知识库。

---

## 不在路线图（明确拒绝）

- **stdio MCP** — Web 容器不 spawn 子进程
- **写工具** — 永远只读（核心约束，自定义工具除外但需自行承担风险）
- **独立部署形态** — 永远是嵌入式库，不做 SaaS
- **前端框架** — 永远 vanilla JS，不引入 React/Vue 构建链
- **向量数据库依赖** — 知识库检索用轻量级全文索引，不引入 Milvus/Pinecone 等外部依赖
