# TDD 需求规格说明书 — 成本与安全 (Advisors 链 + 守卫)

> 版本: 2.0 | 日期: 2026-07-25 | 模块: snap-agent-core (advisor) / snap-agent-spring-boot-2x-starter
> 对应设计: `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` Section 2 (Advisor + SafeGuard + Cost + Observation)

---

## 1. 需求元信息

```yaml
需求ID: REQ-10-COST-SECURITY
需求名称: CostBudgetAdvisor + RateLimiter + SqlGuard/CodePathGuard + SecurityGateway + MicrometerObservation + SafeGuard + Audit Advisors
优先级: P0
迭代: v2.x
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 2.x 将 `CostRecord`/`CostStore`/`BudgetEnforcer` 合并为 `CostBudgetAdvisor` (Advisor, order=300)。新增 `MicrometerObservationAdvisor` (order=10, metrics+tracing)、`SafeGuardAdvisor` (order=50, 内容审核)、`AuditAdvisor` (order=400, 审计)。旧 `AuditCallback` 升级为 Audit Advisor。`RateLimiter`/`SqlGuard`/`CodePathGuard`/`SecurityGateway` 保留不变，位置调整: `RateLimiter`+`SecurityGateway` 在 Controller 层（图执行前），`SqlGuard`+`CodePathGuard` 在 `ToolsNode` 内（工具执行前）。
- **用户价值**: 统一 Advisor 链管理成本/观测/审核，预算超额通过 `InterruptException` 中断图执行（PAUSED + checkpoint），敏感内容过滤，所有 LLM/Tool 调用留审计。
- **成功指标**: `CostBudgetAdvisor`/`SafeGuardAdvisor`/`MicrometerObservationAdvisor` 单测覆盖率 ≥ 85%；POST /runs 鉴权 100% 拦截未认证请求；预算超额 100% 触发 InterruptException。

### 1.2 范围边界
- **包含**: `CostBudgetAdvisor` (before: 检查预算, after: 记录成本), `RateLimiter` (Controller 层, 不变), `SqlGuard` (ToolsNode, 不变), `CodePathGuard` (ToolsNode, 不变), `SecurityGateway` SPI + Adapters (Spring Security/Shiro/default, 不变), `MicrometerObservationAdvisor` (metrics+tracing, 新增), `SafeGuardAdvisor` (内容审核, 新增), `AuditAdvisor` (审计, 新增)。
- **不包含**: 语义层 SQL 分析，独立 DB 用户授权（由运维负责），JWT 签发（由宿主负责），沙箱/SecurityManager。

### 1.3 风险与假设
- R1: `CostBudgetAdvisor.beforeNode` 抛 `InterruptException` 中断图执行 → `GraphExecutor` catch → TaskStatus.PAUSED + checkpoint（中/高，缓解: checkpoint 持久化 + 用户可 resume）
- R2: `SafeGuardAdvisor` 误杀正常内容 → 用户可配置白名单 + 降级策略（中/中）
- R3: `MicrometerObservationAdvisor` 依赖宿主 Micrometer → starter 层可选依赖，缺失时 noop（低/低）
- R4: `AuditAdvisor` 存储失败 → log + continue (degraded)，图执行不中断（低/中）
- R5: prompt injection 不可完全防 → 只读工具+只读 DB 限制爆炸半径，审计是检测非预防（高/高，缓解: SafeGuardAdvisor 内容过滤 + 审计追踪）

---

## 2. 用户故事 (User Stories)

### US-1: CostBudgetAdvisor — before 检查预算, after 记录成本
```gherkin
作为 运维管理员
我希望 CostBudgetAdvisor 作为 Advisor (order=300)，beforeNode 检查 用户/skill/全局 三维每日预算，afterNode 记录本次 LLM 调用成本
以便 防止单用户/skill 拖垮整体 LLM 成本，且自动累计成本记录
```
**AC:**
```gherkin
AC1: Given CostBudgetAdvisor(perUserDaily=$10) 且当日 user "u1" 已用 $10
  When beforeNode("agent", state, ctx)
  Then 抛 InterruptException("Budget exceeded for user u1") 且 INFO 日志
AC2: Given 三维预算均 null
  When beforeNode
  Then 不抛异常且正常执行
AC3: Given LLM 调用消耗 inputTokens=100, outputTokens=200
  When afterNode("agent", state, ctx)
  Then CostStore.record 被调用
  And CostRecord 含 userId/skillName/taskId/model/tokens/cost/timestamp
AC4: Given costStore.record 抛异常
  When afterNode
  Then 异常被 catch + WARN 日志，图执行不中断
AC5: Given perSkillDaily=$50 且 sumCostBySkill("log-analysis")=$50
  When beforeNode("agent", state, ctx) (ctx.skillName="log-analysis")
  Then 抛 InterruptException("Budget exceeded for skill log-analysis")
```

### US-2: 预算执行 — 超额 → InterruptException 或停止执行
```gherkin
作为 平台
我希望 预算超额时 CostBudgetAdvisor 抛 InterruptException，GraphExecutor catch → TaskStatus.PAUSED + checkpoint
以便 用户可后续 resume 或终止任务
```
**AC:**
```gherkin
AC1: Given perUserDaily=$10 已达上限
  When beforeNode
  Then 抛 InterruptException
AC2: Given InterruptException 被 GraphExecutor catch
  When execute
  Then TaskStatus=PAUSED 且 checkpoint 保存
  And SSE 推送 "paused" 事件
AC3: Given perSkillDaily=$50 已达上限
  When beforeNode
  Then 抛 InterruptException("Budget exceeded for skill X")
AC4: Given globalDaily=$200 已达上限
  When beforeNode
  Then 抛 InterruptException("Global budget exceeded")
AC5: Given 用户 POST /runs/{id}/resume
  When GraphExecutor.resume
  Then 从 checkpoint 恢复 + 重新检查预算（若仍超额则再次 PAUSED）
```

### US-3: RateLimiter — per-user 并发 + 小时配额 (unchanged)
```gherkin
作为 平台
我希望 RateLimiter 在 Controller 层执行图前检查 per-user 并发数 + 小时配额
以便 防止单用户耗尽线程池和 LLM 配额
```
**AC:**
```gherkin
AC1: Given RateLimiter(maxConcurrent=1, maxRunsPerHour=20)
  When 连续两次 tryAcquire("u1")
  Then 第一次 true，第二次 false
AC2: Given tryAcquire 成功后线程池拒绝
  When releaseRejected("u1")
  Then concurrentCount=0 且 hourlyCount=0（不泄漏配额）
AC3: Given maxConcurrent=1
  When tryAcquire(null)
  Then 返回 false
AC4: Given maxConcurrent=5, maxRunsPerHour=3
  When tryAcquire("u1") + release("u1")
  Then concurrentCount=0 但 hourlyCount=1（release 不回滚小时配额）
AC5: Given maxConcurrent=1, maxRunsPerHour=20
  When tryAcquire("u1") + release("unknown")
  Then 不抛异常，unknown 仍 0
```

### US-4: SqlGuard — SELECT/SHOW/DESCRIBE/EXPLAIN only, 白名单/黑名单
```gherkin
作为 安全负责人
我希望 SqlGuard 在 ToolsNode 执行 JDBC 工具前校验 SQL，仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN，黑名单关键字拒绝
以便 防止 prompt injection 通过 mysql_query 执行写操作
```
**AC:**
```gherkin
AC1: Given SqlGuard(maxResultRows=1000)
  When validate("DELETE FROM users")
  Then reject "首关键字 DELETE"
AC2: When validate("SELECT * FROM users INTO OUTFILE '/tmp/x'")
  Then reject "黑名单关键字 INTO OUTFILE"
AC3: When validate("SELECT * FROM users")
  Then ok 且 sql 以 "LIMIT 1000" 结尾
AC4: When validate("SHOW TABLES") 或 ("DESCRIBE users") 或 ("EXPLAIN SELECT * FROM users")
  Then ok（白名单允许）
AC5: When validate("SELECT * FROM users; DROP TABLE users; --")
  Then reject "多语句（含分号）"
AC6: When validate("SELECT SLEEP(60)")
  Then reject "黑名单 SLEEP"
AC7: When validate("SELECT * FROM users LIMIT 5000")
  Then ok 且改写为 "LIMIT 1000"
AC8: When validate("") 或 null
  Then reject "SQL 为空"
```

### US-5: CodePathGuard — 路径白名单 for 代码读取工具
```gherkin
作为 安全负责人
我希望 CodePathGuard 在 ToolsNode 执行 code_read 工具前校验路径，禁止目录穿越 + 扩展名白名单 + 大小上限
以便 防止 prompt injection 读取敏感文件
```
**AC:**
```gherkin
AC1: Given CodePathGuard(projectRoot="/opt/app", allowedExtensions=[".java",".xml"], maxFileBytes=524288)
  When validate("../etc/passwd")
  Then reject "禁止目录穿越 (..)"
AC2: When validate("/etc/passwd")
  Then reject "不在项目根目录下"
AC3: When validate("src/Main.java") (存在)
  Then ok 且返回绝对路径
AC4: When validate("secret.env")
  Then reject "扩展名不在白名单 .env"
AC5: When resolveWithinProject("src/main")
  Then 返回 /opt/app/src/main
AC6: When resolveWithinProject("../etc")
  Then 返回 null（不校验存在）
```

### US-6: SecurityGateway SPI + Adapters — Spring Security, Shiro, default (unchanged)
```gherkin
作为 安全管理员
我希望 SecurityGateway SPI 桥接宿主安全框架（Spring Security / Shiro / 默认实现），Controller 层调用
以便 未认证 401，无权限 403，所有访问留痕
```
**AC:**
```gherkin
AC1: Given currentUserId()=null
  When GET /snap-agent/skills
  Then 401 $.error="UNAUTHORIZED"
AC2: Given hasPermission("skills:run")=false
  When GET /snap-agent/models
  Then 403 $.error="FORBIDDEN"
AC3: Given SpringSecuritySecurityGateway 实现
  When onApiAccess
  Then currentUserId() 从 SecurityContextHolder 获取
AC4: Given ShiroSecurityGateway 实现
  When onApiAccess
  Then currentUserId() 从 Subject.getPrincipal 获取
AC5: Given DefaultSecurityGateway 实现
  When onApiAccess
  Then currentUserId() 返回 null（强制鉴权失败）
AC6: Given PrincipalResolver 实现 currentUserName() 返回 "user-001"
  When SecurityGateway.onApiAccess 执行
  Then audit 记录 userId="user-001" 且 RateLimiter.tryAcquire("user-001") 被调用
```

### US-7: MicrometerObservationAdvisor — metrics: tokens, duration, tool calls; traces: 全链路
```gherkin
作为 平台运维
我希望 MicrometerObservationAdvisor (order=10) 在所有图执行前后记录指标（tokens/duration/tool calls）+ 追踪（full chain）
以便 可观测 LLM 调用性能、工具调用次数、图执行全链路
```
**AC:**
```gherkin
AC1: Given MicrometerObservationAdvisor + MeterRegistry
  When beforeNode
  Then 开启 Observation (span name="snap-agent.graph.node")
AC2: Given 节点执行消耗 500ms
  When afterNode
  Then timer "snap-agent.graph.node.duration" 记录 500ms 且 tag nodeName="agent"
AC3: Given LLM 调用消耗 inputTokens=100, outputTokens=200
  When afterNode("agent")
  Then counter "snap-agent.llm.tokens" 增加 (input=100, output=200)
AC4: Given ToolsNode 调用工具 3 次
  When afterNode("tools")
  Then counter "snap-agent.tool.calls" 增加 3，tag toolName 各不同
AC5: Given MicrometerObservationAdvisor 依赖缺失 (MeterRegistry=null)
  When beforeNode
  Then noop 不抛异常
AC6: Given 图执行链 entry → agent → tools → agent → END
  When 全链路追踪
  Then trace 含 5 个 span，父子关系正确（agent→tools→agent）
AC7: Given LLM 调用失败
  When afterNode
  Then counter "snap-agent.llm.errors" 增加 1，tag errorType="RuntimeException"
```

### US-8: SafeGuardAdvisor — before 过滤 prompt, after 过滤 LLM 输出
```gherkin
作为 安全负责人
我希望 SafeGuardAdvisor (order=50) beforeNode 过滤用户 prompt 检测敏感词，afterNode 过滤 LLM 输出检测敏感内容
以便 防止敏感信息泄露或违规内容生成
```
**AC:**
```gherkin
AC1: Given SafeGuardAdvisor(sensitiveWords=["密码","身份证"]) + user prompt 含 "我的密码是"
  When beforeNode
  Then state["user.query"] 被替换为 "我的***是" 且不抛异常
AC2: Given LLM 输出含 "身份证号: 110..."
  When afterNode
  Then 输出被脱敏为 "身份证号: ***"
AC3: Given LLM 输出含恶意内容（如 prompt injection 残留）
  When afterNode
  Then 输出被过滤或替换为 "内容被安全策略拦截"
AC4: Given SafeGuardAdvisor 配置 whitelist=["技术文档"]
  When beforeNode prompt 含 "技术文档"
  Then 不替换（白名单优先）
AC5: Given SafeGuardAdvisor 抛异常
  When beforeNode
  Then 异常被 AdvisorNode catch + WARN 日志，图执行不中断
AC6: Given beforeNode prompt 含敏感词
  When SafeGuardAdvisor 替换
  Then state["user.query"] 含 *** 但不抛 InterruptException（不中断执行）
```

### US-9: 审计追踪 — Advisor 记录所有 Tool 调用 + LLM 调用，存入 audit store
```gherkin
作为 合规审计
我希望 AuditAdvisor (order=400) 记录所有 Tool 调用 + LLM 调用，AuditRecord 存入 AuditStore
以便 合规审计可追溯所有 LLM/Tool 调用历史
```
**AC:**
```gherkin
AC1: Given AuditAdvisor + AuditStore
  When afterNode("agent", state, ctx)
  Then AuditStore.save 被调用
  And AuditRecord 含 taskId/userId/nodeName/llmModel/inputTokens/outputTokens/timestamp
AC2: Given ToolsNode 调用工具
  When afterNode("tools")
  Then AuditRecord 含 toolName/args/result/truncated
AC3: Given AuditStore.save 抛异常
  When afterNode
  Then 异常被 catch + WARN 日志，图执行不中断
AC4: Given AuditStore.listByTask(taskId)
  When 查询
  Then 返回该 task 所有 LLM/Tool 调用记录
AC5: Given AuditAdvisor order=400
  When 多 Advisor 链执行
  Then AuditAdvisor 在 CostBudgetAdvisor(300) 之后执行（afterNode 逆序，先执行）
AC6: Given AuditRecord 含敏感 args (如 SQL 含密码)
  When AuditAdvisor 记录
  Then args 经 SafeGuardAdvisor 脱敏后再写入 AuditStore
```

---

## 3. 功能规格

### 3.1 用例清单

| ID | 用例 | 优先级 | AC | 类型 |
|----|------|--------|----|------|
| UC-01 | CostBudgetAdvisor before 三维预算检查 | P0 | US-1 | 单元 |
| UC-02 | CostBudgetAdvisor after 记录成本 | P0 | US-1 | 单元 |
| UC-03 | CostBudgetAdvisor 异常隔离 | P1 | US-1 | 单元 |
| UC-04 | 预算超额 InterruptException + GraphExecutor PAUSED | P0 | US-2 | 单元 |
| UC-05 | 预算 resume 重新检查 | P1 | US-2 | 集成 |
| UC-06 | RateLimiter 并发+小时配额 | P0 | US-3 | 单元 |
| UC-07 | RateLimiter releaseRejected 回滚 | P0 | US-3 | 单元 |
| UC-08 | RateLimiter null 用户+release unknown | P1 | US-3 | 单元 |
| UC-09 | SqlGuard 白名单/黑名单/LIMIT | P0 | US-4 | 单元 |
| UC-10 | SqlGuard 多语句+空边界 | P0 | US-4 | 单元 |
| UC-11 | CodePathGuard 路径白名单+穿越拒绝 | P0 | US-5 | 单元 |
| UC-12 | CodePathGuard resolveWithinProject | P1 | US-5 | 单元 |
| UC-13 | SecurityGateway 401/403 + SPI | P0 | US-6 | 集成 |
| UC-14 | SecurityGateway Adapters (Spring Security/Shiro/default) | P0 | US-6 | 单元 |
| UC-15 | MicrometerObservationAdvisor metrics tokens+duration | P0 | US-7 | 单元 |
| UC-16 | MicrometerObservationAdvisor traces 全链路 | P0 | US-7 | 单元 |
| UC-17 | MicrometerObservationAdvisor noop 降级 | P1 | US-7 | 单元 |
| UC-18 | SafeGuardAdvisor before 过滤 prompt | P0 | US-8 | 单元 |
| UC-19 | SafeGuardAdvisor after 过滤 LLM 输出 | P0 | US-8 | 单元 |
| UC-20 | SafeGuardAdvisor 白名单优先 | P1 | US-8 | 单元 |
| UC-21 | SafeGuardAdvisor 异常隔离 | P1 | US-8 | 单元 |
| UC-22 | AuditAdvisor 记录 LLM 调用 | P0 | US-9 | 单元 |
| UC-23 | AuditAdvisor 记录 Tool 调用 | P0 | US-9 | 单元 |
| UC-24 | AuditAdvisor 异常隔离 | P1 | US-9 | 单元 |
| UC-25 | AuditAdvisor order + 脱敏 args | P1 | US-9 | 单元 |
| UC-R1 | GET /cost/summary 全局成本摘要 | P1 | - | 集成 |
| UC-R2 | GET /cost/users/{userId}/summary 用户成本 | P1 | - | 集成 |
| UC-R3 | GET /cost/skills/{skillName}/summary 技能成本 | P1 | - | 集成 |
| UC-R4 | GET /cost/records 成本记录分页 | P1 | - | 集成 |
| UC-R5 | GET /audit/records 审计记录查询 | P1 | - | 集成 |

### 3.2 详细用例 (Gherkin)

#### UC-01: CostBudgetAdvisor before 三维预算检查
```gherkin
@priority:high @type:unit
功能: CostBudgetAdvisor beforeNode 三维预算

  场景大纲: 预算维度命中
    Given CostStore 配置 <setup>
    When beforeNode("agent", state, ctx) (ctx.userId="u1", ctx.skillName="log-analysis")
    Then <expect>
    例子:
      | setup | expect |
      | perUserDaily=$10 且 sumCostByUser("u1")=10 | 抛 InterruptException("Budget exceeded for user u1") |
      | perSkillDaily=$50 且 sumCostBySkill("log-analysis")=50 | 抛 InterruptException("Budget exceeded for skill log-analysis") |
      | globalDaily=$200 且 sumCost=200 | 抛 InterruptException("Global budget exceeded") |
      | 三维均 null | 不抛异常 |
      | perUserDaily=$10 且 sumCostByUser("u1")=5 | 不抛异常 |
```

#### UC-02: CostBudgetAdvisor after 记录成本
```gherkin
@priority:high @type:unit
功能: CostBudgetAdvisor afterNode 记录成本

  场景: LLM 调用消耗 tokens 记录
    Given LLM 调用消耗 inputTokens=100, outputTokens=200, cacheReadTokens=50
    When afterNode("agent", state, ctx)
    Then CostStore.record 被调用一次
    And CostRecord.inputTokens=100, outputTokens=200, cacheReadTokens=50
    And costCalculator.computeCost(100,200,50) 被调用

  场景: 无 token 消耗不记录
    Given state 无 token 消耗信息
    When afterNode("agent", state, ctx)
    Then CostStore.record 从未被调用（不创建零成本记录）
```

#### UC-03: CostBudgetAdvisor 异常隔离
```gherkin
@priority:medium @type:unit
功能: CostBudgetAdvisor 异常隔离

  场景: costStore.record 抛异常不中断
    Given costStore.record 抛 RuntimeException
    When afterNode("agent", state, ctx)
    Then 异常被 catch + WARN 日志
    And 图执行不中断
    And delegate 节点仍正常执行
```

#### UC-04: 预算超额 InterruptException + GraphExecutor PAUSED
```gherkin
@priority:high @type:unit
功能: 预算超额中断

  场景: InterruptException 被 GraphExecutor catch
    Given perUserDaily=$10 已达上限
    When beforeNode 抛 InterruptException
    Then GraphExecutor.execute catch 该异常
    And TaskStatus=PAUSED
    And CheckpointStore.save 被调用
    And SSE 推送 "paused" 事件

  场景: 不同维度异常消息
    Given perSkillDaily=$50 已达上限
    When beforeNode
    Then 抛 InterruptException("Budget exceeded for skill log-analysis")
```

#### UC-05: 预算 resume 重新检查
```gherkin
@priority:medium @type:integration
功能: resume 重新检查预算

  场景: resume 时预算仍超额
    Given task PAUSED 且 perUserDaily=$10 仍达上限
    When POST /runs/{id}/resume
    Then GraphExecutor.resume 从 checkpoint 恢复
    And CostBudgetAdvisor.beforeNode 重新检查预算
    And 预算仍超额则再次 PAUSED
```

#### UC-06: RateLimiter 并发+小时配额
```gherkin
@priority:high @type:unit
功能: RateLimiter 限流

  场景大纲: 限流行为
    Given RateLimiter 配置 <config>
    When <action>
    Then <expect>
    例子:
      | config | action | expect |
      | maxConcurrent=1, maxRunsPerHour=20 | tryAcquire("u1") 两次 | 第一次 true，第二次 false |
      | maxConcurrent=10, maxRunsPerHour=2 | tryAcquire+release+tryAcquire+releaseRejected+tryAcquire | 第三次 true（不泄漏配额） |
      | maxConcurrent=1, maxRunsPerHour=20 | tryAcquire(null) | false |
```

#### UC-07: RateLimiter releaseRejected 回滚
```gherkin
@priority:high @type:unit
功能: RateLimiter 回滚

  场景: 线程池拒绝后 releaseRejected 回滚两个计数
    Given tryAcquire 成功后线程池拒绝
    When releaseRejected("u1")
    Then concurrentCount=0 且 hourlyCount=0（不泄漏配额）
```

#### UC-08: RateLimiter null 用户+release unknown
```gherkin
@priority:medium @type:unit
功能: RateLimiter 边界

  场景: release 不回滚小时配额 + release unknown 不抛
    Given maxConcurrent=5, maxRunsPerHour=3
    When tryAcquire("u1") + release("u1")
    Then concurrentCount=0 但 hourlyCount=1
    Given maxConcurrent=1, maxRunsPerHour=20
    When tryAcquire("u1") + release("unknown")
    Then 不抛异常，unknown 仍 0
```

#### UC-09: SqlGuard 白名单/黑名单/LIMIT
```gherkin
@priority:high @type:unit
功能: SqlGuard 校验

  场景大纲: SQL 校验
    Given SqlGuard(maxResultRows=1000)
    When validate(<sql>)
    Then <expect>
    例子:
      | sql | expect |
      | DELETE FROM users WHERE id=1 | reject "首关键字 DELETE" |
      | SELECT * FROM users; DROP TABLE users; -- | reject "多语句（含分号）" |
      | SELECT * FROM users INTO OUTFILE '/tmp/x' | reject "黑名单 INTO OUTFILE" |
      | SELECT SLEEP(60) | reject "黑名单 SLEEP" |
      | SELECT * FROM users | ok，sql 以 "LIMIT 1000" 结尾 |
      | SHOW TABLES | ok |
      | DESCRIBE users | ok |
      | EXPLAIN SELECT * FROM users | ok |
      | WITH agg AS (...) SELECT ... | ok（CTE WITH） |
      | "" 或 null | reject "SQL 为空" |
      | SELECT * FROM users LIMIT 5000 | ok，改写为 "LIMIT 1000" |
```

#### UC-10: SqlGuard 多语句+空边界
```gherkin
@priority:high @type:unit
功能: SqlGuard 边界

  场景: 多语句拒绝
    When validate("SELECT * FROM users; DROP TABLE users; --")
    Then reject "多语句（含分号）"

  场景: 空或 null
    When validate("") 或 validate(null)
    Then reject "SQL 为空"
```

#### UC-11: CodePathGuard 路径白名单+穿越拒绝
```gherkin
@priority:high @type:unit
功能: CodePathGuard 校验

  场景大纲: 路径校验
    Given CodePathGuard(projectRoot="/opt/app", allowedExtensions=[".java",".xml"], maxFileBytes=524288)
    When validate(<path>)
    Then <expect>
    例子:
      | path | expect |
      | "../etc/passwd" | reject "禁止目录穿越 (..)" |
      | "/etc/passwd" | reject "不在项目根目录下" |
      | "src/Main.java" (存在) | ok，返回绝对路径 |
      | "secret.env" | reject "扩展名不在白名单 .env" |
```

#### UC-12: CodePathGuard resolveWithinProject
```gherkin
@priority:medium @type:unit
功能: CodePathGuard resolve

  场景: resolveWithinProject 不校验存在
    When resolveWithinProject("src/main")
    Then 返回 /opt/app/src/main
    When resolveWithinProject("../etc")
    Then 返回 null（不校验存在）
```

#### UC-13: SecurityGateway 401/403 + SPI
```gherkin
@priority:high @type:integration
功能: SecurityGateway 鉴权

  场景大纲: 鉴权分支
    Given <setup>
    When <request>
    Then <expect>
    例子:
      | setup | request | expect |
      | currentUserId()=null | GET /snap-agent/skills | 401 $.error="UNAUTHORIZED" |
      | hasPermission()=false | GET /snap-agent/models | 403 $.error="FORBIDDEN" |
      | securityGateway=null | GET /snap-agent/models | 401 $.message="security not configured" |
      | currentUserId()="user-001", hasPermission("skills:run")=true | 调 hasPermission("")/null | true；hasPermission("skills:admin")=false |

  场景: 审计记录访问 — "user001" GET /models → onApiAccess("user001","GET","/models","LIST_MODELS",_)
  场景: 未认证 → 401 且不审计
  场景: /user-info 不强制 requireAuth — currentUserId()=null → 200 $.authenticated=false
  场景: currentUserName 显示名 — "Alice Wang" → $.username="Alice Wang"；null 时 fallback 到 userId
```

#### UC-14: SecurityGateway Adapters
```gherkin
@priority:high @type:unit
功能: SecurityGateway Adapters

  场景: SpringSecuritySecurityGateway
    Given SpringSecuritySecurityGateway
    When onApiAccess
    Then currentUserId() 从 SecurityContextHolder.getContext().getAuthentication().getPrincipal() 获取

  场景: ShiroSecurityGateway
    Given ShiroSecurityGateway
    When onApiAccess
    Then currentUserId() 从 SecurityUtils.getSubject().getPrincipal() 获取

  场景: DefaultSecurityGateway
    Given DefaultSecurityGateway
    When onApiAccess
    Then currentUserId() 返回 null
```

#### UC-15: MicrometerObservationAdvisor metrics tokens+duration
```gherkin
@priority:high @type:unit
功能: Micrometer 指标

  场景: 节点 duration + LLM tokens 指标
    Given MicrometerObservationAdvisor + MeterRegistry
    When 节点执行消耗 500ms 且 LLM 调用消耗 inputTokens=100, outputTokens=200
    Then afterNode 后 timer "snap-agent.graph.node.duration" 记录 500ms (tag nodeName="agent")
    And counter "snap-agent.llm.tokens" 增加 (input=100, output=200)

  场景: ToolsNode 工具调用计数
    Given ToolsNode 调用工具 3 次
    When afterNode("tools")
    Then counter "snap-agent.tool.calls" 增加 3，tag toolName 各不同
```

#### UC-16: MicrometerObservationAdvisor traces 全链路
```gherkin
@priority:high @type:unit
功能: Micrometer 追踪

  场景: 图执行全链路 trace
    Given 图执行链 entry → agent → tools → agent → END
    When 全链路追踪
    Then trace 含 5 个 span
    And 父子关系正确（agent→tools→agent）
    And span name="snap-agent.graph.node"

  场景: LLM 调用失败计数
    Given LLM 调用失败
    When afterNode
    Then counter "snap-agent.llm.errors" 增加 1 (tag errorType="RuntimeException")
```

#### UC-17: MicrometerObservationAdvisor noop 降级
```gherkin
@priority:medium @type:unit
功能: Micrometer 降级

  场景: MeterRegistry=null 时 noop
    Given MicrometerObservationAdvisor (MeterRegistry=null)
    When beforeNode
    Then noop 不抛异常
    And 不创建任何 span 或 metric
```

#### UC-18: SafeGuardAdvisor before 过滤 prompt
```gherkin
@priority:high @type:unit
功能: SafeGuard before 过滤

  场景: 敏感词替换
    Given SafeGuardAdvisor(sensitiveWords=["密码","身份证"])
    When beforeNode 且 user prompt 含 "我的密码是"
    Then state["user.query"] 被替换为 "我的***是"
    And 不抛异常

  场景: 不中断执行
    Given prompt 含敏感词
    When beforeNode 替换
    Then state["user.query"] 含 *** 但不抛 InterruptException
```

#### UC-19: SafeGuardAdvisor after 过滤 LLM 输出
```gherkin
@priority:high @type:unit
功能: SafeGuard after 过滤

  场景: LLM 输出脱敏
    Given LLM 输出含 "身份证号: 110..."
    When afterNode
    Then 输出被脱敏为 "身份证号: ***"

  场景: 恶意内容替换
    Given LLM 输出含 prompt injection 残留
    When afterNode
    Then 输出被过滤或替换为 "内容被安全策略拦截"
```

#### UC-20: SafeGuardAdvisor 白名单优先
```gherkin
@priority:medium @type:unit
功能: SafeGuard 白名单

  场景: 白名单词不替换
    Given SafeGuardAdvisor(sensitiveWords=["文档"], whitelist=["技术文档"])
    When beforeNode prompt 含 "技术文档"
    Then 不替换（白名单优先）
```

#### UC-21: SafeGuardAdvisor 异常隔离
```gherkin
@priority:medium @type:unit
功能: SafeGuard 异常隔离

  场景: SafeGuard 抛异常不中断
    Given SafeGuardAdvisor.sanitize 抛 RuntimeException
    When beforeNode
    Then 异常被 AdvisorNode catch + WARN 日志
    And 图执行不中断
```

#### UC-22: AuditAdvisor 记录 LLM 调用
```gherkin
@priority:high @type:unit
功能: AuditAdvisor LLM 记录

  场景: afterNode agent 记录 LLM 调用
    Given AuditAdvisor + AuditStore
    When afterNode("agent", state, ctx)
    Then AuditStore.save 被调用
    And AuditRecord 含 taskId/userId/nodeName="agent"/llmModel/inputTokens/outputTokens/timestamp
```

#### UC-23: AuditAdvisor 记录 Tool 调用
```gherkin
@priority:high @type:unit
功能: AuditAdvisor Tool 记录

  场景: afterNode tools 记录工具调用
    Given ToolsNode 调用工具
    When afterNode("tools", state, ctx)
    Then AuditRecord 含 toolName/args/result/truncated
```

#### UC-24: AuditAdvisor 异常隔离
```gherkin
@priority:medium @type:unit
功能: AuditAdvisor 异常隔离

  场景: AuditStore.save 抛异常不中断
    Given AuditStore.save 抛 RuntimeException
    When afterNode
    Then 异常被 catch + WARN 日志
    And 图执行不中断
```

#### UC-25: AuditAdvisor order + 脱敏 args
```gherkin
@priority:medium @type:unit
功能: AuditAdvisor 顺序与脱敏

  场景: order=400 在 CostBudget(300) 之后
    Given 多 Advisor 链: Micrometer(10) → SafeGuard(50) → CostBudget(300) → Audit(400)
    When afterNode 逆序执行
    Then AuditAdvisor 先执行 (afterNode 逆序: 400 → 300 → 50 → 10)

  场景: args 脱敏后写入
    Given SQL args 含密码
    When AuditAdvisor 记录
    Then args 经 SafeGuardAdvisor 脱敏后再写入 AuditStore
```

---

## 4. 接口规格

### 4.1 REST 端点
- `/snap-agent/skills`、`/snap-agent/models`、`/snap-agent/tools`、`/snap-agent/skills/refresh` — 需 `currentUserId()` 非空 + `hasPermission(requiredPermission)`
- `/snap-agent/runs` — 鉴权 + RateLimiter (图执行前) + CostBudgetAdvisor (图执行中 beforeNode)
- `/snap-agent/user-info` — 不调 requireAuth，返回 authenticated/authorized/username
- `/cost/summary`、`/cost/users/{userId}/summary`、`/cost/skills/{skillName}/summary`、`/cost/records` — 需 `cost:query` 权限
- `/audit/records?taskId={id}` — 需 `audit:query` 权限

### 4.2 内部接口
```java
// Advisor SPI (Section 2)
public interface Advisor {
    String getName();
    int getOrder();
    GraphState beforeNode(String nodeName, GraphState state, ExecutionContext ctx);
    GraphState afterNode(String nodeName, GraphState state, ExecutionContext ctx);
}

// CostBudgetAdvisor (order=300)
// before: 检查 perUser/perSkill/global Daily 预算，超额抛 InterruptException
// after: CostStore.record(CostRecord{userId,skillName,taskId,model,tokens,cost,timestamp})

// RateLimiter (Controller 层, 不变)
boolean tryAcquire(String userId);
void release(String userId);
void releaseRejected(String userId);

// SqlGuard (ToolsNode, 不变)
SqlGuard.Result validate(String sql);

// CodePathGuard (ToolsNode, 不变)
CodePathGuard.Result validate(String path);
Path resolveWithinProject(String pathStr);

// SecurityGateway SPI (Controller 层, 不变)
String currentUserId();
String currentUserName();  // default null
boolean hasPermission(String code);

// MicrometerObservationAdvisor (order=10)
// before: 开启 Observation span
// after: 记录 timer/counter + 关闭 span

// SafeGuardAdvisor (order=50)
// before: 过滤 user.query 敏感词
// after: 过滤 LLM 输出敏感内容

// AuditAdvisor (order=400)
// after: AuditStore.save(AuditRecord)
```

### 4.3 MCP/JSON-RPC — SqlGuard 黑名单正则（大小写不敏感）：`\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|RENAME|GRANT|REVOKE|REPLACE|MERGE|CALL|HANDLER|LOCK|UNLOCK|FLUSH|RESET|SHUTDOWN|KILL|LOAD|LOAD_FILE|INTO\s+OUTFILE|INTO\s+DUMPFILE|INTO\s+@|SLEEP|BENCHMARK)\b`

### 4.4 Advisor 链顺序
| order | Advisor | 职责 |
|-------|---------|------|
| 10 | MicrometerObservationAdvisor | metrics + tracing |
| 50 | SafeGuardAdvisor | 内容审核 |
| 100 | MessageChatMemoryAdvisor | 消息窗口 |
| 200 | RetrievalAugmentationAdvisor | RAG 注入 |
| 300 | CostBudgetAdvisor | 预算检查 + 成本记录 |
| 400 | AuditAdvisor | 审计追踪 |

---

## 5. 数据规格

- **CostBudgetAdvisor**: 三维 `BigDecimal perUserDaily/perSkillDaily/globalDaily`，null=无限制；窗口从本地午夜零点（`startOfTodayMillis()`）；beforeNode 抛 InterruptException；afterNode 调用 CostStore.record
- **RateLimiter**: `ConcurrentHashMap<userId, AtomicInteger>` 并发+小时计数；小时窗口 `currentHourMillis()`（`now - now % 3600000`）；CAS 防 TOCTOU；release 不回滚小时配额，releaseRejected 回滚两个计数
- **CostRecord**: `userId, skillName, taskId, model, inputTokens, outputTokens, cacheReadTokens, cost(BigDecimal), timestamp`；CostBudgetAdvisor afterNode 累计 tokens → record
- **AuditRecord**: `taskId, userId, nodeName, llmModel, inputTokens, outputTokens, toolName, args, result, truncated, timestamp, durationMs`；AuditAdvisor afterNode 写入
- **SafeGuardAdvisor**: `sensitiveWords: List<String>`, `whitelist: List<String>`, `replacement: String="***"`
- **测试边界**: `maxConcurrent=1`、`maxRunsPerHour=1`、`LIMIT 1001`、`maxFileBytes+1`

---

## 6. 错误处理

| 错误码 | 描述 | 用户提示 |
|--------|------|----------|
| E200 | UNAUTHORIZED | currentUserId() 返回 null |
| E201 | FORBIDDEN | hasPermission 返回 false |
| E202 | security not configured | securityGateway=null |
| E203 | SQL 被只读策略拒绝 | "SQL 被只读策略拒绝：{reason}" |
| E204 | 代码路径被拒绝 | "代码路径被拒绝：{reason}" |
| E205 | Redis command rejected | "Redis command rejected (read-only): {cmd}" |
| E206 | Git commit_hash 格式无效 | "只接受 7-40 位十六进制" |
| E207 | Budget exceeded | InterruptException → PAUSED + SSE "paused" |
| E208 | Rate limit | 429 + Retry-After |
| E209 | SafeGuard 内容拦截 | "内容被安全策略拦截" |
| E210 | Audit store failure | (log + continue, 不影响用户) |
| E211 | Micrometer 依赖缺失 | (noop, 不影响用户) |

---

## 7. 非功能需求

### 7.1 性能
- SqlGuard.validate P99 < 1ms
- CodePathGuard.validate P99 < 2ms（含 Files.exists）
- RateLimiter.tryAcquire P99 < 0.1ms
- CostBudgetAdvisor.beforeNode 取决于 CostStore 查询；afterNode < 1ms（CostStore.record 异步）
- MicrometerObservationAdvisor.beforeNode/afterNode < 0.1ms（MeterRegistry noop 时）
- SafeGuardAdvisor.beforeNode/afterNode < 1ms（敏感词匹配）
- AuditAdvisor.afterNode < 1ms（AuditStore.save 异步）

### 7.2 安全
- [x] SqlGuard 白名单+黑名单+多语句拒绝+LIMIT 注入
- [x] CodePathGuard 路径穿越拒绝+扩展名白名单+大小上限
- [x] SecurityGateway SPI，宿主可覆盖
- [x] /snap-agent/** 强制鉴权
- [x] CostBudgetAdvisor 预算超额 InterruptException 中断图执行
- [x] SafeGuardAdvisor 内容审核（敏感词过滤 + 输出脱敏）
- [x] AuditAdvisor 全 LLM/Tool 调用留审计
- [x] MicrometerObservationAdvisor 全链路追踪

### 7.3 可测试性
- RateLimiter 无外部依赖纯内存
- SqlGuard/CodePathGuard 纯逻辑无 Mock
- SnapAgentController 用 MockMvc + Mockito
- CostBudgetAdvisor mock CostStore + ExecutionContext
- MicrometerObservationAdvisor mock MeterRegistry 或用 noop
- SafeGuardAdvisor 纯逻辑无 Spring 依赖
- AuditAdvisor mock AuditStore

---

## 8. 测试策略

### 8.1 已有测试覆盖

| 测试文件 | 类型 | 覆盖用例 |
|----------|------|----------|
| `RateLimiterTest` | 单元 | UC-06~08 并发+小时配额+releaseRejected+null 用户+多用户+边界 |
| `SecuritySpiTest` | 单元 | UC-14 SecurityGateway/PrincipalResolver SPI 可实现性 |
| `SnapAgentControllerSecurityTest` | 集成 | UC-13 401/403/审计+/user-info fallback/displayName/null gateway |

**总结**: 2.x 新增 `CostBudgetAdvisor`/`MicrometerObservationAdvisor`/`SafeGuardAdvisor`/`AuditAdvisor` 测试为新增。旧 `CostTrackingLlmClient`/`BudgetEnforcer` 已删除。优先实现 UC-01~25 (单元)。

### 8.2 E2E 关键路径

| 路径ID | 关键路径 | 端点 | 状态 |
|--------|----------|------|------|
| E2E-1 | 费用摘要: GET /cost/summary → 200 (总费用/用户/技能维度) | GET /cost/summary | ⚠未实现 |
| E2E-2 | 费用记录: GET /cost/records → 200 (费用明细列表) | GET /cost/records | ⚠未实现 |
| E2E-3 | 用户费用: GET /cost/users/{id}/summary → 200 (单用户费用) | GET /cost/users/{id}/summary | ⚠未实现 |
| E2E-4 | 技能费用: GET /cost/skills/{name}/summary → 200 (单技能费用) | GET /cost/skills/{name}/summary | ⚠未实现 |
| E2E-5 | 安全流 401: GET /cost/summary 无认证 → 401 | GET /cost/summary | ⚠未实现 |
| E2E-6 | 安全流 403: GET /cost/summary 无 cost:query 权限 → 403 | GET /cost/summary | ⚠未实现 |
| E2E-7 | 安全流 429: POST /runs 超限 → 429 (RateLimiter) | POST /runs | ⚠未实现 |
| E2E-8 | 预算中断: POST /runs 超预算 → 图执行 PAUSED + SSE "paused" 事件 | POST /runs, GET /runs/{id}/stream | ⚠未实现 |
| E2E-9 | 审计查询: GET /audit/records?taskId={id} → 200 (LLM/Tool 调用记录) | GET /audit/records | ⚠未实现 |
| E2E-10 | SafeGuard 拦截: POST /runs (prompt 含敏感词) → 输出脱敏 | POST /runs | ⚠未实现 |

### 8.3 测试缺口

- **P0** `CostBudgetAdvisor` 三维度边界 + null 预算 + InterruptException — mock CostStore 返回边界值
- **P0** `CostBudgetAdvisor.afterNode` 记录成本 + 异常隔离 — mock CostStore.record
- **P0** `MicrometerObservationAdvisor` metrics (tokens/duration/tool calls) + traces 全链路 — mock MeterRegistry
- **P0** `SafeGuardAdvisor` before 过滤 prompt + after 过滤 LLM 输出 + 白名单优先 — 参数化敏感词场景
- **P0** `AuditAdvisor` 记录 LLM/Tool 调用 + 异常隔离 — mock AuditStore
- **P0** `SqlGuard` 9 类拒绝 + LIMIT 改写 + SHOW/DESCRIBE/EXPLAIN 白名单 — 参数化拒绝用例
- **P0** `CodePathGuard` 穿越/扩展名/大小/null — 参数化路径场景
- **P1** `MicrometerObservationAdvisor` noop 降级 (MeterRegistry=null)
- **P1** `SafeGuardAdvisor` 异常隔离
- **P1** `AuditAdvisor` order + 脱敏 args
- **P1** E2E缺失: GET /cost/summary, GET /cost/records REST 端点无 E2E 覆盖 — 见 E2E-1/2
- **P1** E2E缺失: GET /cost/users/{id}/summary, GET /cost/skills/{name}/summary REST 端点无 E2E 覆盖 — 见 E2E-3/4
- **P1** E2E缺失: GET /cost/* 401/403 认证权限路径无 E2E 覆盖 — 见 E2E-5/6
- **P1** E2E缺失: POST /runs 预算中断 PAUSED + SSE "paused" 事件无 E2E — 见 E2E-8
- **P1** E2E缺失: GET /audit/records 审计查询无 E2E — 见 E2E-9
- **P1** E2E缺失: POST /runs SafeGuard 拦截敏感词无 E2E — 见 E2E-10
- **P2** E2E缺失: POST /runs 429 RateLimiter 路径无 E2E — 见 E2E-7
- **P2** `CostBudgetAdvisor.startOfTodayMillis` 时区 + `RateLimiter` 小时窗口切换

### 8.4 Mock 策略
单元: CostStore/AuditStore/MeterRegistry/RedisTemplate/LlmClient=Mockito；集成: MockMvc + mock SecurityGateway/RateLimiter；SqlGuard/CodePathGuard/SafeGuardAdvisor 纯逻辑无 Mock。

---

## 9. 依赖与前置条件

`CostStore` SPI（内存默认）；`AuditStore` SPI（内存默认）；`SecurityGateway` Adapter（SpringSecurity/ShiroAdapter，宿主覆盖 bean）；`PrincipalResolver`（反射 `getId/getUserId/getUsername`）；`SnapAgentFilter`（order=`LOWEST_PRECEDENCE-10`，宿主 auth 之后）；宿主须放行 `/snap-agent/**` 并填充 SecurityContext。
Starter 层可选依赖: `io.micrometer:micrometer-observation`（缺失时 MicrometerObservationAdvisor noop）；`snap-agent.cost.enabled` (默认 false) 控制是否注入 CostBudgetAdvisor。

---

## 10. 可观测性

- **日志**: WARN "SQL rejected by guard: {reason}"；WARN "Redis command rejected (read-only): {cmd}"；WARN "SecurityContext 为空，无法解析 principal 类型 X"；INFO "Budget exceeded for user {u}: {cost} >= {limit}"；WARN "SafeGuard replaced sensitive word in prompt"；WARN "AuditStore.save failed, audit record lost"；INFO "Micrometer registry not available, observation noop"
- **审计**: AuditRecord{taskId,userId,nodeName,llmModel,inputTokens,outputTokens,toolName,args,result,truncated,timestamp,durationMs}；onApiAccess(userId,method,path,action,details)
- **指标**: llm_cost_total{user,skill,model}, budget_check_total{dimension,result}, budget_interrupt_total{dimension}, rate_limit_reject_total{reason}, sql_guard_reject_total{reason}, snap-agent.graph.node.duration{nodeName}, snap-agent.llm.tokens{type=input|output}, snap-agent.tool.calls{toolName}, snap-agent.llm.errors{errorType}, safeguard_replace_total{phase=before|after}, audit_save_total{result}

---

## 11. 原型与交互参考

GET /skills → 200 列表 / 401 UNAUTHORIZED / 403 FORBIDDEN；POST /runs → 200 taskId / 401 / 403 / 429 配额耗尽 / PAUSED 预算超额；GET /user-info → 200 `{authenticated,authorized,userId,username}`；GET /cost/summary → 200 `{total,userBreakdown,skillBreakdown}` / 401 / 403；GET /audit/records → 200 `[{taskId,userId,nodeName,toolName,timestamp}]`。

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 变更 |
|------|------|------|------|
| 1.0 | 2026-07-23 | TDD Bot | 初始版本 (CostRecord/CostStore/BudgetEnforcer + RateLimiter + SqlGuard/CodePathGuard + SecurityGateway) |
| 2.0 | 2026-07-25 | Team | 2.x 重构: CostBudgetAdvisor (合并 CostRecord/CostStore/BudgetEnforcer 为 Advisor) + MicrometerObservationAdvisor (新增 metrics+tracing) + SafeGuardAdvisor (新增内容审核) + AuditAdvisor (新增审计)，删除旧 CostTrackingLlmClient/BudgetEnforcer/AuditCallback，RateLimiter/SqlGuard/CodePathGuard/SecurityGateway 保留不变 |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` (Section 2 Advisor + SafeGuard + Cost + Observation, Section 1 GraphExecutor InterruptException)
- `docs/embeed-skills-agent/07-config-security.md`（历史）
- `docs/superpowers/specs/2026-07-21-user-display-name-spi-design.md`（历史）
- `docs/embeed-skills-agent/04-tools-and-mcp.md`（守卫细节）
- `docs/tdd/TEMPLATE.md`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| Advisor | 2.x 统一切面 SPI，beforeNode/afterNode 包裹 Node 执行 |
| CostBudgetAdvisor | 合并旧 CostRecord/CostStore/BudgetEnforcer 的 Advisor (order=300)，before 检查预算 after 记录成本 |
| RateLimiter | per-user 并发+小时配额，Controller 层图执行前检查（不变） |
| SecurityGateway SPI | 桥接宿主安全框架（Spring Security/Shiro/default），Controller 层调用（不变） |
| SqlGuard | SQL 只读强制（SELECT/SHOW/DESCRIBE/EXPLAIN 白名单+黑名单），ToolsNode 内（不变） |
| CodePathGuard | 路径白名单+穿越拒绝，ToolsNode 内（不变） |
| MicrometerObservationAdvisor | 2.x 新增 Advisor (order=10)，metrics (tokens/duration/tool calls) + traces (全链路) |
| SafeGuardAdvisor | 2.x 新增 Advisor (order=50)，before 过滤 prompt 敏感词，after 过滤 LLM 输出敏感内容 |
| AuditAdvisor | 2.x 新增 Advisor (order=400)，记录所有 LLM/Tool 调用，AuditStore 存审计 |
| InterruptException | 预算超额时抛出，GraphExecutor catch → TaskStatus.PAUSED + checkpoint |
| PrincipalResolver | principal→userId SPI（不变） |
| AdvisorNode | 包裹 Node 的 Advisor 链容器，正序 before + 逆序 after，Advisor 异常不阻塞主流程 |
