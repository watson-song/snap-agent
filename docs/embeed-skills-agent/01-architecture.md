# 01 — 架构

## 1. 模块拆分

```
snap-agent-core/                       com.watsontech.snapagent.core
  ├─ skill/        SkillRegistry, SkillMeta, frontmatter 解析
  ├─ agent/        AgentExecutor, TaskStore, AgentTask, 限流
  ├─ llm/          LlmClient (OkHttp 流式), LlmRequest/Response
  ├─ tool/         ToolProvider SPI, ToolDispatcher, ToolResult
  └─ security/     SecurityGateway SPI (接口定义, 实现在 starter)

snap-agent-spring-boot-2x-starter/     com.watsontech.snapagent.boot2x
  ├─ autoconfig/   SnapAgentAutoConfiguration (条件装配)
  ├─ web/          SnapAgentController (REST+SSE), SnapAgentFilter (javax.servlet)
  ├─ security/     SpringSecurityAdapter, ShiroAdapter, DefaultPrincipalResolver
  ├─ tool/         JdbcQueryToolProvider, RedisReadToolProvider (bean 装配)
  ├─ llm/          AnthropicLlmClient (LlmClient 实现)
  └─ resources/
       ├─ static/snap-agent/   index.html / app.js / style.css (单页, 无构建)
       └─ META-INF/spring.factories   注册 AutoConfiguration
```

### 为什么拆两个 artifact

`javax.servlet`（SB 2.x）与 `jakarta.servlet`（SB 3.x）二进制不兼容。单 artifact 不能同时服务两类宿主。因此：

- **core**：纯逻辑，servlet 无关，**不直接依赖** `javax` 或 `jakarta`。两个 starter 都依赖它。
- **2x-starter**：`javax.servlet` Filter + `spring.factories`，本期交付。
- **3x-starter**：`jakarta.servlet` 版本，Phase 3 交付。

core 暴露的 `SnapAgentController` 所需的 web 抽象由 starter 注入；controller 实现放 starter（因为要用 `javax`/`jakarta` 注解），core 只提供 agent/llm/tool 的纯逻辑 bean。

> 注：controller 的 REST/SSE 逻辑与 servlet API 耦合较深，因此放在 starter。core 不含 HTTP 入口。这也意味着 3x-starter 需重写一份 controller（jakarta 版本）——可接受，因为 Phase 3 才做。

## 2. 组件清单

| 组件 | 所属 | 职责 |
|------|------|------|
| **SkillRegistry** | core | 两层 skill 模型：① builtin skills 由 `ClasspathSkillScanner` 从 classpath（`classpath:/docs/skills/`）扫描；② upload skills 从文件系统 `upload-skills-dir` 扫描；同名时 custom 覆盖 builtin。支持目录型 skill（以 `SKILL.md` 为入口文件）。解析 frontmatter，交叉校验 `tools` 契约，缓存 `SkillMeta`；`POST /skills/refresh` 重扫 upload 目录并合并 builtin |
| **SkillMeta** | core | 一份 skill 的内存表示：name/description/inputs/tools/body/availability |
| **AgentExecutor** | core | LLM 流式循环：构造 system prompt（只读前缀 + skill 正文 + 工具清单）→ 调 LLM → 解析 `tool_use` → 交 ToolDispatcher → 回填 → 直到 `end_turn` 或 `max-turns` |
| **TaskStore** | core | 内存 + 可选 Redis 持久的 `AgentTask` 仓库（status + transcript + 审计） |
| **AgentTask** | core | 一次 run 的运行时态：id/userId/skillId/inputs/model/status/transcript/审计/created/updated |
| **ToolDispatcher** | core | 按 `tool_use.name` 路由到对应 `ToolProvider`，收集结果，截断到 `max-tool-result-chars` |
| **ToolProvider** (SPI) | core | 接口：`name()` / `schema()` / `execute(args, ctx)` |
| **LlmClient** (SPI) | core | 接口：流式调用 Messages API，回推 token/thought/tool_use 事件 |
| **AnthropicLlmClient** | 2x-starter | `LlmClient` 实现，OkHttp SSE 解析 |
| **JdbcQueryToolProvider** | 2x-starter | 只读 DSN + SQL guard + LIMIT 注入 + 行数截断 + 审计 |
| **RedisReadToolProvider** | 2x-starter | get/keys/exists，`KEYS *` 拒绝，前缀 pattern + max-key-count |
| **McpToolProvider** | (Phase 2) | SSE/HTTP MCP 工具桥接 |
| **SnapAgentController** | 2x-starter | REST + SSE 入口，`/snap-agent/**` |
| **SnapAgentFilter** | 2x-starter | `javax.servlet` Filter，解析 principal 注入 AgentRequest 上下文 |
| **SecurityGateway** (SPI) | core 接口 / starter 实现 | `currentUserId()` / `hasPermission(code)` |
| **SpringSecurityAdapter / ShiroAdapter** | 2x-starter | SecurityGateway 两实现，`@ConditionalOnClass` 自动选 |
| **PrincipalResolver** (SPI) | core 接口 / starter 默认实现 | principal → userId 转换 |
| **SnapAgentAutoConfiguration** | 2x-starter | 所有 bean 的条件装配入口 |

## 3. 依赖关系

### core 依赖（无 servlet）
- Spring Boot autoconfig（`spring-boot-autoconfigure`，provided/optional）
- snakeyaml（frontmatter 解析；版本交由宿主 Spring Boot BOM 管控）
- OkHttp（LLM 流式；版本交由 Spring Boot BOM 管控，可选 shade/relocate）
- Jackson（JSON；Spring Boot 自带）
- SLF4J（日志；Spring Boot 自带）

> core **不依赖** your-app-core / biz / sys 任何 jar，也不依赖 `javax.servlet` / `jakarta.servlet`。

### 2x-starter 依赖
- `snap-agent-core`
- `spring-web`（provided，宿主必有）
- `javax.servlet:javax.servlet-api`（provided，宿主必有）
- `spring-boot-starter-security`（optional，`@ConditionalOnClass`）
- `shiro-spring`（optional，`@ConditionalOnClass`）
- `spring-boot-starter-data-redis`（optional，`@ConditionalOnClass`，Redis 工具用）
- `spring-jdbc` / DataSource（optional，JDBC 工具用，宿主提供 DataSource）

### 依赖治理（决策 #17）
- **OkHttp 与 snakeyaml 不自带版本**：`<version>` 交给 Spring Boot BOM 管控，避免与宿主冲突。
- 冲突兜底：OkHttp 可选 shade/relocate（`com.watsontech.snapagent.okhttp.`），仅在宿主版本不兼容时启用。
- 所有 optional 依赖用 `optional=true` + `@ConditionalOnClass`，宿主没有该依赖时静默不装配。

## 4. AutoConfig 默认关闭零影响证明

### 条件链

```java
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
@ConditionalOnClass(SnapAgentAutoConfiguration.class)  // marker, 永真
@EnableConfigurationProperties(SnapAgentProperties.class)
public class SnapAgentAutoConfiguration {
    // 所有内部 @Bean 都在此类内部, 受外层 ConditionalOnProperty 约束
}
```

### `enabled=false`（默认）时发生什么

| 项 | 默认值时 | 证据 |
|----|---------|------|
| AutoConfiguration 装配 | ❌ 不装配 | `@ConditionalOnProperty(enabled=true)` 不满足 |
| SkillRegistry | ❌ | 在 AutoConfig 内部 |
| AgentExecutor / TaskStore | ❌ | 同上 |
| LlmClient | ❌ | 同上 |
| ToolProvider beans | ❌ | 同上 |
| SnapAgentController | ❌ | 同上 |
| **SnapAgentFilter** | ❌ | `FilterRegistrationBean` 在 AutoConfig 内部；不注册则容器无此 Filter |
| **专用线程池** | ❌ | `ThreadPoolTaskExecutor` bean 在 AutoConfig 内部；不创建则无线程 |
| SecurityGateway / Adapter | ❌ | 同上 |
| 静态 UI 资源 | 静态文件仍可能在 classpath，但无 controller 路由 → 无入口 | 资源不主动注册路由 |

### 结论
`enabled=false`（默认）时：**无 bean 装配、无 Filter 注册、无线程池创建、无 HTTP 路由**。宿主启动 / 运行 / 关闭全程无感知。满足用户约束 #1「默认关闭不影响宿主」。

### 启用条件（必须同时满足）
1. `snap-agent.enabled=true`
2. classpath 有 `snap-agent-spring-boot-2x-starter`（引入 pom）
3. LLM `api-key` 非空（否则 `LlmClient` bean 装配时 `@ConditionalOnProperty(api-key)` 失败，starter 启动失败-fast：日志 ERROR 提示缺 key）
4. JDBC/Redis 工具按 `jdbc.enabled` / `redis.enabled` 独立开关，缺对应 bean 则该 tool provider 静默不装配（不崩）。

## 5. 可选 bean 注入策略（决策 #2）

宿主已存在的 bean（DataSource / RedisTemplate）按 **bean 名定向**注入，避免与宿主业务 bean 冲突：

```java
@Bean
@ConditionalOnProperty(prefix = "snap-agent.jdbc", name = "enabled", havingValue = "true")
public JdbcQueryToolProvider jdbcQueryToolProvider(
        ObjectProvider<DataSource> dataSourceProvider,    // 按名延迟解析
        ConfigurableListableBeanFactory beanFactory,
        SnapAgentProperties props) {
    String beanName = props.getJdbc().getDatasourceBeanName(); // snapAgentReadOnlyDataSource
    DataSource ds = (DataSource) beanFactory.getBean(beanName); // 按名取
    if (ds == null) {
        log.warn("DataSource bean '{}' not found; JdbcQueryToolProvider not assembled", beanName);
        return null;  // 静默不装配
    }
    return new JdbcQueryToolProvider(ds, props.getAgent().getMaxResultRows(), ...);
}
```

- 用 `ObjectProvider` / `beanFactory.getBean(name)` 按 yml 配的 bean 名取，**不按类型**（避免多个 DataSource 时歧义）。
- 无对应 bean → 返回 null / 不注册 → 该 tool provider 缺失 → 依赖它的 skill 标 `UNAVAILABLE`（见 [02-skill-loading.md](02-skill-loading.md) §契约校验），不抛异常。

## 6. 与宿主的边界

| 关注点 | 本库 | 宿主 |
|--------|------|------|
| 认证 | ❌ 不做 | 宿主安全框架负责 |
| 鉴权（已登录？有 permission？） | ✅ 读 principal + `hasPermission` | 委托宿主，本库只判断 |
| 数据访问 | ✅ 独立只读 DSN（不走宿主业务 DataSource/MyBatis） | 宿主提供只读 DB 账号 |
| 租户隔离 | ⚠ raw JDBC 绕过 MyBatis Plus 租户拦截器；靠 skill 自带 `tenant_id` 占位 + 审计 | 多租户宿主须评估 |
| 事务 | ❌ 不参与 | 只读，无事务 |
| 业务逻辑 | ❌ 不调宿主 service | 宿主业务零耦合 |

## 7. 风险（架构层）

- **线程池饥饿**：agent 长任务（多轮 LLM + 多次 SQL）若跑在 servlet 请求线程会占满容器线程池。解：专用有界线程池（core=2/max=4/queue=10），满则 429。见 [03-agent-engine.md](03-agent-engine.md)。
- **starter 多 SB 版本兼容**：本期锁定 2.x（javax）；3.x 单独 starter。宿主升级 SB 大版本须换 starter。
- **依赖冲突**：见决策 #17 与上文依赖治理。
