# LangGraph vs SnapAgent Engine — 架构差异分析

> 研究日期: 2026-07-25
> 研究范围: LangGraph 核心架构与 SnapAgent AgentEngine 的对比，识别能力差距

---

## 一、架构对比总览

| 维度 | LangGraph | SnapAgent | 差距等级 |
|------|-----------|-----------|----------|
| **执行模型** | StateGraph（有向图，支持环 + DAG） | 线性 ReAct 循环 | P0 |
| **状态管理** | TypedDict + reducer，每步 checkpoint | 内存 ConcurrentHashMap，无持久化 | P0 |
| **路由** | `add_conditional_edges()` 编程式路由 | LLM 自主决策 + workflow 正则条件 | P1 |
| **多 Agent** | supervisor-worker，subgraph 组合 | 无直接支持，workflow 串行链接 skill | P1 |
| **HITL（人机交互）** | `interrupt()` + `Command(resume=...)` | 仅支持 cancel，无 pause/resume | P0 |
| **并行执行** | `Send` API 支持 fan-out/map-reduce | 单 task 内串行 | P2 |
| **调试** | time-travel，从任意 checkpoint 回放 | 仅 transcript 事件日志 | P1 |
| **持久化** | SqliteSaver / RedisSaver / PostgresSaver | 无（重启即丢失） | P0 |
| **类型化状态** | TypedDict + Annotated reducer | Map<String, String> 无类型 | P2 |
| **子图组合** | Subgraph 嵌套，channel 通信 | 无 | P1 |

---

## 二、我们缺少的核心能力（按权重和优先级排序）

### P0 — 架构级缺陷（必须补齐，影响产品可用性）

#### 1. 状态图模型（StateGraph） — 权重: 10/10

**LangGraph 实现:**
- `StateGraph` 是核心抽象：节点(Node) = 计算单元，边(Edge) = 控制流
- 支持环（agent loop）和 DAG（确定性流水线）混合
- `add_conditional_edges()` 基于状态编程式决定下一个节点
- 图编译 `compile()` 后可可视化

**SnapAgent 现状:**
- `GraphExecutor` 基于 `StateGraph`（由 `ReActGraphFactory` 构建），ReAct 节点：`EntryNode` → `AgentNode` → `ToolsNode` → `ShouldContinue`：
  ```
  for turn = 0 to maxTurns:
      call LLM → if tool_use → dispatch → feed back → continue
      if end_turn → return
  ```
- `SimpleWorkflowEngine` 虽有条件分支，但只是线性步骤序列 + 正则匹配
- 无法表达"先分类 → 根据分类路由到不同诊断 skill → 汇总"这类图结构

**缺少的组件:**
- `StateGraph` / `Node` / `Edge` / `ConditionalEdge` 抽象
- 图编译和执行引擎
- 图可视化输出（Mermaid/JSON）

---

#### 2. Checkpoint 持久化 — 权重: 10/10

**LangGraph 实现:**
- 每个节点执行后自动保存 checkpoint
- 支持多种后端：SqliteSaver（开发）、RedisSaver（生产）、PostgresSaver（企业）
- checkpoint 包含：完整 graph state + 配置 + 元数据
- 通过 `thread_id` 隔离不同会话/任务

**SnapAgent 现状:**
- `TaskStore.java:15` 是 `ConcurrentHashMap<String, AgentTask>` — 纯内存
- 进程崩溃 = 所有运行中任务丢失
- 无任何恢复机制

**缺少的组件:**
- `CheckpointStore` SPI（save / load / list / delete）
- 至少一个持久化实现（SQLite for dev, Redis for prod）
- 从 checkpoint 恢复执行的机制
- checkpoint 清理策略（TTL / 容量限制）

---

#### 3. 中断/恢复（Interrupt / Resume） — 权重: 9/10

**LangGraph 实现:**
- `interrupt()` 可在任意节点边界暂停执行
- 等待人工审批后用 `Command(resume=...)` 继续
- checkpoint 保证暂停点状态完整保存
- 常见模式：工具调用审批、内容审核、用户反馈收集

**SnapAgent 现状:**
- 只有 `POST /runs/{id}/cancel`（`SnapAgentController.java:1499`）
- 一旦取消即终止，无法暂停后恢复
- 工具执行前没有审批门

**缺少的组件:**
- `interrupt()` 机制 — 在节点边界暂停
- `resume()` — 从暂停点恢复执行
- 审批队列 — 等待人工确认后继续
- 超时自动处理 — 长时间未审批的处理策略

---

### P1 — 重要能力差距（影响复杂场景和可观测性）

#### 4. 多 Agent 编排（Supervisor-Worker） — 权重: 8/10

**LangGraph 实现:**
- Supervisor-Worker 模式：supervisor 路由任务到 worker subgraph
- Worker 独立执行后通过共享 state + reducer 合并结果
- Subgraph 嵌套 — 每个 worker 可以是完整的子图
- `Send` API 支持 fan-out/map-reduce
- `Command` 基于状态模式匹配动态路由

**SnapAgent 现状:**
- 没有多 agent 通信机制
- `SimpleWorkflowEngine` 串行执行多个 skill，每个 skill 是独立 AgentTask
- 无法在 agent 间传递中间状态、并行执行、委托

**缺少的组件:**
- Supervisor-Worker 拓扑抽象
- Agent 间消息传递 / 共享 state
- Subgraph 嵌套和 channel 通信
- `Send` API（fan-out/map-reduce）
- `Command` 动态路由原语

---

#### 5. 条件路由（Conditional Edges） — 权重: 7/10

**LangGraph 实现:**
- `add_conditional_edges("agent", should_continue, {"tools": "tools", END: END})`
- 基于状态编程式决定下一个节点
- 支持任意复杂的路由逻辑（Python 函数）
- 可基于 LLM 输出动态路由

**SnapAgent 现状:**
- `SimpleWorkflowEngine.java:227-266` 用正则匹配条件表达式
- 支持的基础条件：`${step.result != null}`、`${step.result.contains('text')}`、`${step.status == 'SUCCEEDED'}`
- 不支持 AND/OR 逻辑组合、嵌套条件、LLM 输出路由

**缺少的组件:**
- `ConditionalEdge` 抽象（接受 state，返回下一节点名）
- 复合条件（AND/OR/NOT）
- 基于 LLM 输出的动态路由
- 路由策略 SPI（允许自定义路由逻辑）

---

#### 6. Time-Travel 调试 — 权重: 6/10

**LangGraph 实现:**
- 从任意 checkpoint 回放执行
- 修改状态后重新执行，探索不同路径
- checkpoint history API 列出所有历史节点
- 用于调试异常 agent 行为、测试不同 prompt

**SnapAgent 现状:**
- 只有 `TranscriptEvent` 日志流（上限 500 条）
- 不能回放执行，不能修改状态重跑
- 调试只能看日志，不能"倒带"

**缺少的组件:**
- Checkpoint 历史查询 API
- 从指定 checkpoint 回放执行
- 状态修改 + 重新执行
- 可视化执行路径回放

---

### P2 — 增强能力差距（影响开发体验和扩展性）

#### 7. 类型化状态 + Reducer 合并 — 权重: 5/10

**LangGraph 实现:**
- `TypedDict` / `dataclass` 定义状态 schema
- `Annotated[list, operator.add]` 指定 reducer
- 多 worker 结果通过 reducer 自动合并（如 `add_messages`）
- 编译时类型检查

**SnapAgent 现状:**
- Workflow 用 `Map<String, String>` — 无类型
- Step 间只传 `result` 字符串
- 无 reducer 语义，无状态合并概念

**缺少的组件:**
- 状态 schema 定义（Java Pojo / interface）
- Reducer SPI（如何合并多个 worker 的结果）
- 类型安全的 state 访问

---

#### 8. 并行执行 / Fan-out — 权重: 4/10

**LangGraph 实现:**
- `Send` API 实现 map-reduce 模式
- 多个 worker subgraph 并行执行
- 结果通过 reducer 合并

**SnapAgent 现状:**
- 单 task 内工具串行执行
- 仅 anchor 预处理用 `CompletableFuture`（`AnchorOrchestrator.java:62-66`）
- 无并行工具调用支持

---

## 三、我们有而 LangGraph 没有的（不需补齐，但属于差异化优势）

| 能力 | 文件 | 说明 |
|------|------|------|
| Spring Boot 2.x 深度集成 | 8 个领域 `@Configuration` 类（SecurityAutoConfiguration, ToolAutoConfiguration, WebAutoConfiguration, PatrolAutoConfiguration, KnowledgeAutoConfiguration, IssueAutoConfiguration, CostAutoConfiguration, WorkflowAutoConfiguration） | auto-config，嵌入式部署，企业级开箱即用 |
| 插件热加载 | `PluginRegistry.java`, `PluginUploader.java` | 运行时 JAR 上传 + 隔离 classloader |
| MCP 协议支持 | `McpToolProvider.java` | 已集成 Model Context Protocol |
| SSE 跨 Pod 中继 | `PeerSseRelay.java`, `PeerRouter.java` | K8s/DNS peer discovery + 代理流 |
| Anchor 注入 | `AnchorOrchestrator.java` | 页面上下文摘要 + 分类 + RAG 注入 |
| Patrol 调度 | `PatrolScheduler.java` | cron 自主巡检 + 告警收敛 |
| 限流 | `RateLimiter.java` | per-user 并发 + 小时配额 + CAS |
| 审计系统 | `AuditCallback.java`, `AuditStore.java` | 内置审计追踪 |
| Issue 闭环 | `IssueClosureService.java` | 自动创建外部 tracker 工单 |
| 提示注入防御 | `GraphExecutor.java` | `<user_inputs>` XML 隔离 + 长度截断 + 控制字符过滤 |

---

## 四、补齐路线图

### Phase 1 (P0): 图模型 + 持久化 + 中断恢复

**目标:** `GraphExecutor` 基于 `StateGraph`（由 `ReActGraphFactory` 构建），增加 checkpoint 和 HITL

```
1.1 StateGraph SPI
    ├── Node 接口 (execute(State) -> State)
    ├── Edge / ConditionalEdge 抽象
    ├── GraphBuilder + Graph.compile()
    └── `GraphExecutor` 基于 `StateGraph` 的 ReAct 图（`EntryNode` → `AgentNode` → `ToolsNode` → `ShouldContinue`）

1.2 CheckpointStore SPI
    ├── CheckpointStore 接口 (save / load / list / delete)
    ├── SqliteCheckpointStore (开发环境)
    ├── RedisCheckpointStore (生产环境)
    └── 从 checkpoint 恢复执行

1.3 Interrupt / Resume
    ├── InterruptException 在 Node 边界抛出
    ├── resume(config, Command) 从暂停点继续
    ├── 审批队列 + 超时策略
    └── SSE 事件通知前端 "paused for approval"
```

**迁移策略:** ReAct 循环本质上是 2-node 环图（agent node ↔ tools node），迁移成本可控。先在 core 层新增 graph SPI，`GraphExecutor` 基于 `StateGraph`（由 `ReActGraphFactory` 构建），保持向后兼容。

### Phase 2 (P1): 多 Agent + 条件路由

```
2.1 Supervisor-Worker 拓扑
    ├── Supervisor 节点路由任务
    ├── Worker 节点独立执行
    ├── 共享 State + Reducer 合并
    └── Subgraph 嵌套

2.2 增强 ConditionalEdge
    ├── 复合条件 (AND/OR/NOT)
    ├── 基于 LLM 输出动态路由
    ├── 路由策略 SPI
    └── Send API (fan-out/map-reduce)

2.3 Time-Travel 调试
    ├── Checkpoint 历史查询
    ├── 从指定点回放
    └── 执行路径可视化
```

### Phase 3 (P2): 调试增强 + 类型化

```
3.1 类型化状态
    ├── StateSchema 接口
    ├── Reducer SPI
    └── 类型安全访问

3.2 图可视化
    ├── Mermaid 导出
    ├── JSON 结构化输出
    └── 执行路径实时追踪
```

---

## 五、关键文件引用

| 组件 | 文件路径 | 关键行号 |
|------|----------|----------|
| GraphExecutor (StateGraph ReAct) | `snap-agent-core/.../agent/GraphExecutor.java` | — |
| TaskStore (内存存储) | `snap-agent-core/.../agent/TaskStore.java` | 15 |
| AgentTask (运行时状态) | `snap-agent-core/.../agent/AgentTask.java` | 35-45 |
| ToolCallbackRegistry (工具自动发现) | `snap-agent-core/.../tool/ToolCallbackRegistry.java` | — |
| PluginRegistry (插件注册) | `snap-agent-core/.../tool/PluginRegistry.java` | — |
| SimpleWorkflowEngine | `snap-agent-spring-boot-2x-starter/.../workflow/SimpleWorkflowEngine.java` | 104-186, 227-266 |
| SnapAgentController (SSE) | `snap-agent-spring-boot-2x-starter/.../web/SnapAgentController.java` | 1298-1489 |
| Cancel endpoint | `SnapAgentController.java` | 1499-1540 |
| AnchorOrchestrator | `snap-agent-spring-boot-2x-starter/.../anchor/AnchorOrchestrator.java` | 62-66 |
| PatrolScheduler | `snap-agent-core/.../patrol/PatrolScheduler.java` | — |
| RateLimiter | `snap-agent-core/.../agent/RateLimiter.java` | 94-106 |
| Prompt 防御 | `GraphExecutor.java` | — |

---

## 六、参考资料

- [LangGraph 官方文档](https://langchain-ai.github.io/langgraph/)
- [LangGraph 多 Agent 编排](https://langchain-ai.github.io/langgraph/tutorials/multi_agent/)
- [LangGraph Conditional Edges API](https://langchain-ai.github.io/langgraph/reference/graphs/#langgraph.graph.StateGraph.add_conditional_edges)
- [Building Effective Agents (Anthropic)](https://www.anthropic.com/research/building-effective-agents)
- [ReAct: Synergizing Reasoning and Acting (Yao et al.)](https://arxiv.org/abs/2210.03629)
