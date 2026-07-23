# TDD需求规格说明书 — Agent 引擎

> 版本: 2.0
> 适用: AI辅助开发 + TDD流程

---

## 1. 需求元信息

```yaml
需求ID: REQ-AGENT-ENGINE
需求名称: AgentExecutor 多轮循环与 SSE 流式推送
优先级: P0
迭代: Sprint 1
负责人: snap-agent team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: 诊断 agent 需要在专用线程池中执行多轮 LLM 调用循环，解析 tool_use 事件并分发工具、回填结果，最终输出结构化诊断报告。
- **用户价值**: 实时流式推送 thought/tool_call/tool_result，用户无需等待全部完成即可看到诊断进展，体验提升 80%。
- **成功指标**: 单轮 LLM 调用到 SSE 推送延迟 < 500ms；任务完成率 > 95%。

### 1.2 范围边界
- **包含**: AgentExecutor 循环、TurnCollector 事件收集、停止条件 (end_turn/max_turns/max_tokens/error/cancel)、system prompt 构建顺序、TaskStore/AgentTask/TranscriptEvent、RateLimiter 限流。
- **不包含**: LLM 客户端实现 (LlmClient)、工具分发器实现 (ToolDispatcher)、SSE controller 层。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 | 负责人 |
|--------|------|------|------|----------|--------|
| R1 | max-turns=20 对复杂 skill 误伤 | 中 | 中 | yml 可调大，默认 20 为成本折中 | team |
| R2 | 线程池 max=4 时 4 个长任务占满导致 429 | 低 | 中 | 可调 executor 池大小 | team |
| R3 | 失控 agent 产大量事件撑爆内存 | 低 | 高 | transcript 事件上限 500 + max-turns + max-result-rows 三重兜底 | team |

**关键假设**:
- 假设1: LLM 客户端的 stream 方法是阻塞 IO，占一个工作线程直到 turn 结束。
- 假设2: TaskStore 为内存级 (ConcurrentHashMap)，重启丢失可接受 (诊断是即时的)。

---

## 2. 用户故事 (User Stories)

### US-1: 多轮诊断循环
```gherkin
As a diagnostic user
I want the agent to execute multiple turns of LLM calls with tool dispatch
So that complex diagnoses can probe multiple layers before reaching a root cause
```

**验收标准 (AC):**
```gherkin
AC1: 单轮 end_turn 正常结束
  Given LLM 在第 0 轮返回 stop_reason="end_turn"
  When execute 完成后
  Then task.status == SUCCEEDED
  And transcript 最后一个事件类型为 "done"
  And report 包含 LLM 最终文本

AC2: 多轮 tool_use 后 end_turn
  Given LLM 第 0 轮返回 tool_use，第 1 轮返回 end_turn
  When execute 完成后
  Then task.status == SUCCEEDED
  And transcript 包含 thought → tool_call → tool_result → done 序列
```

### US-2: 停止条件与超时
```gherkin
As a system operator
I want the agent to stop when max-turns is reached or LLM errors occur
So that runaway agents do not consume unbounded resources
```

**验收标准 (AC):**
```gherkin
AC1: max-turns 触发 TIMEOUT
  Given maxTurns=3 且 LLM 每轮返回 tool_use (不 end_turn)
  When 第 3 轮完成后
  Then task.status == TIMEOUT
  And report 包含 "max-turns"

AC2: LLM 异常触发 FAILED
  Given LLM stream 抛出 RuntimeException
  When 异常被捕获后
  Then task.status == FAILED
  And transcript 包含 TYPE_ERROR 事件
```

### US-3: max_tokens 截断续传
```gherkin
As a diagnostic user
I want the agent to continue when LLM output is truncated by max_tokens
So that partial responses are not lost
```

**验收标准 (AC):**
```gherkin
AC1: max_tokens 无 tool_use 时继续
  Given 第 0 轮 stop_reason="max_tokens" 无 tool_use
  When 第 1 轮返回 end_turn
  Then task.status == SUCCEEDED
  And report 包含第 1 轮文本

AC2: max_tokens 持续触发达 max-turns
  Given 每轮均返回 max_tokens 且不 end_turn
  When 达到 maxTurns 后
  Then task.status == TIMEOUT
```

### US-4: 取消机制
```gherkin
As a user
I want to cancel a running task
So that I can stop a wrong diagnosis early
```

**验收标准 (AC):**
```gherkin
AC1: 执行前取消
  Given task.status == CANCELLED (在 execute 调用前设置)
  When execute 被调用
  Then 不调用 LLM
  And task.status 保持 CANCELLED

AC2: 轮间取消
  Given 第 0 轮 tool dispatch 时 task 被设为 CANCELLED
  When 第 0 轮 tool 返回后进入下一轮检查
  Then task.status 保持 CANCELLED
  And transcript 不包含 TYPE_ERROR
```

### US-5: System Prompt 构建顺序
```gherkin
As a security-conscious developer
I want the read-only prefix to always precede skill body and user context
So that skill authors cannot inject write operations
```

**验收标准 (AC):**
```gherkin
AC1: 只读前缀在最前
  Given 一个有 skill body 和 inputs 的 task
  When buildSystemPrompt 被调用
  Then prompt 以 "你是只读诊断 agent" 开头
  And prompt 包含 skill name 和 body
  And prompt 不包含 input 值 (值在 user message 中)
  And prompt 不包含工具名 (工具在 tools 数组中)

AC2: 输入值在 user message 中
  Given inputs={skuCode:A001, env:sit}
  When buildInputMessage 被调用
  Then 结果包含 <user_inputs> 标签
  And 结果包含 "skuCode=A001" 和 "env=sit"
```

### US-6: 限流防护
```gherkin
As a system operator
I want per-user concurrency and hourly rate limits
So that a single user cannot exhaust the thread pool or LLM quota
```

**验收标准 (AC):**
```gherkin
AC1: 并发限制
  Given maxConcurrentPerUser=1
  When 用户 u1 首次 tryAcquire
  Then 返回 true
  When 用户 u1 第二次 tryAcquire
  Then 返回 false

AC2: 被拒回滚
  Given tryAcquire 成功后线程池拒绝
  When releaseRejected 被调用
  Then concurrentCount 归零且 hourlyCount 归零
```

---

## 2.5 用户故事地图

| 用户阶段 | 用户故事 | 价值目标 | 衡量指标 | 依赖关系 |
|----------|----------|----------|----------|----------|
| 启动 | US-6 限流 | 防止资源耗尽 | 429 请求率 < 5% | - |
| 执行 | US-1 多轮循环 | 复杂诊断能力 | 任务完成率 > 95% | US-6 |
| 容错 | US-2/US-3 停止与续传 | 资源安全 | 无 OOM | US-1 |
| 控制 | US-4 取消 | 用户可控 | 取消响应 < 1s | US-1 |
| 安全 | US-5 Prompt 顺序 | 注入防护 | 0 次 prompt injection | US-1 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 用例名称 | 优先级 | 对应AC | 测试类型 |
|--------|----------|--------|--------|----------|
| UC-01 | 单轮 end_turn 成功 | P0 | US-1 AC1 | 单元 |
| UC-02 | 多轮 tool_use + end_turn | P0 | US-1 AC2 | 单元 |
| UC-03 | max-turns 超限 TIMEOUT | P0 | US-2 AC1 | 单元 |
| UC-04 | LLM 异常 FAILED | P0 | US-2 AC2 | 单元 |
| UC-05 | LLM 报错 FAILED | P0 | US-2 AC2 | 单元 |
| UC-06 | 工具异常不中断循环 | P0 | US-2 | 单元 |
| UC-07 | max_tokens 续传成功 | P0 | US-3 AC1 | 单元 |
| UC-08 | max_tokens 持续达 TIMEOUT | P1 | US-3 AC2 | 单元 |
| UC-09 | 执行前取消 | P0 | US-4 AC1 | 单元 |
| UC-10 | 轮间取消 | P0 | US-4 AC2 | 单元 |
| UC-11 | LLM 异常后保持 CANCELLED | P0 | US-4 | 单元 |
| UC-12 | System prompt 顺序 | P0 | US-5 AC1 | 单元 |
| UC-13 | 输入值在 user message | P0 | US-5 AC2 | 单元 |
| UC-14 | 并发与小时限流 | P0 | US-6 | 单元 |
| UC-15 | 多 extender 追加 | P1 | US-5 | 单元 |

### 3.2 详细用例 (Gherkin格式)

#### UC-01: 单轮 end_turn 成功
```gherkin
@priority:high @type:unit
Feature: Single-turn end_turn succeeds

  Scenario: LLM returns end_turn on first turn
    Given a mock LlmClient that emits thought "诊断完成" then stop "end_turn"
    And a SkillMeta with name "test-skill" and tools [mysql_query]
    And an AgentTask with userId "user-1" and inputs {skuCode=A001}
    When AgentExecutor.execute(task, skill) completes
    Then task.status should be SUCCEEDED
    And task.report should contain "诊断完成"
    And transcript last event type should be "done"
    And taskStore should contain the task
```

#### UC-02: 多轮 tool_use + end_turn
```gherkin
@priority:high @type:unit
Feature: Multi-turn tool_use then end_turn

  Scenario: LLM calls tool in turn 0, then end_turn in turn 1
    Given turn 0: thought "查数据" + tool_use(id=toolu_01, name=mysql_query, input={sql:SELECT 1}) + stop "tool_use"
    And turn 1: thought "完成" + stop "end_turn"
    And mysqlProvider returns ToolResult.success("1", 1, 10ms)
    When execute completes
    Then task.status should be SUCCEEDED
    And transcript should contain events: thought, tool_call, tool_result, done
    And auditRecords should have size 1
    And the second LlmRequest should contain assistant message with tool_use block
```

#### UC-03: max-turns 超限
```gherkin
@priority:high @type:unit
Feature: Max turns reached triggers TIMEOUT

  Scenario: LLM never ends, always returns tool_use
    Given maxTurns=3
    And every turn returns thought + tool_use + stop "tool_use"
    And mysqlProvider returns success
    When execute completes
    Then task.status should be TIMEOUT
    And task.report should contain "max-turns"
```

#### UC-04: LLM 异常 FAILED
```gherkin
@priority:high @type:unit
Feature: LLM exception triggers FAILED

  Scenario: LLM stream throws RuntimeException
    Given LlmClient.stream throws RuntimeException("LLM connection timeout")
    When execute completes
    Then task.status should be FAILED
    And transcript should contain TYPE_ERROR event
```

#### UC-06: 工具异常不中断循环
```gherkin
@priority:high @type:unit
Feature: Tool exception does not break loop

  Scenario: Tool provider throws RuntimeException during dispatch
    Given turn 0: tool_use + stop "tool_use"
    And mysqlProvider.execute throws RuntimeException("DB connection failed")
    And turn 1: thought "工具出错了" + stop "end_turn"
    When execute completes
    Then task.status should be SUCCEEDED
    And transcript should contain TYPE_TOOL_RESULT event
```

#### UC-07: max_tokens 续传成功
```gherkin
@priority:high @type:unit
Feature: max_tokens continuation

  Scenario: max_tokens on turn 0, end_turn on turn 1
    Given turn 0: thought "正在分析" + stop "max_tokens" (no tool_use)
    And turn 1: thought "诊断完成。" + stop "end_turn"
    When execute completes
    Then task.status should be SUCCEEDED
    And task.report should contain "诊断完成"
```

#### UC-09: 执行前取消
```gherkin
@priority:high @type:unit
Feature: Cancel before execution

  Scenario: Task cancelled before execute is called
    Given task.status == CANCELLED
    When execute is called
    Then no LLM interaction should occur
    And task.status should remain CANCELLED
```

#### UC-10: 轮间取消
```gherkin
@priority:high @type:unit
Feature: Cancel between turns

  Scenario: Task cancelled during tool dispatch in turn 0
    Given turn 0: tool_use + stop "tool_use"
    And during tool dispatch, task.status is set to CANCELLED
    When the between-turn check runs
    Then task.status should remain CANCELLED
    And transcript should NOT contain TYPE_ERROR event
```

#### UC-12: System prompt 构建顺序
```gherkin
@priority:high @type:unit
Feature: System prompt build order

  Scenario: Prompt contains read-only prefix, skill body, userId, extender
    Given skill with body "WHERE sku_code='{skuCode}'"
    And inputs {skuCode=A001}
    And a SystemPromptExtender returning "## 项目结构\n模块: core"
    When buildSystemPrompt is called
    Then prompt should start with "你是只读诊断 agent"
    And prompt should contain "test-skill"
    And prompt should NOT contain "A001"
    And prompt should NOT contain "mysql_query"
    And prompt should contain "{skuCode}" (placeholder kept)
    And prompt should contain "user_inputs" instruction
    And "user-1" should appear before "项目结构"
```

#### UC-14: 限流并发与小时限制
```gherkin
@priority:high @type:unit
Feature: Rate limiter concurrency and hourly limits

  Scenario: Concurrency limit blocks second acquire
    Given maxConcurrentPerUser=1, maxRunsPerHour=20
    When user u1 tryAcquire (first)
    Then return true and concurrentCount=1
    When user u1 tryAcquire (second)
    Then return false

  Scenario: releaseRejected rolls back both counters
    Given tryAcquire succeeded for u1
    When releaseRejected("u1") is called
    Then concurrentCount should be 0
    And hourlyCount should be 0

  Scenario: null userId returns false
    When tryAcquire(null)
    Then return false
```

---

## 4. 接口规格 (API Specs)

### 4.2 内部接口

#### AgentExecutor.execute
```java
/**
 * Execute the agent loop for the given task and skill.
 * Runs on caller thread (should be submitted to snapAgentExecutor pool).
 *
 * @param task  runtime state (inputs, model, history)
 * @param skill parsed skill metadata (body, tools)
 *
 * 测试要点:
 *   - TC1: single turn end_turn -> SUCCEEDED
 *   - TC2: multi-turn tool_use -> SUCCEEDED
 *   - TC3: max_turns -> TIMEOUT
 *   - TC4: LLM exception -> FAILED
 *   - TC5: cancel before/between/after
 *   - TC6: max_tokens continuation
 */
void execute(AgentTask task, SkillMeta skill);
```

#### AgentExecutor.buildSystemPrompt
```java
/**
 * Build system prompt: read-only prefix + skill body + input-ref + userId + extenders.
 * Input values are NOT substituted (they go in the user message).
 */
String buildSystemPrompt(SkillMeta skill, AgentTask task);
```

#### RateLimiter.tryAcquire / release / releaseRejected
```java
boolean tryAcquire(String userId);  // CAS loop, rollback on concurrency fail
void release(String userId);        // decrement concurrency only
void releaseRejected(String userId); // decrement both concurrency and hourly
```

---

## 5. 数据规格 (Data Specs)

### 5.1 数据模型

```yaml
实体: AgentTask
表名: (内存 ConcurrentHashMap, 无持久化)

字段:
  - taskId: String, 格式 sa_{timestamp}_{random12}
  - userId: String
  - skillId: String
  - inputs: Map<String,String> (ConcurrentHashMap)
  - model: String
  - status: enum [PENDING, RUNNING, SUCCEEDED, FAILED, TIMEOUT, CANCELLED]
  - transcript: List<TranscriptEvent> (synchronized, limit=500)
  - auditRecords: List<AuditRecord> (synchronized, limit=1000)
  - report: String (volatile)
  - streamQueue: LinkedBlockingQueue<TranscriptEvent> (capacity=2000)

实体: TranscriptEvent (immutable)
  - type: thought | tool_call | tool_result | done | error
  - text: String (for thought/error)
  - data: Map (for tool_call/tool_result/done)
  - timestamp: long
```

### 5.3 测试数据
```yaml
标准测试skill:
  name: test-skill
  tools: [mysql_query]
  body: "## Phase 1\nWHERE sku_code='{skuCode}'"
  inputs: [{key: skuCode, label: 件号, required: true, type: string}]

标准测试task:
  userId: user-1
  skillId: test-skill
  inputs: {skuCode: A001}
  model: claude-sonnet-4-6
```

---

## 6. 错误处理规格 (Error Handling)

### 6.1 错误码定义

| 错误码 | 级别 | 描述 | 用户提示 | 行为 |
|--------|------|------|----------|------|
| LLM_STREAM_ERR | ERROR | LLM stream 抛异常 | 诊断失败 | task=FAILED, transcript+error |
| LLM_REPORT_ERR | ERROR | LLM 报 onError | 诊断失败 | task=FAILED, transcript+error |
| TOOL_ERR | WARN | 工具执行抛异常 | (不中断) | tool_result 含错误, 循环继续 |
| MAX_TURNS | INFO | 达到最大轮数 | 已达 max-turns | task=TIMEOUT |
| CANCELLED | INFO | 用户取消 | 任务已取消 | task=CANCELLED, 不记 error |

### 6.2 错误场景
```gherkin
Scenario: LLM stream throws after cancel
  When LLM stream throws RuntimeException 且 task 已被 CANCELLED
  Then task.status 保持 CANCELLED
  And transcript 不包含 TYPE_ERROR

Scenario: LLM reports error after cancel
  When LLM sink.onError 被调用 且 task 已被 CANCELLED
  Then task.status 保持 CANCELLED
  And transcript 不包含 TYPE_ERROR

Scenario: Tool exception during dispatch
  When toolDispatcher.dispatch 抛 RuntimeException
  Then 不中断循环
  And tool_result 事件被记录
  And LLM 在下一轮收到错误描述
```

---

## 7. 非功能需求 (NFR)

### 7.1 性能要求
```yaml
指标:
  - SSE token 推送延迟: < 500ms (TurnCollector.onThought 实时推送)
  - transcript 事件上限: 500 events/task (防失控)
  - audit 事件上限: 1000 records/task
  - streamQueue 容量: 2000 events
```

### 7.4 可测试性要求
- [x] 核心逻辑单元测试覆盖率 > 80%
- [x] 所有停止条件分支有测试
- [x] 取消机制 (3 个时机) 全覆盖
- [x] system prompt 构建顺序有断言

---

## 8. 测试策略 (Test Strategy)

### 8.1 测试金字塔
```
   /\
  /  \  E2E (完整诊断流程 — 未来)
 /____\
/        \  集成 (AgentExecutor + mock LlmClient + mock ToolProvider)
/          \  单元 (AgentExecutor, AgentTask, RateLimiter, TranscriptEvent)
```

### 8.2 测试清单

| 测试ID | 类型 | 描述 | 自动化 | 优先级 |
|--------|------|------|--------|--------|
| UT-001 | 单元 | 单轮 end_turn 成功 | 是 | P0 |
| UT-002 | 单元 | 多轮 tool_use + end_turn | 是 | P0 |
| UT-003 | 单元 | 多 tool_use 单轮 | 是 | P0 |
| UT-004 | 单元 | LLM 异常 FAILED | 是 | P0 |
| UT-005 | 单元 | LLM 报错 FAILED | 是 | P0 |
| UT-006 | 单元 | 工具异常不中断 | 是 | P0 |
| UT-007 | 单元 | max-turns TIMEOUT | 是 | P0 |
| UT-008 | 单元 | max_tokens 续传 | 是 | P0 |
| UT-009 | 单元 | 执行前取消 | 是 | P0 |
| UT-010 | 单元 | 轮间取消 | 是 | P0 |
| UT-011 | 单元 | LLM 异常后保持 CANCELLED | 是 | P0 |
| UT-012 | 单元 | system prompt 顺序 | 是 | P0 |
| UT-013 | 单元 | 输入值在 user message | 是 | P0 |
| UT-014 | 单元 | tool defs 从 schema 构建 | 是 | P0 |
| UT-015 | 单元 | audit 记录 | 是 | P0 |
| UT-016 | 单元 | 多 extender 追加 | 是 | P1 |
| UT-017 | 单元 | AgentTask 线程安全 | 是 | P0 |
| UT-018 | 单元 | transcript/audit 限制 | 是 | P0 |
| UT-019 | 单元 | RateLimiter 并发限制 | 是 | P0 |
| UT-020 | 单元 | RateLimiter 小时限制 | 是 | P0 |
| UT-021 | 单元 | RateLimiter releaseRejected | 是 | P0 |

### 8.3 Mock策略
```yaml
需要Mock的外部依赖:
  - LlmClient: Mockito mock, doAnswer 模拟流式事件
  - ToolProvider: Mockito mock, when().thenReturn(ToolResult)
  - ToolDispatcher: 真实对象 (封装 mock provider)
  - TaskStore: 真实对象 (内存)
  - SkillMeta: 真实对象 (POJO)
```

### 8.4 已有测试覆盖

| 测试类 | 文件路径 | 覆盖内容 |
|--------|----------|----------|
| AgentExecutorTest | `snap-agent-core/src/test/java/.../agent/AgentExecutorTest.java` | 单轮/多轮成功、max-turns、max_tokens 续传、LLM 异常/报错 FAILED、工具异常不中断、取消 (前/轮间/异常后)、system prompt 顺序、placeholder 保留、input message、tool defs、audit、extender (单/多/空/null)、model 传递 |
| AgentTaskTest | `snap-agent-core/src/test/java/.../agent/AgentTaskTest.java` | PENDING 初始、状态转换、transcript 顺序/限制、audit 限制、report 设置、inputs 持有、defensive copy、线程安全 (10 线程 x 100 次) |
| RateLimiterTest | `snap-agent-core/src/test/java/.../agent/RateLimiterTest.java` | 并发限制、小时限制、release/releaseRejected、null userId、unknown user、不超零、多用户并发、配置读取、不泄漏 hourly quota |

### 8.5 测试缺口 (Bug 候选)

| 缺口ID | 描述 | 风险 | 优先级 |
|--------|------|------|--------|
| GAP-1 | TaskStore 无独立测试类，query/count/countByUserAndStatus/countByUser 仅被间接覆盖 | 分页/过滤逻辑可能有 bug | P1 |
| GAP-2 | TranscriptEvent 无独立测试，factory 方法 (thought/toolCall/toolResult/done/error) 未直接验证 data map 内容 | 事件 data 结构可能错 | P1 |
| GAP-3 | AgentTask.streamQueue (pollStreamEvent/drainStreamEvents) 完全未测试 | SSE 实时推送可能丢事件 | P0 |
| GAP-4 | AgentExecutor.sanitizeInput 未直接测试，超长输入 (>1000 chars) 和控制字符剥离无断言 | prompt injection 防护可能失效 | P0 |
| GAP-5 | AgentExecutor.serializeResult/escape 未直接测试，JSON 序列化正确性无断言 | tool_result 回填 LLM 可能 JSON 格式错 | P1 |
| GAP-6 | 多轮对话历史 (task.getHistory()) 注入 messages 的行为未测试 | follow-up 问答可能丢失上下文 | P1 |
| GAP-7 | RateLimiter 小时窗口翻转 (rolloverHourIfNeeded) 无时间模拟测试，CAS 竞态无并发测试 | 跨小时时计数可能不准 | P1 |
| GAP-8 | task-timeout-minutes (总时长限制) 设计文档提及但 AgentExecutor 未实现 | 长时间任务无法中断 | P2 |
| GAP-9 | AgentExecutor.buildToolDefs 的 pluginOverrides 选择逻辑未测试 | 插件覆盖可能选错 | P1 |
| GAP-10 | AgentTask.create 的 ID 格式 (sa_{timestamp}_{random12}) 唯一性无并发测试 | 高并发下可能 ID 碰撞 | P2 |

---

## 9. 依赖与前置条件

### 9.1 外部依赖
| 依赖 | 状态 | 降级策略 |
|------|------|----------|
| LlmClient | 已完成 | api-key 为空时 task=FAILED |
| ToolDispatcher | 已完成 | null dispatcher 时 tools 为空 |
| Thread Pool (snapAgentExecutor) | 已完成 | 满时 429 |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 变更内容 |
|------|------|------|----------|
| 2.0 | 2026-07-23 | snap-agent team | 初始 TDD 规格 |

### 12.2 参考文档
- `docs/embeed-skills-agent/03-agent-engine.md` — Agent 引擎设计
- `docs/superpowers/specs/2026-07-03-two-tier-skill-system-design.md` — 两层 skill 系统

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| TurnCollector | 每轮 LLM 事件收集器，实现 LlmEventSink |
| stop_reason | LLM 返回的停止原因 (end_turn/tool_use/max_tokens) |
| transcript | 任务事件流水 (thought/tool_call/tool_result/done/error) |
| max-turns | 最大循环轮数 (默认 20) |
