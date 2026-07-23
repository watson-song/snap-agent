# TDD 需求规格说明书 — 成本与安全

> 版本: 1.0 | 日期: 2026-07-23 | 模块: snap-agent-spring-boot-2x-starter / snap-agent-core

---

## 1. 需求元信息

```yaml
需求ID: REQ-10-COST-SECURITY
需求名称: 三维预算+限流+安全网关+只读工具守卫
优先级: P0
迭代: v0.5
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: LLM + 工具调用具备 prompt injection 风险，需多维成本控制与语法层安全防护；v1.0 仅有限流，缺预算和审计闭环。
- **用户价值**: 限制用户/skill/全局每日 LLM 成本；防止 SQL 写入、路径穿越、Git 命令注入、Redis 写入；统一 SecurityGateway SPI 接宿主鉴权。
- **成功指标**: SqlGuard/CodePathGuard/RateLimiter/BudgetEnforcer 单测覆盖率 ≥ 85%；POST /runs 鉴权 100% 拦截未认证请求。

### 1.2 范围边界
- **包含**: `BudgetEnforcer` 三维预算、`CostTrackingLlmClient` 装饰器、`RateLimiter` 并发+小时配额、`SecurityGateway` SPI + Adapter、`SqlGuard` 白名单/黑名单、`CodePathGuard` 路径白名单、`RedisReadToolProvider` 只读、`GitLogToolProvider` 命令注入防护、`ConfigReadToolProvider` 敏感字段脱敏、`SnapAgentController` 鉴权/审计。
- **不包含**: 语义层 SQL 分析、SecurityManager/沙箱、独立 DB 用户授权（由运维负责）、JWT 签发（由宿主负责）。

### 1.3 风险与假设
- R1: SqlGuard 语法层非语义层，可被合法语法绕过（中/高，缓解：只读 DB 用户 + 审计）
- R2: prompt injection 不可完全防（高/高，缓解：只读工具+只读 DB 限制爆炸半径，审计是检测非预防）
- R3: 多租户 raw JDBC 绕过租户拦截器（中/高，缓解：skill 自带 tenant_id + 只读 DSN）
- R4: CostTrackingLlmClient 依赖 LlmClient 主动发 onUsage（低/中，文档标注）

---

## 2. 用户故事 (User Stories)

### US-1: 三维预算阻止超额 LLM 调用
```gherkin
作为 运维管理员
我希望 按 用户/skill/全局 三维度每日预算拒绝超额调用
以便 防止单用户或单个 skill 拖垮整体 LLM 成本
```
**AC:**
```gherkin
AC1: 预算超额拒绝
  Given BudgetEnforcer(perUserDaily=$10) 且 costStore.sumCostByUser("u1")=10
  When enforcer.isWithinBudget("u1", "log-analysis")
  Then 返回 false 且 INFO 日志 "Budget exceeded for user u1"
AC2: null 预算无限制
  Given 三维均 null
  When enforcer.isWithinBudget("u1", "any")
  Then 返回 true
```

### US-2: 限流并发与小时配额
```gherkin
作为 平台
我希望 限制单用户并发数和每小时运行数
以便 防止单用户耗尽线程池和拖垮 LLM 配额
```
**AC:**
```gherkin
AC3: 并发上限触发
  Given RateLimiter(maxConcurrent=1, maxRunsPerHour=20)
  When 连续两次 tryAcquire("u1")
  Then 第一次 true，第二次 false
AC4: 线程池拒绝任务后 releaseRejected 回滚
  Given tryAcquire 成功后线程池拒绝
  When releaseRejected("u1")
  Then concurrentCount=0 且 hourlyCount=0（不泄漏配额）
```

### US-3: SqlGuard + CodePathGuard 工具层守卫
```gherkin
作为 安全负责人
我希望 拒绝 SQL 写操作/危险函数和路径穿越
以便 防止 prompt injection 通过 mysql_query/code_read 破坏或读取敏感文件
```
**AC:**
```gherkin
AC5: SqlGuard 首关键字白名单+黑名单
  Given SqlGuard(maxResultRows=1000)
  When validate("DELETE FROM users")
  Then reject "首关键字 DELETE"
  When validate("SELECT * FROM users INTO OUTFILE '/tmp/x'")
  Then reject "黑名单关键字 INTO OUTFILE"
AC6: LIMIT 注入
  When validate("SELECT * FROM users")
  Then ok，sql 以 "LIMIT 1000" 结尾
AC7: CodePathGuard 路径穿越拒绝
  Given CodePathGuard(projectRoot="/opt/app", allowedExtensions=[".java"])
  When validate("../etc/passwd")
  Then reject "禁止目录穿越 (..)"
  When validate("/etc/passwd")
  Then reject "不在项目根目录下"
```

### US-4: SnapAgentController 鉴权+审计
```gherkin
作为 平台
我希望 所有 /snap-agent/** 端点强制鉴权并记录审计
以便 未认证 401，已认证无权限 403，所有访问留痕
```
**AC:**
```gherkin
AC8: 401 未认证 — currentUserId()=null → 401 $.error="UNAUTHORIZED"
AC9: 403 无权限 — hasPermission()=false → 403 $.error="FORBIDDEN"
AC10: 审计 onApiAccess — 已认证用户访问触发 auditLogger.onApiAccess(userId, method, path, action, _)
```

### US-5: Redis/Git/Config 只读与脱敏
```gherkin
作为 安全负责人
我希望 Redis 仅 get/exists、Git 命令走 ProcessBuilder、Config 敏感字段脱敏
以便 限制工具的写能力和敏感信息泄露
```
**AC:**
```gherkin
AC11: Redis 写命令拒绝 — command="set" 返回 error "Redis command rejected (read-only): set"
AC12: Git commit_hash 正则 — "^[0-9a-f]{7,40}$" 不匹配则拒绝
AC13: Config 敏感字段脱敏 — password/secret/token/credential/key 字段值显示 "****"
```

### US-6: PrincipalResolver SPI 用户解析
```gherkin
作为 安全管理员
我希望 SecurityGateway 通过 PrincipalResolver 解析当前用户身份
  以便 审计日志和限流计数基于真实用户而非匿名
```
**AC:**
```gherkin
AC14: Given PrincipalResolver 实现 currentUserName() 返回 "user-001"
  When SecurityGateway.onApiAccess 执行
  Then audit 记录 userId="user-001"
  And RateLimiter.tryAcquire("user-001") 被调用
AC15: Given PrincipalResolver 实现 currentUserName() 返回 null
  When SecurityGateway.onApiAccess 执行
  Then audit 记录 userId="(anonymous)"
  And RateLimiter.tryAcquire 返回 false (拒绝匿名)
```

---

## 3. 功能规格

### 3.1 用例清单

| ID | 用例 | 优先级 | AC | 类型 |
|----|------|--------|----|------|
| UC-01 | BudgetEnforcer 三维预算 | P0 | AC1,AC2 | 单元 |
| UC-02 | RateLimiter 并发+小时配额 | P0 | AC3,AC4 | 单元 |
| UC-03 | SqlGuard 白名单/黑名单/LIMIT | P0 | AC5,AC6 | 单元 |
| UC-04 | CodePathGuard 路径白名单 | P0 | AC7 | 单元 |
| UC-05 | SnapAgentController 401/403/审计 + SecurityGateway SPI | P0 | AC8,AC9,AC10 | 集成 |
| UC-06 | 只读工具守卫 (Redis/Git/Config) | P0 | AC11,AC12,AC13 | 单元 |
| UC-07 | CostTrackingLlmClient 成本记录 | P1 | US-1 | 单元 |

### 3.2 详细用例 (Gherkin)

#### UC-01: BudgetEnforcer 三维预算
```gherkin
@priority:high @type:unit
功能: 三维度每日预算检查

  场景大纲: 预算维度命中
    Given costStore 配置 <setup>
    When enforcer.isWithinBudget("u1", "log-analysis")
    Then <expect>
    例子:
      | setup | expect |
      | perUserDaily=$10 且 sumCostByUser=10 | false，INFO "Budget exceeded for user u1" |
      | perSkillDaily=$50 且 sumCostBySkill=50 | false，INFO "Budget exceeded for skill log-analysis" |
      | globalDaily=$200 且 sumCost=200 | false，INFO "Global budget exceeded" |
      | 三维均 null | true（无限制） |
      | perUserDaily=$10 且 sumCostByUser=5 | true |
```

#### UC-02: RateLimiter 并发与小时配额
```gherkin
@priority:high @type:unit
功能: 单用户并发+小时配额

  场景大纲: 限流行为
    Given RateLimiter 配置 <config>
    When <action>
    Then <expect>
    例子:
      | config | action | expect |
      | maxConcurrent=1, maxRunsPerHour=20 | tryAcquire("u1") 两次 | 第一次 true，第二次 false |
      | maxConcurrent=10, maxRunsPerHour=2 | tryAcquire+release+tryAcquire+releaseRejected+tryAcquire | 第三次 true（不泄漏配额） |
      | maxConcurrent=1, maxRunsPerHour=20 | tryAcquire(null) | false |
      | maxConcurrent=5, maxRunsPerHour=3 | tryAcquire("u1")+release("u1") | concurrentCount=0 但 hourlyCount=1 |
      | maxConcurrent=1, maxRunsPerHour=20 | tryAcquire("u1")+release("unknown") | 不抛异常，unknown 仍 0 |
```

#### UC-03: SqlGuard 只读强制
```gherkin
@priority:high @type:unit
功能: SQL 白名单/黑名单/LIMIT 注入

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
      | WITH agg AS (...) SELECT ... | ok（CTE WITH） |
      | "" 或 null | reject "SQL 为空" |
      | SELECT * FROM users LIMIT 5000 | ok，改写为 "LIMIT 1000" |
```

#### UC-04: CodePathGuard 路径白名单
```gherkin
@priority:high @type:unit
功能: 代码路径白名单+穿越拒绝

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

  场景: resolveWithinProject("src/main")→/opt/app/src/main；("../etc")→null（不校验存在）
```

#### UC-05: SnapAgentController 鉴权+审计 + SecurityGateway SPI
```gherkin
@priority:high @type:integration
功能: /snap-agent/** 端点鉴权、审计、SecurityGateway SPI

  场景大纲: 鉴权分支
    Given <setup>
    When <request>
    Then <expect>
    例子:
      | setup | request | expect |
      | currentUserId()=null | GET /snap-agent/skills | 401 $.error="UNAUTHORIZED" |
      | hasPermission()=false | GET /snap-agent/models | 403 $.error="FORBIDDEN" |
      | securityGateway=null | GET /snap-agent/models | 401 $.message="security not configured" |
      | SecurityGateway impl: currentUserId()="user-001", hasPermission("skills:run")=true | 调 hasPermission("")/null | true；hasPermission("skills:admin")=false |
      | 不覆盖 currentUserName() | 调 currentUserName() | null（controller fallback 到 userId） |

  场景: 审计记录访问 — "user001" GET /models → onApiAccess("user001","GET","/models","LIST_MODELS",_)；未认证 → 401 且不审计
  场景: /user-info 不强制 requireAuth — currentUserId()=null → 200 $.authenticated=false（鉴权在 filter 链）
  场景: currentUserName 显示名 — "Alice Wang" → $.username="Alice Wang"；null 时 fallback 到 userId
```

#### UC-06: 只读工具守卫 (Redis/Git/Config)
```gherkin
@priority:high @type:unit
功能: Redis 仅 get/exists + Git ProcessBuilder + Config 脱敏

  场景大纲: 工具命令校验
    When provider.execute(<args>, ctx)
    Then <expect>
    例子:
      | tool | args | expect |
      | Redis | {key:"k1", command:"get"} | 调 redisTemplate.opsForValue().get("k1") |
      | Redis | {key:"k1", command:"exists"} | 调 redisTemplate.hasKey("k1") |
      | Redis | {command:"set"/"del"} | error "Redis command rejected (read-only): {cmd}" |
      | Redis | {key:null} | error "missing required parameter: key" |
      | Git | {mode:"log", max_entries:5} | buildCommand List<String> 不经 shell |
      | Git | {mode:"show", commit_hash:"abc1234"} | 正则 ^[0-9a-f]{7,40}$ 通过 |
      | Git | {commit_hash:"; rm -rf /"} | error "commit_hash 格式无效" |
      | Git | {mode:"blame", file_path:"../etc/passwd"} | CodePathGuard 拒绝穿越 |
      | Config | {source:"local", key_prefix:"spring.ds"} | password/secret/token/credential/key 值 "****" |
      | Config | {source:"local"} 无 prefix | 枚举所有属性，clamp maxKeys=100 |
      | Config | {source:"nacos", nacos_data_id:null} | error "missing nacos_data_id" |
      | Config | {source:"invalid"} | error "invalid source: invalid" |
```

#### UC-07: CostTrackingLlmClient 成本记录
```gherkin
@priority:medium @type:unit
功能: LlmClient 装饰器记录成本

  场景: onUsage 已调用，onStop 触发记录
    Given delegate.stream 发 onUsage(inputTokens=100, outputTokens=200, cacheReadTokens=50) 后 onStop("end_turn")
    When trackingClient.stream(req, sink, "task-1")
    Then costTracker.record 被调用一次，CostRecord.inputTokens=100, outputTokens=200
    And costCalculator.computeCost(100,200,50) 被调用

  场景: 未发 onUsage 不记录
    Given delegate.stream 直接 onStop
    When trackingClient.stream(req, sink, "task-1")
    Then costTracker.record 从未调用（不创建零成本记录）

  场景: record 异常不抛出
    Given costTracker.record 抛 RuntimeException
    When trackingClient.stream(req, sink, "task-1")
    Then 不向上抛，仅 WARN 日志，delegate.onStop 仍被调用
```

---

## 4. 接口规格

### 4.1 REST 端点
- `/snap-agent/skills`、`/snap-agent/models`、`/snap-agent/tools`、`/snap-agent/skills/refresh` — 需 `currentUserId()` 非空 + `hasPermission(requiredPermission)`
- `/snap-agent/runs` — 鉴权 + RateLimiter + BudgetEnforcer
- `/snap-agent/user-info` — 不调 requireAuth，返回 authenticated/authorized/username

### 4.2 内部接口
- `boolean isWithinBudget(userId, skillName)` — BudgetEnforcer
- `boolean tryAcquire(userId)` / `release(userId)` / `releaseRejected(userId)` — RateLimiter
- `SqlGuard.Result validate(sql)` — SqlGuard
- `CodePathGuard.Result validate(path)` / `Path resolveWithinProject(pathStr)` — CodePathGuard
- `String currentUserId()` / `String currentUserName()` (default null) / `boolean hasPermission(code)` — SecurityGateway
- `ToolResult execute(args, ctx)` — ToolProvider 实现

### 4.3 MCP/JSON-RPC — SqlGuard 黑名单正则（大小写不敏感）：`\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|RENAME|GRANT|REVOKE|REPLACE|MERGE|CALL|HANDLER|LOCK|UNLOCK|FLUSH|RESET|SHUTDOWN|KILL|LOAD|LOAD_FILE|INTO\s+OUTFILE|INTO\s+DUMPFILE|INTO\s+@|SLEEP|BENCHMARK)\b`

---

## 5. 数据规格

- **BudgetEnforcer**: 三维 `BigDecimal perUserDaily/perSkillDaily/globalDaily`，null=无限制；窗口从本地午夜零点（`startOfTodayMillis()`）
- **RateLimiter**: `ConcurrentHashMap<userId, AtomicInteger>` 并发+小时计数；小时窗口 `currentHourMillis()`（`now - now % 3600000`）；CAS 防 TOCTOU；release 不回滚小时配额，releaseRejected 回滚两个计数
- **CostRecord**: `userId, skillName, taskId, model, inputTokens, outputTokens, cacheReadTokens, cost(BigDecimal), timestamp`；CostTrackingLlmClient `onUsage` 累计 → `onStop` 触发 `costTracker.record`
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
| E207 | Budget exceeded | INFO 日志 + 调用方决定 429 或拒绝 |
| E208 | Rate limit | 429 + Retry-After |

---

## 7. 非功能需求

### 7.1 性能 — SqlGuard.validate P99 < 1ms；CodePathGuard.validate P99 < 2ms（含 Files.exists）；RateLimiter.tryAcquire P99 < 0.1ms；BudgetEnforcer.isWithinBudget 取决于 CostStore 查询。

### 7.2 安全 — [x] SqlGuard 白名单+黑名单+多语句拒绝+LIMIT 注入；[x] CodePathGuard 路径穿越拒绝+扩展名白名单+大小上限；[x] RedisReadToolProvider 仅 get/exists，无 set/del/incr/keys；[x] GitLogToolProvider 用 ProcessBuilder（非 shell）+ commit_hash 正则 + 路径走 CodePathGuard；[x] ConfigReadToolProvider 敏感字段脱敏（password/secret/token/credential/key）；[x] SecurityGateway SPI，宿主可覆盖；[x] /snap-agent/** 强制鉴权。

### 7.3 可测试性 — RateLimiter 无外部依赖纯内存；SqlGuard/CodePathGuard 纯逻辑；SnapAgentController 用 MockMvc + Mockito；CostTrackingLlmClient mock delegate LlmClient。

---

## 8. 测试策略

### 8.1 已有测试覆盖

| 测试文件 | 类型 | 覆盖用例 |
|----------|------|----------|
| `RateLimiterTest` | 单元 | UC-02 并发+小时配额+releaseRejected+null 用户+多用户+边界 |
| `SecuritySpiTest` | 单元 | UC-05 SecurityGateway/PrincipalResolver SPI 可实现性 |
| `SnapAgentControllerSecurityTest` | 集成 | UC-05 401/403/审计+/user-info fallback/displayName/null gateway + LoggingSecurityAuditLogger |

### 8.2 测试缺口

- **P0** `BudgetEnforcer` 三维度边界 + null 预算 — mock CostStore 返回边界值
- **P0** `SqlGuard` 9 类拒绝 + LIMIT 改写 — 参数化拒绝用例 + 正常 SELECT
- **P0** `CodePathGuard` 穿越/扩展名/大小/null — 参数化路径场景 + resolveWithinProject
- **P0** `RedisReadToolProvider` 只读命令拒绝 — mock RedisTemplate 验证 get/exists/set 拒绝
- **P0** `GitLogToolProvider` ProcessBuilder + 正则 — mock CodePathGuard + 验证 buildCommand List
- **P0** `ConfigReadToolProvider` 脱敏 — MockEnvironment + EnumerablePropertySource
- **P1** `CostTrackingLlmClient` 装饰器 — mock LlmClient 发 onUsage+onStop 验证 record
- **P1** `SecurityGateway.currentUserName` 默认 null — 验证 default 方法 + controller fallback
- **P2** `BudgetEnforcer.startOfTodayMillis` 时区 + `RateLimiter` 小时窗口切换

### 8.3 Mock 策略
单元: CostStore/RedisTemplate/LlmClient=Mockito, Environment=MockEnvironment；集成: MockMvc + mock SecurityGateway/RateLimiter；SqlGuard/CodePathGuard 纯逻辑无 Mock。

---

## 9. 依赖与前置条件

`CostStore` SPI（内存默认）；`CostTracker`+`CostCalculator`（已就绪）；`SecurityGateway` Adapter（SpringSecurity/ShiroAdapter，宿主覆盖 bean）；`PrincipalResolver`（反射 `getId/getUserId/getUsername`）；`SnapAgentFilter`（order=`LOWEST_PRECEDENCE-10`，宿主 auth 之后）；宿主须放行 `/snap-agent/**` 并填充 SecurityContext。

---

## 10. 可观测性

- **日志**: WARN "SQL rejected by guard: {reason}"；WARN "Redis command rejected (read-only): {cmd}"；WARN "SecurityContext 为空，无法解析 principal 类型 X"；INFO "Budget exceeded for user {u}: {cost} >= {limit}"
- **审计**: AuditRecord{taskId,userId,toolName,args,rowCount,truncated,timestamp,durationMs}；onApiAccess(userId,method,path,action,details)
- **指标**: llm_cost_total{user,skill,model}, budget_check_total{dimension,result}, rate_limit_reject_total{reason}, sql_guard_reject_total{reason}

---

## 11. 原型与交互参考

GET /skills → 200 列表 / 401 UNAUTHORIZED / 403 FORBIDDEN；POST /runs → 200 taskId / 401 / 403 / 429 配额耗尽；GET /user-info → 200 `{authenticated,authorized,userId,username}`。

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 变更 |
|------|------|------|------|
| 1.0 | 2026-07-23 | TDD Bot | 初始版本 |

### 12.2 参考文档
`docs/embeed-skills-agent/07-config-security.md`；`docs/superpowers/specs/2026-07-21-user-display-name-spi-design.md`；`docs/embeed-skills-agent/04-tools-and-mcp.md`（守卫细节）；`docs/tdd/TEMPLATE.md`

### 12.3 术语表
BudgetEnforcer=三维每日预算检查器；RateLimiter=并发+小时配额；SecurityGateway SPI=桥接宿主安全框架；SqlGuard=SQL 只读强制；CodePathGuard=路径白名单+穿越拒绝；PrincipalResolver=principal→userId SPI；SecurityAuditLogger=API 访问审计 SPI
