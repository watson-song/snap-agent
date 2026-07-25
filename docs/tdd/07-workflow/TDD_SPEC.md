# TDD需求规格说明书 — 工作流图 (StateGraph + ConditionalEdge + GraphExecutor)

> 版本: 3.0 | 模块: 07-workflow | 基于 TEMPLATE.md
> 对应设计: `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` Section 1 (Graph Runtime) + Section 3 (Controller API / Checkpoint) + Section 4 (Anchor 工作流图模式)

---

## 1. 需求元信息

```yaml
需求ID: REQ-WF-07
需求名称: StateGraph (YAML→图) + ConditionalEdge + GraphExecutor(DAG) + Checkpoint 时间旅行 + 子图
优先级: P0
迭代: v2.x
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 2.x 删除 `SimpleWorkflowEngine`、`WorkflowDefinition`、`WorkflowStep` 及正则条件 DSL，统一为图架构。工作流 YAML 不再映射为顺序步骤+正则条件，而是直接编译为 `StateGraph`（节点 + 边 + 条件边）。图运行时 `GraphExecutor` 同时驱动 ReAct 循环和 Workflow DAG，拓扑不同但执行循环、checkpoint、错误处理完全一致。新增时间旅行调试（checkpoint 历史回放）和子图组合（嵌套图 + channel 通信）。
- **用户价值**: 工作流与 ReAct 共用一套运行时，消除概念冗余；条件路由由正则升级为 `EdgeCondition` 函数式接口（`Map<String,String>` 路由表），可编程能力提升 100%；checkpoint 历史支持任意节点回放，调试效率提升 50%。
- **成功指标**: YAML→`StateGraph` 编译成功率 100%；`ConditionalEdge` 路由准确率 100%；`GraphExecutor` 执行 DAG 不陷入循环（无环校验）；checkpoint 列表 P95 < 50ms；回放从任意 checkpoint 恢复成功率 100%。

### 1.2 范围边界
- **包含**: YAML workflow → `StateGraph` 编译（nodes/edges/conditional edges/entry point）、`ConditionalEdge` (`EdgeCondition` 函数式接口 + `Map<String,String>` 路由)、`GraphExecutor` DAG 执行（与 ReAct 共享执行循环）、DAG 无环校验、`CheckpointStore` 历史 API（`GET /runs/{id}/checkpoints`、`POST /runs/{id}/replay`）、时间旅行调试（checkpoint 列表 + 回放）、子图组合（nested `StateGraph` + channel 通信）、并行执行 `Send` API（fan-out/map-reduce）、Workflow vs ReAct 拓扑区分。
- **不包含**: `GraphExecutor` ReAct 循环本身的细节（属 01-agent-engine 模块）、`CheckpointStore` 具体存储实现（`SqliteCheckpointStore`/`RedisCheckpointStore` 属 starter 层）、HITL 流程（属 01-agent-engine）、`Advisor`/`@Tool` SPI（属 03-tool-dispatcher/06-knowledge）。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | YAML 工作流含环 → DAG 校验失败 | 中 | 高 | `compile()` 拓扑排序检测环，失败抛 `IllegalGraphException` |
| R2 | `ConditionalEdge.route` 返回未知 key | 中 | 高 | 未知 key → 默认路由 `END` + WARN 日志，不抛异常 |
| R3 | 并行 `Send` API 节点异常导致部分结果丢失 | 中 | 中 | fan-out 节点异常 catch + map-reduce 汇总错误，不阻塞其他分支 |
| R4 | 子图 channel 名冲突导致状态污染 | 低 | 中 | 子图 channel 命名空间隔离（`subgraph.channel`） |
| R5 | checkpoint 历史过大撑爆存储 | 低 | 中 | `CheckpointStore.list` 默认返回最近 100 条 + TTL 清理 |
| R6 | 回放后 GraphState 与原始 turn 不一致 | 中 | 高 | `GraphState.deserialize` 严格还原 `threadId/checkpointId/turn`，不可变 |

**关键假设**: `snap-agent.checkpoint.type` 默认 `sqlite`（开发环境），生产环境 `redis`；YAML 工作流文件遵循 2.x 新 schema（`nodes`/`edges`/`conditional_edges`/`entry`）；`EdgeCondition` 实现为纯函数，无副作用；子图组合通过 `StateGraph.addSubgraph(name, subGraph, channelMapping)` 嵌入。

---

## 2. 用户故事 (User Stories)

### US-1: YAML 工作流定义编译为 StateGraph
```gherkin
作为 工作流开发者
我希望 YAML 工作流定义（nodes/edges/conditional_edges/entry）被编译为 StateGraph（节点 + 边 + 条件边 + 入口）
以便 无需编码即可声明 DAG 工作流，复用与 ReAct 相同的图运行时
```
**AC:**
```gherkin
AC1: Given YAML 含 nodes=[A,B,C] + edges=[A→B, B→C] + entry=A
  When WorkflowYamlCompiler.compile(yaml)
  Then 返回 StateGraph 含 3 个 Node + 2 条 Edge + entryPoint="A"
AC2: Given YAML 含 conditional_edges=[A→{condition, routing: {ok→B, fail→C}}]
  When compile
  Then StateGraph 含 1 条 ConditionalEdge，routing 含 "ok"→"B" 和 "fail"→"C"
AC3: Given YAML 缺 entry 字段
  When compile
  Then 抛 WorkflowCompileException "missing entry point"
AC4: Given YAML edges 引用不存在节点 (如 A→X, X 不在 nodes)
  When compile
  Then 抛 WorkflowCompileException "unknown node: X"
AC5: Given YAML nodes 为空
  When compile
  Then 抛 WorkflowCompileException "nodes must not be empty"
```

### US-2: ConditionalEdge — EdgeCondition 函数式接口 + Map 路由
```gherkin
作为 工作流开发者
我希望 ConditionalEdge 通过 EdgeCondition 函数式接口（`String route(GraphState state)`）+ `Map<String,String>` 路由表实现条件分支
以便 替代旧正则 DSL，支持任意 Java 逻辑判断（如基于 state["severity"] 路由到不同节点）
```
**AC:**
```gherkin
AC1: Given EdgeCondition 实现 `state -> state.get("severity").equals("CRITICAL") ? "critical" : "normal"`
  And routing = {"critical" → "escalate", "normal" → "log"}
  When GraphExecutor 执行到该 ConditionalEdge 且 state["severity"]="CRITICAL"
  Then 下一节点为 "escalate"
AC2: Given state["severity"]="NORMAL"
  When 路由
  Then 下一节点为 "log"
AC3: Given EdgeCondition.route 返回 "unknown"（routing 不含该 key）
  When 路由
  Then 默认路由到 END + WARN 日志 "unknown route key: unknown, fallback to END"
AC4: Given EdgeCondition.route 抛 RuntimeException
  When 路由
  Then catch 异常 → 路由到 END + ERROR 日志，图执行不中断（TaskStatus=FAILED）
AC5: Given routing 为空 Map
  When 路由
  Then 路由到 END + WARN 日志 "empty routing table"
```

### US-3: GraphExecutor 执行工作流 DAG
```gherkin
作为 平台开发者
我希望 GraphExecutor 以与 ReAct 相同的执行循环驱动工作流 DAG，仅拓扑不同（DAG 无环 vs ReAct 循环）
以便 复用 checkpoint、错误处理、cancel 检查、maxTurns 兜底等运行时能力
```
**AC:**
```gherkin
AC1: Given 工作流 DAG: A→B→C，所有节点返回 SUCCEEDED
  When GraphExecutor.execute(compiledGraph, initialState, ctx)
  Then 依次执行 A→B→C→END，TaskStatus=SUCCEEDED
  And 每节点执行后保存 checkpoint
AC2: Given 节点 B 抛 RuntimeException
  When execute
  Then TaskStatus=FAILED，checkpoint 保存 B 失败状态
  And 节点 C 未执行
AC3: Given 工作流含条件边 A→{ok→B, fail→C}，A 返回 state["result"]="ok"
  When execute
  Then 执行 A→B→END
AC4: Given DAG 含环（A→B→A）
  When compile()
  Then 抛 IllegalGraphException "cycle detected: A→B→A"
AC5: Given 工作流执行 turn 超过 maxTurns=50
  When execute
  Then TaskStatus=TIMEOUT
AC6: Given ctx.isCancelled()=true（在节点 B 执行前设置）
  When execute
  Then TaskStatus=CANCELLED，节点 B 未执行
AC7: Given 节点 B 抛 InterruptException（HITL 暂停）
  When execute
  Then TaskStatus=PAUSED + checkpoint 保存
```

### US-4: 时间旅行调试 — checkpoint 历史 + 回放
```gherkin
作为 调试工程师
我希望 查看工作流执行过程中的所有 checkpoint 历史，并从任意 checkpoint 回放后续执行
以便 定位哪一步条件路由错误或状态异常，无需重新触发完整工作流
```
**AC:**
```gherkin
AC1: Given 工作流执行完成（A→B→C，3 个 checkpoint）
  When CheckpointStore.list(threadId)
  Then 返回 3 条 CheckpointMetadata，按 turn 升序排列
AC2: Given checkpoint 列表含 checkpointId="cp-2"（节点 B 执行后）
  When GraphExecutor.resume(compiledGraph, "cp-2", ctx)
  Then 从 checkpoint 反序列化 GraphState，继续执行 C→END
  And 新增 checkpoint（cp-4, cp-5...）
AC3: Given checkpointId 不存在
  When resume
  Then 抛 CheckpointNotFoundException
AC4: Given GraphState.serialize → byte[] → deserialize
  When 比较原 state 和反序列化 state
  Then values/threadId/checkpointId/turn 完全一致
AC5: Given 回放后条件路由结果与原执行不同（因 state 被修改）
  When resume
  Then 按新 state 路由，不抛异常（允许分叉）
AC6: Given checkpoint 保存失败（存储异常）
  When execute
  Then 图执行不中断，log WARN "checkpoint save failed, degraded"
  And TaskStatus 不变为 FAILED（checkpoint 失败不阻塞）
```

### US-5: Checkpoint 历史 REST API
```gherkin
作为 调试工程师
我希望 通过 REST API 查询工作流 checkpoint 列表并触发回放
以便 无需 SSH 登录即可调试生产环境工作流
```
**AC:**
```gherkin
AC1: Given 工作流 taskId="run-001" 含 3 个 checkpoint
  When GET /runs/run-001/checkpoints
  Then 返回 200 + JSON 数组 [{checkpointId, turn, nodeName, timestamp, status}]
AC2: Given taskId 不存在
  When GET /runs/run-001/checkpoints
  Then 返回 404
AC3: Given taskId 存在但无 checkpoint
  When GET /runs/run-001/checkpoints
  Then 返回 200 + 空数组
AC4: Given checkpointId="cp-2"
  When POST /runs/run-001/replay {"checkpointId":"cp-2"}
  Then 返回 202 + 新 taskId="run-002"
  And 新任务从 cp-2 继续执行
AC5: Given POST /runs/run-001/replay 缺 checkpointId
  When 请求
  Then 返回 400 "missing checkpointId"
AC6: Given checkpointId 不属于该 taskId
  When POST /runs/run-001/replay {"checkpointId":"other-run-cp"}
  Then 返回 404 "checkpoint not found"
AC7: Given 原 taskId 状态为 RUNNING
  When POST /runs/run-001/replay
  Then 返回 409 "original run still running"
```

### US-6: Workflow vs ReAct — 相同 StateGraph 不同拓扑
```gherkin
作为 平台架构师
我希望 工作流和 ReAct 都使用 StateGraph + GraphExecutor，仅拓扑不同（DAG 无环 vs 循环）
以便 一套运行时同时覆盖两种 agent 模式，消除概念冗余
```
**AC:**
```gherkin
AC1: Given ReAct 图: entry→agent↔tools→END（含循环 agent→tools→agent）
  When ReActGraphFactory.compile()
  Then 允许循环（ReAct 模式不校验无环）
  And ConditionalEdge 在 agent 节点后路由 (tool_use→tools, end_turn→END)
AC2: Given 工作流图: A→B→C→END（DAG 无环）
  When WorkflowYamlCompiler.compile()
  Then 校验无环，含环则抛 IllegalGraphException
  And 条件边路由基于 state 字段（如 severity）
AC3: Given 工作流图与 ReAct 图使用同一 GraphExecutor
  When 对比执行循环
  Then 节点执行、checkpoint、错误处理、cancel、maxTurns 逻辑完全一致
AC4: Given 工作流节点含 agent_node（调用 LLM）
  When execute
  Then 该节点复用 AgentNode 实现，但工作流不进入 agent↔tools 循环（DAG 顺序执行）
AC5: Given ReAct 图和工作流图都支持 Advisor 包裹
  When AdvisorNode 包裹节点
  Then before/after chain 一致，不因拓扑不同而行为分叉
```

### US-7: 子图组合 — 嵌套 StateGraph + channel 通信
```gherkin
作为 工作流开发者
我希望 工作流节点可嵌入子 StateGraph，通过 channel 与父图通信
以便 复杂工作流模块化组合，子图可独立测试和复用
```
**AC:**
```gherkin
AC1: Given 父图含节点 "diagnose" 嵌入子图 subGraph（A→B→END）
  When compile 父图
  Then "diagnose" 节点为 SubgraphNode，内含子图引用
  And 父图执行到 "diagnose" 时委托子图执行
AC2: Given 父图 channel="parent.input" → 子图 channel="child.input"
  When 父图 state["parent.input"]="data"
  Then 子图执行时 state["child.input"]="data"（channel 映射）
AC3: Given 子图执行完成 state["child.output"]="result"
  When 子图返回
  Then 父图 state["parent.output"]="result"
AC4: Given 子图节点 B 抛异常
  When 子图执行
  Then 异常传播到父图，父图按 onFailure 处理（FAILED）
AC5: Given 子图含环（不应发生）
  When 子图 compile
  Then 抛 IllegalGraphException（子图也校验无环）
AC6: Given 子图嵌入深度超过 3 层
  When 执行
  Then 抛 SubgraphDepthExceededException "max depth: 3"
```

### US-8: 并行执行 — Send API fan-out/map-reduce
```gherkin
作为 工作流开发者
我希望 工作流节点通过 Send API 触发 fan-out（并行执行多个分支），并通过 map-reduce 节点汇总结果
以便 多个独立诊断任务并行执行，整体耗时由最慢分支决定
```
**AC:**
```gherkin
AC1: Given 节点 A 通过 Send API 发送 3 个并行任务 [B1, B2, B3]
  When 执行 A
  Then B1/B2/B3 并行执行，state 分别独立
AC2: Given B1/B2/B3 全部完成
  When 进入 map-reduce 节点 M
  Then M 收到 3 个子 state 的 results 列表，合并为单一 state
AC3: Given B2 抛异常，B1/B3 成功
  When map-reduce
  Then M 收到 2 个成功结果 + 1 个错误条目，不阻塞汇总
AC4: Given fan-out 任务数超过 maxParallel=10
  When Send API 调用
  Then 抛 ParallelLimitExceededException "max parallel: 10"
AC5: Given 所有并行分支完成超时（30s 内未完成）
  When 超时
  Then TaskStatus=TIMEOUT，已完成分支结果保留在 state
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 定义 | US-1 YAML→StateGraph | 无代码编排 | 编译成功率 100% | - |
| 路由 | US-2 ConditionalEdge | 函数式条件 | 路由准确率 100% | US-1 |
| 执行 | US-3 GraphExecutor DAG | 统一运行时 | 无环校验 100% | US-1 |
| 调试 | US-4 时间旅行 | 任意回放 | 回放成功率 100% | US-3 |
| API | US-5 Checkpoint API | 远程调试 | P95<50ms | US-4 |
| 拓扑 | US-6 Workflow vs ReAct | 消除冗余 | 同一执行循环 | US-3 |
| 组合 | US-7 子图嵌套 | 模块化 | depth<=3 | US-1 |
| 并行 | US-8 Send API | 并发加速 | 并行<=10 | US-3 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | YAML nodes+edges 编译为 StateGraph | P0 | US-1 AC1 | 单元 |
| UC-02 | YAML conditional_edges 编译为 ConditionalEdge | P0 | US-1 AC2 | 单元 |
| UC-03 | YAML 缺 entry 抛异常 | P0 | US-1 AC3 | 单元 |
| UC-04 | YAML 引用不存在节点抛异常 | P0 | US-1 AC4 | 单元 |
| UC-05 | YAML 空 nodes 抛异常 | P1 | US-1 AC5 | 单元 |
| UC-06 | EdgeCondition route 命中 routing key | P0 | US-2 AC1/2 | 单元 |
| UC-07 | EdgeCondition 返回未知 key 默认 END | P0 | US-2 AC3 | 单元 |
| UC-08 | EdgeCondition 抛异常降级 END | P1 | US-2 AC4 | 单元 |
| UC-09 | EdgeCondition 空 routing 表降级 END | P1 | US-2 AC5 | 单元 |
| UC-10 | GraphExecutor 顺序执行 DAG | P0 | US-3 AC1 | 单元 |
| UC-11 | 节点异常 → FAILED + checkpoint | P0 | US-3 AC2 | 单元 |
| UC-12 | 条件边路由成功 | P0 | US-3 AC3 | 单元 |
| UC-13 | DAG 含环 compile 抛异常 | P0 | US-3 AC4 | 单元 |
| UC-14 | maxTurns 超限 TIMEOUT | P1 | US-3 AC5 | 单元 |
| UC-15 | cancel 信号 CANCELLED | P0 | US-3 AC6 | 单元 |
| UC-16 | InterruptException → PAUSED + checkpoint | P1 | US-3 AC7 | 单元 |
| UC-17 | CheckpointStore.list 返回历史 | P0 | US-4 AC1 | 单元 |
| UC-18 | GraphExecutor.resume 从 checkpoint 回放 | P0 | US-4 AC2 | 单元 |
| UC-19 | checkpointId 不存在抛异常 | P0 | US-4 AC3 | 单元 |
| UC-20 | GraphState serialize/deserialize 一致 | P0 | US-4 AC4 | 单元 |
| UC-21 | 回放后路由分叉允许 | P1 | US-4 AC5 | 单元 |
| UC-22 | checkpoint 保存失败不阻塞 | P1 | US-4 AC6 | 单元 |
| UC-23 | GET /runs/{id}/checkpoints 200 | P0 | US-5 AC1 | 集成 |
| UC-24 | GET /runs/{id}/checkpoints 404 | P0 | US-5 AC2 | 集成 |
| UC-25 | GET /runs/{id}/checkpoints 空数组 | P1 | US-5 AC3 | 集成 |
| UC-26 | POST /runs/{id}/replay 202 | P0 | US-5 AC4 | 集成 |
| UC-27 | POST /runs/{id}/replay 缺 checkpointId 400 | P0 | US-5 AC5 | 集成 |
| UC-28 | POST /runs/{id}/replay checkpoint 不属于 404 | P1 | US-5 AC6 | 集成 |
| UC-29 | POST /runs/{id}/replay 原 run 仍在运行 409 | P1 | US-5 AC7 | 集成 |
| UC-30 | ReAct 图允许循环 | P1 | US-6 AC1 | 单元 |
| UC-31 | 工作流图校验无环 | P0 | US-6 AC2 | 单元 |
| UC-32 | 工作流与 ReAct 共用 GraphExecutor | P0 | US-6 AC3 | 单元 |
| UC-33 | 工作流含 agent_node 不循环 | P1 | US-6 AC4 | 单元 |
| UC-34 | Advisor 包裹工作流节点一致 | P2 | US-6 AC5 | 单元 |
| UC-35 | 子图嵌入 SubgraphNode | P1 | US-7 AC1 | 单元 |
| UC-36 | 父图→子图 channel 映射 | P1 | US-7 AC2 | 单元 |
| UC-37 | 子图→父图 channel 回传 | P1 | US-7 AC3 | 单元 |
| UC-38 | 子图异常传播到父图 | P1 | US-7 AC4 | 单元 |
| UC-39 | 子图含环抛异常 | P2 | US-7 AC5 | 单元 |
| UC-40 | 子图深度超 3 抛异常 | P2 | US-7 AC6 | 单元 |
| UC-41 | Send API fan-out 并行执行 | P2 | US-8 AC1 | 单元 |
| UC-42 | map-reduce 汇总并行结果 | P2 | US-8 AC2 | 单元 |
| UC-43 | 并行分支部分失败不阻塞 | P2 | US-8 AC3 | 单元 |
| UC-44 | fan-out 超过 maxParallel 抛异常 | P2 | US-8 AC4 | 单元 |
| UC-45 | 并行分支超时 TIMEOUT | P2 | US-8 AC5 | 单元 |
| UC-R1 | GET /workflows 列出全部工作流定义 | P1 | - | 集成 |
| UC-R2 | GET /workflows/{name} 工作流详情 | P1 | - | 集成 |
| UC-R3 | POST /workflows/{name}/run 执行工作流 | P0 | US-3 | 集成 |

### 3.2 详细用例 (Gherkin)

#### UC-01: YAML nodes+edges 编译为 StateGraph
```gherkin
@priority:high @type:unit
功能: YAML→StateGraph 编译

  场景: 标准 DAG 编译
    Given YAML 含 nodes=[A,B,C] + edges=[A→B, B→C] + entry=A
    When WorkflowYamlCompiler.compile(yaml)
    Then 返回 StateGraph
    And getNodes() 含 3 个 Node
    And getEdgesFrom("A") 含 B
    And getEdgesFrom("B") 含 C
    And getEntryPoint()="A"

  场景: 含条件边编译
    Given YAML 含 conditional_edges=[A→{condition, routing: {ok→B, fail→C}}]
    When compile
    Then StateGraph 含 1 条 ConditionalEdge
    And routing 含 "ok"→"B" 和 "fail"→"C"
```

#### UC-02: YAML 缺 entry 抛异常
```gherkin
@priority:high @type:unit
功能: YAML 缺 entry 校验

  场景: 缺 entry 字段
    Given YAML 含 nodes=[A,B] + edges=[A→B] 但无 entry
    When compile
    Then 抛 WorkflowCompileException
    And 异常消息含 "missing entry point"
```

#### UC-03: YAML 引用不存在节点
```gherkin
@priority:high @type:unit
功能: YAML 节点引用校验

  场景大纲: 引用不存在节点
    Given YAML 含 <edge>，目标节点不在 nodes 列表
    When compile
    Then 抛 WorkflowCompileException "unknown node"
    例子:
      | edge | 说明 |
      | A→X (X 不在 nodes) | 直接边引用 |
      | A→{routing: {ok→Y}} (Y 不在 nodes) | 条件边路由引用 |
```

#### UC-06: EdgeCondition route 命中 routing key
```gherkin
@priority:high @type:unit
功能: ConditionalEdge 路由命中

  场景: route 返回 routing 含的 key
    Given EdgeCondition 实现 `state -> state.get("severity").equals("CRITICAL") ? "critical" : "normal"`
    And routing = {"critical" → "escalate", "normal" → "log"}
    When state["severity"]="CRITICAL" 时路由
    Then 下一节点为 "escalate"

  场景: route 返回 "normal"
    Given 同上 EdgeCondition
    When state["severity"]="NORMAL" 时路由
    Then 下一节点为 "log"
```

#### UC-07: EdgeCondition 返回未知 key 默认 END
```gherkin
@priority:high @type:unit
功能: 未知 route key 降级

  场景: route 返回 routing 不含的 key
    Given EdgeCondition.route 返回 "unknown"
    And routing = {"ok" → "B"}
    When 路由
    Then 默认路由到 END
    And WARN 日志 "unknown route key: unknown, fallback to END"

  场景: 空 routing 表
    Given routing = {}
    When 路由
    Then 路由到 END
    And WARN 日志 "empty routing table"
```

#### UC-08: EdgeCondition 抛异常降级
```gherkin
@priority:medium @type:unit
功能: EdgeCondition 异常降级

  场景: route 抛 RuntimeException
    Given EdgeCondition.route 抛 RuntimeException("NPE")
    When 路由
    Then 异常被 catch + ERROR 日志
    And 路由到 END
    And 图执行不中断（TaskStatus=FAILED）
```

#### UC-10: GraphExecutor 顺序执行 DAG
```gherkin
@priority:high @type:unit
功能: GraphExecutor DAG 顺序执行

  场景: A→B→C 全成功
    Given 工作流 DAG: A→B→C→END，所有节点返回 SUCCEEDED
    When GraphExecutor.execute(compiledGraph, initialState, ctx)
    Then 依次执行 A→B→C→END
    And TaskStatus=SUCCEEDED
    And CheckpointStore.save 被调用 3 次（每节点一次）

  场景: 条件边路由
    Given 工作流含条件边 A→{ok→B, fail→C}
    And 节点 A 返回 state["result"]="ok"
    When execute
    Then 执行 A→B→END（不执行 C）
```

#### UC-11: 节点异常 → FAILED + checkpoint
```gherkin
@priority:high @type:unit
功能: 节点异常处理

  场景: 节点 B 抛 RuntimeException
    Given 工作流 A→B→C
    And 节点 B.execute 抛 RuntimeException("DB error")
    When execute
    Then TaskStatus=FAILED
    And checkpoint 保存 B 失败状态
    And 节点 C 未执行
    And transcript 含 TYPE_ERROR 事件
```

#### UC-13: DAG 含环 compile 抛异常
```gherkin
@priority:high @type:unit
功能: DAG 无环校验

  场景: 含环 A→B→A
    Given YAML 含 nodes=[A,B] + edges=[A→B, B→A] + entry=A
    When compile()
    Then 抛 IllegalGraphException
    And 异常消息含 "cycle detected: A→B→A"

  场景: 自环 A→A
    Given YAML 含 edges=[A→A]
    When compile
    Then 抛 IllegalGraphException "cycle detected: A→A"

  场景: ReAct 循环不校验无环
    Given ReAct 图 agent→tools→agent（循环）
    When ReActGraphFactory.compile()
    Then 不抛异常（ReAct 模式允许循环）
```

#### UC-16: InterruptException → PAUSED + checkpoint
```gherkin
@priority:medium @type:unit
功能: HITL 暂停

  场景: 节点抛 InterruptException
    Given 工作流 A→B→C
    And 节点 B.execute 抛 InterruptException("approval required")
    When execute
    Then TaskStatus=PAUSED
    And checkpoint 保存 B 暂停状态
    And SSE 推送 "paused" 事件
```

#### UC-17: CheckpointStore.list 返回历史
```gherkin
@priority:high @type:unit
功能: checkpoint 历史

  场景: 工作流执行完成含 3 个 checkpoint
    Given 工作流执行完成（A→B→C，3 个 checkpoint）
    When CheckpointStore.list(threadId)
    Then 返回 3 条 CheckpointMetadata
    And 按 turn 升序排列
    And 每条含 checkpointId/turn/nodeName/timestamp/status

  场景: 默认返回最近 100 条
    Given threadId 含 150 个 checkpoint
    When list(threadId)
    Then 返回最近 100 条（按 timestamp 降序截取）
```

#### UC-18: GraphExecutor.resume 从 checkpoint 回放
```gherkin
@priority:high @type:unit
功能: 从 checkpoint 回放

  场景: 从 cp-2 回放继续执行 C
    Given checkpointId="cp-2"（节点 B 执行后）
    When GraphExecutor.resume(compiledGraph, "cp-2", ctx)
    Then 从 cp-2 反序列化 GraphState
    And 继续执行 C→END
    And 新增 checkpoint（cp-4, cp-5...）

  场景: 回放后条件路由分叉
    Given 原 cp-2 state["result"]="ok" 路由到 B
    And 回放前修改 state["result"]="fail"（通过 human.input）
    When resume
    Then 路由到 C（而非 B），不抛异常
```

#### UC-19: checkpointId 不存在抛异常
```gherkin
@priority:high @type:unit
功能: checkpoint 不存在

  场景: resume 不存在 checkpointId
    Given checkpointId="nonexistent"
    When GraphExecutor.resume
    Then 抛 CheckpointNotFoundException
```

#### UC-20: GraphState serialize/deserialize 一致
```gherkin
@priority:high @type:unit
功能: GraphState 序列化

  场景: 序列化→反序列化字段一致
    Given GraphState(threadId="t-1", checkpointId="cp-1", turn=3, values={key1:"v1", key2:42})
    When serialize → byte[] → deserialize
    Then 反序列化 state.getThreadId()="t-1"
    And state.getCheckpointId()="cp-1"
    And state.getTurn()=3
    And state.get("key1")="v1"
    And state.get("key2")=42

  场景: 不可变性 — with 返回新实例
    Given state1 turn=3
    When state2 = state1.with("key", "value")
    Then state1 != state2（不同实例）
    And state1.get("key")=null（原实例不变）
    And state2.get("key")="value"
```

#### UC-22: checkpoint 保存失败不阻塞
```gherkin
@priority:medium @type:unit
功能: checkpoint 降级

  场景: 存储异常
    Given CheckpointStore.save 抛 RuntimeException
    When execute
    Then 图执行不中断
    And WARN 日志 "checkpoint save failed, degraded"
    And TaskStatus 不变为 FAILED（checkpoint 失败不阻塞主流程）
```

#### UC-23: GET /runs/{id}/checkpoints 200
```gherkin
@priority:high @type:integration
功能: checkpoint 列表 API

  场景: 返回 checkpoint 列表
    Given 工作流 taskId="run-001" 含 3 个 checkpoint
    When GET /runs/run-001/checkpoints
    Then 返回 200
    And JSON 数组长度=3
    And 每条含 {checkpointId, turn, nodeName, timestamp, status}

  场景: 空列表
    Given taskId="run-002" 无 checkpoint
    When GET /runs/run-002/checkpoints
    Then 返回 200 + 空数组

  场景: taskId 不存在
    When GET /runs/nonexistent/checkpoints
    Then 返回 404
```

#### UC-26: POST /runs/{id}/replay 202
```gherkin
@priority:high @type:integration
功能: 回放 API

  场景: 触发回放
    Given taskId="run-001" + checkpointId="cp-2"
    When POST /runs/run-001/replay {"checkpointId":"cp-2"}
    Then 返回 202 + 新 taskId="run-002"
    And 新任务从 cp-2 继续执行

  场景: 缺 checkpointId
    When POST /runs/run-001/replay {}
    Then 返回 400 "missing checkpointId"

  场景: checkpoint 不属于该 run
    When POST /runs/run-001/replay {"checkpointId":"other-run-cp"}
    Then 返回 404 "checkpoint not found"

  场景: 原 run 仍在运行
    Given taskId="run-001" status=RUNNING
    When POST /runs/run-001/replay
    Then 返回 409 "original run still running"
```

#### UC-31: 工作流图校验无环
```gherkin
@priority:high @type:unit
功能: 工作流 DAG 校验

  场景: 工作流含环抛异常
    Given 工作流 YAML edges=[A→B, B→A]
    When WorkflowYamlCompiler.compile
    Then 抛 IllegalGraphException

  场景: ReAct 图允许循环
    Given ReAct 图 agent↔tools
    When ReActGraphFactory.compile
    Then 不抛异常（ReAct 模式不校验无环）
```

#### UC-32: 工作流与 ReAct 共用 GraphExecutor
```gherkin
@priority:high @type:unit
功能: 统一运行时

  场景: 同一 GraphExecutor 执行两种图
    Given ReAct 图 (entry→agent↔tools→END)
    And 工作流图 (A→B→C→END)
    When 同一 GraphExecutor 实例执行两者
    Then 节点执行/checkpoint/错误处理/cancel/maxTurns 逻辑完全一致
    And 仅拓扑不同（循环 vs DAG）
```

#### UC-35: 子图嵌入 SubgraphNode
```gherkin
@priority:medium @type:unit
功能: 子图组合

  场景: 父图嵌入子图
    Given 父图含节点 "diagnose" 嵌入子图 subGraph (A→B→END)
    When compile 父图
    Then "diagnose" 节点为 SubgraphNode
    And 父图执行到 "diagnose" 时委托子图执行
    And 子图 A→B→END 完成后返回父图

  场景: channel 映射父→子
    Given 父图 channel="parent.input" → 子图 channel="child.input"
    And 父图 state["parent.input"]="data"
    When 子图执行
    Then 子图 state["child.input"]="data"

  场景: channel 回传子→父
    Given 子图执行完成 state["child.output"]="result"
    When 子图返回
    Then 父图 state["parent.output"]="result"
```

#### UC-38: 子图异常传播
```gherkin
@priority:medium @type:unit
功能: 子图异常传播

  场景: 子图节点异常传播到父图
    Given 子图节点 B.execute 抛 RuntimeException
    When 子图执行
    Then 异常传播到父图
    And 父图按 onFailure 处理（TaskStatus=FAILED）

  场景: 子图深度超限
    Given 嵌套深度=4（超过 max=3）
    When 执行
    Then 抛 SubgraphDepthExceededException "max depth: 3"
```

#### UC-41: Send API fan-out 并行执行
```gherkin
@priority:low @type:unit
功能: 并行 fan-out

  场景: fan-out 3 个并行分支
    Given 节点 A 通过 Send API 发送 3 个并行任务 [B1, B2, B3]
    When 执行 A
    Then B1/B2/B3 并行执行
    And state 分别独立

  场景: map-reduce 汇总
    Given B1/B2/B3 全部完成
    When 进入 map-reduce 节点 M
    Then M 收到 3 个子 state 的 results 列表
    And 合并为单一 state

  场景: 部分分支失败不阻塞
    Given B2 抛异常，B1/B3 成功
    When map-reduce
    Then M 收到 2 个成功结果 + 1 个错误条目
    And 不阻塞汇总

  场景: 超过 maxParallel 抛异常
    Given fan-out 任务数=11（超过 maxParallel=10）
    When Send API 调用
    Then 抛 ParallelLimitExceededException "max parallel: 10"
```

---

## 4. 接口规格

```java
// StateGraph — 替代旧 WorkflowDefinition/WorkflowStep
public class StateGraph {
    public StateGraph addNode(String name, Node node);
    public StateGraph addEdge(String from, String to);
    public StateGraph addConditionalEdges(String from, EdgeCondition condition, Map<String, String> routing);
    public StateGraph addSubgraph(String name, StateGraph subGraph, Map<String, String> channelMapping);
    public StateGraph setEntryPoint(String entry);
    public CompiledGraph compile();  // 验证图完整性 + 无环校验（工作流模式）
}

// Edge + ConditionalEdge — 替代旧正则条件 DSL
public class Edge {
    private final String from;
    private final String to;
}

public class ConditionalEdge {
    private final String from;
    private final EdgeCondition condition;
    private final Map<String, String> routing;
}

@FunctionalInterface
public interface EdgeCondition {
    String route(GraphState state);
}

// GraphExecutor — 与 ReAct 共用
public class GraphExecutor {
    private final CheckpointStore checkpointStore;
    private final int maxTurns;

    public TaskResult execute(CompiledGraph graph, GraphState state, ExecutionContext ctx);
    public TaskResult resume(CompiledGraph graph, String checkpointId, ExecutionContext ctx);
}

// CheckpointStore — 时间旅行调试
public interface CheckpointStore {
    String save(String threadId, GraphState state);
    GraphState load(String checkpointId);
    List<CheckpointMetadata> list(String threadId);
    void delete(String checkpointId);
    void deleteByThread(String threadId);
}

// GraphState — 不可变，with() 返回新实例
public class GraphState {
    private final Map<String, Object> values;
    private final String threadId;
    private final String checkpointId;
    private final int turn;

    public <T> T get(String key);
    public <T> T get(String key, T defaultValue);
    public GraphState with(String key, Object value);
    public GraphState nextTurn();
    public byte[] serialize();
    public static GraphState deserialize(byte[] data);
}

// SubgraphNode — 子图组合
public class SubgraphNode implements Node {
    private final StateGraph subGraph;
    private final Map<String, String> channelMapping;  // 父 channel → 子 channel
    // execute: 传入映射后 state，执行子图，回传 channel
}

// Send API — 并行 fan-out
public class Send {
    public static Send of(String nodeName, Map<String, Object> stateOverrides);
}
// 节点返回 List<Send> 触发 fan-out，map-reduce 节点接收 List<GraphState>
```

REST 端点:
```yaml
GET /runs/{id}/checkpoints:
  200: [{checkpointId, turn, nodeName, timestamp, status}]
  404: taskId 不存在
POST /runs/{id}/replay:
  request: {checkpointId: String}
  202: {newTaskId: String}  # 新任务从 checkpoint 回放
  400: 缺 checkpointId
  404: checkpoint 不属于该 run
  409: 原 run 仍在运行
GET /workflows:
  200: [{name, description, entry, nodeCount}]
GET /workflows/{name}:
  200: {name, description, nodes, edges, conditional_edges, entry}
  404: 工作流不存在
POST /workflows/{name}/run:
  request: {inputs: Map<String,Object>}
  202: {taskId: String}
```

---

## 5. 数据规格

```yaml
实体: GraphState (不可变)
字段: values(Map<String,Object>, ConcurrentHashMap) | threadId(String) | checkpointId(String) | turn(int)
约束: with() 返回新实例（深拷贝 values） | serialize 返回 byte[] | deserialize 严格还原所有字段

实体: ConditionalEdge
字段: from(String) | condition(EdgeCondition 函数式接口) | routing(Map<String,String>, key=route结果, value=节点名)
约束: routing 为空时降级 END | route 返回未知 key 时降级 END

实体: CheckpointMetadata
字段: checkpointId(String) | threadId(String) | turn(int) | nodeName(String) | timestamp(long) | status(TaskStatus)
约束: list 默认返回最近 100 条 | 按 timestamp 降序

实体: CompiledGraph
字段: entryPoint(String) | nodes(Map<String,Node>) | edges(Map<String,List<EdgeTarget>>)
约束: compile 时校验节点引用完整性 + 无环（工作流模式）

实体: SubgraphNode
字段: subGraph(StateGraph) | channelMapping(Map<String,String>, 父 channel → 子 channel)
约束: 嵌套深度 <= 3 | 子图也校验无环

实体: Send
字段: nodeName(String) | stateOverrides(Map<String,Object>)
约束: fan-out 任务数 <= maxParallel=10 | 超时 30s
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 | 行为 |
|--------|------|------|------|
| WORKFLOW_COMPILE_ERROR | ERROR | YAML 编译失败（缺 entry/引用不存在节点/空 nodes） | 抛 WorkflowCompileException |
| ILLEGAL_GRAPH_CYCLE | ERROR | 工作流 DAG 含环 | 抛 IllegalGraphException |
| EDGE_CONDITION_UNKNOWN_KEY | WARN | EdgeCondition.route 返回未知 key | 降级 END |
| EDGE_CONDITION_EMPTY_ROUTING | WARN | routing 表为空 | 降级 END |
| EDGE_CONDITION_EXCEPTION | ERROR | EdgeCondition.route 抛异常 | 降级 END + TaskStatus=FAILED |
| CHECKPOINT_NOT_FOUND | ERROR | resume 时 checkpointId 不存在 | 抛 CheckpointNotFoundException |
| CHECKPOINT_SAVE_FAILED | WARN | 存储异常 | 不阻塞图执行，degraded 模式 |
| SUBGRAPH_DEPTH_EXCEEDED | ERROR | 子图嵌套深度 > 3 | 抛 SubgraphDepthExceededException |
| PARALLEL_LIMIT_EXCEEDED | ERROR | fan-out 任务数 > 10 | 抛 ParallelLimitExceededException |
| NODE_EXECUTION_FAILED | ERROR | 节点抛 RuntimeException | TaskStatus=FAILED + checkpoint |
| NODE_INTERRUPTED | INFO | 节点抛 InterruptException | TaskStatus=PAUSED + checkpoint |
| MAX_TURNS_EXCEEDED | INFO | 工作流执行 turn > maxTurns | TaskStatus=TIMEOUT |
| CANCEL_SIGNAL | INFO | ctx.isCancelled()=true | TaskStatus=CANCELLED |

```gherkin
场景: EdgeCondition 返回未知 key
  Given EdgeCondition.route 返回 "unknown"
  When 路由
  Then 默认路由到 END + WARN 日志
  And 不抛异常

场景: checkpoint 保存失败
  Given CheckpointStore.save 抛 RuntimeException
  When execute
  Then 图执行不中断
  And WARN 日志 "checkpoint save failed, degraded"
  And TaskStatus 不变为 FAILED

场景: 子图异常传播
  Given 子图节点 B 抛 RuntimeException
  When 子图执行
  Then 异常传播到父图
  And 父图 TaskStatus=FAILED
```

---

## 7. 非功能需求

```yaml
性能: checkpoint list P95<50ms | GraphState.serialize/deserialize <10ms | DAG 编译 <100ms | 并行 fan-out 调度开销<5ms
可测试性: 核心覆盖>80% | StateGraph/ConditionalEdge/GraphExecutor/CheckpointStore 全部可 Mock | 子图独立测试
可靠性: checkpoint 保存失败不阻塞主流程 | EdgeCondition 异常降级 END | 并行分支部分失败不阻塞 map-reduce
可观测性: 每节点执行 log INFO | 条件路由 log DEBUG | 异常 log ERROR | checkpoint 保存失败 log WARN
```

---

## 8. 测试策略

### 8.1 已有测试覆盖

| 测试文件 | 类型 | 覆盖用例 |
|----------|------|----------|
| (2.x 重构后旧 `SimpleWorkflowEngineTest`/`YamlWorkflowLoaderTest`/`WorkflowStepTest` 等已废弃，新测试待编写) | - | - |

**总结**: 2.x 架构重构删除旧 `SimpleWorkflowEngine`/`WorkflowDefinition`/`WorkflowStep`/正则条件 DSL，所有 2.x 测试为新增。优先实现 UC-01~22 (单元) 和 UC-23~29 (集成) 和 UC-R1~3 (集成)。子图组合 (UC-35~40) 和并行 Send API (UC-41~45) 为 P2 优先级。

### 8.2 E2E 关键路径

| 路径ID | 关键路径 | 端点 | 状态 |
|--------|----------|------|------|
| E2E-1 | 工作流列表: GET /workflows → 200 (workflow 列表) | GET /workflows | ⚠未实现 (GAP-8) |
| E2E-2 | 工作流详情: GET /workflows/{name} → 200 (nodes/edges) / 404 | GET /workflows/{name} | ⚠未实现 (GAP-9) |
| E2E-3 | 工作流执行: POST /workflows/{name}/run → 202 → GET /runs/{id}/stream (SSE) | POST /workflows/{name}/run | ⚠未实现 (GAP-10) |
| E2E-4 | checkpoint 历史: GET /runs/{id}/checkpoints → 200 (列表) | GET /runs/{id}/checkpoints | ⚠未实现 (GAP-11) |
| E2E-5 | 回放: POST /runs/{id}/replay → 202 → 新任务执行 | POST /runs/{id}/replay | ⚠未实现 (GAP-12) |
| E2E-6 | 条件路由: POST /workflows/{name}/run → SSE 含条件分支执行路径 | POST /workflows/{name}/run | ⚠未实现 (GAP-13) |
| E2E-7 | 子图组合: POST /workflows/{name}/run (含子图) → SSE 含子图执行 | POST /workflows/{name}/run | ⚠未实现 (GAP-14) |
| E2E-8 | 并行 fan-out: POST /workflows/{name}/run (含 Send) → SSE 含并行执行 | POST /workflows/{name}/run | ⚠未实现 (GAP-15) |

### 8.3 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | `WorkflowYamlCompiler.compile` nodes+edges+entry+条件边+校验 无单测 | P0 | UC-01~05 |
| GAP-2 | `ConditionalEdge`/`EdgeCondition` route 命中/未知 key/异常/空 routing 无单测 | P0 | UC-06~09 |
| GAP-3 | `GraphExecutor.execute` DAG 顺序/条件边/异常/无环/maxTurns/cancel/Interrupt 无单测 | P0 | UC-10~16 |
| GAP-4 | DAG 无环校验（含环抛异常）+ ReAct 允许循环 无单测 | P0 | UC-13/31 |
| GAP-5 | `CheckpointStore.list` 历史+默认 100 条+排序 无单测 | P0 | UC-17 |
| GAP-6 | `GraphExecutor.resume` 从 checkpoint 回放+路由分叉 无单测 | P0 | UC-18/21 |
| GAP-7 | `CheckpointNotFoundException` + checkpoint 不存在 无单测 | P0 | UC-19 |
| GAP-8 | E2E缺失: GET /workflows REST 端点无 E2E — 见 E2E-1 | P1 | 需 E2E 集成测试 |
| GAP-9 | E2E缺失: GET /workflows/{name} 200/404 无 E2E — 见 E2E-2 | P1 | 需 E2E 集成测试 |
| GAP-10 | E2E缺失: POST /workflows/{name}/run REST 端点无 E2E — 见 E2E-3 | P0 | 需 E2E 集成测试 |
| GAP-11 | E2E缺失: GET /runs/{id}/checkpoints REST 端点无 E2E — 见 E2E-4 | P0 | 需 E2E 集成测试 |
| GAP-12 | E2E缺失: POST /runs/{id}/replay REST 端点无 E2E — 见 E2E-5 | P0 | 需 E2E 集成测试 |
| GAP-13 | E2E缺失: 条件路由执行路径无 E2E — 见 E2E-6 | P1 | 需 E2E 集成测试 |
| GAP-14 | E2E缺失: 子图组合执行无 E2E — 见 E2E-7 | P2 | 需 E2E 集成测试 |
| GAP-15 | E2E缺失: 并行 fan-out 执行无 E2E — 见 E2E-8 | P2 | 需 E2E 集成测试 |
| GAP-16 | `GraphState.serialize/deserialize` 一致性 + 不可变 `with()` 无单测 | P0 | UC-20 |
| GAP-17 | checkpoint 保存失败不阻塞主流程 无单测 | P1 | UC-22 |
| GAP-18 | 工作流与 ReAct 共用 GraphExecutor 行为一致 无单测 | P0 | UC-32 |
| GAP-19 | 工作流含 agent_node 不进入循环 无单测 | P1 | UC-33 |
| GAP-20 | `SubgraphNode` 嵌入+channel 映射+异常传播+深度限制 无单测 | P1/P2 | UC-35~40 |
| GAP-21 | `Send` API fan-out+map-reduce+部分失败+maxParallel+超时 无单测 | P2 | UC-41~45 |
| GAP-22 | `CompiledGraph` 节点引用完整性校验 无单测 | P1 | UC-03/04 |
| GAP-23 | GET /runs/{id}/checkpoints 200/404/空数组 无集成测试 | P0 | UC-23~25 |
| GAP-24 | POST /runs/{id}/replay 202/400/404/409 无集成测试 | P0 | UC-26~29 |

### 8.4 Mock 策略
```yaml
Mock: Node(匿名 lambda), EdgeCondition(lambda), CheckpointStore(匿名内存实现), ExecutionContext(Mockito)
文件: @TempDir 创建临时 .yml 工作流文件
图: 使用真实 StateGraph + CompiledGraph，避免 Mock 图结构
checkpoint: 使用 InMemoryCheckpointStore (HashMap 实现)，避免真实 SQLite/Redis 依赖
并行: 使用 CountDownLatch 确保并行分支同步，验证 map-reduce 汇总
子图: 真实 SubgraphNode + 真实子 StateGraph，验证 channel 映射
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| snap-agent-core graph/ SPI (StateGraph/Node/Edge/ConditionalEdge/CompiledGraph) | 2.x 新增 | 无 |
| snap-agent-core execution/ (GraphExecutor/InterruptException) | 2.x 新增 | 无 |
| snap-agent-core checkpoint/ (CheckpointStore SPI) | 2.x 新增 | 存储失败时 degraded 模式 |
| snap-agent-spring-boot-2x-starter checkpoint/ (SqliteCheckpointStore/RedisCheckpointStore) | starter 层提供 | 缺失时 snap-agent.checkpoint.type=sqlite |
| SnakeYAML | Spring Boot 自带 | 解析异常跳过文件 |
| LlmClient (工作流含 agent_node 时) | 已完成 | 工作流可不依赖 LLM |
| SkillRegistry (工作流引用 skill 时) | 已完成 | 未找到按 onFailure |

---

## 10. 可观测性设计

```yaml
日志: INFO "workflow compiled: nodes={}, edges={}, conditional_edges={}" | INFO "node {} executed in {}ms, status={}"
  DEBUG "conditional edge from {} routed to {} (key={})" | WARN "unknown route key: {}, fallback to END"
  WARN "checkpoint save failed, degraded" | ERROR "node {} failed: {}"
指标: workflow_compile_total / workflow_compile_latency_seconds / workflow_node_execute_total
  / workflow_conditional_route_total / checkpoint_save_total / checkpoint_list_total
  / checkpoint_resume_total / subgraph_depth_max / send_parallel_count
追踪: MicrometerObservationAdvisor span "snap-agent.workflow.compile" / "snap-agent.workflow.execute"
  / "snap-agent.checkpoint.list" / "snap-agent.checkpoint.replay"
```

---

## 11. 原型与交互参考

| 状态 | 表现 | 说明 |
|------|------|------|
| 工作流列表 | 工作流名+节点数+入口 | GET /workflows 驱动 |
| 工作流详情 | 节点+边+条件边图 | GET /workflows/{name} 驱动 |
| 执行中 | SSE 推送节点执行事件 | thought/tool_call/tool_result/done |
| checkpoint 列表 | 时间轴+节点名+状态 | GET /runs/{id}/checkpoints 驱动 |
| 回放 | 从 checkpoint 重新执行 | POST /runs/{id}/replay 触发 |
| 条件路由 | 图中高亮实际路径 | SSE 含 routing key |
| 子图 | 嵌套折叠展开 | SubgraphNode 执行时展开 |
| 并行 fan-out | 分支扇出图 | Send API 触发 |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 2.0 | 2026-07-23 | Team | 初始 TDD 规格 (SimpleWorkflowEngine + 正则条件 DSL) |
| 3.0 | 2026-07-25 | Team | 2.x 重构: 删除 SimpleWorkflowEngine/WorkflowDefinition/WorkflowStep/正则条件 DSL，替换为 StateGraph (YAML→图) + ConditionalEdge (EdgeCondition 函数式接口 + Map 路由) + GraphExecutor (DAG) + CheckpointStore 时间旅行 + 子图组合 + Send API 并行；新增 GET /runs/{id}/checkpoints 和 POST /runs/{id}/replay REST 端点 |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` (Section 1 Graph Runtime, Section 3 Controller API/Checkpoint, Section 4 Anchor 工作流图模式)
- `docs/tdd/TEMPLATE.md`
- `docs/tdd/01-agent-engine/TDD_SPEC.md` (GraphExecutor 共用执行循环)
- `snap-agent-core/.../graph/` (StateGraph, Node, Edge, ConditionalEdge, CompiledGraph — 2.x 新增)
- `snap-agent-core/.../execution/` (GraphExecutor, InterruptException — 2.x 新增)
- `snap-agent-core/.../checkpoint/` (CheckpointStore, CheckpointMetadata — 2.x 新增)
- `snap-agent-spring-boot-2x-starter/.../workflow/` (WorkflowYamlCompiler — 2.x 新增)
- `snap-agent-spring-boot-2x-starter/.../checkpoint/` (SqliteCheckpointStore, RedisCheckpointStore — 2.x 新增)

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| StateGraph | 图结构（节点+边+条件边+入口），替代旧 WorkflowDefinition/WorkflowStep |
| Node | 图节点接口，execute(state, ctx) → 新 state |
| Edge | 直接边（from→to） |
| ConditionalEdge | 条件边（from + EdgeCondition + Map<String,String> routing），替代旧正则条件 DSL |
| EdgeCondition | 函数式接口，`String route(GraphState state)` 返回 routing key |
| GraphExecutor | 图执行器，与 ReAct 共用，支持 DAG（工作流）和循环（ReAct）两种拓扑 |
| CompiledGraph | 编译后的图（验证完整性+无环），提供 getEntryPoint/getEdgesFrom/getNodes |
| GraphState | 不可变图状态，with() 返回新实例，支持 serialize/deserialize |
| CheckpointStore | checkpoint 存储 SPI（save/load/list/delete） |
| CheckpointMetadata | checkpoint 元数据（checkpointId/turn/nodeName/timestamp/status） |
| 时间旅行调试 | 通过 checkpoint 历史回放任意节点后续执行 |
| SubgraphNode | 子图节点，嵌入子 StateGraph，通过 channel 与父图通信 |
| Send API | 并行 fan-out API，节点返回 List<Send> 触发并行分支 |
| map-reduce | 汇总并行分支结果的节点 |
| WorkflowYamlCompiler | YAML→StateGraph 编译器 |
| IllegalGraphException | 图非法异常（含环/引用不存在节点） |
| WorkflowCompileException | YAML 编译异常（缺 entry/空 nodes） |
| DAG | 有向无环图，工作流默认拓扑 |
| maxTurns | 图执行最大轮数（默认 50，防失控） |
| maxParallel | Send API 最大并行数（默认 10） |
| max depth | 子图嵌套最大深度（默认 3） |
