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

## v0.3 — 代码理解能力（已交付）

**目标**：Agent 不止能查数据，还能读懂项目代码，回答更精准

### 已完成

| 工具 | 能力 |
|------|------|
| `code_read` | 读取项目源码文件，支持行范围和关键词过滤（带上下文 ±2 行） |
| `project_structure` | 扫描项目目录结构，返回树形布局，支持深度控制和 pattern 过滤 |
| `git_log` | 读取 git log/blame/show，查看代码变更历史 |

| 增强 | 说明 |
|------|------|
| SystemPromptExtender SPI | 新增单方法 SPI，启动时注入项目结构摘要到 system prompt |
| ProjectContextExtender | 启动时扫描模块/Java文件数/关键目录，缓存摘要注入 LLM |
| CodePathGuard | 路径安全守卫，project-root 限制 + 扩展名白名单 + 大小限制 |
| code-analysis 内置 skill | 引导 LLM 五阶段分析代码（理解→定位→读取→追溯→结论） |

### 延后到 v0.3.1 / v0.4

| 项 | 说明 |
|----|------|
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
| `KnowledgeInjector` | Agent 启动时注入知识摘要到系统提示；运行时按用户问题检索相关知识片段，动态注入。完整设计见 [知识编排层](#知识编排层--knowledgeinjector横切-v07v08v09) |

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

## 知识编排层 — KnowledgeInjector（横切 v0.7/v0.8/v0.9）

> 本节不是独立版本，而是贯穿 v0.7→v0.8→v0.9 的编排核心。
> 当三个知识源（业务知识库、代码图谱、问题经验）就绪后，**如何让 Agent 在面对用户问题时自动选择正确的知识源和工具组合**，是整个知识体系能否落地的关键。

### 核心设计原则：不路由，注入

不需要单独的"意图分类层"或"路由器"。现有 `AgentExecutor` 已经是 LLM 驱动的工具选择循环——**LLM 本身就是最好的路由器**。`KnowledgeInjector` 的职责是在 LLM 开始思考之前，把相关知识自动注入到上下文中，让 LLM"天然知道该怎么做"。

```
用户问题
    │
    ▼
┌──────────────────────────────────────────────────────────┐
│  第一层：预注入（Pre-injection，自动，LLM 之前）           │
│                                                          │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────┐ │
│  │ 业务知识库       │  │ 问题经验库      │  │ 代码结构摘要│ │
│  │ full-text       │  │ 相似 Q&A      │  │ (条件触发)  │ │
│  │ search → top-5  │  │ search → top-3│  │             │ │
│  └───────┬────────┘  └───────┬────────┘  └──────┬─────┘ │
│          └───────────────────┼───────────────────┘       │
│                              ▼                           │
│                   组装到 System Prompt                    │
└──────────────────────────────┬───────────────────────────┘
                               ▼
┌──────────────────────────────────────────────────────────┐
│  第二层：Skill 编排提示（Markdown，场景化引导）            │
│  Skill body 中的步骤描述引导 LLM 的工具选择顺序            │
│  e.g. "1. 查参数表 2. 查任务过滤逻辑 3. 参考历史案例"     │
└──────────────────────────────┬───────────────────────────┘
                               ▼
┌──────────────────────────────────────────────────────────┐
│  第三层：LLM 自主工具选择（AgentExecutor 多轮循环）        │
│  LLM 看到: 注入的知识 + Skill 步骤 + 可用工具列表         │
│  自主决策: 调 JDBC? 调 CodeGraph? 调 knowledge_search?   │
│  多轮循环: think → tool_use → result → think → ...       │
└──────────────────────────────────────────────────────────┘
```

### SPI 接口

```java
package cn.watsontech.snapagent.core.knowledge;

/**
 * 知识注入器 — 在 LLM 请求发出前，自动检索相关知识并注入到系统提示中。
 * 宿主可实现此接口替换默认实现（如对接向量数据库、外部知识平台）。
 */
public interface KnowledgeInjector {

    /**
     * 在 AgentExecutor 发起首次 LLM 调用前调用。
     * 根据用户问题检索各知识源，将结果拼装为上下文段落。
     *
     * @param context 包含用户问题、Skill 元数据、可用工具列表
     * @return 注入到 system prompt 的知识上下文（可能为空字符串）
     */
    String inject(InjectionContext context);

    /**
     * 在 AgentExecutor 多轮循环中，每轮 LLM 调用前可选触发。
     * 用于根据已有 tool_result 动态补充知识（如发现新表名后补查业务知识）。
     * 默认实现返回 null（不补充）。
     */
    default String injectPerTurn(InjectionContext context) { return null; }
}
```

```java
public class InjectionContext {
    private String userQuery;           // 用户原始问题
    private String skillName;           // 当前 Skill 名称
    private String skillDescription;    // Skill 描述
    private List<String> toolNames;     // 可用工具名称列表
    private List<ToolResult> priorResults; // 前序轮次的 tool 结果（仅 perTurn 时有值）
    private Map<String, Object> skillInputs; // Skill 输入参数
}
```

### 预注入策略：各知识源的注入规则

| 知识源 | 注入时机 | 注入策略 | Top-K | Token 预算 | 理由 |
|--------|---------|---------|-------|-----------|------|
| 业务知识库 | **每次对话首次 LLM 调用前** | full-text search 用用户原始问题 | 5 | ≤ 600 | 业务规则是理解问题的前提，LLM 需要先知道"该查什么" |
| 问题经验库 | **每次对话首次 LLM 调用前** | 相似度搜索用用户原始问题 | 3 | ≤ 400 | 类似 few-shot learning，教 LLM "这类问题之前怎么解的" |
| 代码结构摘要 | **条件触发**（检测到代码关键词） | 仅注入项目结构概览，不注入具体调用链 | 1 | ≤ 300 | 让 LLM 知道项目有哪些模块/关键类，具体查询交给工具 |
| 代码图谱查询 | **不预注入**，作为工具 | — | — | — | 调用链/影响分析是针对性查询，需要 LLM 先理解问题再决定查什么 |
| 知识深挖 | **不预注入**，作为工具 | — | — | — | `knowledge_search` 作为工具暴露，LLM 按需深挖 |

#### 代码关键词检测规则

```java
private static final Set<String> CODE_KEYWORDS = ImmutableSet.of(
    "代码", "方法", "接口", "实现", "调用", "逻辑",
    "class", "method", "interface", "impl", "service",
    "controller", "mapper", "config", "java", "源码",
    "谁改的", "git", "commit", "blame", "影响"
);

boolean shouldInjectCodeSummary(String query) {
    String lower = query.toLowerCase();
    return CODE_KEYWORDS.stream().anyMatch(lower::contains);
}
```

### 默认实现：DefaultKnowledgeInjector

```java
@Component
@ConditionalOnMissingBean(KnowledgeInjector.class)
@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
public class DefaultKnowledgeInjector implements KnowledgeInjector {

    private final KnowledgeBase knowledgeBase;           // v0.7 业务知识库
    private final IssueExperienceStore issueStore;       // v0.9 问题经验库
    private final CodeGraphIndex codeGraphIndex;         // v0.8 代码图谱
    private final KnowledgeInjectorProperties config;    // 配置

    @Override
    public String inject(InjectionContext ctx) {
        StringBuilder sb = new StringBuilder();
        String query = ctx.getUserQuery();

        // 1. 业务知识（始终注入）
        if (knowledgeBase != null) {
            List<KnowledgeSnippet> results = knowledgeBase.search(query, config.getBusinessTopK());
            if (!results.isEmpty()) {
                sb.append("## 业务知识上下文\n");
                for (KnowledgeSnippet s : results) {
                    sb.append("### ").append(s.getTitle()).append("\n")
                      .append(s.getContent()).append("\n\n");
                }
            }
        }

        // 2. 问题经验（始终注入）
        if (issueStore != null) {
            List<IssueQa> similar = issueStore.searchSimilar(query, config.getIssueTopK());
            if (!similar.isEmpty()) {
                sb.append("## 历史相似问题\n");
                for (IssueQa qa : similar) {
                    sb.append("### 问题: ").append(qa.getQuestion()).append("\n")
                      .append("根因: ").append(qa.getRootCause()).append("\n")
                      .append("方案: ").append(qa.getSolution()).append("\n")
                      .append("(来源: ").append(qa.getIssueId()).append(")\n\n");
                }
            }
        }

        // 3. 代码结构摘要（条件注入）
        if (codeGraphIndex != null && shouldInjectCodeSummary(query)) {
            String summary = codeGraphIndex.getProjectSummary();
            if (summary != null) {
                sb.append("## 项目代码结构\n").append(summary).append("\n\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : "";
    }

    @Override
    public String injectPerTurn(InjectionContext ctx) {
        // 默认不补充。宿主可覆写此方法实现动态知识补充。
        // 例如: 解析 tool_result 中的表名，自动补查该表的业务知识。
        return null;
    }
}
```

### 配置

```yaml
snap-agent:
  knowledge:
    enabled: true
    injector:
      business-top-k: 5              # 业务知识注入条数
      issue-top-k: 3                 # 问题经验注入条数
      inject-code-summary: true      # 是否条件注入代码结构摘要
      max-injection-tokens: 1500    # 注入内容 token 上限（超限则截断低相关项）
      per-turn-injection: false     # 是否每轮 LLM 调用前都补充注入（默认仅首次）
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
      - type: file-conversation
        dir: ${upload-skills-dir}/knowledge/
      - type: external-api
        url: https://wiki.internal/api/search
        token: ${KNOWLEDGE_TOKEN}
```

### Token 预算管理

预注入会增加 system prompt 的 token 数量。需要控制总量避免成本失控：

```java
class InjectionBudget {
    private static final int MAX_INJECTION_TOKENS = 1500;

    String trimToFit(String injection, int maxTokens) {
        // 1. 估算 token 数（近似: 字符数 / 3.5 for 中文混合英文）
        int estimatedTokens = (int) (injection.length() / 3.5);

        if (estimatedTokens <= maxTokens) {
            return injection;
        }

        // 2. 超限时按优先级截断:
        //    业务知识 > 问题经验 > 代码结构
        //    每个知识源内部按相关度分数排序，保留 top-N
        //    截断时添加 "..." 标记
        return truncateByPriority(injection, maxTokens);
    }
}
```

| 优先级 | 知识源 | 截断策略 |
|--------|--------|---------|
| P0 | 业务知识 | 保留 top-3，每条截断到 200 token |
| P1 | 问题经验 | 保留 top-2，每条截断到 150 token |
| P2 | 代码结构 | 保留模块列表，删除类/方法明细 |

### 知识检索也作为工具暴露

除了预注入，知识检索也作为工具暴露给 LLM，允许 LLM 在多轮推理中主动深挖：

```java
@Component
@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeSearchToolProvider implements ToolProvider {

    @Override
    public List<ToolDef> getToolDefinitions() {
        return List.of(
            ToolDef.builder()
                .name("knowledge_search")
                .description("搜索业务知识库，获取业务规则、操作手册、配置说明等。" +
                             "当预注入的知识不足以回答问题时使用。输入自然语言查询。")
                .inputSchema(JsonSchema.object()
                    .property("query", JsonSchema.string("自然语言查询"))
                    .property("top_k", JsonSchema.integer("返回条数，默认5", 5))
                    .build())
                .build()
        );
    }

    @Override
    public ToolResult execute(ToolContext ctx) {
        String query = ctx.getInput("query");
        int topK = ctx.getInput("top_k", 5);
        List<KnowledgeSnippet> results = knowledgeBase.search(query, topK);
        return ToolResult.success(results.stream()
            .map(r -> Map.of("title", r.getTitle(), "content", r.getContent()))
            .collect(Collectors.toList()));
    }
}
```

| 工具 | 输入 | 输出 | 使用场景 |
|------|------|------|---------|
| `knowledge_search` | 自然语言查询 + top_k | 业务知识片段列表 | 预注入不够时，LLM 主动深挖特定业务规则 |
| `issue_search` | 自然语言查询 + top_k | 历史 Q&A 列表 | 查找更多类似问题的处理经验 |
| `code_graph_call_chain` | 方法签名 | 调用链 | v0.8 工具，LLM 按需调用 |
| `code_graph_impact_analysis` | 类/方法 | 影响范围 | v0.8 工具，LLM 按需调用 |

### 与 AgentExecutor 的集成

`KnowledgeInjector` 在 `AgentExecutor` 的执行流程中注入点如下：

```java
public class AgentExecutor {

    private final KnowledgeInjector knowledgeInjector;  // 可为 null

    public AgentTask execute(String userQuery, String skillName, ...) {
        // ── 注入点 1: 首次 LLM 调用前 ──
        String knowledgeContext = "";
        if (knowledgeInjector != null) {
            InjectionContext ctx = new InjectionContext(userQuery, skillName, toolNames);
            knowledgeContext = knowledgeInjector.inject(ctx);
        }

        // 组装 system prompt
        String systemPrompt = buildSystemPrompt(skillBody, knowledgeContext);

        // 多轮循环
        for (int turn = 0; turn < maxTurns; turn++) {
            // ── 注入点 2: 每轮 LLM 调用前（可选）──
            if (turn > 0 && knowledgeInjector != null && perTurnEnabled) {
                InjectionContext ctx = new InjectionContext(
                    userQuery, skillName, toolNames, priorToolResults);
                String补充 = knowledgeInjector.injectPerTurn(ctx);
                if (补充 != null) {
                    messages.add(Message.system(补充));
                }
            }

            // LLM 调用 → tool_use 分发 → result 反馈
            LlmResponse resp = llmClient.call(systemPrompt, messages, tools);
            ...
        }
    }
}
```

**两个注入点**：

| 注入点 | 时机 | 默认行为 | 可选行为 |
|--------|------|---------|---------|
| **首次注入** | LLM 首次调用前 | 始终执行 | — |
| **每轮注入** | 每轮 LLM 调用前 | 默认关闭（`per-turn-injection: false`） | 开启后可动态补充（如解析 tool_result 中的表名，补查业务知识） |

### 完整场景演示

```
用户: "SKU-001 为什么没有生成补货策略？"

═══ 预注入阶段（自动，LLM 之前） ═══

KnowledgeInjector.inject("SKU-001 为什么没有生成补货策略？")
  │
  ├─ 业务知识库 search("补货策略 生成规则"):
  │   → 命中 1: "补货策略依赖 replm_inv_param_sku_wh_input 表"
  │   → 命中 2: "常见原因: init_replenishment_param 任务未执行"
  │   → 命中 3: "策略生成后需通过 allocation_score 评分才下发"
  │
  ├─ 问题经验库 searchSimilar("补货策略 未生成"):
  │   → 命中 1: "SKU-999 → 根因: SKU 状态为 INACTIVE 被过滤"
  │   → 命中 2: "SKU-888 → 根因: 仓库未配置在 warehouse_group 中"
  │
  └─ 代码结构: 未触发（"补货策略"不包含代码关键词）

System Prompt 组装:
  "你是诊断 Agent。Skill 步骤: 1.查参数表 2.查策略表 3.查任务逻辑
   ## 业务知识上下文
   补货策略依赖 replm_inv_param_sku_wh_input 表...
   常见原因: init_replenishment_param 任务未执行...
   ## 历史相似问题
   SKU-999: 根因 INACTIVE 被过滤...
   SKU-888: 根因 仓库未配置..."

═══ LLM 第一轮 ═══

LLM 思考: "业务知识说需要查 replm_inv_param_sku_wh_input 表，
          历史经验说常见原因是任务未执行或 SKU 被过滤。
          先查参数表确认 SKU-001 是否有数据。"

→ 调用: jdbc_query("SELECT * FROM replm_inv_param_sku_wh_input
                     WHERE sku_code = 'SKU-001'")
← 结果: 空

═══ LLM 第二轮 ═══

LLM 思考: "参数表无数据。历史案例 SKU-999 是因为状态为 INACTIVE。
          查一下 SKU-001 的状态，同时看看任务的过滤逻辑。
          这次涉及代码逻辑，调用 code_graph 查调用链。"

→ 并行调用:
   1. jdbc_query("SELECT status FROM sku_master WHERE sku_code = 'SKU-001'")
      ← 结果: status = 'INACTIVE'
   2. code_graph_call_chain("ReplenishmentParamTask.generate")
      ← 结果: generate() → filterByStatus() → 只处理 ACTIVE 的 SKU

═══ LLM 第三轮 ═══

LLM 思考: "确认: SKU-001 状态 INACTIVE，被 filterByStatus() 过滤。
          和 SKU-999 案例一致。可以给结论了。"

→ 输出:
   "## 根因
    SKU-001 状态为 INACTIVE，被 init_replenishment_param 任务的
    filterByStatus() 方法过滤，未生成补货参数记录。

    ## 证据
    1. replm_inv_param_sku_wh_input 表中无 SKU-001 记录
    2. sku_master.status = 'INACTIVE'
    3. ReplenishmentParamTask.generate() → filterByStatus() 只处理 ACTIVE

    ## 参考案例
    SKU-999（2026-07-10），相同根因，已通过修改状态解决。

    ## 建议方案
    1. 确认 SKU-001 是否应为 ACTIVE
    2. 修改状态后重新执行 init_replenishment_param
    3. 建议在 filterByStatus() 中增加告警日志"
```

### 为什么不预注入代码图谱

| 维度 | 预注入业务知识 | 预注入代码图谱 |
|------|--------------|--------------|
| 查询通用性 | 业务规则对大多数同类问题通用（"补货策略依赖什么表"对所有 SKU 都适用） | 调用链是特定方法的，不同问题查不同方法 |
| 查询精确度 | 用用户原始问题即可命中 | 需要知道具体类/方法名，LLM 需先推理 |
| Token 效率 | 5 条 ≈ 600 token，信息密度高 | 调用链输出长，可能 1000+ token，但不一定相关 |
| 结论 | **始终预注入** | **作为工具按需调用** |

### 动态知识补充（可选，per-turn injection）

当 `per-turn-injection: true` 时，宿主可实现更激进的注入策略：

```java
@Override
public String injectPerTurn(InjectionContext ctx) {
    // 示例: 解析前序 tool_result 中的表名，自动补查该表的业务知识
    List<ToolResult> results = ctx.getPriorResults();
    for (ToolResult r : results) {
        String content = r.getContent();
        // 简单正则提取表名（以 _ 分隔的全小写单词）
        Matcher m = Pattern.compile("\\b([a-z]+_[a-z_]+)\\b").matcher(content);
        while (m.find()) {
            String tableName = m.group(1);
            List<KnowledgeSnippet> tableKnowledge = knowledgeBase.search(tableName, 2);
            if (!tableKnowledge.isEmpty()) {
                return "## 补充知识: 表 " + tableName + "\n" + format(tableKnowledge);
            }
        }
    }
    return null;
}
```

**权衡**：动态补充能提供更精准的知识，但每轮增加 LLM 调用前的检索延迟和 token 消耗。默认关闭，仅在知识库质量高且问题复杂度大时开启。

### 知识注入与 Skill 步骤的协同

Skill Markdown 是第二层编排，它不影响预注入，但影响 LLM 的工具选择顺序：

```markdown
---
name: replenishment-strategy-diagnose
description: 诊断 SKU 补货策略未生成问题
inputs:
  - name: skuCode
    description: SKU 编码
---

## 诊断步骤

1. **理解业务规则**（已自动注入到上下文）
   - 确认补货策略的关键依赖表和常见原因

2. **查询参数表** — 调用 jdbc_query
   - 检查 `replm_inv_param_sku_wh_input` 是否有该 SKU 的记录

3. **查询策略表** — 调用 jdbc_query
   - 检查 `drp_allocation_plan` 是否有策略记录

4. **代码追踪**（如需要）— 调用 code_graph_call_chain
   - 查 `init_replenishment_param` 任务的过滤逻辑

5. **参考历史经验**（已自动注入）
   - 如果有类似案例，参考其根因

6. **输出结论**
   - 根因 + 证据 + 建议方案
```

LLM 读到步骤后按顺序推理。这不是强制路由——LLM 可以跳步、增加步骤或回溯——而是**场景化引导**，让 LLM 的推理路径更可预期、更高效。

### 装配方式

```java
// SnapAgentAutoConfiguration
@Bean
@ConditionalOnMissingBean
@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
public KnowledgeInjector knowledgeInjector(
        Optional<KnowledgeBase> knowledgeBase,
        Optional<IssueExperienceStore> issueStore,
        Optional<CodeGraphIndex> codeGraph,
        KnowledgeInjectorProperties config) {
    return new DefaultKnowledgeInjector(
        knowledgeBase.orElse(null),
        issueStore.orElse(null),
        codeGraph.orElse(null),
        config
    );
}

// AgentExecutor 构造时注入（可选）
@Bean
public AgentExecutor agentExecutor(
        LlmClient llmClient,
        ToolDispatcher toolDispatcher,
        Optional<KnowledgeInjector> knowledgeInjector) {
    return new AgentExecutor(llmClient, toolDispatcher, knowledgeInjector.orElse(null));
}
```

`KnowledgeInjector` 是 `Optional` 注入——如果知识功能未启用，`AgentExecutor` 行为与当前完全一致，零影响。

### 知识源就绪度矩阵

| 知识源 | 版本 | 预注入 | 工具暴露 | 状态 |
|--------|------|--------|---------|------|
| 业务知识库 | v0.7 | ✅ 始终 | ✅ `knowledge_search` | 设计中 |
| 问题经验库 | v0.9 | ✅ 始终 | ✅ `issue_search` | 设计中 |
| 代码结构摘要 | v0.8 | ✅ 条件触发 | — | 设计中 |
| 代码图谱查询 | v0.8 | ❌ 不预注入 | ✅ `code_graph_*` | 设计中 |
| JDBC/Redis 等工具 | v0.1 | ❌ | ✅ 已有 | ✅ 已交付 |

### 版本演进路径

```
v0.1-alpha   AgentExecutor + JDBC/Redis 工具               ← 已交付
    │
    ▼
v0.7          KnowledgeBase SPI + KnowledgeInjector(仅业务)  ← 业务知识预注入
    │
    ▼
v0.8          CodeGraph + 代码结构摘要条件注入                ← 代码知识按需
    │
    ▼
v0.9          IssueExperienceStore + 问题经验预注入            ← 三源齐备
    │                                                        KnowledgeInjector 完整版
    ▼
v0.9+         动态 per-turn 注入 + 知识检索工具暴露             ← 编排层完善
```

每个版本 `KnowledgeInjector` 的行为是渐进增强的：知识源未就绪时自动跳过，不影响已有功能。

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
9. **注入优先于路由** — 知识编排不靠意图分类层，而是在 LLM 调用前自动注入相关知识，让 LLM 天然知道该怎么做。预注入通用知识（业务规则、历史经验），按需调用精确工具（代码图谱、深挖检索）。

---

## 不在路线图（明确拒绝）

- **stdio MCP** — Web 容器不 spawn 子进程
- **写工具** — 永远只读（核心约束，自定义工具除外但需自行承担风险）
- **独立部署形态** — 永远是嵌入式库，不做 SaaS
- **前端框架** — 永远 vanilla JS，不引入 React/Vue 构建链
- **向量数据库依赖** — 知识库检索用轻量级全文索引，不引入 Milvus/Pinecone 等外部依赖
