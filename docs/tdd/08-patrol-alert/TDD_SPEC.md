# TDD需求规格说明书 — 巡检告警与问题闭环 (Patrol, Alert & Issue Closure)

> 版本: 2.0 | 模块: `snap-agent-core/patrol` + `snap-agent-spring-boot-2x-starter/patrol` + `issue`

---

## 1. 需求元信息

```yaml
需求ID: REQ-PA-08
需求名称: 巡检调度/告警收敛/推送通道/异常监听/问题闭环
优先级: P0 | 迭代: v0.5+v0.9 | 状态: 开发中
```

**背景**: v0.5实现从"用户问→诊断"到"Agent主动巡检发现异常并推送"。v0.9补齐"问题→诊断→方案→修复→验证→沉淀"完整闭环。**目标**: 异常发现到告警<5分钟；去重率>70%；闭环率>80%。**范围**: ScheduledPatrolScheduler、InMemoryAlertConverger、AlertPushChannel SPI、Webhook/Email通道、DefaultAnomalyEventListener、IssueClosureService。**不含**: Jira/GitHub IssueTracker（v0.9.1）。

| 风险 | 描述 | 缓解 |
|------|------|------|
| R1 | InMemoryConverger重启丢失 | v0.9.1 DB存储 |
| R2 | 多Pod巡检重复 | PatrolLockProvider SPI，Noop默认单Pod |
| R3 | 推送通道异常丢告警 | 异常捕获不影响其他通道 |
| R4 | ALERT_SUMMARY依赖LLM格式 | 正则大小写不敏感+去尾部markdown |

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
AC2: Given lockProvider.tryAcquire=false / When 巡检触发 / Then 跳过执行，不调AgentExecutor，记录INFO
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
AC2: Given Skill返回FAILED / When 巡检完成 / Then anomalyDetected=true
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
AC1: Given AnomalyEvent skillName=null / When onEvent / Then 默认skill="error-spike-investigation"，AgentExecutor调用，报告保存+推送
AC2: Given skillName="custom-diag" / When onEvent / Then SkillRegistry.get("custom-diag")调用
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
AC2: Given status=FIX_IN_PROGRESS / When verify / Then 运行verify-fix Skill，status=VERIFIED
AC3: Given status=VERIFIED / When close / Then 提取KnowledgeFragment+reload，status=CLOSED
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

### 3.2 详细用例 (Gherkin)

#### UC-03/04/05: 异常检测 (ALERT_SUMMARY / FAILED / 正常)
```gherkin
@priority:high @type:unit
功能: 巡检后异常判定与推送
  场景: LLM输出ALERT_SUMMARY
    Given report="CPU at 95%\nALERT_SUMMARY: CPU critically high"
    When 巡检完成
    Then extractAlertSummary返回"CPU critically high"，anomalyDetected=true
    And AlertConverger.record调用，pushChannel.push调用
  场景: 大小写不敏感+去尾部markdown
    Given report="alert_summary: **disk full**"
    Then 返回"**disk full"（去尾部*号）
  场景: 无ALERT_SUMMARY且SUCCEEDED时无异常
    Given report="All systems normal" status=SUCCEEDED
    Then 返回null，anomalyDetected=false，不触发推送
  场景: FAILED状态自动判定异常
    Given AgentExecutor返回TaskStatus.FAILED
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
功能: DefaultAnomalyEventListener触发诊断Skill
  场景: 默认skill触发
    Given event skillName=null
    When onEvent
    Then SkillRegistry.get("error-spike-investigation")调用，AgentExecutor.execute调用
    And 报告保存anomalyDetected=true，pushChannel.push调用
  场景: skill未找到存储失败报告
    Given skillName="nonexistent" SkillRegistry返回null
    Then 报告status=FAILED summary含"Skill not found"，AgentExecutor未调用
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
    Then 返回null(save未调用) 或 suggester调用(Executor未调用)
  场景: 验证修复
    Given status=FIX_IN_PROGRESS verify-fix report含"通过"
    When verify("issue-001")
    Then passed=true status=VERIFIED
  场景: 验证—Runner返回null fallback Skill / Issue不存在返回null
    Given VerificationRunner返回null 或 issueStore.load返回null
    Then AgentExecutor.execute调用(Skill路径) 或 返回null
  场景: 关闭+沉淀
    Given status=VERIFIED
    When close("issue-005")
    Then KnowledgeFragment提取，reload调用，status=CLOSED，knowledgeEntryId="sedimentation:issue-005"
  场景: 关闭—knowledgeBase=null不报错
    Given knowledgeBase=null
    Then status=CLOSED，reload未调用
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
IssueClosure verify(String issueId); // →VERIFIED
IssueClosure close(String issueId); // →CLOSED+沉淀
```

---

## 5. 数据规格 (Data Specs)
```yaml
PatrolTask: id/name/skillName/cron/userId/enabled/inputs/alertKeywords
PatrolReport: id/patrolId/taskId/userId/skillName/triggeredAt/status/summary/anomalyDetected
AnomalyEvent: type/source/message/timestamp/metadata/skillName/inputs
AlertConvergence: 线程安全(count=AtomicInteger,lastSeen/status=volatile)，STATUS_ACTIVE/RESOLVED
IssueStatus: DIAGNOSED→SOLUTION_PROPOSED→FIX_IN_PROGRESS→VERIFIED→CLOSED
```

---

## 6. 错误处理规格
| 场景 | 行为 | 日志 |
|------|------|------|
| Skill未找到/AgentExecutor异常 | 存储FAILED报告 | ERROR |
| 推送通道/httpPost/MailException | 捕获不传播，不影响其他通道 | ERROR |
| AlertConverger=null | 仅推送不收敛 | - |
| reportStore=null | 不存储仍执行诊断 | - |
| Issue不存在 | 返回null | WARN |
| verify-fix Skill未找到 | 返回null(legacy) | ERROR |

---

## 7. 非功能需求 (NFR)
- [x] AlertConvergence线程安全(AtomicInteger+volatile) — 推送通道异常隔离 — 多Pod锁竞争 — 环形缓冲区有界内存 — 自动解决过期告警(懒检查)

---

## 8. 测试策略 (Test Strategy)

### 8.1 已有测试覆盖

| 测试类 | 模块 | 覆盖内容 | 数量 |
|--------|------|----------|------|
| ScheduledPatrolSchedulerTest | starter | extractAlertSummary(7)/schedule(3)/cancel(2)/toggle(2)/list+reports(3)/executePatrol(8)/构造器(4) | 29 |
| InMemoryAlertConvergerTest | starter | record新建/去重递增/不同source/query排序/resolve/count/环形淘汰 | 7 |
| WebhookAlertPushChannelTest | starter | 非异常跳过/null跳过/JSON payload/event块/无auth/IOException吞/type/超时默认 | 8 |
| EmailAlertPushChannelTest | starter | 非异常跳过/null跳过/空收件人/邮件字段/默认prefix/默认from/默认event/MailException吞/type | 9 |
| DefaultAnomalyEventListenerTest | starter | 触发skill/指定skill/默认skill/skill未找到/异常存储/null converger/null store/合并inputs/通道异常/null通道/四参构造 | 12 |
| IssueClosureServiceTest | starter | propose(3)/createIssue(3)/verify(3)/close(3)/SPI路径(3) | 15 |
| PatrolModelTest | core | AnomalyEvent(7)/AlertConvergence(5)/PatrolTask(7)/PatrolReport(3)/BugfixSuggestion(7) | 29 |

### 8.2 测试缺口

| 缺口 | 描述 | 优先级 | 涉及类 |
|------|------|--------|--------|
| GAP-1 | autoResolveStale独立测试缺失 — 未直接验证过期告警自动转RESOLVED | P1 | InMemoryAlertConverger |
| GAP-2 | query按type过滤分支未覆盖 | P1 | InMemoryAlertConverger |
| GAP-3 | count按type过滤分支未覆盖 | P2 | InMemoryAlertConverger |
| GAP-4 | _user_message注入内容验证较弱 — 仅验证executor调用 | P1 | ScheduledPatrolScheduler |
| GAP-5 | PatrolEndpoint集成测试覆盖面有限 | P2 | PatrolEndpointIntegrationTest |
| GAP-6 | listIssues/loadIssue方法测试缺失 | P2 | IssueClosureService |
| GAP-7 | 多推送通道并发场景缺失 | P2 | Scheduler/Listener |
| GAP-8 | NoopLockProvider/NoopIssueTracker覆盖较浅 | P3 | Noop* |

### 8.3 Mock策略
TaskScheduler(mock可立即执行) / AgentExecutor(mock设status/report) / SkillRegistry(mock) / JavaMailSender(mock+ArgumentCaptor) / AlertConverger/IssueStore/IssueTracker/KnowledgeBase(mock) / PatrolLockProvider(mock默认true) / HTTP测试(继承覆写httpPost捕获参数)

---

## 9. 依赖与前置条件
| 依赖 | 状态 | 降级 |
|------|------|------|
| AgentExecutor/SkillRegistry | 已完成(v0.1) | 同步/未找到存FAILED |
| TaskScheduler | Spring自带 | NoopLockProvider单Pod |
| JavaMailSender | 可选(starter-mail) | @ConditionalOnClass保护 |
| KnowledgeBase | 已完成(v0.7) | null时跳过reload |
| IssueStore/Tracker | 已完成(v0.9) | Noop默认空实现 |

---

## 10. 可观测性设计
```yaml
日志: 调度/锁跳过/执行/异常检测=INFO, 推送=INFO, 推送失败=ERROR, Issue状态转换=INFO
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
### 参考文档
`docs/superpowers/specs/2026-07-16-v0.5-active-monitoring-design.md` / `2026-07-16-v0.9-issue-closure-design.md` / `docs/tdd/TEMPLATE.md`
### 术语表
| 术语 | 定义 |
|------|------|
| PatrolTask | 定时巡检任务(cron/skill/inputs) |
| ALERT_SUMMARY | LLM巡检模式输出的异常标记行 |
| AlertConvergence | 收敛告警(指纹/计数/状态)，指纹=SHA-256(type\|source)前16字符 |
| AlertPushChannel | 推送通道SPI(webhook/email) |
| AnomalyEventListener | 异常事件监听SPI，触发诊断Skill |
| IssueClosure | 问题闭环记录(状态机全生命周期) |
| IssueStatus | DIAGNOSED→PROPOSED→FIX_IN_PROGRESS→VERIFIED→CLOSED |
