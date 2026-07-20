# 07 — 配置与安全

## 1. 完整配置树

```yaml
snap-agent:
  enabled: false                      # 默认关, 零影响（见 01 §4）
  base-path: /snap-agent            # controller 前缀
  builtin-skills-dir: classpath:/docs/skills/  # 只读，打包在 JAR 中
  upload-skills-dir: /tmp/snap-agent-skills     # 读写，重启持久化
  llm:
    base-url: https://api.anthropic.com
    api-key: ${LLM_API_KEY:}          # 空 → LlmClient 不装配
    model: claude-sonnet-4-6          # 默认 model
    allowed-models: [claude-sonnet-4-6, claude-opus-4-6]   # 服务端强制白名单
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true
    # shade-okhttp: false             # Phase 3, 冲突时启用
    # provider: anthropic             # Phase 3 加 openai
  agent:
    max-turns: 20
    task-timeout-minutes: 30
    executor: snapAgentExecutor     # 专用线程池 bean 名 (core=2/max=4/queue=10)
    max-concurrent-runs-per-user: 1
    max-runs-per-hour: 20
    max-result-rows: 1000             # JDBC 行数上限
    max-tool-result-chars: 50000      # 回填 LLM 的字符上限
    transcript-event-limit: 500       # 单 task transcript 事件上限
  jdbc:
    enabled: true
    datasource-bean-name: snapAgentReadOnlyDataSource   # 独立只读 DSN（旧: 单环境）
    # 多环境数据源 (v0.6): 配置后覆盖单 DSN 模式, mysql_query 工具 schema 新增 env 参数
    datasources:
      sit:
        url: "jdbc:mysql://sit-db:3306/orders"
        username: "readonly_user"
        password: "${SIT_DB_PASSWORD:}"
        driver-class-name: "com.mysql.cj.jdbc.Driver"
      uat:
        url: "jdbc:mysql://uat-db:3306/orders"
        username: "readonly_user"
        password: "${UAT_DB_PASSWORD:}"
        driver-class-name: "com.mysql.cj.jdbc.Driver"
    default-env: ""    # 默认环境名, 空=第一个 datasources 条目
  redis:
    enabled: true
    redis-template-bean-name: redisTemplate
    max-key-count: 100
  logs:
    enabled: true                        # log_read 工具（日志分析）
    allowed-paths: [/opt/app/logs]       # 允许读取的日志目录白名单
    max-lines: 500                       # 单次最多返回行数
    max-file-bytes: 10485760             # 拒绝读取 >10MB 的文件（10MB）
    app-log-file: ""                     # 应用日志文件路径；空=自动从 logging.file.name 解析
  code:
    enabled: false                       # 代码理解工具（code_read/project_structure/git_log），默认关
    project-root: ""                     # 宿主项目根目录，为空则工具不启用
    allowed-extensions: [".java",".xml",".yml",".yaml",".properties",".sql",".md",".txt",".json",".csv"]
    max-lines: 500                       # 单次读取最大行数
    max-file-bytes: 524288               # 单个文件最大 512KB
    structure-depth: 3                  # project_structure 默认扫描深度
    context-injection: true              # 是否注入项目结构摘要到 system prompt
  metrics:
    enabled: false                       # 可观测性指标工具（metrics_query），默认关
    base-url: ""                         # Prometheus URL, e.g. http://prometheus:9090
    auth-header: ""                      # HTTP header name (e.g. "Authorization")
    auth-header-value: ""               # Header value (e.g. "Bearer xxx")
    timeout-seconds: 15
    max-points: 200                      # 最大数据点数/series
  log-search:
    enabled: false                       # 日志搜索工具（log_search），默认关
    base-url: ""                         # Loki URL, e.g. http://loki:3100
    auth-header: ""
    auth-header-value: ""
    timeout-seconds: 15
    max-lines: 500                       # 单次返回最大行数
  trace:
    enabled: false                       # 链路追踪工具（trace_search），默认关
    base-url: ""                         # Jaeger URL, e.g. http://jaeger:16686
    auth-header: ""
    auth-header-value: ""
    timeout-seconds: 15
    max-traces: 20                       # 单次返回最大 trace 数
  config-read:
    enabled: false                       # 配置读取工具（config_read），默认关
    nacos-base-url: ""                   # Nacos URL
    nacos-namespace: ""                  # 默认 namespace
    nacos-auth-token: ""                 # Nacos 鉴权 token
    max-keys: 100                        # 本地属性最大返回数
    sensitive-key-patterns: ["password", "secret", "token", "credential", "key"]
  mcp:
    enabled: false                    # Phase 2
    servers: {}                       # {name: {transport: sse, url, auth-header, auth-header-value}}
  security:
    framework: auto                   # auto | spring-security | shiro
    required-permission: ""           # 空=已登录即可；填权限码则需 hasPermission
    filter-order: <Ordered.LOWEST_PRECEDENCE - 10>   # 见 §4 注
    principal-resolver-class: ""      # 空=默认实现；填全限定类名=自定义
    audit-log: true
    auth-token-header: ""             # 前后端分离 token 鉴权：HTTP header 名称（如 token）
    auth-token-cookie: ""             # token 存 cookie 时：cookie 名称
    auth-token-local-storage-key: ""  # token 存 localStorage 时：key 名称（如 TOKEN）
  patrol:
    enabled: false          # 主动监控巡检子系统（v0.5），默认关
    scheduler-pool-size: 2  # 定时巡检线程池大小
    report-buffer-size: 500 # 内存中保留的最大巡检报告数
    lock-ttl-seconds: 300   # 多 Pod 协调锁存活时间（秒，v1.1 新增，默认 5 分钟）
  alert:
    enabled: false            # 告警收敛子系统（v0.5），默认关
    buffer-size: 1000         # 内存中保留的最大告警数
    auto-resolve-minutes: 30  # 无更新的告警自动 resolve 的分钟数
    push:                     # 异常报告推送（v1.1 新增）
      email:
        enabled: false              # Email 推送开关
        from: snap-agent@example.com # 发件人
        to:                          # 收件人列表
          - ops@example.com
        subject-prefix: "[SnapAgent 告警]"
      webhook:
        enabled: false              # Webhook 推送开关
        url: https://hook.example.com/snap-agent  # 推送 URL
        auth-header: Authorization   # 认证头名称
        auth-token: ""               # 认证令牌（支持 ${ENV}）
        connect-timeout-ms: 5000
        read-timeout-ms: 10000
  knowledge:                   # 嵌入式业务知识库（v0.7），默认关
    enabled: false
    sources:                   # 知识源列表
      - type: markdown         # 目前仅支持 markdown
        dir: classpath:/docs/knowledge/  # classpath: 前缀=JAR 内资源；否则文件系统路径
    max-fragments: 3           # 每次查询注入的最大知识片段数
    min-score: 0.1             # 最小相关度阈值 [0.0, 1.0]
```

### 配置字段落点矩阵（验证项 #1 文档自检）

| 配置 | 落点组件 | 文档 |
|------|---------|------|
| `enabled` | AutoConfig `@ConditionalOnProperty` | 01 §4 |
| `base-path` | SnapAgentController `@RequestMapping` | 06 |
| `builtin-skills-dir` | 内置 Skill 扫描路径（classpath，只读），默认 `classpath:/docs/skills/` | 02 §4 |
| `upload-skills-dir` | 上传 Skill 扫描路径（文件系统，读写），默认 `/tmp/snap-agent-skills` | 02 §4 |
| `llm.*` | AnthropicLlmClient / `GET /models` / POST /runs 校验 | 05 |
| `agent.max-turns` | AgentExecutor 停止条件 | 03 §4 |
| `agent.task-timeout-minutes` | AgentExecutor 超时 | 03 §4 |
| `agent.executor` | 线程池 bean 名 | 03 §7 |
| `agent.max-concurrent-runs-per-user` / `max-runs-per-hour` | 限流 | 03 §6 |
| `agent.max-result-rows` / `max-tool-result-chars` | ToolDispatcher / JdbcToolProvider | 04 §2.2 |
| `agent.transcript-event-limit` | TaskStore | 03 §5 |
| `jdbc.*` | JdbcQueryToolProvider | 04 §2 |
| `redis.*` | RedisReadToolProvider | 04 §3 |
| `logs.*` | LogReadToolProvider | 04 §2.3 |
| `code.*` | CodeReadToolProvider / GitLogToolProvider / ProjectStructureToolProvider | 04 §4 |
| `metrics.*` | MetricsToolProvider（Prometheus 查询） | 04 §5 |
| `log-search.*` | LogSearchToolProvider（Loki 日志搜索） | 04 §5 |
| `trace.*` | TraceSearchToolProvider（Jaeger 链路追踪） | 04 §5 |
| `config-read.*` | ConfigReadToolProvider（本地配置 + Nacos） | 04 §5 |
| `mcp.*` | McpToolProvider（Phase 2） | 04 §4 |
| `knowledge.*` | KnowledgeBase / MarkdownKnowledgeSource / SimpleKeywordSearcher / KnowledgeInjector | §12 |
| `security.framework` | SecurityGateway Adapter 选择 | §3 |
| `security.required-permission` | Controller 鉴权 | §3 |
| `security.filter-order` | SnapAgentFilter 注册 order | §4 |
| `security.principal-resolver-class` | PrincipalResolver 装配 | §5 |
| `security.audit-log` | AuditRecord 落库开关 | 04 §2.6 |

## 2. SecurityGateway SPI（决策 #5）

```java
public interface SecurityGateway {
    /** 当前已认证用户 ID（非空，否则 controller 直接 401） */
    String currentUserId();
    /** 是否具某权限码；required-permission 为空时恒 true */
    boolean hasPermission(String code);
}
```

controller 入口：
```java
String userId = securityGateway.currentUserId();           // 401 if null
if (!securityGateway.hasPermission(props.getSecurity().getRequiredPermission())) {
    return 403;                                            // 已登录但无权限
}
```

## 3. 双 Adapter（Phase 1 都做 — 决策 #5）

### 3.1 SpringSecurityAdapter
- 装配条件：`@ConditionalOnClass(SecurityContextHolder.class)` 且 `security.framework` ∈ `{auto, spring-security}`。
- `currentUserId()`：`SecurityContextHolder.getContext().getAuthentication()` → 若为 null 抛 401；否则 `principalResolver.resolve(auth.getPrincipal())`。
- `hasPermission(code)`：`code` 空 → true；否则遍历 `auth.getAuthorities()`，**精确匹配** `authority.getAuthority().equals(code)`。

### 3.2 ShiroAdapter
- 装配条件：`@ConditionalOnClass(SecurityUtils.class)` 且 `security.framework` ∈ `{auto, shiro}`。
- `currentUserId()`：`SecurityUtils.getSubject().getPrincipal()` → principalResolver 解析。
- `hasPermission(code)`：`code` 空 → true；否则 `SecurityUtils.getSubject().isPermitted(code)`（Shiro 走 **wildcard** 匹配，如 `skills:*` 命中 `skills:run`）。

### 3.3 auto 模式选择
- `framework=auto`（默认）：按 classpath 优先级选。两者都在 → 默认 Spring Security（可 yml 强制 shiro）。两者都不在 → SecurityGateway 不装配 → 所有 skill UNAVAILABLE（理由：缺安全框架），starter 不崩。

### 3.4 语义差异（文档显式标注 — 决策 #5）
| 框架 | hasPermission 语义 | 示例 |
|------|--------------------|------|
| Spring Security | authority **精确匹配** | `required-permission: snap-agent:run` → 用户须有 authority `snap-agent:run` |
| Shiro | **wildcard** 匹配 | `required-permission: snap-agent:run` → 用户有 `snap-agent:*` 或 `snap-agent:run` 都通过 |

> 集成方须按自己框架的权限码体系配 `required-permission`，理解精确 vs 通配差异。

### 3.5 自定义 SecurityGateway（权限不在 GrantedAuthority 时必须）

**常见场景**：许多企业级 Spring Boot 项目将权限码存在 principal 对象的自定义字段（如 `LoginUser.permissionList`）中，而非标准 `GrantedAuthority`。此时 `SpringSecurityAdapter.hasPermission()` 遍历 `auth.getAuthorities()` 找不到权限码 → `authorized: false`。

**现象**：`GET /snap-agent/user-info` 返回 `authenticated: true, authorized: false`，但用户 principal 的 permissionList 确实包含 `snap-agent:access`。

**解决方案**：宿主声明自定义 `SecurityGateway` bean，覆盖默认 `SpringSecurityAdapter`（`@ConditionalOnMissingBean` 生效）：

```java
@Component
public class HostSecurityGateway extends SpringSecurityAdapter {

    public HostSecurityGateway(PrincipalResolver principalResolver) {
        super(principalResolver);
    }

    @Override
    public boolean hasPermission(String code) {
        if (code == null || code.isEmpty()) return true;
        // 1. 先查标准 GrantedAuthority
        if (super.hasPermission(code)) return true;
        // 2. 再查 principal 的自定义权限字段
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser) {
            LoginUser loginUser = (LoginUser) auth.getPrincipal();
            List<String> permissions = loginUser.getPermissionList();
            return permissions != null && permissions.contains(code);
        }
        return false;
    }
}
```

> `currentUserId()` 继承自 `SpringSecurityAdapter`，通过 `PrincipalResolver` 解析，无需重写。

**替代方案**：修改宿主 JWT filter，在构造 `Authentication` 时将 `permissionList` 转为 `GrantedAuthority`：

```java
List<GrantedAuthority> authorities = loginUser.getPermissionList().stream()
    .map(SimpleGrantedAuthority::new)
    .collect(Collectors.toList());
```

这是 Spring Security 的"标准做法"，但可能影响宿主其他模块的权限逻辑。自定义 `SecurityGateway` 更安全、影响面最小。

### 3.6 Skill 级别权限控制（v0.6）

除了全局 `security.required-permission`，v0.6 支持在 Skill frontmatter 中声明 `required-permission`，实现细粒度权限控制：

```yaml
# skill markdown frontmatter
---
name: database-query
description: "执行只读 SQL 查询"
tools: [mysql_query]
required-permission: snap-agent:db-query   # 运行此 skill 需要的权限码
---
```

**安全语义：**
- `required-permission` 为空 → 继承全局 `security.required-permission`（向后兼容）
- `required-permission` 非空 → 覆盖全局权限，仅检查 skill 级别权限码
- 宿主可同时配置全局和 skill 级权限：用户需同时拥有全局 access 权限 + 具体 skill 权限

**运行时检查：** `POST /runs` 时，Controller 从 SkillRegistry 获取 skill，如果 skill 声明了 `required-permission` 且用户无此权限 → 返回 403。

**GET /skills 响应：** skill DTO 包含 `requiredPermission` 字段（仅在非空时出现），前端可据此显示权限标识。

## 4. Filter 可配序、鉴权委托宿主（决策 #4）

### Filter 职责
`SnapAgentFilter`（`javax.servlet.Filter`）只做一件事：在 `/snap-agent/**` 请求进入 controller 前，从安全框架取 principal，塞进 `AgentRequestContext`（ThreadLocal 或 request attribute），供 controller / ToolDispatcher / 审计用。**不做认证本身**（认证由宿主安全框架负责）。

### Filter 注册
```java
@Bean
@ConditionalOnProperty(prefix="snap-agent", name="enabled", havingValue="true")
public FilterRegistrationBean<SnapAgentFilter> snapAgentFilter(...) {
    FilterRegistrationBean<SnapAgentFilter> reg = new FilterRegistrationBean<>(new SnapAgentFilter(...));
    reg.addUrlPatterns("/snap-agent/*");
    reg.setOrder(props.getSecurity().getFilterOrder());
    return reg;
}
```

### filter-order 说明与 reconciliation 注
> **设计 reconciliation（来自计划评审）**：原计划 YAML 写 `filter-order: -2147483638`，注释 `# LOWEST_PRECEDENCE-10`。两者冲突 —— Spring `Ordered.LOWEST_PRECEDENCE = Integer.MAX_VALUE = 2147483647`，`LOWEST_PRECEDENCE - 10 = 2147483637`（**低优先级 = 高 order 值 = 后执行**，落在宿主 auth 之后，符合决策 #4「宿主 auth 之后」的意图）。而 `-2147483638`（=`Integer.MIN_VALUE+10`）是**高优先级 = 先执行**，会在宿主 auth 之前跑，principal 尚未填充，违背意图。本设计采用**符号常量 `Ordered.LOWEST_PRECEDENCE - 10`（=2147483637）**以契合意图。**请确认此修正。**

### 为什么放在宿主 auth 之后
- 宿主安全过滤器链（Spring Security `FilterChainProxy` 默认 order `-100`；Shiro `ShiroFilterFactoryBean` 类似）先跑，填充 `SecurityContextHolder` / `Subject`。
- 本 filter order = 2147483637 ≫ -100，确保安全上下文已就绪，再读 principal。
- 宿主须放行 `/snap-agent/**`（见 [09](09-integration-guide.md) §4），否则请求到不了本 filter。注意：所有 `/snap-agent/**` 端点（含 `/snap-agent/user-info`）都需认证，不要将其加入 JWT filter 白名单。

### 可配序
- 宿主若有别的后置 filter 需在本 filter 之前/之后，调 `security.filter-order`。

## 5. PrincipalResolver SPI（决策 #6）

principal 类型是 app-specific 的（可能是 String username、`UserDetails`、自定义 `User` 对象）。本库不假设其结构，提供 SPI：

```java
public interface PrincipalResolver {
    String resolve(Object principal);   // 返回 userId；null 表示解析失败
}
```

### DefaultPrincipalResolver（默认实现）
解析顺序：
1. `principal instanceof String` → 直接返回（许多 JWT/CAS 场景 principal 就是 username）。
2. `principal instanceof UserDetails` → `((UserDetails) principal).getUsername()`。
3. 反射：尝试调 `getId()` / `getUserId()` / `getUsername()` / `getUserName()` 方法（按顺序），返回第一个非 null 结果。非 String 返回值（如 `Long`）自动通过 `toString()` 转换。`getUserName()` 覆盖 Lombok 对 `userName` 字段生成的访问器。
4. 都失败 → 返回 null → controller 401 + 日志 WARN「无法解析 principal 类型 X，请配 principal-resolver-class」。

### 自定义
两种方式（二选一）：

**方式 A**：yml 配置类名
```yaml
snap-agent:
  security:
    principal-resolver-class: com.example.app.security.AppPrincipalResolver
```

**方式 B**：声明 `@Bean`（`@ConditionalOnMissingBean` 自动跳过默认实现）
```java
@Bean
public PrincipalResolver snapAgentPrincipalResolver() {
    return principal -> { /* ... */ };
}
```

> 常见需要自定义的场景：principal 的用户标识字段不在 `getId`/`getUserId`/`getUsername`/`getUserName` 列表中（如 `getEmpNo`），或需要跳过 `getId()`（返回数据库自增 ID）取业务标识。详见 [09](09-integration-guide.md) §5。

## 6. 审计（决策 #14）

- 每次 tool 调用落 `AuditRecord{ taskId, userId, toolName, args, rowCount, truncated, timestamp, durationMs }`。
- 存储：内存 ring-buffer（默认 1000 条/run）或 Redis list `snap-agent:audit:{taskId}`（TTL 24h，有 RedisTemplate 时优先）。
- `GET /runs/{id}/transcript` 含审计记录。
- 开关：`security.audit-log`（默认 true）。
- **审计是检测而非预防**：用于事后追溯「谁在何时用 skill 跑了什么 SQL」。不能阻止 prompt injection，但能发现异常。

## 7. 限流（决策 #15）

详见 [03-agent-engine.md](03-agent-engine.md) §6。配置：`agent.max-concurrent-runs-per-user`（默认 1）、`agent.max-runs-per-hour`（默认 20）、`agent.executor`（线程池 core=2/max=4/queue=10，满则 429）。

## 8. 租户绕过风险（诚实声明）

- 本库 `mysql_query` 走 **raw JDBC**，绕过宿主 MyBatis Plus 租户拦截器（`IgnoreCustomTenantLineAspect`）。
- 缓解：
  1. **只读 DSN**：DB 用户授权范围限制（不授跨租户表 / 敏感表）。
  2. **skill 自带 `tenant_id` 占位**：标准 skill 的 SQL 习惯带 `tenant_id = IF('{tenantId}'='', tenant_id, '{tenantId}')` 过滤（见现有 3 个 skill），skill 作者须保留此惯例。
  3. **PrincipalResolver 提供 tenantId**：`AgentRequestContext` 带当前用户 tenantId，注入 system prompt，提示 LLM 在 SQL 中带该 tenantId。
  4. **审计**：事后可追溯跨租户查询。
- **多租户宿主须评估**：若 skill 漏带 tenant_id 且只读 DSN 授权过宽，存在跨租户读风险。集成指南建议只读 DSN 仅授本租户可见的视图或带 RLS（Row-Level Security）的表。

## 9. 验证（验证项 #5 双框架鉴权走查）

### Spring Security 路径
1. 宿主 SecurityFilterChain 已认证，`SecurityContextHolder.authentication.principal = "user001"`（String）。
2. 请求 `POST /snap-agent/runs` → SnapAgentFilter（order 2147483637，在 SecurityFilterChain 之后）→ `SecurityGateway.currentUserId()` → SpringSecurityAdapter → principalResolver.resolve("user001") = "user001"。
3. `hasPermission("")` → true（required-permission 空）。
4. controller 放行，AgentExecutor 启动，审计 userId=user001。

### Shiro 路径
1. Shiro Subject 已认证，`Subject.principal = new User(id="u9", perms=["snap-agent:*"])`。
2. 请求进入 → ShiroAdapter → principalResolver 反射 `getId()` = "u9"。
3. `hasPermission("snap-agent:run")` → `Subject.isPermitted("snap-agent:run")` → wildcard 命中 `snap-agent:*` → true。
4. 放行。

### 失败路径
- 未登录：`SecurityContextHolder.authentication == null` → `currentUserId()` 返回 null → 401。
- 已登录无权限：`hasPermission` false → 403。
- principal 解析失败：resolver 返回 null → 401 + WARN 日志提示配 `principal-resolver-class`。

## 10. 主动监控 — 巡检配置（v0.5，v1.1 更新）

SnapAgent v0.5 引入主动健康巡检调度，按 cron 定时执行诊断 Skill，发现异常自动生成报告。
v1.1 新增：多 Pod 协调锁、异常报告推送渠道、PatrolReportStore SPI 化。

```yaml
snap-agent:
  patrol:
    enabled: false             # 巡检子系统总开关
    scheduler-pool-size: 2     # 定时巡检线程池大小
    report-buffer-size: 500    # 内存中保留的最大巡检报告数（ring buffer）
    lock-ttl-seconds: 300      # 多 Pod 协调锁 TTL（v1.1 新增，秒）
  alert:
    enabled: false             # 告警收敛开关
    buffer-size: 1000
    auto-resolve-minutes: 30
    push:                      # 异常报告推送（v1.1 新增）
      email:
        enabled: false
        from: snap-agent@example.com
        to: [ops@example.com]
        subject-prefix: "[SnapAgent 告警]"
      webhook:
        enabled: false
        url: https://hook.example.com/snap-agent
        auth-header: Authorization
        auth-token: ""
        connect-timeout-ms: 5000
        read-timeout-ms: 10000
```

当 `patrol.enabled=true` 时，自动装配以下 Bean：

| Bean | 职责 |
|------|------|
| `PatrolReportStore` (SPI) | 默认 `InMemoryPatrolReportStore` 内存 ring buffer；宿主可替换为 DB 实现（v1.1 SPI 化） |
| `PatrolLockProvider` (SPI) | 默认 `NoopPatrolLockProvider` 单 Pod 直接放行；多 Pod 部署时宿主可替换为 Redis/k8s lease/DB 行锁（v1.1 新增） |
| `ThreadPoolTaskScheduler` | Spring 调度器，驱动 cron 触发的巡检任务 |
| `ScheduledPatrolScheduler` | 实现 `PatrolScheduler` SPI，管理巡检任务生命周期（v1.1 注入 lockProvider + pushChannels） |
| `DefaultAnomalyEventListener` | 实现 `AnomalyEventListener` SPI，接收异常事件并自动触发诊断 Skill（v1.1 注入 pushChannels） |

当 `alert.push.email.enabled=true` 且 classpath 含 `JavaMailSender` 时装配：

| Bean | 职责 |
|------|------|
| `EmailAlertPushChannel` | 通过 Spring `JavaMailSender` 发送异常报告邮件（需 `spring-boot-starter-mail` 可选依赖，v1.1 新增） |

当 `alert.push.webhook.enabled=true` 且 `url` 非空时装配：

| Bean | 职责 |
|------|------|
| `WebhookAlertPushChannel` | 通过 JDK `HttpURLConnection` POST JSON 到 webhook URL（无外部依赖，v1.1 新增） |

> 巡检执行时若 `detectAnomaly()` 判定为异常（状态 FAILED/TIMEOUT 或报告摘要含
> "critical"/"warning"/"error"/"异常"/"错误"/"失败" 等关键词），所有
> `AlertPushChannel` bean 会同时收到推送，单 channel 失败不影响其它。

### 巡检 API

| 端点 | 说明 |
|------|------|
| `POST /patrol/tasks` | 创建巡检任务（body: `skillName`, `cron`, `inputs`） |
| `GET /patrol/tasks` | 列出所有巡检任务 |
| `DELETE /patrol/tasks/{id}` | 取消巡检任务 |
| `GET /patrol/reports?page=&size=` | 分页查询巡检报告 |
| `GET /patrol/reports/{id}` | 获取单个巡检报告详情 |

### Cron 表达式

使用 Spring 6 字段 cron（秒 分 时 日 月 周），例如：
- `0 */5 * * * *` — 每 5 分钟
- `0 0 */1 * * *` — 每小时整点
- `0 30 9 * * MON-FRI` — 工作日 9:30

## 11. 告警收敛配置（v0.5）

告警收敛将重复的同类异常事件聚合为单条告警记录，减少噪声。

```yaml
snap-agent:
  alert:
    enabled: false            # 告警子系统总开关
    buffer-size: 1000         # 内存中保留的最大告警数
    auto-resolve-minutes: 30  # 无更新的告警自动 resolve 的分钟数
```

当 `enabled=true` 时，自动装配 `InMemoryAlertConverger`，使用 SHA-256 指纹对同类型+同来源的异常事件去重，按计数递增。

### 告警 API

| 端点 | 说明 |
|------|------|
| `GET /alerts?page=&size=&type=&status=` | 分页查询告警（可选按 type/status 过滤） |
| `POST /alerts/{id}/resolve` | 手动 resolve 告警 |

### 异常事件桥接

宿主可通过 `AnomalyEventListener` SPI 将外部消息队列（Kafka/RabbitMQ）的异常事件接入：

```java
@Component
public class KafkaAnomalyBridge {
    @Autowired
    private AnomalyEventListener listener;

    @KafkaListener(topics = "anomaly-events")
    public void onMessage(ConsumerRecord<String, String> record) {
        AnomalyEvent event = parse(record.value());
        listener.onEvent(event);
    }
}
```

事件进入后，`DefaultAnomalyEventListener` 会自动触发对应的诊断 Skill，并将结果写入巡检报告和告警收敛。

## 12. 嵌入式业务知识库配置（v0.7）

```yaml
snap-agent:
  knowledge:
    enabled: true                    # 知识库总开关，默认 false（零 bean）
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/  # JAR 内置知识
      - type: markdown
        dir: /data/snap-agent/knowledge/ # 文件系统知识（可热更新）
    max-fragments: 3                 # 每次查询注入的最大片段数
    min-score: 0.1                   # 最小相关度 [0.0, 1.0]
```

### 知识加载与分段

`MarkdownKnowledgeSource` 从指定目录递归扫描 `.md` 文件，按 `## ` 标题自动分段：
- 文件级 `# ` 标题 → 存为每个片段的 `category` 元数据
- `## ` 之前的内容 → 一个 "概述" 片段
- 每个 `## ` 标题 → 一个独立知识片段（标题=片段标题，正文=片段内容）
- 无 `## ` 的文件 → 整个文件作为一个片段

### 检索算法

`SimpleKeywordSearcher` 基于词频重叠评分：
- 英文：按空格/标点分词，转小写，过滤 <2 字符的词
- 中文：2 字符 bigram 分词（"补货策略" → ["补货","货策","策略"]）
- 评分 = `(标题命中数 × 2 + 正文命中数) / (查询词数 × 2)`，截断到 [0.0, 1.0]
- 标题命中权重 ×2，确保标题直接相关的片段排名更高

### 知识注入

`KnowledgeInjector` 实现 `SystemPromptExtender`，与 v0.3 的 `ProjectContextExtender` 并行生效：
- 运行时从 `AgentTask` 的输入值构建查询
- 调用 `KnowledgeBase.search(query, maxFragments, minScore)` 检索相关片段
- 将匹配片段格式化为 Markdown 注入 system prompt
- 无匹配时返回空字符串（不影响 LLM）

### 自定义知识源

宿主可实现 `KnowledgeSource` SPI 接入 Confluence / 语雀 / 自定义 API：

```java
@Component
public class ConfluenceKnowledgeSource implements KnowledgeSource {
    @Override
    public List<KnowledgeFragment> load() {
        // 从 Confluence API 加载文档，分割为片段
        return fetchAndSplit();
    }
    @Override
    public void reload() { /* 重新加载 */ }
    @Override
    public String type() { return "confluence"; }
}
```

自定义 `KnowledgeSource` bean 会被 `KnowledgeBase` 自动收集。
