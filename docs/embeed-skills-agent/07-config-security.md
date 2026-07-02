# 07 — 配置与安全

## 1. 完整配置树

```yaml
snap-agent:
  enabled: false                      # 默认关, 零影响（见 01 §4）
  base-path: /snap-agent            # controller 前缀
  skills-dir: classpath:/skills/      # 或 file:/opt/skills/
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
    datasource-bean-name: snapAgentReadOnlyDataSource   # 独立只读 DSN
  redis:
    enabled: true
    redis-template-bean-name: redisTemplate
    max-key-count: 100
  mcp:
    enabled: false                    # Phase 2
    servers: {}                       # {name: {transport: sse, url, auth-header, auth-header-value}}
  security:
    framework: auto                   # auto | spring-security | shiro
    required-permission: ""           # 空=已登录即可；填权限码则需 hasPermission
    filter-order: <Ordered.LOWEST_PRECEDENCE - 10>   # 见 §4 注
    principal-resolver-class: ""      # 空=默认实现；填全限定类名=自定义
    audit-log: true
```

### 配置字段落点矩阵（验证项 #1 文档自检）

| 配置 | 落点组件 | 文档 |
|------|---------|------|
| `enabled` | AutoConfig `@ConditionalOnProperty` | 01 §4 |
| `base-path` | SnapAgentController `@RequestMapping` | 06 |
| `skills-dir` | SkillRegistry 扫描路径 | 02 §4 |
| `llm.*` | AnthropicLlmClient / `GET /models` / POST /runs 校验 | 05 |
| `agent.max-turns` | AgentExecutor 停止条件 | 03 §4 |
| `agent.task-timeout-minutes` | AgentExecutor 超时 | 03 §4 |
| `agent.executor` | 线程池 bean 名 | 03 §7 |
| `agent.max-concurrent-runs-per-user` / `max-runs-per-hour` | 限流 | 03 §6 |
| `agent.max-result-rows` / `max-tool-result-chars` | ToolDispatcher / JdbcToolProvider | 04 §2.2 |
| `agent.transcript-event-limit` | TaskStore | 03 §5 |
| `jdbc.*` | JdbcQueryToolProvider | 04 §2 |
| `redis.*` | RedisReadToolProvider | 04 §3 |
| `mcp.*` | McpToolProvider（Phase 2） | 04 §4 |
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
- 宿主须放行 `/snap-agent/**`（见 [09](09-integration-guide.md) §4），否则请求到不了本 filter。

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
3. 反射：尝试调 `getId()` / `getUserId()` / `getUsername()` 方法（按顺序），返回第一个非 null。
4. 都失败 → 返回 null → controller 401 + 日志 WARN「无法解析 principal 类型 X，请配 principal-resolver-class」。

### 自定义
```yaml
snap-agent:
  security:
    principal-resolver-class: com.example.app.security.AppPrincipalResolver
```
宿主写 ~5 行实现 `PrincipalResolver`，把自定义 `User` 对象的 `userId` 字段取出来。这是**唯一**可能需要宿主写代码的集成点。

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
