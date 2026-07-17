# SnapAgent 问题闭环架构

> 版本：v0.9 | 更新日期：2026-07-17

## 1. 架构概览

SnapAgent v0.9 问题闭环系统将诊断、方案建议、外部 Issue 创建、修复验证、经验沉淀串联为一个完整闭环。诊断结束后, 根因与方案被记录; 用户选定方案后创建外部 Issue (Jira/GitHub); 修复完成后通过验证 skill 复查; 验证通过后, 经验被抽取为知识片段沉淀回 KnowledgeBase, 供未来诊断复用。

```
┌──────────────────────────────────────────────────────────────────────┐
│                         IssueClosureService                          │
│                  (编排诊断 → 方案 → 验证 → 沉淀全流程)                  │
└──────┬───────────────┬─────────────────┬──────────────┬──────────────┘
       │               │                 │              │
       ▼               ▼                 ▼              ▼
┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│ Solution    │ │ IssueTracker │ │ Verification │ │ KnowledgeSedimen │
│ Suggester   │ │ (外部 Issue   │ │ Runner       │ │ tationExtractor  │
│ (方案建议)   │ │  对接)        │ │ (修复验证)    │ │ (经验沉淀)        │
└─────────────┘ └──────────────┘ └──────────────┘ └────────┬─────────┘
       │               │                 │                      │
       ▼               ▼                 ▼                      ▼
┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│ solution-   │ │ Jira/GitHub  │ │ verify-fix   │ │   KnowledgeBase  │
│ suggest     │ │ /NoopIssue   │ │ skill /      │ │   (知识库, 供未来 │
│ skill /     │ │ Tracker      │ │ SimpleVerifi │ │   诊断检索)       │
│ Template    │ │              │ │ cationRunner │ │                  │
└─────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘
       │                                              ▲
       │              ┌──────────────────┐            │
       └──────────────│  IssueStore      │────────────┘
                      │  (闭环记录持久化)  │
                      │  {issueId}.json   │
                      └──────────────────┘
```

### 闭环主流程

```
        诊断完成 (root cause)
              │
              ▼
      ┌───────────────┐
      │ 1. Propose    │  方案建议: SolutionSuggester 或
      │   Solution   │  solution-suggest skill
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │ 2. Create     │  外部 Issue: IssueTracker.createIssue()
      │   External    │  (NoopIssueTracker 返回 null)
      │   Issue       │
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │   修复中       │  开发者在外部系统执行修复
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │ 3. Verify     │  验证: VerificationRunner 或
      │   Fix         │  verify-fix skill 复查
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │ 4. Close +    │  关闭: 抽取知识片段 →
      │   Sediment    │  沉淀回 KnowledgeBase
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │  KnowledgeBase│  未来诊断可检索到本次经验
      │  (反哺)       │  ← 闭环完成, 形成学习循环
      └───────────────┘
```

---

## 2. 核心 SPI

核心 SPI 位于 `snap-agent-core` 的 `cn.watsontech.snapagent.core.issue` 包, 定义了问题闭环的状态机、值对象和存储/对接接口。

### 2.1 IssueStatus (状态枚举)

```java
public enum IssueStatus {
    DIAGNOSED,           // 已诊断, 待出方案
    SOLUTION_PROPOSED,   // 方案已生成, 待创建 Issue
    ISSUE_CREATED,       // 外部 Issue 已创建, 待开始修复
    FIX_IN_PROGRESS,    // 修复中
    VERIFIED,            // 已验证修复生效
    CLOSED,              // 已关闭, 经验已沉淀
    FAILED               // 失败终态 (修复无法完成或验证不通过)
}
```

> **注意**: `ISSUE_CREATED` 和 `FAILED` 在枚举中定义, 但当前 `IssueClosureService` 的编排逻辑中未驱动这两个状态——`createExternalIssue()` 直接跳到 `FIX_IN_PROGRESS`, 验证路径不区分 pass/fail 而是一律标记为 `VERIFIED`。这两个状态为自定义扩展预留 (如自定义 IssueTracker 或 VerificationRunner 可使用)。

### 2.2 IssueClosure (不可变值对象)

`IssueClosure` 是贯穿全生命周期的核心值对象, 所有字段在构造时固定, 变更通过 `with*` 方法返回新实例:

```java
public final class IssueClosure {
    private final String issueId;              // 内部闭环 ID (UUID)
    private final String externalIssueId;     // 外部 Issue ID (Jira/工单, 可空)
    private final String taskId;              // 关联的诊断任务 ID
    private final String conversationId;      // 关联的会话 ID (可空)
    private final String userQuery;           // 用户原始问题
    private final String rootCause;           // 根因摘要
    private final SolutionSuggestion solution;     // 方案建议 (可空)
    private final String selectedSolution;    // 用户选择的方案 ID (可空)
    private final IssueStatus status;         // 当前状态
    private final String fixCommitId;         // 修复 commit (可空)
    private final VerificationResult verificationResult; // 验证结果 (可空)
    private final String knowledgeEntryId;    // 沉淀到知识库的条目 ID (可空)
    private final long createdAt;             // 创建时间 (epoch millis)
    private final long updatedAt;             // 更新时间 (epoch millis)
}
```

**`with*` 变更方法** (均返回新 `IssueClosure` 实例):

| 方法 | 变更字段 |
|------|---------|
| `withStatus(IssueStatus, long updatedAt)` | status, updatedAt |
| `withSolution(SolutionSuggestion, long updatedAt)` | solution, updatedAt |
| `withExternalIssue(String externalIssueId, String selectedSolution, IssueStatus status, long updatedAt)` | externalIssueId, selectedSolution, status, updatedAt |
| `withVerification(VerificationResult, long updatedAt)` | verificationResult, updatedAt |
| `withKnowledgeEntry(String knowledgeEntryId, long updatedAt)` | knowledgeEntryId, updatedAt |

### 2.3 SolutionSuggestion / SolutionOption (方案建议值对象)

```java
public final class SolutionOption {
    private final String id;           // 方案 ID (如 "opt-1")
    private final String title;        // 方案标题
    private final String description;  // 方案描述
    private final String effort;      // 实施成本: "low" / "medium" / "high"
    private final boolean temporary;   // 是否为临时方案
}

public final class SolutionSuggestion {
    private final List<SolutionOption> options;  // 防御拷贝
    private final String recommendedOptionId;    // 推荐方案 ID
    private final String rationale;              // 推荐理由
    private final String relatedCode;           // 相关代码位置 (可空)
}
```

### 2.4 VerificationResult (验证结果值对象)

```java
public final class VerificationResult {
    private final boolean passed;        // 是否通过
    private final String summary;        // 验证摘要
    private final String beforeStatus;   // 修复前任务状态
    private final String afterStatus;    // 修复后任务状态
    private final long verifiedAt;       // 验证时间 (epoch millis)
}
```

### 2.5 IssueStore (存储 SPI)

```java
public interface IssueStore {
    void save(IssueClosure issue);                          // 创建或更新 (upsert)
    IssueClosure load(String issueId);                       // 按 ID 加载
    IssueClosure findByTaskId(String taskId);                // 按诊断任务 ID 查找
    List<IssueClosure> list();                               // 全部 (按 updatedAt 降序)
    List<IssueClosure> listByStatus(IssueStatus status);     // 按状态过滤
    void delete(String issueId);                            // 删除
}
```

### 2.6 IssueTracker (外部 Issue 对接 SPI)

```java
public interface IssueTracker {
    String createIssue(String title, String description, String assignee);
    // 返回外部 Issue ID, NoopIssueTracker 返回 null

    void updateStatus(String externalIssueId, String status);
    String getIssueUrl(String externalIssueId);
    String type();  // "noop" / "jira" / "github"
}
```

### 2.7 SolutionSuggester / VerificationRunner (扩展 SPI)

v0.9 提供了两个可替换的编排 SPI, 允许宿主绕过内置 skill 直接注入方案生成和验证逻辑:

```java
public interface SolutionSuggester {
    // 从已诊断的 IssueClosure 生成方案建议
    SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary);
}

public interface VerificationRunner {
    // 验证修复是否生效
    VerificationResult verify(IssueClosure issue);
}
```

`IssueClosureService` 优先使用注入的 SPI 实现; 若未注入则回退到运行内置 skill。详见第 4 节。

---

## 3. 状态机

### 3.1 状态流转

```
                          proposeSolution()
              ┌──────────────────────────────────────┐
              │                                      │
              ▼                                      │
      ┌───────────────┐                              │
      │   DIAGNOSED   │  已诊断, 待出方案              │
      │  (issue 创建)  │                              │
      └───────┬───────┘                              │
              │ proposeSolution()                     │
              │ (方案生成后 withStatus)                │
              ▼                                      │
      ┌───────────────────┐                           │
      │ SOLUTION_PROPOSED │  方案已生成, 待创建 Issue   │
      └───────┬───────────┘                           │
              │ createExternalIssue()                 │
              │ (调用 IssueTracker.createIssue)       │
              ▼                                      │
      ┌──────────────────┐                            │
      │ FIX_IN_PROGRESS  │  修复中                      │
      │ (外部 Issue 已建)  │                            │
      └───────┬──────────┘                            │
              │ verify()                              │
              │ (验证修复生效)                          │
              ▼                                      │
      ┌───────────────┐                              │
      │   VERIFIED    │  已验证修复生效                 │
      └───────┬───────┘                              │
              │ close()                               │
              │ (沉淀知识 + 关闭)                       │
              ▼                                      │
      ┌───────────────┐                              │
      │    CLOSED     │  已关闭, 经验已沉淀             │
      └───────────────┘                              │
                                                       │
              ┌──────────────────────────────┐         │
              │ ISSUE_CREATED (枚举已定义,    │         │
              │ 当前 Service 未驱动)          │─────────┘
              │ FAILED (枚举已定义, 当前 Service│
              │ 未驱动, 供自定义扩展)          │
              └──────────────────────────────┘
```

### 3.2 状态说明

| 状态 | 含义 | 触发动作 |
|------|------|---------|
| `DIAGNOSED` | 诊断已完成, 待生成方案 | `proposeSolution(taskId)` 创建 IssueClosure 时初始状态 |
| `SOLUTION_PROPOSED` | 方案已生成, 待创建外部 Issue | `proposeSolution()` 生成方案后 `withStatus()` |
| `ISSUE_CREATED` | 外部 Issue 已创建 (枚举定义, 当前 Service 未使用) | 预留给自定义 IssueTracker |
| `FIX_IN_PROGRESS` | 修复进行中 | `createExternalIssue()` 调 `IssueTracker.createIssue()` 后 |
| `VERIFIED` | 修复已验证生效 | `verify()` 运行验证后 |
| `CLOSED` | 已关闭, 经验已沉淀到 KnowledgeBase | `close()` 抽取知识后 |
| `FAILED` | 失败终态 (枚举定义, 当前 Service 未使用) | 预留给自定义 VerificationRunner |

### 3.3 流转特性

- **单向前进**: 当前 `IssueClosureService` 实现中状态只能向前推进, 不支持回退。每个 `with*` 方法产生新实例并写入 `IssueStore`, 覆盖旧状态。
- **无显式 FAILED 路径**: 当前实现中 `verify()` 无论验证是否通过都标记为 `VERIFIED` (passed 字段记录在 `VerificationResult` 中), 不触发 `FAILED` 状态。
- **`ISSUE_CREATED` 为预留态**: `createExternalIssue()` 直接从 `SOLUTION_PROPOSED` 跳到 `FIX_IN_PROGRESS`, 跳过了 `ISSUE_CREATED`。自定义 `IssueTracker` 可在 `createIssue` 后手动标记此状态。

---

## 4. Starter 实现

Starter 模块 (`snap-agent-spring-boot-2x-starter`) 的 `cn.watsontech.snapagent.boot2x.issue` 包提供了默认实现。

### 4.1 FileIssueStore (默认存储)

JSON 文件存储, 与 `FileConversationStore` 同模式:

- **文件路径**: `{storageDir}/{issueId}.json`
- **默认目录**: `snap-agent.issue-closure.storage-dir` 为空时使用 `{upload-skills-dir}/issues/`
- **序列化**: Jackson `ObjectMapper`, 每个文件一个 `IssueClosure` 的 JSON 对象
- **排序**: `list()` 和 `listByStatus()` 按 `updatedAt` 降序 (最新在前)
- **向后兼容**: `fromMap()` 支持 legacy 格式——旧版本中 `solutions` 存为 `List<String>`、`verificationResult` 存为纯字符串, 加载时自动转换:

```java
// Legacy: solutions 为 List<String> → 转为 SolutionOption (id="opt-N", effort="medium")
private SolutionSuggestion solutionFromLegacyList(Object raw) {
    List<SolutionOption> options = new ArrayList<>();
    int index = 1;
    for (Object item : (List<Object>) raw) {
        options.add(new SolutionOption("opt-" + index, item.toString(), item.toString(),
                "medium", false));
        index++;
    }
    String recommended = options.isEmpty() ? null : "opt-1";
    return new SolutionSuggestion(options, recommended, null, null);
}

// Legacy: verificationResult 为纯字符串 → passed 通过关键词推断
private VerificationResult verificationFromLegacyString(Object raw) {
    String text = raw.toString();
    boolean passed = text.contains("通过") || text.toLowerCase().contains("pass");
    return new VerificationResult(passed, text, null, null, 0L);
}
```

### 4.2 NoopIssueTracker (默认 Issue 对接)

默认空实现, `snap-agent.issue-closure.tracker-type=noop` 时装配:

```java
public class NoopIssueTracker implements IssueTracker {
    @Override public String createIssue(String title, String description, String assignee) {
        return null;  // 不创建外部 Issue, 返回 null
    }
    @Override public void updateStatus(String externalIssueId, String status) { /* no-op */ }
    @Override public String getIssueUrl(String externalIssueId) { return null; }
    @Override public String type() { return "noop"; }
}
```

宿主可声明自定义 `IssueTracker` bean (Jira/GitHub) 替换, `@ConditionalOnMissingBean` 生效。

### 4.3 TemplateSolutionSuggester (默认方案建议)

基于关键词模板匹配根因摘要, 生成 2-3 个候选 `SolutionOption`。**首个匹配的模板生效**, 无匹配时返回 fallback:

| 匹配条件 (根因包含关键词) | 生成方案 |
|------|---------|
| `参数` + (缺失/缺少/未生成) | 手动补齐参数 (low, 临时) / 修复参数过滤逻辑 (medium) |
| `连接` + (超时/失败/拒绝) | 调整连接池配置 (low) / 增加超时时间 (low, 临时) / 排查慢查询 (high) |
| `数据` + (为空/缺失/不存在) | 检查上游任务 (medium) / 手动补数据 (low, 临时) |
| `权限` / `鉴权` / `认证` | 权限自检 (low) / 联系管理员 (low) |
| 无匹配 (fallback) | 代码图谱定位 (medium) / 联系负责人 (low) |

`recommendedOptionId` 始终为 `"opt-1"` (首个方案)。根因为空时回退到 `transcriptSummary`, 两者都空时触发 fallback 模板。

### 4.4 SimpleVerificationRunner (默认验证)

重新运行原始诊断 skill, 检查新任务是否达到 `TaskStatus.SUCCEEDED`:

```java
public VerificationResult verify(IssueClosure issue) {
    // 1. 从 issue.taskId 加载原始诊断任务
    AgentTask originalTask = taskStore.get(issue.getTaskId());
    // 2. 用相同的 skill 和 inputs 创建新任务 (systemUserId 执行)
    AgentTask verifyTask = AgentTask.create(systemUserId, skillName, originalTask.getInputs(), null);
    agentExecutor.execute(verifyTask, skill);
    // 3. passed = 新任务状态为 SUCCEEDED
    boolean passed = TaskStatus.SUCCEEDED.equals(verifyTask.getStatus());
    // 4. beforeStatus=原任务状态, afterStatus=新任务状态
    return new VerificationResult(passed, verifyTask.getReport(),
            originalTask.getStatus().name(), verifyTask.getStatus().name(), now);
}
```

### 4.5 IssueClosureService (编排服务)

核心编排器, 连接 `AgentExecutor`、`IssueStore`、`IssueTracker`、`KnowledgeBase`:

#### `proposeSolution(taskId)` — 方案建议

```
1. taskStore.get(taskId) 加载诊断任务
   ├─ 未找到 → 返回 null
   └─ 找到 → rootCause = task.getReport()
              userQuery = 拼接 task.inputs 所有非空值

2. 创建 IssueClosure (status=DIAGNOSED, solution=null)

3. 生成方案 (双路径):
   ├─ SolutionSuggester 已注入 → suggester.suggest(issue, rootCause)
   │   └─ 默认装配 TemplateSolutionSuggester
   └─ SolutionSuggester 为 null → 回退运行 solution-suggest skill
       ├─ inputs: root_cause, original_query, task_id
       ├─ agentExecutor.execute(solutionTask, skill)
       ├─ 解析 report 多行为 SolutionOption 列表 (id="opt-N", effort="medium")
       └─ skill 不存在 → 返回 null (保留 legacy 行为)

4. issue.withSolution(suggestion, now).withStatus(SOLUTION_PROPOSED, now)
5. issueStore.save(issue)
6. 返回 issue
```

#### `createExternalIssue(taskId, selectedSolution)` — 创建外部 Issue

```
1. issueStore.findByTaskId(taskId)
   ├─ 未找到 → 返回 null
   └─ 找到 → 继续

2. title = truncate(rootCause, 80)  // 根因截断为标题
   description = selectedSolution
   externalIssueId = issueTracker.createIssue(title, description, null)
   // NoopIssueTracker 返回 null, 但状态仍推进

3. issue.withExternalIssue(externalIssueId, selectedSolution, FIX_IN_PROGRESS, now)
4. issueStore.save(updated)
5. 返回 updated
```

#### `verify(issueId)` — 验证修复

```
1. issueStore.load(issueId)
   ├─ 未找到 → 返回 null
   └─ 找到 → 继续

2. 验证 (双路径):
   ├─ VerificationRunner 已注入 → runner.verify(issue)
   │   └─ 默认装配 SimpleVerificationRunner (重跑诊断 skill)
   └─ VerificationRunner 为 null → 回退运行 verify-fix skill
       ├─ inputs: root_cause, original_query, issue_id
       ├─ agentExecutor.execute(verifyTask, skill)
       ├─ passed = report 含 "通过" 或 "pass"
       └─ skill 不存在 → 返回 null

3. issue.withVerification(result, now).withStatus(VERIFIED, now)
4. issueStore.save(updated)
5. 返回 updated
```

#### `close(issueId)` — 关闭并沉淀

```
1. issueStore.load(issueId)
   ├─ 未找到 → 返回 null
   └─ 找到 → 继续

2. KnowledgeFragment fragment = sedimentationExtractor.extract(issue)
   // 抽取知识片段 (见第 5 节)

3. knowledgeBase != null → knowledgeBase.reload()
   // 重载知识库, 使新片段可被检索

4. issue.withKnowledgeEntry("sedimentation:" + issueId, now)
      .withStatus(CLOSED, now)
5. issueStore.save(updated)
6. 返回 updated
```

> **注**: `knowledgeBase` 可为 `null` (当 `snap-agent.knowledge.enabled=false` 时)。此时 `close()` 仍记录 `knowledgeEntryId` 但不重载知识库——知识片段不会立即可检索, 但 `IssueStore` 中保留了完整记录。

---

## 5. 知识沉淀

### 5.1 沉淀机制

当 issue 关闭时, `KnowledgeSedimentationExtractor.extract()` 从 `IssueClosure` 抽取一个 `KnowledgeFragment`, 经验以结构化 Markdown 形式沉淀回 v0.7 的 `KnowledgeBase`:

```
IssueClosure (CLOSED)
    │
    ▼
KnowledgeSedimentationExtractor.extract(issue)
    │
    ├─ title = "问题: " + truncate(userQuery, 60)
    ├─ content = Markdown 结构化内容 (见下)
    ├─ source = "sedimentation:" + issueId
    └─ metadata = {category: "经验沉淀"}
    │
    ▼
KnowledgeBase.reload()
    │
    ▼
未来诊断时 KnowledgeInjector 检索到该片段
    → 注入 system prompt → LLM 可参考历史经验
```

### 5.2 抽取的知识片段格式

```markdown
## 问题
{userQuery}

## 根因
{rootCause}

## 解决方案
{selectedSolution}
// 或无 selectedSolution 时列出所有 SolutionOption:
- [low] 手动补齐缺失参数: 定位缺失参数的来源...
- [medium] 修复参数过滤逻辑: 排查参数生成链路...

## 验证结果
passed: true
{verificationResult.summary}
```

### 5.3 完整示例

假设一个已关闭的 issue:

- `userQuery`: "SKU-001 为什么没生成补货策略？"
- `rootCause`: "replm_inv_param_sku_wh_input 表中 SKU-001 的 init_replenishment_param 字段为空, 参数初始化任务未执行"
- `selectedSolution`: "opt-1: 手动补齐缺失参数"
- `verificationResult.passed`: true
- `verificationResult.summary`: "补货策略已生成, 验证通过"

抽取的 `KnowledgeFragment`:

```
title:   "问题: SKU-001 为什么没生成补货策略？"
source:  "sedimentation:issue_1721234567890_a1b2c3d4"
metadata: {category: "经验沉淀"}

content:
## 问题
SKU-001 为什么没生成补货策略？

## 根因
replm_inv_param_sku_wh_input 表中 SKU-001 的 init_replenishment_param 字段为空, 参数初始化任务未执行

## 解决方案
opt-1: 手动补齐缺失参数

## 验证结果
passed: true
补货策略已生成, 验证通过
```

未来用户提问类似问题时, `KnowledgeInjector` 检索到此片段, 注入 system prompt, LLM 可直接参考"参数缺失 → 补齐 → 验证"的历史经验, 形成学习闭环。

---

## 6. REST API

所有端点挂载在 SnapAgent basePath 下 (默认 `/snap-agent`), 需通过 `requireAuth()` 权限校验。`IssueClosureService` 未装配时 (issue-closure disabled) 返回 **503** + `ISSUE_CLOSURE_DISABLED`。

### 端点概览

| 方法 | 路径 | 说明 | 触发状态 |
|------|------|------|---------|
| POST | `/runs/{taskId}/solution` | 为已诊断任务生成方案 | DIAGNOSED → SOLUTION_PROPOSED |
| POST | `/runs/{taskId}/issue` | 创建外部 Issue | SOLUTION_PROPOSED → FIX_IN_PROGRESS |
| GET | `/issues/{issueId}` | 查询 Issue 详情 | (无状态变更) |
| POST | `/issues/{issueId}/verify` | 验证修复 | FIX_IN_PROGRESS → VERIFIED |
| POST | `/issues/{issueId}/close` | 关闭并沉淀知识 | VERIFIED → CLOSED |

### POST /runs/{taskId}/solution

为已完成的诊断任务生成方案建议。无需请求体。

**响应** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "externalIssueId": null,
    "taskId": "task_1721234500000_x7y8z9",
    "conversationId": null,
    "userQuery": "SKU-001 为什么没生成补货策略？",
    "rootCause": "replm_inv_param_sku_wh_input 表中 SKU-001 的 init_replenishment_param 字段为空...",
    "solution": {
        "options": [
            {
                "id": "opt-1",
                "title": "手动补齐缺失参数",
                "description": "定位缺失参数的来源, 通过配置或接口手动补齐, 快速恢复服务.",
                "effort": "low",
                "temporary": true
            },
            {
                "id": "opt-2",
                "title": "修复参数过滤逻辑",
                "description": "排查参数生成链路, 修复导致参数缺失的过滤/校验逻辑, 防止复发.",
                "effort": "medium",
                "temporary": false
            }
        ],
        "recommendedOptionId": "opt-1",
        "rationale": "根因指向参数缺失, 优先手动补齐恢复, 再修复生成逻辑.",
        "relatedCode": null
    },
    "selectedSolution": null,
    "status": "SOLUTION_PROPOSED",
    "fixCommitId": null,
    "verificationResult": null,
    "knowledgeEntryId": null,
    "createdAt": 1721234567890,
    "updatedAt": 1721234567890
}
```

**错误**:

| HTTP | code | 触发条件 |
|------|------|---------|
| 401 | `UNAUTHORIZED` | 未认证 |
| 403 | `FORBIDDEN` | 无权限 |
| 404 | `TASK_NOT_FOUND` | 任务或 skill 不存在 |
| 503 | `ISSUE_CLOSURE_DISABLED` | issue-closure 未启用 |

### POST /runs/{taskId}/issue

用户选定方案后创建外部 Issue。

**请求体**:

```json
{
    "selected_solution": "opt-1: 手动补齐缺失参数"
}
```

**响应** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "externalIssueId": "JIRA-12345",
    "taskId": "task_1721234500000_x7y8z9",
    "userQuery": "SKU-001 为什么没生成补货策略？",
    "rootCause": "replm_inv_param_sku_wh_input 表中...",
    "solution": { ... },
    "selectedSolution": "opt-1: 手动补齐缺失参数",
    "status": "FIX_IN_PROGRESS",
    "fixCommitId": null,
    "verificationResult": null,
    "knowledgeEntryId": null,
    "createdAt": 1721234567890,
    "updatedAt": 1721234600000
}
```

> **注**: 使用 `NoopIssueTracker` 时 `externalIssueId` 为 `null`, 但状态仍推进到 `FIX_IN_PROGRESS`。

### GET /issues/{issueId}

查询 Issue 详情, 无状态变更。

**响应** (200 OK): 同上 IssueClosure DTO 结构。

**错误**: 404 `ISSUE_NOT_FOUND` | 503 `ISSUE_CLOSURE_DISABLED`

### POST /issues/{issueId}/verify

验证修复是否生效。无需请求体。

**响应** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "status": "VERIFIED",
    "verificationResult": {
        "passed": true,
        "summary": "补货策略已生成, 验证通过",
        "beforeStatus": "SUCCEEDED",
        "afterStatus": "SUCCEEDED",
        "verifiedAt": 1721234700000
    },
    ...
}
```

### POST /issues/{issueId}/close

关闭 Issue 并沉淀知识。无需请求体。

**响应** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "status": "CLOSED",
    "knowledgeEntryId": "sedimentation:issue_1721234567890_a1b2c3d4",
    ...
}
```

---

## 7. 内置 Skills

问题闭环使用两个内置 skill (位于 `starter/src/main/resources/docs/skills/`), 在 `SolutionSuggester` / `VerificationRunner` 未注入时作为回退路径执行。

### 7.1 solution-suggest.md

**用途**: 基于诊断根因, 生成 2-3 个候选解决方案。

**输入**:

| key | label | required | 说明 |
|-----|-------|----------|------|
| `root_cause` | 根因摘要 | 是 | 诊断输出的根因 |
| `original_query` | 用户原始问题 | 是 | 用于上下文理解 |
| `task_id` | 关联诊断任务 ID | 否 | 关联追溯 |

**执行步骤**:
1. 理解根因 (`{root_cause}`)
2. 考虑用户原始问题 (`{original_query}`)
3. 如需, 使用 `code_graph_impact_analysis` 检查修改影响范围
4. 生成方案: 每个方案带推荐级别 (high/medium/low)
5. 推荐一个方案并解释理由

**输出格式**: 多行方案描述, 每行一个方案。`IssueClosureService.suggestViaSkill()` 将每行解析为 `SolutionOption` (id=`opt-N`, effort=`medium`)。

### 7.2 verify-fix.md

**用途**: 验证修复是否解决了原始问题, 通过重新运行诊断检查。

**输入**:

| key | label | required | 说明 |
|-----|-------|----------|------|
| `root_cause` | 原始根因 | 是 | 诊断时的根因 |
| `original_query` | 用户原始问题 | 是 | 原始问题 |
| `issue_id` | Issue ID | 否 | 关联追溯 |

**执行步骤**:
1. 理解原始问题 (`{original_query}`)
2. 理解原始根因 (`{root_cause}`)
3. 验证: 使用可用工具 (`mysql_query`/`redis_read`/`metrics_query`/`log_search` 等) 重新检查症状
4. 判断: 问题是否已解决

**输出格式**: `Verification result: pass/fail` + 检查项列表。`IssueClosureService.verifyViaSkill()` 通过检查 report 是否包含 "通过" 或 "pass" 推断 passed。

> **注**: 默认装配的 `TemplateSolutionSuggester` 和 `SimpleVerificationRunner` 会**替代**这两个 skill 执行——TemplateSolutionSuggester 用关键词模板匹配生成方案 (不调用 LLM), SimpleVerificationRunner 重跑原始诊断 skill (而非 verify-fix skill)。这两个 skill 仅在 `SolutionSuggester` / `VerificationRunner` bean 未注入时作为回退。

---

## 8. 配置与扩展

### 8.1 配置

```yaml
snap-agent:
  issue-closure:
    enabled: false              # 总开关, 默认 false (零 issue bean)
    system-user-id: system      # 执行 solution-suggest/verify-fix skill 的用户 ID
    storage-dir: ""             # Issue JSON 存储目录, 空则默认 {upload-skills-dir}/issues/
    tracker-type: noop          # IssueTracker 类型: noop / jira / github
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `false` | 总开关。`false` 时不装配任何 issue bean, controller 返回 503 |
| `system-user-id` | `system` | 执行方案/验证 skill 时的 `userId` (不归属真实用户) |
| `storage-dir` | `""` | 为空时回退到 `{upload-skills-dir}/issues/` |
| `tracker-type` | `noop` | 标识 IssueTracker 类型, 当前仅 `noop` 有内置实现 |

### 8.2 Bean 装配条件

所有 issue bean 都在 `snap-agent.issue-closure.enabled=true` 时装配 (`@ConditionalOnProperty`):

| Bean | 类型 | 条件 | 替换方式 |
|------|------|------|---------|
| `FileIssueStore` | `IssueStore` | `@ConditionalOnMissingBean(IssueStore.class)` | 声明自定义 `IssueStore` bean |
| `NoopIssueTracker` | `IssueTracker` | `@ConditionalOnMissingBean(IssueTracker.class)` | 声明自定义 `IssueTracker` bean |
| `KnowledgeSedimentationExtractor` | — | `@ConditionalOnMissingBean` | 声明自定义 bean |
| `TemplateSolutionSuggester` | `SolutionSuggester` | `@ConditionalOnMissingBean(SolutionSuggester.class)` | 声明自定义 `SolutionSuggester` bean |
| `SimpleVerificationRunner` | `VerificationRunner` | `@ConditionalOnMissingBean(VerificationRunner.class)` | 声明自定义 `VerificationRunner` bean |
| `IssueClosureService` | — | `@ConditionalOnMissingBean`, `ObjectProvider<KnowledgeBase>` 可空 | — |

`IssueClosureService` 通过 `ObjectProvider` 注入 `KnowledgeBase` (可空)、`SolutionSuggester` (可空, 回退到 skill)、`VerificationRunner` (可空, 回退到 skill)。

### 8.3 自定义 IssueTracker (Jira/GitHub)

实现 `IssueTracker` 接口 + `@Component`:

```java
@Component
public class JiraIssueTracker implements IssueTracker {
    private final JiraClient jiraClient;

    @Override
    public String createIssue(String title, String description, String assignee) {
        // 调用 Jira REST API 创建 Issue
        return jiraClient.createIssue(
            IssueRequest.builder()
                .projectKey("OPS")
                .summary(title)
                .description(description)
                .assignee(assignee)
                .build()
        ).getKey();  // 如 "OPS-1234"
    }

    @Override
    public void updateStatus(String externalIssueId, String status) {
        jiraClient.transitionIssue(externalIssueId, status);
    }

    @Override
    public String getIssueUrl(String externalIssueId) {
        return "https://jira.company.com/browse/" + externalIssueId;
    }

    @Override
    public String type() { return "jira"; }
}
```

注册后 `@ConditionalOnMissingBean(IssueTracker.class)` 不再装配 `NoopIssueTracker`。Jira/GitHub 官方实现计划在 v0.9.1 提供。

### 8.4 自定义 IssueStore (数据库存储)

实现 `IssueStore` 接口, 替换 `FileIssueStore`:

```java
@Component
public class DatabaseIssueStore implements IssueStore {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void save(IssueClosure issue) {
        jdbcTemplate.update(
            "INSERT INTO snap_agent_issues (issue_id, task_id, status, root_cause, " +
            "user_query, solution_json, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE status=?, updated_at=?",
            issue.getIssueId(), issue.getTaskId(), issue.getStatus().name(),
            issue.getRootCause(), issue.getUserQuery(),
            serializeSolution(issue.getSolution()),
            issue.getCreatedAt(), issue.getUpdatedAt(),
            issue.getStatus().name(), issue.getUpdatedAt()
        );
    }
    // ... 其他方法
}
```

### 8.5 自定义 SolutionSuggester / VerificationRunner

替换默认的模板/重跑实现:

```java
@Component
public class LlmSolutionSuggester implements SolutionSuggester {
    @Override
    public SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary) {
        // 调用外部 LLM 或知识图谱生成更精准的方案
        // 可使用 issue.getRootCause() 和 transcriptSummary
        return llmClient.generateSolutions(issue.getRootCause());
    }
}

@Component
public class MetricBasedVerificationRunner implements VerificationRunner {
    @Override
    public VerificationResult verify(IssueClosure issue) {
        // 直接检查 metrics 而非重跑 skill
        double errorRate = metricsClient.query("error_rate{service='order'}");
        boolean passed = errorRate < 0.01;
        return new VerificationResult(passed, "error rate: " + errorRate,
                "FAILED", "OK", System.currentTimeMillis());
    }
}
```

### 8.6 路线图

| 功能 | 计划版本 | 说明 |
|------|---------|------|
| Jira IssueTracker | v0.9.1 | 官方 Jira REST API 实现 |
| GitHub IssueTracker | v0.9.1 | GitHub Issues API 实现 |
| 自动 PR 创建 | v0.9.1 | 验证通过后自动创建 Pull Request |
| 定时回归验证 | v0.9.2 | 定期重新验证已关闭 issue, 防止复发 |
| ConversationKnowledgeSource | v0.7.1 | 从历史对话提取 Q&A 知识 |
