# TDD需求规格说明书 — 巡检告警与问题闭环 (Patrol, Alert & Issue Closure)

> 版本: 2.1 (SnapAgent 2.x 架构重构) | 模块: `snap-agent-core/patrol` + `snap-agent-spring-boot-2x-starter/patrol` + `issue`
> 变更: PatrolScheduler 内部从 AgentExecutor 切换到 ReActGraphFactory + GraphExecutor; 告警收敛和 issue 闭环作为图执行后处理; 知识沉淀注入改由 RetrievalAugmentationAdvisor 承担

---

## 1. 需求元信息

```yaml
需求ID: REQ-PA-08
需求名称: 巡检调度/告警收敛/推送通道/异常监听/问题闭环
优先级: P0 | 迭代: v0.5+v0.9 | 状态: 开发中
```

**背景**: v0.5实现从"用户问→诊断"到"Agent主动巡检发现异常并推送"。v0.9补齐"问题→诊断→方案→修复→验证→沉淀"完整闭环。SnapAgent 2.x 中，PatrolScheduler 内部用 ReActGraphFactory.build(skill, task, advisors) 编译 ReAct 图并由 GraphExecutor 执行；告警收敛、推送和 issue 闭环作为图执行后的后处理步骤；问题沉淀知识通过 RetrievalAugmentationAdvisor 注入后续诊断图。**目标**: 异常发现到告警<5分钟；去重率>70%；闭环率>80%。**范围**: ScheduledPatrolScheduler、InMemoryAlertConverger、AlertPushChannel SPI、Webhook/Email通道、DefaultAnomalyEventListener、IssueClosureService、IssueClosureNode (可选终态节点)。**不含**: Jira/GitHub IssueTracker（v0.9.1）。

| 风险 | 描述 | 缓解 |
|------|------|------|
| R1 | InMemoryConverger重启丢失 | v0.9.1 DB存储 |
| R2 | 多Pod巡检重复 | PatrolLockProvider SPI，Noop默认单Pod |
| R3 | 推送通道异常丢告警 | 异常捕获不影响其他通道 |
| R4 | ALERT_SUMMARY依赖LLM格式 | 正则大小写不敏感+去尾部markdown |
| R5 | 图执行 checkpoint 失败致状态不一致 | GraphExecutor 失败不阻塞主流程，降级 RUNNING→FAILED |

---

## 2. 用户故事 (User Stories)

### US-1: 定时巡检调度
```gherkin
作为 运维管理员
我希望 通过cron定时调度巡检执行诊断Skill
以便 实现无需人工触发的持续健康检查
```
**AC:**
```gherkin
AC1: Given cron="0 */5 * * * ?" enabled=true / When schedule(task) / Then TaskScheduler.schedule调用，ID为null时自动生成"patrol_N"
AC2: Given lockProvider.tryAcquire=false / When 巡检触发 / Then 跳过执行，不调 GraphExecutor，记录INFO
```

### US-2: 巡检执行与异常检测
```gherkin
作为 平台开发者
我希望 巡检后通过ALERT_SUMMARY自动判断异常
以便 LLM自身决定是否告警，无需关键词匹配
```
**AC:**
```gherkin
AC1: Given report含"ALERT_SUMMARY: CPU high" / When 巡检完成 / Then anomalyDetected=true，AlertConverger.record调用，推送通道收到告警
AC2: Given Skill返回FAILED (GraphExecutor 抛异常或 TaskStatus.FAILED) / When 巡检完成 / Then anomalyDetected=true
AC3: Given report无ALERT_SUMMARY且状态SUCCEEDED / Then anomalyDetected=false，不触发推送
```

### US-3: 告警收敛与指纹去重
```gherkin
作为 平台开发者
我希望 相同type+source的异常收敛为一条并累加计数
以便 避免告警风暴，聚焦真实问题
```
**AC:**
```gherkin
AC1: Given 两次event type+source相同 / When record / Then 同一alertId，count=2，lastSeen更新
AC2: Given type相同source不同 / When record / Then 不同alertId，各自count=1
AC3: Given 容量=3已满 / When record第4条 / Then 最旧淘汰，总数=3
AC4: Given lastSeen超过autoResolveMinutes / When query / Then status=RESOLVED
```

### US-4: Webhook告警推送
```gherkin
作为 运维管理员
我希望 异常告警通过Webhook推送到外部系统
以便 对接钉钉/Slack/企业微信
```
**AC:**
```gherkin
AC1: Given anomalyDetected=true / When push / Then POST JSON含patrol_id/status/anomaly_detected/report_summary
AC2: Given anomalyDetected=false / When push / Then 不发送HTTP
AC3: Given authToken="Bearer token" / Then 请求头含Authorization
AC4: Given httpPost抛IOException / Then 异常捕获不传播
```

### US-5: 邮件告警推送
```gherkin
作为 运维管理员
我希望 异常告警通过邮件推送给运维团队
以便 离线场景也能收到通知
```
**AC:**
```gherkin
AC1: Given anomalyDetected=true收件人非空 / When push / Then JavaMailSender.send调用，subject含类型+状态，body含摘要
AC2: Given 收件人空 / When push / Then 不发送，记录WARN
```

### US-6: 异常事件触发诊断
```gherkin
作为 平台开发者
我希望 外部系统异常事件能触发诊断Skill
以便 实现事件驱动自动诊断
```
**AC:**
```gherkin
AC1: Given AnomalyEvent skillName=null / When onEvent / Then 默认skill="error-spike-investigation"，ReActGraphFactory.build + GraphExecutor.execute 调用，报告保存+推送
AC2: Given skillName="custom-diag" / When onEvent / Then SkillRegistry.get("custom-diag") 调用并以此 skill 编译图
```

### US-7: 问题闭环状态机
```gherkin
作为 平台开发者
我希望 问题从诊断到关闭有状态机管理
以便 确保有序推进，经验最终沉淀
```
**AC:**
```gherkin
AC1: Given 诊断任务完成 / When proposeSolution / Then status=SOLUTION_PROPOSED，含方案选项
AC2: Given status=FIX_IN_PROGRESS / When verify / Then 运行verify-fix Skill (经 ReActGraphFactory 编译)，status=VERIFIED
AC3: Given status=VERIFIED / When close / Then 提取KnowledgeFragment+reload，status=CLOSED
```

### US-8: 外部工单创建与状态流转
```gherkin
作为 运维工程师
我希望 告警确认后自动创建外部工单并流转状态
  以便 问题在工单系统中跟踪而非仅停留在告警
```
**AC:**
```gherkin
AC1: Given issue 状态为 OPEN 且 issueTracker 配置完成
  When createExternalIssue(issue)
  Then issue.status 变为 FIX_IN_PROGRESS
  And issueTracker.createIssue 被调用一次
AC2: Given issueTracker 未配置 (NoopIssueTracker)
  When createExternalIssue(issue)
  Then 不抛异常，issue.status 保持不变
AC3: Given issue.status 为 RESOLVED
  When createExternalIssue(issue)
  Then 不创建工单 (仅 OPEN 状态可创建)
```

### US-9: 问题沉淀知识注入图运行时 (2.x 重构)
```gherkin
作为 运维工程师
我希望 问题关闭后沉淀的知识通过 RetrievalAugmentationAdvisor 自动注入后续诊断图的 agent 节点
  以便 下次同类问题诊断时 Agent 可参考历史经验
```
**AC:**
```gherkin
AC1: Given issue 关闭时 KnowledgeSedimentationExtractor 提取 KnowledgeFragment
  When KnowledgeBase.reload() / VectorStore.add(docs)
  Then 下次 ReActGraphFactory.build(skill, task, advisors) 时 RetrievalAugmentationAdvisor.beforeNode("agent") 返回含经验沉淀的片段
  And state["rag.context"] 含"业务知识参考"和"经验沉淀"标记

AC2: Given KnowledgeBase 与 VectorStore 均为 null
  When issue.close()
  Then 不报错，status=CLOSED
  And RetrievalAugmentationAdvisor 无新片段可注入 (state["rag.context"] 为空)
```

### US-10: IssueClosureNode 作为可选终态节点 (2.x 新增)
```gherkin
作为 平台开发者
我希望 agent end_turn 后可路由到 IssueClosureNode 完成闭环
  以便 闭环逻辑嵌入图而非散落在 PatrolScheduler
```
**AC:**
```gherkin
AC1: Given 配置 snap-agent.patrol.issue-closure-node=true
  When ReActGraphFactory.build(skill, task, advisors) 编译
  Then CompiledGraph 含 IssueClosureNode 作为可选终态
  And ShouldContinue 路由 end_turn → IssueClosureNode → END

AC2: Given snap-agent.patrol.issue-closure-node=false (默认)
  When 图编译
  Then 不含 IssueClosureNode，end_turn → END (legacy 路径)
  And 闭环由 PatrolScheduler 后处理调用 IssueClosureService
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 发现 | US-1 调度 | 主动监控 | 执行率>95% | - |
| 检测 | US-2 异常检测 | LLM自判定 | 提取率100% | US-1 |
| 收敛 | US-3 去重 | 避免风暴 | 去重>70% | US-2 |
| 通知 | US-4 Webhook | 外部对接 | 成功>99% | US-3 |
| 通知 | US-5 邮件 | 离线通知 | 送达>95% | US-3 |
| 诊断 | US-6 事件触发 | 事件驱动 | 延迟<30s | US-2 |
| 治理 | US-7 闭环 | 经验沉淀 | 闭环>80% | US-6 |
| 闭环 | US-8 | 工单跟踪 | 创建率 100% | US-7 |
| 沉淀 | US-9 | 经验注入图 | 沉淀注入 100% | US-7 |
| 图集成 | US-10 | 终态节点 | 路由正确 100% | US-7 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单
| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | 调度注册+自动ID | P0 | US-1 AC1 | 单元 |
| UC-02 | 锁竞争跳过 | P0 | US-1 AC2 | 单元 |
| UC-03/04/05 | 异常检测(ALERT_SUMMARY/FAILED/正常) | P0 | US-2 | 单元 |
| UC-06/07/08 | 告警收敛(去重/淘汰/自动解决) | P0 | US-3 | 单元 |
| UC-09/10/11 | 推送通道(Webhook+Email) | P0 | US-4/5 | 单元 |
| UC-12 | 事件触发默认Skill | P0 | US-6 AC1 | 单元 |
| UC-13/14/15 | 问题闭环(方案/验证/关闭) | P0 | US-7 | 单元 |
| UC-16 | 问题沉淀→RetrievalAugmentationAdvisor | P1 | US-9 | 单元 |
| UC-17 | IssueClosureNode 路由 | P1 | US-10 | 单元 |
| UC-R1 | POST /patrol/tasks 创建巡检任务 | P0 | US-1 AC1 | 集成 |
| UC-R2 | GET /patrol/tasks 列出巡检任务 | P1 | - | 集成 |
| UC-R3 | DELETE /patrol/tasks/{id} 删除巡检任务 | P1 | - | 集成 |
| UC-R4 | PATCH /patrol/tasks/{id}/toggle 启停巡检 | P1 | - | 集成 |
| UC-R5 | POST /patrol/infer 手动触发巡检推断 | P0 | US-2 | 集成 |
| UC-R6 | GET /patrol/reports 巡检报告列表 | P1 | - | 集成 |
| UC-R7 | GET /patrol/reports/{id} 报告详情 | P1 | - | 集成 |
| UC-R8 | GET /alerts 告警列表 | P0 | US-3 | 集成 |
| UC-R9 | POST /alerts/{id}/resolve 解决告警 | P0 | US-3 | 集成 |
| UC-R10 | POST /runs/{id}/bugfix-suggestion 修复建议 | P0 | US-7 | 集成 |
| UC-R11 | POST /runs/{taskId}/solution 提交方案 | P0 | US-7 | 集成 |
| UC-R12 | POST /runs/{taskId}/issue 创建问题 | P0 | US-7 | 集成 |
| UC-R13 | GET /issues/recent-runs 最近问题运行 | P1 | - | 集成 |
| UC-R14 | GET /issues 问题列表 | P1 | - | 集成 |
| UC-R15 | GET /issues/{issueId} 问题详情 | P1 | - | 集成 |
| UC-R16 | POST /issues/{issueId}/verify 验证问题 | P0 | US-7 | 集成 |
| UC-R17 | POST /issues/{issueId}/close 关闭问题 | P0 | US-7 | 集成 |

### 3.2 详细用例 (Gherkin)

#### UC-03/04/05: 异常检测 (ALERT_SUMMARY / FAILED / 正常)
```gherkin
@priority:high @type:unit
功能: 巡检后异常判定与推送 (基于 GraphExecutor 输出)
  场景: LLM输出ALERT_SUMMARY
    Given report="CPU at 95%\nALERT_SUMMARY: CPU critically high"
    When 巡检完成 (GraphExecutor.execute 返回 SUCCEEDED)
    Then extractAlertSummary返回"CPU critically high"，anomalyDetected=true
    And AlertConverger.record调用，pushChannel.push调用
  场景: 大小写不敏感+去尾部markdown
    Given report="alert_summary: **disk full**"
    Then 返回"**disk full"（去尾部*号）
  场景: 无ALERT_SUMMARY且SUCCEEDED时无异常
    Given report="All systems normal" status=SUCCEEDED
    Then 返回null，anomalyDetected=false，不触发推送
  场景: FAILED状态自动判定异常
    Given GraphExecutor.execute 返回 TaskStatus.FAILED
    Then anomalyDetected=true，AlertConverger.record调用
```

#### UC-06/07/08: 告警收敛 (去重 / 淘汰 / 自动解决)
```gherkin
@priority:high @type:unit
功能: InMemoryAlertConverger去重+淘汰+自动解决
  场景: 相同type+source收敛
    Given event1和event2 type="ERROR_SPIKE" source="order-svc"
    When 分别record
    Then 返回相同alertId，count=2，lastSeen更新
  场景: 不同source独立告警
    Given event1 source="order-svc" event2 source="payment-svc"
    Then 返回不同alertId，各自count=1
  场景: 环形淘汰 — 容量3存第4条
    Given 容量=3已存svc-a/svc-b/svc-c
    When record source="svc-d"
    Then count=3，不含svc-a，含svc-b/svc-c/svc-d
  场景: 自动解决 — 超过autoResolveMinutes
    Given alert lastSeen距今超过阈值
    When query触发autoResolveStale
    Then alert.status=RESOLVED
```

#### UC-09/10/11: 推送通道 (Webhook + Email)
```gherkin
@priority:high @type:unit
功能: AlertPushChannel推送（仅anomalyDetected=true触发）
  场景: Webhook POST JSON
    Given anomalyDetected=true patrolId="patrol_42"
    When push(report, null)
    Then httpPost调用，headers含Content-Type+Authorization
    And JSON含patrol_id/anomaly_detected/report_summary
  场景: Webhook含event块
    Given event type="HIGH_CPU" source="metrics"
    Then JSON含event.type="HIGH_CPU"
  场景: Webhook null report/non-anomaly跳过/authToken=null无认证头
    Given report=null 或 anomalyDetected=false 或 authToken=null
    Then httpPost未调用 或 headers仅含Content-Type
  场景: Webhook IOException被吞不传播
    Given httpPost抛IOException
    Then 异常捕获不向上传播
  场景: Email完整邮件
    Given anomalyDetected=true event type="DB_DOWN"
    When push(report, event)
    Then mailSender.send调用，subject含"DB_DOWN (CRITICAL)"，body含诊断摘要
  场景: Email event=null默认ANOMALY/空收件人跳过/MailException被吞
    Given event=null 或 收件人空 或 mailSender抛异常
    Then subject含"ANOMALY" 或 不发送 或 不传播
```

#### UC-12: 异常事件触发诊断
```gherkin
@priority:high @type:unit
功能: DefaultAnomalyEventListener触发诊断Skill (经 ReActGraphFactory + GraphExecutor)
  场景: 默认skill触发
    Given event skillName=null
    When onEvent
    Then SkillRegistry.get("error-spike-investigation")调用
    And ReActGraphFactory.build(skill, task, advisors) + GraphExecutor.execute 调用
    And 报告保存anomalyDetected=true，pushChannel.push调用
  场景: skill未找到存储失败报告
    Given skillName="nonexistent" SkillRegistry返回null
    Then 报告status=FAILED summary含"Skill not found"，GraphExecutor未调用
  场景: skill 不可用 (UNAVAILABLE)
    Given skill.available == UNAVAILABLE
    Then 报告status=FAILED summary含 unavailableReason
    And ReActGraphFactory.build 抛 SkillUnavailableException 被捕获
```

#### UC-13/14/15: 问题闭环 (方案/验证/关闭)
```gherkin
@priority:high @type:unit
功能: IssueClosureService状态机 DIAGNOSED→PROPOSED→FIX_IN_PROGRESS→VERIFIED→CLOSED
  场景: 方案生成
    Given 诊断task-001 report="Root cause: pool exhausted"
    When proposeSolution("task-001")
    Then status=SOLUTION_PROPOSED，options含2项，recommended="opt-1"，save调用
  场景: 方案—任务不存在返回null / SPI路径用SolutionSuggester
    Given taskStore.get返回null 或 SolutionSuggester已配置
    Then 返回null(save未调用) 或 suggester调用(GraphExecutor未执行)
  场景: 验证修复
    Given status=FIX_IN_PROGRESS verify-fix report含"通过"
    When verify("issue-001")
    Then passed=true status=VERIFIED (verify-fix Skill 经 ReActGraphFactory 编译)
  场景: 验证—Runner返回null fallback Skill / Issue不存在返回null
    Given VerificationRunner返回null 或 issueStore.load返回null
    Then GraphExecutor.execute调用(Skill路径) 或 返回null
  场景: 关闭+沉淀
    Given status=VERIFIED
    When close("issue-005")
    Then KnowledgeFragment提取，VectorStore.add 调用，status=CLOSED，knowledgeEntryId="sedimentation:issue-005"
  场景: 关闭—knowledgeBase=null不报错
    Given knowledgeBase=null 且 VectorStore=null
    Then status=CLOSED，VectorStore.add 未调用
```

#### UC-16: 问题沉淀→RetrievalAugmentationAdvisor
```gherkin
@priority:medium @type:unit
功能: 问题沉淀知识通过 RetrievalAugmentationAdvisor 注入 agent 节点
  场景: 关闭后沉淀知识可被后续诊断检索
    Given IssueClosure(issueId="issue-005", userQuery="为什么订单服务超时?", rootCause="连接池打满")
    And KnowledgeSedimentationExtractor.extract 提取 KnowledgeFragment
    When close("issue-005")
    Then KnowledgeBase.reload + VectorStore.add 被调用
    And 下次 ReActGraphFactory.build(skill, task, advisors) 时
    And RetrievalAugmentationAdvisor.beforeNode("agent", state, ctx) 调用 DocumentRetriever.retrieve
    And state["rag.context"] 返回非空含"经验沉淀"和"连接池打满"

  场景: knowledgeBase=null + VectorStore=null 时关闭不报错且不注入
    Given knowledgeBase=null 且 VectorStore=null
    When close("issue-005")
    Then status=CLOSED，不抛异常
    And RetrievalAugmentationAdvisor 检索结果为空 (state["rag.context"] 为空串)
```

#### UC-17: IssueClosureNode 路由
```gherkin
@priority:medium @type:unit
功能: IssueClosureNode 作为可选终态节点
  场景: 启用 issue-closure-node
    Given snap-agent.patrol.issue-closure-node=true
    When ReActGraphFactory.build(skill, task, advisors) 编译
    Then CompiledGraph.getNodes() 含 "issue_closure" 节点
    And ShouldContinue 路由 end_turn → "issue_closure" → END
    And IssueClosureNode.execute 调用 IssueClosureService.close(taskId)

  场景: 默认禁用 (legacy 路径)
    Given snap-agent.patrol.issue-closure-node=false (默认)
    When 图编译
    Then CompiledGraph.getNodes() 不含 "issue_closure"
    And ShouldContinue 路由 end_turn → END
    And 闭环由 PatrolScheduler 后处理调用 IssueClosureService
```

---

## 4. 接口规格 (API Specs)

### 4.1 PatrolScheduler SPI
```java
void schedule(PatrolTask task); // ID为null自动生成patrol_N
void cancel(String patrolId); // 移除+cancel future
Boolean toggleEnabled(String patrolId); // 切换enabled
List<PatrolReport> getReports(String userId, int limit, int offset);
long countReports(String userId);
// 内部: build via ReActGraphFactory + execute via GraphExecutor
```

### 4.2 AlertConverger SPI
```java
AlertConvergence record(AnomalyEvent event); // 指纹=SHA-256(type|source)前16字符
List<AlertConvergence> query(String userId, String type, int limit, int offset);
long count(String userId, String type);
void resolve(String alertId);
```

### 4.3 AlertPushChannel SPI
```java
void push(PatrolReport report, AnomalyEvent event); // 仅anomalyDetected=true触发
String type(); // "webhook"/"email"
```

### 4.4 IssueClosureService
```java
IssueClosure proposeSolution(String taskId); // DIAGNOSED→SOLUTION_PROPOSED
IssueClosure createExternalIssue(String taskId, String selectedSolution); // →FIX_IN_PROGRESS
IssueClosure verify(String issueId); // →VERIFIED (verify-fix 经 ReActGraphFactory 编译)
IssueClosure close(String issueId); // →CLOSED+沉淀 (VectorStore.add)
```

### 4.5 IssueClosureNode (2.x 新增)
```java
/**
 * 可选终态节点，agent end_turn 后路由到此。
 * 调用 IssueClosureService.close(taskId) 完成闭环。
 * 仅当 snap-agent.patrol.issue-closure-node=true 时由 ReActGraphFactory 加入图。
 */
public class IssueClosureNode implements Node {
    public String getName() { return "issue_closure"; }
    public GraphState execute(GraphState state, ExecutionContext ctx);
}
```

---

## 5. 数据规格 (Data Specs)
```yaml
PatrolTask: id/name/skillName/cron/userId/enabled/inputs/alertKeywords
PatrolReport: id/patrolId/taskId/userId/skillName/triggeredAt/status/summary/anomalyDetected
AnomalyEvent: type/source/message/timestamp/metadata/skillName/inputs
AlertConvergence: 线程安全(count=AtomicInteger,lastSeen/status=volatile)，STATUS_ACTIVE/RESOLVED
IssueStatus: DIAGNOSED→SOLUTION_PROPOSED→FIX_IN_PROGRESS→VERIFIED→CLOSED
CheckpointStore: 2.x 新增 — 巡检任务失败可 resume (threadId="patrol:{patrolId}:{runId}")
```

---

## 6. 错误处理规格
| 场景 | 行为 | 日志 |
|------|------|------|
| Skill未找到/ReActGraphFactory 抛 SkillUnavailableException | 存储FAILED报告 | ERROR |
| GraphExecutor.execute 异常 (LLM/tool) | catch → checkpoint → FAILED 报告 | ERROR |
| 推送通道/httpPost/MailException | 捕获不传播，不影响其他通道 | ERROR |
| AlertConverger=null | 仅推送不收敛 | - |
| reportStore=null | 不存储仍执行诊断 | - |
| Issue不存在 | 返回null | WARN |
| verify-fix Skill未找到 | 返回null(legacy) | ERROR |
| IssueClosureNode 异常 | catch → END 不阻塞图成功 | WARN |

---

## 7. 非功能需求 (NFR)
- [x] AlertConvergence线程安全(AtomicInteger+volatile) — 推送通道异常隔离 — 多Pod锁竞争 — 环形缓冲区有界内存 — 自动解决过期告警(懒检查)
- [x] 2.x: GraphExecutor 失败 → checkpoint → 后续可 resume，巡检任务可断点续传

---

## 8. 测试策略 (Test Strategy)

### 8.1 已有测试覆盖

| 测试类 | 模块 | 覆盖内容 | 数量 |
|--------|------|----------|------|
| ScheduledPatrolSchedulerTest | starter | extractAlertSummary(7)/schedule(3)/cancel(2)/toggle(2)/list+reports(3)/executePatrol(8, 含 ReActGraphFactory+GraphExecutor 调用断言)/构造器(4) | 29 |
| InMemoryAlertConvergerTest | starter | record新建/去重递增/不同source/query排序/resolve/count/环形淘汰 | 7 |
| WebhookAlertPushChannelTest | starter | 非异常跳过/null跳过/JSON payload/event块/无auth/IOException吞/type/超时默认 | 8 |
| EmailAlertPushChannelTest | starter | 非异常跳过/null跳过/空收件人/邮件字段/默认prefix/默认from/默认event/MailException吞/type | 9 |
| DefaultAnomalyEventListenerTest | starter | 触发skill/指定skill/默认skill/skill未找到/异常存储/null converger/null store/合并inputs/通道异常/null通道/四参构造/SkillUnavailableException | 13 |
| IssueClosureServiceTest | starter | propose(3)/createIssue(3)/verify(3, 含 ReActGraphFactory 编译 verify-fix)/close(3, 含 VectorStore.add)/SPI路径(3)/IssueClosureNode 路由(2) | 17 |
| PatrolModelTest | core | AnomalyEvent(7)/AlertConvergence(5)/PatrolTask(7)/PatrolReport(3)/BugfixSuggestion(7) | 29 |

### 8.2 E2E 关键路径

| 路径ID | 关键路径 | 端点 | 状态 |
|--------|----------|------|------|
| E2E-1 | Patrol 任务全生命周期: POST /patrol/tasks (创建) → GET /patrol/tasks (列表) → POST /patrol/tasks/{id}/toggle (启停) → GET /patrol/reports (报告) | POST /patrol/tasks, GET /patrol/tasks, POST /patrol/tasks/{id}/toggle, GET /patrol/reports | ⚠部分覆盖 (GAP-5) |
| E2E-2 | Patrol LLM 推断: POST /patrol/infer (content) → 200 (anomaly 检测结果) | POST /patrol/infer | ⚠未实现 (GAP-9) |
| E2E-3 | Alert 全生命周期: GET /alerts (列表) → POST /alerts/{id}/resolve (解决) → GET /alerts (验证已解决) | GET /alerts, POST /alerts/{id}/resolve | ⚠未实现 (GAP-10) |
| E2E-4 | Issue 闭环流程: POST /anchor/inject (proposeSolution) → POST /issues (createIssue) → POST /issues/{id}/verify → POST /issues/{id}/close | POST /anchor/inject, POST /issues, POST /issues/{id}/verify, POST /issues/{id}/close | ⚠未实现 (GAP-11) |
| E2E-5 | Patrol → Alert 联动: POST /patrol/infer → anomaly → Alert 自动汇聚 → GET /alerts 验证告警 | POST /patrol/infer, GET /alerts | ⚠未实现 (GAP-12) |
| E2E-6 | 认证/权限: GET /alerts 无认证 → 401 / 无权限 → 403 | GET /alerts | ⚠未实现 (GAP-13) |
| E2E-7 | Checkpoint resume: 巡检失败 → checkpoint → POST /runs/{id}/resume → 继续执行 | POST /runs/{id}/resume | ⚠未实现 (GAP-14) |

### 8.3 测试缺口

| 缺口 | 描述 | 优先级 | 涉及类 |
|------|------|--------|--------|
| GAP-1 | ✅已关闭: autoResolveStale 已由 `InMemoryAlertConvergerTest` 覆盖 (shouldAutoResolveStaleAlertsOnQuery/shouldNotReprocessResolvedAlerts) | — | P1 |
| GAP-2 | ✅已关闭: query/count按type过滤已由 `InMemoryAlertConvergerTest` 覆盖 (shouldQueryFilterByType/shouldQueryReturnAllWhenTypeIsNullOrEmpty/shouldQueryReturnEmptyForUnknownType/shouldCountFilterByType/shouldCountReturnTotalWhenTypeIsNullOrEmpty) | — | P1 |
| GAP-3 | ✅已关闭: count按type过滤已由 `InMemoryAlertConvergerTest` 覆盖 (shouldCountFilterByType/shouldCountReturnTotalWhenTypeIsNullOrEmpty) | — | P2 |
| GAP-4 | ✅已关闭: _user_message注入内容验证已由 `ScheduledPatrolSchedulerTest` 覆盖 (executePatrolShouldAppendPatrolSuffixToExistingUserMessage/executePatrolShouldSetPatrolSuffixAsUserMessageWhenAbsent/executePatrolShouldPreserveOtherInputsAlongsideUserMessage) | — | P1 |
| GAP-5 | ⚠部分覆盖: PatrolEndpoint 基本集成测试已有，但覆盖面有限 (缺少异常场景/权限边界) | P2 | 需扩展集成测试 |
| GAP-6 | ✅已关闭: listIssues/loadIssue 已由 `IssueClosureServiceTest` 覆盖 (shouldListIssuesDelegatingToStore/shouldReturnEmptyListWhenNoIssuesExist/shouldLoadIssueDelegatingToStore/shouldReturnNullWhenLoadIssueNotFound) | — | P2 |
| GAP-7 | ⚠并发测试: 多推送通道并发场景需并发测试框架 (CountDownLatch/ExecutorService)，ScheduledPatrolSchedulerTest 已验证异常隔离但未验证并发安全 | P2 | 需并发测试 |
| GAP-8 | NoopLockProvider/NoopIssueTracker覆盖较浅 | P3 | Noop* |
| GAP-9 | ⚠E2E缺失: POST /patrol/infer REST 端点无 E2E 覆盖 — 见 E2E-2 | P1 | 需 E2E 集成测试 |
| GAP-10 | ⚠E2E缺失: GET /alerts + POST /alerts/{id}/resolve REST 端点无 E2E 覆盖 — 见 E2E-3 | P1 | 需 E2E 集成测试 |
| GAP-11 | ⚠E2E缺失: Issue 闭环流程 (propose→create→verify→close) 无端到端 E2E 覆盖 — 见 E2E-4 | P1 | 需 E2E 集成测试 |
| GAP-12 | ⚠E2E缺失: Patrol→Alert 联动 (infer→anomaly→converge→alert) 无 E2E 覆盖 — 见 E2E-5 | P2 | 需 E2E 集成测试 |
| GAP-13 | ⚠E2E缺失: GET /alerts 401/403 认证权限路径无 E2E 覆盖 — 见 E2E-6 | P2 | 需 E2E 集成测试 |
| GAP-14 | ⚠E2E缺失: 巡检任务 checkpoint resume (POST /runs/{id}/resume) 无 E2E 覆盖 — 见 E2E-7 | P2 | 需 E2E 集成测试 |
| GAP-15 | ✅已关闭: IssueClosureNode 路由已由 `IssueClosureServiceTest` 覆盖 (shouldRouteToEndViaIssueClosureNodeWhenEnabled/shouldSkipIssueClosureNodeWhenDisabled) | — | P1 |

### 8.4 Mock策略
TaskScheduler(mock可立即执行) / ReActGraphFactory(mock 返回预设 CompiledGraph) / GraphExecutor(mock设status/report) / SkillRegistry(mock) / JavaMailSender(mock+ArgumentCaptor) / AlertConverger/IssueStore/IssueTracker/VectorStore/KnowledgeBase(mock) / PatrolLockProvider(mock默认true) / HTTP测试(继承覆写httpPost捕获参数)

---

## 9. 依赖与前置条件
| 依赖 | 状态 | 降级 |
|------|------|------|
| ReActGraphFactory + GraphExecutor | 已完成 (2.x) | 同步/未找到存FAILED |
| SkillRegistry | 已完成(v0.1) | SkillUnavailableException |
| TaskScheduler | Spring自带 | NoopLockProvider单Pod |
| JavaMailSender | 可选(starter-mail) | @ConditionalOnClass保护 |
| VectorStore + EmbeddingModel | 可选 (2.x) | null 时跳过 reload |
| IssueStore/Tracker | 已完成(v0.9) | Noop默认空实现 |

---

## 10. 可观测性设计
```yaml
日志: 调度/锁跳过/执行/异常检测=INFO, 推送=INFO, 推送失败=ERROR, Issue状态转换=INFO, Checkpoint save=DEBUG
指标: patrol_graph_execution_duration_seconds, alert_converge_count, issue_state_transition_total
```

---

## 11. 原型与交互参考
不适用（纯后端SPI+服务模块，端点由Controller层暴露）。

---

## 12. 附录
### 变更历史
| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0 | 2026-07-23 | 基于v0.5+v0.9设计文档创建 |
| 2.1 | 2026-07-25 | 适配 2.x: PatrolScheduler 内部从 AgentExecutor 切换到 ReActGraphFactory + GraphExecutor; 告警收敛和 issue 闭环改为图执行后处理; 知识注入由 KnowledgeInjector 改为 RetrievalAugmentationAdvisor; 新增 IssueClosureNode 可选终态节点 (US-10 / UC-17); 沉淀目标改为 VectorStore |

### 参考文档
`docs/superpowers/specs/2026-07-16-v0.5-active-monitoring-design.md` / `2026-07-16-v0.9-issue-closure-design.md` / `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` / `docs/tdd/TEMPLATE.md`

### 术语表
| 术语 | 定义 |
|------|------|
| PatrolTask | 定时巡检任务(cron/skill/inputs) |
| ALERT_SUMMARY | LLM巡检模式输出的异常标记行 |
| AlertConvergence | 收敛告警(指纹/计数/状态)，指纹=SHA-256(type\|source)前16字符 |
| AlertPushChannel | 推送通道SPI(webhook/email) |
| AnomalyEventListener | 异常事件监听SPI，触发诊断Skill (经 ReActGraphFactory 编译) |
| IssueClosure | 问题闭环记录(状态机全生命周期) |
| IssueStatus | DIAGNOSED→PROPOSED→FIX_IN_PROGRESS→VERIFIED→CLOSED |
| IssueClosureNode | 2.x 可选终态图节点，agent end_turn 后路由到此完成闭环 |
| RetrievalAugmentationAdvisor | 2.x 知识注入 Advisor，替代 KnowledgeInjector (经 VectorStore 检索) |
