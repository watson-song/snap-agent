# SnapAgent 主动监控架构

> 版本：v0.5 | 更新日期：2026-07-17

SnapAgent 的主动监控能力由 `snap-agent-core` 的 `patrol` 包（SPI + 数据模型）和
`snap-agent-spring-boot-2x-starter` 的 `patrol` 包（默认实现 + 自动装配）共同提供。
本文档描述两种互补的监控模式——**巡检（Patrol）** 与 **告警（Alert）**——的完整架构。

---

## 1. 架构概览

主动监控分为两条独立的数据流，共享同一套 `AgentExecutor` 执行引擎和 `PatrolReportStore` 报告存储：

```
巡检流 (Patrol):
  ┌──────────────┐   cron 触发    ┌──────────────────┐   execute()   ┌──────────────┐
  │ PatrolTask   │ ────────────► │ ScheduledPatrol  │ ───────────► │ AgentExecutor │
  │ (skill+cron) │               │ Scheduler         │               │ (运行 skill)  │
  └──────────────┘               └────────┬─────────┘               └──────┬───────┘
                                          │                                 │
                                          │  PatrolReport                  │ task.getReport()
                                          ▼                                 ▼
                                        ┌──────────────────────────────────────┐
                                        │ PatrolReportStore (ring buffer, 500)  │
                                        └──────────────────────────────────────┘

告警流 (Alert):
  ┌──────────────┐  onEvent()   ┌──────────────────────┐  record()   ┌────────────────────┐
  │ AnomalyEvent │ ──────────► │ DefaultAnomaly        │ ─────────► │ InMemoryAlert      │
  │ (type/source)│             │ EventListener          │            │ Converger (去重)    │
  └──────────────┘             └────────┬─────────────┘            └─────────┬──────────┘
                                         │                                      │
                                         │ determine skill                      │ AlertConvergence
                                         │ (默认 error-spike-investigation)       │ (fingerprint 去重)
                                         ▼                                      ▼
                                       ┌──────────────┐   execute()   ┌──────────────────────────┐
                                       │ AgentExecutor │ ───────────► │ PatrolReportStore (存报告) │
                                       │ (运行 skill)  │               │ + AlertConverger (关联)   │
                                       └──────────────┘               └──────────────────────────┘
```

### 两种模式对比

| 维度 | 巡检 (Patrol) | 告警 (Alert) |
|------|--------------|-------------|
| 触发方式 | cron 表达式定时触发 | 外部系统推送 `AnomalyEvent` |
| 触发入口 | `PatrolScheduler.schedule(PatrolTask)` | `AnomalyEventListener.onEvent(AnomalyEvent)` |
| 执行 skill | PatrolTask.skillName（任意 skill） | AnomalyEvent.skillName（默认 `error-spike-investigation`） |
| 去重 | 无（每次 cron 独立执行） | SHA-256 指纹去重（type\|source） |
| 报告标记 | `anomalyDetected=false` | `anomalyDetected=true` |
| 存储位置 | PatrolReportStore | PatrolReportStore + AlertConverger |

### 核心接口一览

```
core/patrol/
├── PatrolScheduler.java        (SPI: cron 调度 + 报告查询)
├── AlertConverger.java         (SPI: 告警去重 + 查询)
├── AnomalyEventListener.java   (SPI: 接收异常事件)
├── BugfixSuggester.java        (SPI: 从 transcript 提取修复建议)
├── PatrolTask.java             (值对象: 调度任务定义)
├── PatrolReport.java           (值对象: 巡检/告警报告)
├── PatrolReportStore.java     (ring buffer 报告存储)
├── AnomalyEvent.java           (值对象: 异常事件)
├── AlertConvergence.java       (值对象: 去重后的告警记录)
└── BugfixSuggestion.java       (值对象: 修复建议)

boot2x/patrol/
├── ScheduledPatrolScheduler.java      (PatrolScheduler 默认实现)
├── InMemoryAlertConverger.java        (AlertConverger 默认实现)
├── DefaultAnomalyEventListener.java   (AnomalyEventListener 默认实现)
└── TemplateBugfixSuggester.java       (BugfixSuggester 默认实现)
```

---

## 2. 核心 SPI

所有 SPI 定义在 `cn.watsontech.snapagent.core.patrol` 包中。

### 2.1 PatrolScheduler（巡检调度器）

```java
public interface PatrolScheduler {
    /** 注册一个 cron 定时巡检任务 */
    void schedule(PatrolTask task);

    /** 取消已注册的巡检任务 */
    void cancel(String patrolId);

    /** 返回所有已注册的巡检任务 */
    List<PatrolTask> listTasks();

    /** 分页查询报告（按 triggeredAt 降序，按 userId 过滤） */
    List<PatrolReport> getReports(String userId, int limit, int offset);

    /** 返回指定用户的报告总数 */
    long countReports(String userId);
}
```

### 2.2 AlertConverger（告警收敛器）

```java
public interface AlertConverger {
    /** 记录异常事件，创建或更新收敛记录 */
    AlertConvergence record(AnomalyEvent event);

    /** 分页查询收敛记录（可选 type 过滤） */
    List<AlertConvergence> query(String userId, String type, int limit, int offset);

    /** 返回匹配过滤条件的记录总数 */
    long count(String userId, String type);

    /** 标记告警为已解决 */
    void resolve(String alertId);
}
```

### 2.3 AnomalyEventListener（异常事件监听器）

```java
public interface AnomalyEventListener {
    /** 当检测到异常事件时被调用 */
    void onEvent(AnomalyEvent event);
}
```

### 2.4 BugfixSuggester（修复建议生成器）

```java
public interface BugfixSuggester {
    /**
     * 分析巡检任务的 transcript，生成修复建议
     * @return 修复建议，若无法生成则返回 null
     */
    BugfixSuggestion suggest(String taskId, List<TranscriptEvent> transcript);
}
```

### 2.5 数据模型

**PatrolTask**（巡检任务定义）:

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 任务 ID，自动生成 `patrol_{n}` |
| `skillName` | String | 要执行的 skill 名称 |
| `cron` | String | Spring CronTrigger 表达式（6 位） |
| `userId` | String | 执行用户 ID（用于权限隔离） |
| `enabled` | boolean | 是否启用（默认 true） |
| `inputs` | Map\<String,String\> | skill 输入参数 |

**PatrolReport**（巡检/告警报告）:

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 报告 ID，自动生成 `pr_{ts}_{uuid}` |
| `patrolId` | String | 关联的巡检任务 ID |
| `taskId` | String | AgentTask 的 ID（可能为 null） |
| `userId` | String | 用户 ID（null 表示系统生成，对所有用户可见） |
| `skillName` | String | 执行的 skill |
| `triggeredAt` | long | 触发时间戳（毫秒） |
| `status` | String | `COMPLETED` / `FAILED` / `UNKNOWN` |
| `summary` | String | 报告摘要（取自 `task.getReport()`） |
| `anomalyDetected` | boolean | 是否为告警触发（巡检=false，告警=true） |

**AnomalyEvent**（异常事件）:

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 异常类型（如 `error-spike`、`cpu-high`） |
| `source` | String | 异常来源（服务名/实例标识） |
| `message` | String | 异常描述 |
| `timestamp` | long | 事件时间戳（自动设为当前时间） |
| `metadata` | Map\<String,Object\> | 附加元数据（防御拷贝） |
| `skillName` | String | 指定触发的 skill（null 则用默认） |
| `inputs` | Map\<String,String\> | 传入 skill 的输入参数 |

**AlertConvergence**（收敛后的告警记录）:

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 告警 ID，格式 `alert_{n}` |
| `fingerprint` | String | SHA-256(type\|source) 前 8 字节 |
| `type` | String | 异常类型 |
| `source` | String | 异常来源 |
| `firstMessage` | String | 首次出现时的消息 |
| `count` | AtomicInteger | 出现次数（原子递增） |
| `firstSeen` | long (volatile) | 首次出现时间戳 |
| `lastSeen` | long (volatile) | 最近出现时间戳 |
| `status` | String (volatile) | `ACTIVE` / `RESOLVED` |
| `relatedTaskId` | String | 关联的诊断任务 ID |

`AlertConvergence` 使用 `AtomicInteger` + `volatile` 字段保证 `record()` 与
`autoResolveStale()` 并发调用的线程安全。

**BugfixSuggestion**（修复建议）:

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 关联的任务 ID |
| `rootCause` | String | 根因分析（取自 transcript 的 done 事件） |
| `affectedFiles` | List\<String\> | 受影响的文件列表 |
| `suggestion` | String | Markdown 格式的建议正文 |
| `confidence` | String | `HIGH` / `MEDIUM` / `LOW` |
| `commitRefs` | List\<String\> | 相关 commit 哈希 |

---

## 3. 巡检机制 — ScheduledPatrolScheduler

`ScheduledPatrolScheduler` 是 `PatrolScheduler` 的默认实现，基于 Spring
`TaskScheduler` + `CronTrigger` 实现 cron 定时调度。

### 3.1 调度流程

```
POST /patrol/tasks (skillName, cron, inputs)
    │
    ▼
ScheduledPatrolScheduler.schedule(task)
    │
    ├─ 若 task.id 为空 → 生成 "patrol_{n}"（AtomicLong 自增）
    ├─ tasks.put(id, task)                          // ConcurrentHashMap 注册
    ├─ if task.enabled:
    │     taskScheduler.schedule(
    │         () -> executePatrol(task),            // lambda 作为调度目标
    │         new CronTrigger(task.cron))           // Spring CronTrigger 解析 cron
    │     scheduledFutures.put(id, future)          // 记录 ScheduledFuture
    └─ 日志: "Scheduled patrol task {} (skill={}, cron={})"
```

### 3.2 executePatrol 执行逻辑

当 cron 触发时，`executePatrol(task)` 在调度线程池中执行：

```java
private void executePatrol(PatrolTask task) {
    long triggeredAt = System.currentTimeMillis();

    // 1. 查找 skill
    SkillMeta skill = skillRegistry.get(task.getSkillName());
    if (skill == null) {
        // skill 不存在 → 记录 FAILED 报告
        PatrolReport report = new PatrolReport(
            null, task.getId(), null,
            task.getSkillName(), triggeredAt, "FAILED",
            "Skill not found: " + task.getSkillName(), false);
        report.setUserId(task.getUserId());
        reportStore.save(report);
        return;
    }

    try {
        // 2. 创建 AgentTask 并执行
        AgentTask agentTask = AgentTask.create(
            task.getUserId(), task.getSkillName(),
            task.getInputs(), null);
        agentExecutor.execute(agentTask, skill);

        // 3. 提取结果，存储报告
        TaskStatus status = agentTask.getStatus();
        String summary = agentTask.getReport() != null
            ? agentTask.getReport() : "Patrol completed";

        PatrolReport report = new PatrolReport(
            null, task.getId(), agentTask.getTaskId(),
            task.getSkillName(), triggeredAt,
            status.name(), summary, false);
        report.setUserId(task.getUserId());
        reportStore.save(report);
    } catch (Exception e) {
        // 4. 异常 → 记录 FAILED 报告
        PatrolReport report = new PatrolReport(
            null, task.getId(), null,
            task.getSkillName(), triggeredAt, "FAILED",
            e.getMessage(), false);
        report.setUserId(task.getUserId());
        reportStore.save(report);
    }
}
```

### 3.3 取消巡检

```java
public void cancel(String patrolId) {
    ScheduledFuture<?> future = scheduledFutures.remove(patrolId);
    if (future != null) {
        future.cancel(false);    // 不中断正在执行的任务
    }
    tasks.remove(patrolId);
}
```

`cancel(false)` 不会中断正在运行的巡检任务，仅取消后续调度。

### 3.4 线程池配置

巡检使用独立的 `ThreadPoolTaskScheduler`（bean 名 `patrolTaskScheduler`）：

```yaml
snap-agent:
  patrol:
    enabled: true
    scheduler-pool-size: 2        # 默认 2 个线程
    report-buffer-size: 500      # PatrolReportStore 环形缓冲区容量
```

- `schedulerPoolSize`：调度线程池大小，控制并发巡检数
- `reportBufferSize`：报告存储环形缓冲区容量，满时淘汰最旧报告
- 线程名前缀 `patrol-`，`waitForTasksToCompleteOnShutdown=true` 保证优雅停机

---

## 4. 告警机制 — DefaultAnomalyEventListener + AlertConverger

告警流由外部系统推送 `AnomalyEvent` 触发，经 `DefaultAnomalyEventListener`
处理后执行诊断 skill。

### 4.1 事件处理流程

```
AnomalyEvent (外部系统推送)
    │
    ▼
DefaultAnomalyEventListener.onEvent(event)
    │
    ├─ 1. 记录告警: alertConverger.record(event) → AlertConvergence
    │     (fingerprint 去重，若已有 ACTIVE 同源告警则 count++ 不重复创建)
    │
    ├─ 2. 确定 skill: event.skillName ?? "error-spike-investigation"
    │
    ├─ 3. 构建输入: 合并 event.inputs + _event_type/source/message
    │
    ├─ 4. 查找 skill: skillRegistry.get(skillName)
    │     └─ 不存在 → 存 FAILED PatrolReport (anomalyDetected=true)，返回
    │
    ├─ 5. 执行诊断: AgentTask.create("patrol-user", skillName, inputs, null)
    │              agentExecutor.execute(agentTask, skill)
    │
    └─ 6. 存储报告: PatrolReport(patrolId="event-{alertId}", anomalyDetected=true)
                   reportStore.save(report)
```

### 4.2 默认 skill 与输入注入

当 `AnomalyEvent.skillName` 为空时，默认使用 `error-spike-investigation` skill
（6 阶段：定位时间窗口 → 提取错误日志 → 关联变更 → 调用链分析 → 代码定位 → 输出根因）。

事件信息自动注入到 skill 输入参数：

```java
inputs.put("_event_type", event.getType());
inputs.put("_event_source", event.getSource());
inputs.put("_event_message", event.getMessage());
```

skill markdown 可通过 `{_event_type}`、`{_event_source}`、`{_event_message}` 引用这些值。

### 4.3 异常处理

`onEvent` 捕获所有异常，确保单个事件处理失败不会影响后续事件：

```java
} catch (Exception e) {
    log.error("Anomaly-triggered diagnosis failed: {}", e.getMessage(), e);
    PatrolReport report = new PatrolReport(
        null, patrolId, null, skillName, triggeredAt,
        "FAILED", e.getMessage(), true);
    reportStore.save(report);
}
```

---

## 5. 告警收敛算法 — InMemoryAlertConverger

`InMemoryAlertConverger` 是 `AlertConverger` 的默认实现，使用指纹去重 +
环形缓冲区 + 自动过时解决机制。

### 5.1 指纹计算

```java
private String computeFingerprint(AnomalyEvent event) {
    String key = event.getType() + "|" + event.getSource();
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] hash = md.digest(key.getBytes("UTF-8"));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {           // 取前 8 字节（16 hex 字符）
        sb.append(String.format("%02x", hash[i]));
    }
    return sb.toString();
}
```

- 指纹 = `SHA-256(type|source)` 的前 8 字节
- 相同 type + source 的事件收敛到同一条 `AlertConvergence` 记录
- SHA-256 不可用时降级为 `String.valueOf(key.hashCode())`

### 5.2 去重逻辑

```java
public AlertConvergence record(AnomalyEvent event) {
    String fingerprint = computeFingerprint(event);
    synchronized (fingerprint.intern()) {          // 指纹级锁，避免全局锁
        AlertConvergence existing = findActiveByFingerprint(fingerprint);
        if (existing != null) {
            existing.incrementCount();              // count++ (AtomicInteger)
            existing.setLastSeen(event.getTimestamp());
            return existing;                       // 不创建新记录
        }
        // 创建新告警
        AlertConvergence alert = new AlertConvergence(
            "alert_" + idCounter.incrementAndGet(),
            fingerprint, event.getType(), event.getSource(),
            event.getMessage(), null);
        alerts.put(id, alert);
        // 环形缓冲区满 → 淘汰最旧
        if (!ringBuffer.offer(id)) {
            String evicted = ringBuffer.poll();
            alerts.remove(evicted);
            ringBuffer.offer(id);
        }
        return alert;
    }
}
```

关键设计：
- `synchronized (fingerprint.intern())`：按指纹字符串加锁，不同指纹并行处理
- `findActiveByFingerprint`：遍历 `alerts.values()` 查找相同指纹且状态为 `ACTIVE` 的记录
- 命中已有记录 → `count++` + 更新 `lastSeen`，不创建新记录
- 未命中 → 创建新 `AlertConvergence`，存入 ring buffer

### 5.3 环形缓冲区淘汰

```java
private final ArrayBlockingQueue<String> ringBuffer;   // 存 alertId
private final ConcurrentHashMap<String, AlertConvergence> alerts;
```

- ring buffer 容量 = `snap-agent.alert.buffer-size`（默认 1000）
- 满时 `poll()` 淘汰最旧 alertId，并从 `alerts` map 中移除对应记录
- 双结构（queue + map）保证 FIFO 淘汰 + O(1) 查找

### 5.4 自动过时解决

```java
private void autoResolveStale() {
    long threshold = System.currentTimeMillis()
        - (autoResolveMinutes * 60_000L);
    for (AlertConvergence alert : alerts.values()) {
        if (STATUS_ACTIVE.equals(alert.getStatus())
                && alert.getLastSeen() < threshold) {
            alert.setStatus(STATUS_RESOLVED);
        }
    }
}
```

- 懒触发：仅在 `query()` 和 `count()` 调用时执行
- `autoResolveMinutes` = `snap-agent.alert.auto-resolve-minutes`（默认 30 分钟）
- 超过阈值未更新的 ACTIVE 告警自动标记为 `RESOLVED`

### 5.5 趋势预测

SnapAgent 的趋势预测通过 **skill** 实现，而非独立的 Java 算法类。内置的
`trend-prediction` skill 可作为巡检任务定时执行：

```
trend-prediction skill (4 步):
  1. 查询 7 天指标趋势 → metrics_query
  2. 检测增长率 → 线性/指数/稳定
  3. 估算到达阈值时间 → < 7 天则告警
  4. 生成预警 → 当前值/趋势方向/预测时间/置信度/建议
```

配置为巡检任务：

```bash
POST /patrol/tasks
{
  "skillName": "trend-prediction",
  "cron": "0 0 8 * * *",          # 每天早 8 点
  "inputs": {
    "metric": "node_memory_MemAvailable_bytes"
  }
}
```

---

## 6. Bugfix 建议与报告交付

### 6.1 TemplateBugfixSuggester

`TemplateBugfixSuggester` 是 `BugfixSuggester` 的默认实现，从巡检/告警任务的
transcript 中提取修复线索。

**提取逻辑：**

```
遍历 transcript events:
  ├─ TYPE_TOOL_CALL (name=code_read)     → 从 args 提取 file_path
  ├─ TYPE_TOOL_CALL (name=git_log)       → 从 args 提取 file_path
  ├─ TYPE_TOOL_RESULT (对应 git_log)    → 从 content 提取 commit hash (regex [a-f0-9]{6,40})
  └─ TYPE_DONE                           → 从 data.report 提取 rootCause
```

**置信度规则：**

| 条件 | 置信度 | 说明 |
|------|--------|------|
| 有 affectedFiles **且** 有 commitRefs | `HIGH` | 代码+变更双证据 |
| 有 affectedFiles **或** 有 commitRefs | `MEDIUM` | 单一证据 |
| 两者均无 | `LOW` | 无诊断数据 |

**文件路径提取（双策略）：**

```java
private void extractFilePaths(Object argsObj, Set<String> files) {
    if (argsObj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) argsObj;
        Object filePath = map.get("file_path");      // 直接从 Map 取
        if (filePath != null) files.add(String.valueOf(filePath));
    }
    Matcher m = FILE_PATH_PATTERN.matcher(argsObj.toString());  // regex 兜底
    while (m.find()) files.add(m.group(1));
}
```

### 6.2 报告交付

巡检/告警报告通过 REST API 交付，而非独立推送渠道：

```
GET /patrol/reports?page=0&size=20    → 分页查询报告列表
GET /patrol/reports/{id}              → 查询单条报告详情
GET /alerts?page=0&size=20&type=...  → 分页查询告警
POST /alerts/{id}/resolve            → 手动解决告警
POST /runs/{id}/bugfix-suggestion    → 从任务 transcript 生成修复建议
```

外部系统可通过轮询 `/patrol/reports` 和 `/alerts` 端点获取最新报告，或通过
webhook 轮询集成到告警平台（如 PagerDuty、企业微信等）。

---

## 7. 自动装配与生命周期

### 7.1 Bean 装配

所有 patrol/alert bean 在 `SnapAgentAutoConfiguration` 中通过 `@ConditionalOnProperty`
条件装配：

| Bean | 条件 | 默认实现 | 说明 |
|------|------|---------|------|
| `patrolReportStore` | `snap-agent.patrol.enabled=true` | `PatrolReportStore` | ring buffer 报告存储 |
| `patrolTaskScheduler` | `snap-agent.patrol.enabled=true` | `ThreadPoolTaskScheduler` | 独立调度线程池（pool-size=2, 前缀 `patrol-`） |
| `scheduledPatrolScheduler` | `snap-agent.patrol.enabled=true` | `ScheduledPatrolScheduler` | cron 调度器 |
| `inMemoryAlertConverger` | `snap-agent.alert.enabled=true` | `InMemoryAlertConverger` | 告警去重器 |
| `defaultAnomalyEventListener` | `snap-agent.patrol.enabled=true` | `DefaultAnomalyEventListener` | 异常事件监听器 |
| `templateBugfixSuggester` | 无条件（`@ConditionalOnMissingBean`） | `TemplateBugfixSuggester` | 修复建议生成器 |

### 7.2 生命周期

patrol 线程池 `patrolTaskScheduler` 设置 `waitForTasksToCompleteOnShutdown=true`，
Spring 容器关闭时等待正在执行的巡检任务完成后再关闭线程池。`ScheduledFuture`
通过 `cancel(false)` 取消后续调度，不中断正在运行的任务。

### 7.3 与 Controller 的集成

`SnapAgentController` 通过 `ObjectProvider` 注入 patrol 组件，disabled 时返回 503：

```java
PatrolScheduler scheduler = patrolScheduler;
if (scheduler == null) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Collections.singletonMap("error", "patrol is not enabled"));
}
```

- `patrolScheduler`、`alertConverger`、`bugfixSuggester` 均为可选依赖
- 未启用时对应端点返回 503 Service Unavailable
- `TemplateBugfixSuggester` 无条件装配，始终可用

---

## 8. 配置与扩展

### 8.1 配置参考

```yaml
snap-agent:
  patrol:
    enabled: false                    # 巡检总开关（默认关闭）
    scheduler-pool-size: 2            # 调度线程池大小
    report-buffer-size: 500           # 报告环形缓冲区容量
  alert:
    enabled: false                    # 告警收敛总开关（默认关闭）
    buffer-size: 1000                  # 告警环形缓冲区容量
    auto-resolve-minutes: 30           # 自动解决阈值（分钟）
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `snap-agent.patrol.enabled` | `false` | 巡检总开关 |
| `snap-agent.patrol.scheduler-pool-size` | `2` | 调度线程池大小 |
| `snap-agent.patrol.report-buffer-size` | `500` | 报告存储容量（满时淘汰最旧） |
| `snap-agent.alert.enabled` | `false` | 告警去重总开关 |
| `snap-agent.alert.buffer-size` | `1000` | 告警存储容量（满时淘汰最旧） |
| `snap-agent.alert.auto-resolve-minutes` | `30` | 自动标记 RESOLVED 的阈值 |

### 8.2 自定义 PatrolScheduler

实现 `PatrolScheduler` 接口 + `@Component`（或 `@Bean`），替代默认的
`ScheduledPatrolScheduler`。`@ConditionalOnMissingBean` 会让自定义 bean 优先：

```java
@Component
public class QuartzPatrolScheduler implements PatrolScheduler {
    @Override
    public void schedule(PatrolTask task) {
        // 使用 Quartz Scheduler 替代 Spring CronTrigger
        // 支持持久化任务、misfire 策略等
    }
    // ...
}
```

### 8.3 自定义 AlertConverger

实现 `AlertConverger` 接口，可替换为 Redis/DB 后端的告警存储：

```java
@Component
public class RedisAlertConverger implements AlertConverger {
    @Override
    public AlertConvergence record(AnomalyEvent event) {
        // Redis SETEX 滑动窗口去重
        // 支持多实例共享告警状态
    }
    // ...
}
```

### 8.4 自定义 AnomalyEventListener

实现 `AnomalyEventListener` SPI，接入外部事件源（Kafka、RabbitMQ 等）：

```java
@Component
public class KafkaAnomalyEventListener implements AnomalyEventListener {
    @KafkaListener(topics = "anomaly-alerts")
    public void onMessage(ConsumerRecord<String, String> record) {
        AnomalyEvent event = parseEvent(record.value());
        onEvent(event);     // 触发诊断
    }
    @Override
    public void onEvent(AnomalyEvent event) {
        // 自定义处理逻辑
    }
}
```

### 8.5 自定义 BugfixSuggester

实现 `BugfixSuggester` 接口，可接入 LLM 增强修复建议生成：

```java
@Component
public class LlmBugfixSuggester implements BugfixSuggester {
    @Override
    public BugfixSuggestion suggest(String taskId, List<TranscriptEvent> transcript) {
        // 将 transcript 交给 LLM 分析，生成更精准的根因和建议
        // 可利用 KnowledgeBase 注入业务上下文
    }
}
```

### 8.6 内置 Skills

| Skill | 工具 | 用途 |
|-------|------|------|
| `health-patrol` | metrics_query | 综合健康巡检：CPU/内存/错误率/延迟，阈值判断 |
| `trend-prediction` | metrics_query | 7 天趋势分析，预测到达阈值时间 |
| `error-spike-investigation` | metrics_query, log_search, trace_search, code_read, git_log | 错误率突增排查（告警默认 skill，6 阶段） |
| `ops-health-check` | metrics_query | 运营健康检查 |

典型巡检配置示例：

```yaml
snap-agent:
  patrol:
    enabled: true
    scheduler-pool-size: 2
    report-buffer-size: 500
  alert:
    enabled: true
    buffer-size: 1000
    auto-resolve-minutes: 30
```

```bash
# 注册每天早 8 点的健康巡检
curl -X POST http://localhost:8080/skills-agent/patrol/tasks \
  -H "Content-Type: application/json" \
  -u admin:password \
  -d '{
    "skillName": "health-patrol",
    "cron": "0 0 8 * * *",
    "inputs": { "service": "order-service" }
  }'

# 查询巡检报告
curl -u admin:password \
  "http://localhost:8080/skills-agent/patrol/reports?page=0&size=20"

# 手动触发异常事件（通过外部系统调用 onEvent）
# 查看收敛后的告警
curl -u admin:password \
  "http://localhost:8080/skills-agent/alerts?type=error-spike"

# 从诊断任务生成修复建议
curl -X POST -u admin:password \
  "http://localhost:8080/skills-agent/runs/{taskId}/bugfix-suggestion"
```
