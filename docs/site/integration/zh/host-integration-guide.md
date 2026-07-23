# SnapAgent 宿主集成指南

> 版本：v1.0 | 更新日期：2026-07-17

## 1. 集成概述

SnapAgent 是一个嵌入式 LLM 诊断 Agent 库，让 Spring Boot 2.x 应用在内嵌场景下获得只读诊断能力（数据库查询、日志分析、指标巡检、代码理解等）。它以两个 Maven 模块的形式发布：

- **`snap-agent-core`** — 纯逻辑层（servlet 无关）：Skill 解析、Agent 执行循环、LLM SPI、Tool SPI。
- **`snap-agent-spring-boot-2x-starter`** — Spring Boot 2.x 自动装配层：AutoConfiguration、Controller、Filter、Security 适配器、内置 Tool 实现、Anthropic/OpenAI LLM 客户端。

宿主应用只需引入 Starter 依赖并开启 `snap-agent.enabled=true`，即可在 `{base-path}/**` 路径下获得完整的 Agent Web UI 与 REST API。宿主负责提供业务数据源（DataSource / RedisTemplate）、安全框架（Spring Security / Shiro）以及 LLM Provider 凭证；SnapAgent 负责技能解析、工具调度、流式执行与权限校验。

```
┌──────────────────────────────────────────────────────────────┐
│                     宿主应用 (Host App)                       │
│  ┌────────────┐  ┌─────────────┐  ┌────────────────────┐    │
│  │ 业务逻辑    │  │ DataSource  │  │ RedisTemplate      │    │
│  │ Controller  │  │ (只读)       │  │ / 日志目录 / 代码    │    │
│  └────────────┘  └──────┬──────┘  └─────────┬──────────┘    │
│                         │                   │                │
│  ┌──────────────────────▼───────────────────▼──────────┐     │
│  │        SnapAgent Starter (自动发现, @Component)       │     │
│  │  ┌──────────────┐ ┌────────────┐ ┌───────────────┐  │     │
│  │  │ ToolProvider │ │ SkillLoader│ │ SecurityGateway│  │     │
│  │  │ (Jdbc/Redis/ │ │ (内置+上传) │ │ (Spring/Shiro) │  │     │
│  │  │  Code/Ops…)  │ └────────────┘ └───────────────┘  │     │
│  │  └──────────────┘                                   │     │
│  │         AgentExecutor + SSE Controller              │     │
│  └──────────────────────┬──────────────────────────────┘     │
│                         │ /v1/messages (SSE)                    │
└─────────────────────────┼────────────────────────────────────┘
                          ▼
              ┌───────────────────────┐
              │   LLM Provider         │
              │  (Anthropic / 通义 /    │
              │   文心 / 智谱 / 自建)    │
              └───────────────────────┘
```

### 设计原则

- **零侵入**：`snap-agent.enabled=false`（默认）时，Starter 不创建任何 Bean —— 无 Filter、无线程池、无路由（满足 TDD_SPEC §AC15）。
- **只读诊断**：所有内置工具均为只读（SELECT 查询、GET 请求、文件读取），不会修改宿主状态。
- **认证委托**：SnapAgent 不自行实现认证，仅读取宿主已认证的 Principal 并做权限校验。
- **自动发现**：自定义工具只需实现 `ToolProvider` 接口 + `@Component` 注解，即被 `ToolDispatcher` 自动收集。

---

## 2. Maven 依赖

### 2.1 核心依赖

两个模块的 Maven 坐标（groupId 为 `cn.watsontech.snapagent`，与 Java 包名一致）：

```xml
<dependency>
    <groupId>cn.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>0.5.0</version>
</dependency>
```

> `snap-agent-core` 是 Starter 的传递依赖，无需单独声明。Starter 已把 `spring-web`、`javax.servlet-api`、`spring-security-core`、`spring-jdbc`、`spring-data-redis`、`okhttp` 等标记为 `<optional>`，宿主按需引入即可，不会污染 classpath。

### 2.2 宿主需要自行引入的可选依赖

Starter 中的内置工具按 `@ConditionalOnClass` / `@ConditionalOnBean` 条件装配，只有当对应依赖在 classpath 时才会激活：

| 内置工具 | 需要的依赖 | 备注 |
|---------|-----------|------|
| `mysql_query` (JdbcQueryToolProvider) | `spring-jdbc` + 一个 `DataSource` Bean + JDBC 驱动 | 默认 `snap-agent.jdbc.enabled=true` |
| `redis_read` (RedisReadToolProvider) | `spring-data-redis` + 一个 `RedisTemplate` Bean | 默认 `snap-agent.redis.enabled=true` |
| `log_read` (LogReadToolProvider) | 无额外依赖 | 默认 `snap-agent.logs.enabled=true` |
| `metrics_query` / `log_search` / `trace_search` | 无额外依赖（JDK HttpURLConnection） | 各自 `enabled` 默认 false，需配 `base-url` |
| LLM 流式（AnthropicLlmClient / OpenAiLlmClient） | `com.squareup.okhttp3:okhttp` | **必需**，否则 LLM 调用无法工作 |

一个典型的宿主 `pom.xml` 依赖片段（参考 `snap-agent-demo` 模块）：

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <!-- SnapAgent Starter（已传递依赖 core） -->
    <dependency>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
        <version>0.5.0</version>
    </dependency>
    <!-- OkHttp（Starter 中为 optional，宿主必须显式引入） -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
    </dependency>
    <!-- MySQL 驱动（按实际数据库选择） -->
    <dependency>
        <groupId>mysql</groupId>
        <artifactId>mysql-connector-java</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 2.3 排除 SnapAgent 内置 Skill 的最佳实践

SnapAgent 的内置技能 Markdown 打包在 Starter JAR 的 `classpath:/docs/skills/` 下。`ClasspathSkillScanner` 采用两趟扫描策略：**先扫描 SnapAgent JAR 资源（URL 含 `snap-agent-spring-boot` 或 `snap-agent-core`），再扫描宿主 classpath 资源**；同名时 SnapAgent 版本优先，宿主版本会被跳过并打印 WARN 日志。

如果宿主希望**完全移除** SnapAgent 内置技能（例如要提供自己的一整套技能集），可在 Maven 依赖中用 `<excludes>` 排除 Starter JAR 内的 `docs/skills/` 资源：

```xml
<dependency>
    <groupId>cn.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>0.5.0</version>
    <exclusions>
        <exclusion>
            <groupId>cn.watsontech.snapagent</groupId>
            <artifactId>snap-agent-core</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> 注意：排除 `snap-agent-core` 会同时移除核心 SPI，不建议这样做。更常见的做法是保留内置技能，用上传目录（`snap-agent.upload-skills-dir`）中的 custom skill 按 name 覆盖同名 builtin（custom 优先，删除 custom 后 builtin 自动恢复）。详见第 6 节。

如果宿主项目自身的 `src/main/resources/docs/skills/` 不希望被扫描，可在 Maven `<build><resources>` 中排除该目录，或把宿主技能放到上传目录而非 classpath。

---

## 3. 配置项详解

所有配置项前缀为 `snap-agent`，由 `SnapAgentProperties`（`@ConfigurationProperties(prefix = "snap-agent")`）承载。下表按功能模块分组列出全部属性、默认值与说明。

### 3.1 核心配置

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `snap-agent.enabled` | `false` | 总开关。为 false 时不创建任何 Bean |
| `snap-agent.base-path` | `/snap-agent` | Controller 路径前缀，所有端点挂载在 `{base-path}/**` |
| `snap-agent.builtin-skills-dir` | `classpath*:/docs/skills/` | 内置技能的 classpath 目录（只读，打包在 JAR）。`classpath*:` 前缀确保多 classpath root 都被扫描 |
| `snap-agent.upload-skills-dir` | `/tmp/snap-agent-skills` | 上传技能的文件系统目录（读写，重启持久化）。会话历史与 Issue 也存于此 |
| `snap-agent.app-profiles` | `""`（自动解析） | 宿主 Spring active profiles，逗号分隔。启动时从 `environment.getActiveProfiles()` 自动解析，技能可引用 `{_app_profile}` |

### 3.2 LLM 配置 (`snap-agent.llm.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `api-type` | `anthropic` | LLM API 类型：`anthropic`（默认）或 `openai`（OpenAI 兼容） |
| `base-url` | `https://api.anthropic.com` | LLM 服务根 URL |
| `api-key` | `""` | API Key（Anthropic 用 `x-api-key` 头） |
| `auth-token` | `""` | Bearer Token（与 api-key 二选一或组合使用） |
| `proxy-url` | `""` | HTTP 代理 URL（用于代理网关，如 cc-switch） |
| `model` | `claude-sonnet-4-6` | 默认模型名 |
| `allowed-models` | `[]` | 允许的模型白名单（空=不限制） |
| `max-tokens` | `8192` | 单次响应最大 token 数 |
| `timeout-seconds` | `120` | HTTP 连接 + 读取超时（共享此预算） |
| `streaming` | `true` | 是否启用 SSE 流式输出 |

> `llmClient` Bean 仅当 `api-key` 或 `auth-token` 非空时才创建（`@ConditionalOnExpression`）。两者皆空时 AgentExecutor 会 WARN 且无法工作。

### 3.3 Agent 执行配置 (`snap-agent.agent.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `max-turns` | `20` | 单次任务最大 LLM 轮次 |
| `task-timeout-minutes` | `30` | 任务超时分钟数 |
| `executor` | `snapAgentExecutor` | 异步执行器 Bean 名 |
| `max-concurrent-runs-per-user` | `1` | 每用户并发任务上限 |
| `max-runs-per-hour` | `20` | 每用户每小时任务上限 |
| `max-result-rows` | `1000` | SQL 查询返回行数上限 |
| `max-tool-result-chars` | `50000` | 单个工具结果字符数上限 |
| `transcript-event-limit` | `500` | transcript 事件数量上限 |

### 3.4 JDBC 配置 (`snap-agent.jdbc.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `true` | 是否装配 JdbcQueryToolProvider |
| `datasource-bean-name` | `snapAgentReadOnlyDataSource` | 单数据源模式下的 DataSource Bean 名 |
| `datasources` | `{}` | 多环境数据源 Map（v0.6）。key=环境名，value=`{url,username,password,driver-class-name}` |
| `default-env` | `""` | 默认环境名（空=取第一个条目） |

> 不配 `datasources` 时走单 DataSource 模式（从宿主上下文取 Bean）；配了则走 `DataSourceRegistry` 多环境模式，技能 schema 增加 `env` 参数。

### 3.5 Redis 配置 (`snap-agent.redis.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `true` | 是否装配 RedisReadToolProvider（还需 classpath 有 `RedisTemplate`） |
| `redis-template-bean-name` | `redisTemplate` | RedisTemplate Bean 名 |
| `max-key-count` | `100` | 单次返回 key 数上限 |

### 3.6 日志配置 (`snap-agent.logs.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `true` | 是否装配 LogReadToolProvider |
| `allowed-paths` | `[]` | 允许读取的日志目录列表 |
| `max-lines` | `500` | 单次返回日志行数上限 |
| `max-file-bytes` | `10485760` (10MB) | 单文件大小上限（防 OOM） |
| `app-log-file` | `""`（自动解析） | 应用自身日志路径，从 `logging.file.name` 自动解析 |

### 3.7 安全配置 (`snap-agent.security.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `framework` | `auto` | 安全框架：`auto`（按 classpath 自动选 Spring/Shiro）/ `spring` / `shiro` |
| `required-permission` | `snap-agent:access` | 访问 SnapAgent 所需权限码。**设为空字符串等价于允许匿名访问**（`hasPermission("")` 返回 true） |
| `filter-order` | `Integer.MAX_VALUE - 10` | SnapAgentFilter 在过滤链中的顺序（确保晚于宿主安全过滤链） |
| `principal-resolver-class` | `""` | 自定义 PrincipalResolver 全限定类名 |
| `audit-log` | `true` | 是否启用审计日志 |
| `auth-token-header` | `""` | 前端认证 token 的 Header 名 |
| `auth-token-cookie` | `""` | 前端认证 token 的 Cookie 名 |
| `auth-token-local-storage-key` | `""` | 前端 localStorage key（供 Web UI 读取） |

> 说明：SnapAgent 没有 `allow-anonymous` 开关。匿名访问通过将 `required-permission` 置空实现（权限码为空时 `hasPermission` 直接返回 true），但认证仍由宿主框架完成。详见第 7 节。

### 3.8 代码理解配置 (`snap-agent.code.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 代码工具总开关 |
| `project-root` | `""` | 宿主项目根目录（绝对路径）。**非空且为真实目录时才装配** CodePathGuard |
| `allowed-extensions` | `.java,.xml,.yml,.yaml,.properties,.sql,.md,.txt,.json,.csv` | 文件扩展名白名单 |
| `max-lines` | `500` | code_read 单次返回行数 |
| `max-file-bytes` | `524288` (512KB) | 单文件大小上限 |
| `structure-depth` | `3` | project_structure 默认扫描深度 |
| `context-injection` | `true` | 是否把项目结构摘要注入 system prompt |

> `CodePathGuard` 用 `@ConditionalOnExpression` 确保 `enabled=true` 且 `project-root` 非空；三个工具（`code_read`/`project_structure`/`git_log`）用 `@ConditionalOnBean(CodePathGuard.class)` 装配。

### 3.9 运营诊断配置

**指标 (`snap-agent.metrics.*`)** — Prometheus：

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | metrics_query 工具开关 |
| `base-url` | `""` | Prometheus URL，如 `http://prometheus:9090` |
| `auth-header` / `auth-header-value` | `""` | 认证头名与值（如 `Authorization` / `Bearer xxx`） |
| `timeout-seconds` | `15` | HTTP 超时 |
| `max-points` | `200` | 每条序列返回点数上限 |

**日志搜索 (`snap-agent.log-search.*`)** — Loki：结构与 metrics 相同，`max-lines` 默认 500。

**链路追踪 (`snap-agent.trace.*`)** — Jaeger：结构与 metrics 相同，`max-traces` 默认 20。

**配置读取 (`snap-agent.config-read.*`)**：

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | config_read 工具开关 |
| `nacos-base-url` | `""` | Nacos 远程配置 URL（空=仅本地 Spring Environment） |
| `nacos-namespace` | `""` | Nacos namespace ID |
| `nacos-auth-token` | `""` | Nacos 认证 token |
| `max-keys` | `100` | 本地模式返回属性数上限 |
| `sensitive-key-patterns` | `password,secret,token,credential,key` | 敏感字段脱敏模式（值替换为 `****`） |

### 3.10 知识库配置 (`snap-agent.knowledge.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 知识库开关 |
| `sources` | `[]` | 知识源列表，每项 `{type: markdown, dir: classpath:/docs/knowledge/}` |
| `max-fragments` | `3` | 每次注入 system prompt 的最大片段数 |
| `min-score` | `0.1` | 注入阈值，低于此分数的片段不注入 |

### 3.11 代码图谱配置 (`snap-agent.code-graph.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 代码图谱开关（需同时 `code.enabled=true` 共享 CodePathGuard） |
| `scan-packages` | `[]` | 扫描包前缀（空=扫描 project-root 下所有 .java） |
| `max-depth` | `5` | 调用链查询最大深度 |
| `max-impact-depth` | `3` | 影响分析查询最大深度 |

### 3.12 主动监控配置

**巡检 (`snap-agent.patrol.*`)**：

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 定时巡检开关 |
| `scheduler-pool-size` | `2` | 巡检调度线程池大小 |
| `report-buffer-size` | `500` | 巡检报告环形缓冲大小 |
| `lock-ttl-seconds` | `300` | 多 Pod 协调锁存活时间（秒，默认 5 分钟，v1.1 新增） |

**告警收敛 (`snap-agent.alert.*`)**：

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 告警收敛开关 |
| `buffer-size` | `1000` | 告警缓冲大小 |
| `auto-resolve-minutes` | `30` | 告警自动恢复分钟数 |
| `push.email.enabled` | `false` | Email 推送开关（v1.1 新增） |
| `push.email.from` | `snap-agent@local` | Email 发件人 |
| `push.email.to` | `[]` | Email 收件人列表 |
| `push.email.subject-prefix` | `[SnapAgent 告警]` | Email 主题前缀 |
| `push.webhook.enabled` | `false` | Webhook 推送开关（v1.1 新增） |
| `push.webhook.url` | _空_ | Webhook 推送 URL，非空时才装配 |
| `push.webhook.auth-header` | `Authorization` | Webhook 认证头名称 |
| `push.webhook.auth-token` | _空_ | Webhook 认证令牌 |
| `push.webhook.connect-timeout-ms` | `5000` | Webhook 连接超时 |
| `push.webhook.read-timeout-ms` | `10000` | Webhook 读超时 |

### 3.13 问题闭环配置 (`snap-agent.issue-closure.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 问题闭环开关 |
| `system-user-id` | `system` | 执行 solution-suggest/verify-fix 技能时使用的系统用户 ID |
| `storage-dir` | `""` | Issue JSON 存储目录（空=`{upload-skills-dir}/issues/`） |
| `tracker-type` | `noop` | IssueTracker 类型（`noop`/`jira`/`github`） |

### 3.14 成本核算配置 (`snap-agent.cost.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 成本核算开关 |
| `pricing.input` | `3.00` | 每 100 万输入 token 价格 |
| `pricing.output` | `15.00` | 每 100 万输出 token 价格 |
| `pricing.cache-read` | `0.30` | 每 100 万缓存读取 token 价格 |
| `pricing.currency` | `CNY` | 货币代码 |
| `budgets.per-user-daily` | `null` | 每用户日预算（null=不限） |
| `budgets.per-skill-daily` | `null` | 每技能日预算 |
| `budgets.global-daily` | `null` | 全局日预算 |
| `storage-dir` | `""` | 成本记录存储目录（空=`{upload-skills-dir}/cost/`） |
| `warn-threshold` | `0.8` | 预算使用率达到此比例时告警 |

### 3.15 工作流配置 (`snap-agent.workflows.*`)

| 属性 | 默认值 | 说明 |
|------|-------|------|
| `enabled` | `false` | 工作流引擎开关 |
| `dir` | `""` | 工作流 `.yml` 文件目录（空=`{upload-skills-dir}/workflows/`） |

### 3.16 其他配置

- **技能热重载 (`snap-agent.skill.hot-reload`)**：默认 `true`，监听上传目录文件变化自动刷新 SkillRegistry。
- **MCP (`snap-agent.mcp.*`)**：`enabled` 默认 false；`servers` 为 Map，每项 `{transport, url, auth-header, auth-header-value}`。
- **跨 Pod 路由 (`snap-agent.routing.*`)**：`mode` 默认 `none`；降级链 `k8s-api` → `headless-dns` → `static` → `none`。

### 3.17 最小可运行配置示例

```yaml
snap-agent:
  enabled: true
  llm:
    base-url: https://api.anthropic.com
    auth-token: ${ANTHROPIC_AUTH_TOKEN}   # 推荐用环境变量注入
    model: claude-sonnet-4-6
  security:
    required-permission: snap-agent:access  # 设为空字符串允许匿名
  jdbc:
    enabled: true
    datasources:
      sit:
        url: jdbc:mysql://sit-db:3306/mydb
        username: readonly
        password: ${SIT_DB_PASSWORD}
        driver-class-name: com.mysql.cj.jdbc.Driver
    default-env: sit
```

---

## 4. 启动流程

宿主应用启动时，Spring Boot 通过 `META-INF/spring.factories` 自动装配 `SnapAgentAutoConfiguration`。该类带 `@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")`，只有 `snap-agent.enabled=true` 时才会激活。激活后的装配顺序如下：

```
宿主启动
  │
  ▼
SnapAgentAutoConfiguration 激活 (snap-agent.enabled=true)
  │
  ├─ 1. 基础设施 Bean
  │     SqlGuard(maxResultRows) → TaskStore → RateLimiter
  │     → PrincipalResolver → AuditStore + SecurityAuditLogger
  │
  ├─ 2. SecurityGateway (按 classpath 二选一)
  │     @ConditionalOnClass(SecurityContextHolder) → SpringSecurityAdapter
  │     @ConditionalOnClass(ShiroUtils)            → ShiroAdapter
  │     (宿主可声明自定义 SecurityGateway Bean 覆盖, @ConditionalOnMissingBean)
  │
  ├─ 3. LlmClient (api-type 路由)
  │     api-key 或 auth-token 非空 → 创建 LlmClient:
  │       api-type=openai  → OpenAiLlmClient  (POST {base-url}/v1/chat/completions)
  │       api-type=anthropic(默认) → AnthropicLlmClient (POST {base-url}/v1/messages)
  │
  ├─ 4. 工具层 (按条件装配)
  │     DataSourceRegistry(多env) / JdbcQueryToolProvider(@ConditionalOnBean DataSource)
  │     RedisReadToolProvider(@ConditionalOnClass RedisTemplate)
  │     LogPathGuard + LogReadToolProvider
  │     CodePathGuard(@ConditionalOnExpression code.enabled + project-root 非空)
  │       → ProjectContextExtender + CodeReader/ProjectStructure/GitLog ToolProvider
  │     Metrics/LogSearch/Trace/ConfigRead ToolProvider (各自 enabled+base-url)
  │
  ├─ 5. ToolDispatcher (收集所有 ToolProvider Bean)
  │     ObjectProvider<ToolProvider>.orderedStream() → List
  │     + McpBootstrap.getProviders() (如启用)
  │     自定义 @Component ToolProvider 也会被自动收集
  │
  ├─ 6. 技能层
  │     ClasspathSkillScanner.scan(builtin-skills-dir)
  │       两趟扫描: SnapAgent JAR 资源优先 → 宿主 classpath 资源次之
  │     SkillRegistry(uploadDir, builtinSkills, toolDispatcher)
  │       合并: custom 按 name 覆盖 builtin; 删除 custom 后 builtin 自动恢复
  │     SkillHotReloader (watch upload-skills-dir, 默认开启)
  │
  ├─ 7. SystemPromptExtender 收集 (ObjectProvider.orderedStream)
  │     ProjectContextExtender (v0.3, 项目结构摘要)
  │     KnowledgeInjector (v0.7, 业务知识片段) — 如 knowledge.enabled
  │
  ├─ 8. AgentExecutor
  │     若 cost.enabled → 用 CostTrackingLlmClient 包装原始 LlmClient
  │     new AgentExecutor(llmClient, toolDispatcher, taskStore, maxTurns, maxTokens, extenders)
  │
  ├─ 9. 线程池 + 路由
  │     snapAgentExecutor (ThreadPoolTaskExecutor: core=2, max=4, queue=10)
  │     PeerRouter (mode: k8s-api/headless-dns/static/none)
  │     PeerSseRelay + InternalTaskController (内部 Pod 间端点)
  │
  └─ 10. Web 层
        SnapAgentFilter (FilterRegistrationBean, url-pattern={base-path}/*)
        SnapAgentController (挂载 {base-path}/**, 注入所有 ObjectProvider 可选依赖)
        自动解析 app-profiles + app-log-file
```

### 关键装配要点

1. **`@ConditionalOnMissingBean` 优先**：几乎所有内置 Bean 都标注了 `@ConditionalOnMissingBean`，宿主声明同名 Bean 即可替换（如自定义 `SecurityGateway`、`ConversationStore`、`LlmClient`、`PrincipalResolver`）。
2. **工具按条件激活**：未引入 `spring-jdbc` 则 `JdbcQueryToolProvider` 不会被创建（`@ConditionalOnBean(DataSource.class)`）；未引入 Redis 则 `RedisReadToolProvider` 跳过。
3. **`ClasspathSkillScanner` 双趟扫描**：先处理 SnapAgent JAR 资源（URL 含 `snap-agent-spring-boot` 或 `snap-agent-core`），再处理宿主 classpath 资源；同名时宿主版本被跳过并 WARN，防止宿主 `docs/skills/` 误覆盖内置技能。
4. **`ToolDispatcher` 收集所有 `ToolProvider` Bean**：包括内置的（Jdbc/Redis/Code/Ops）和宿主自定义的（`@Component` 实现 `ToolProvider`），统一调度。

---

## 5. 自定义工具

### 5.1 ToolProvider SPI

自定义工具只需实现 `ToolProvider` 接口（`snap-agent-core` 模块）并标注 `@Component`，即被 `ToolDispatcher` 自动发现：

```java
package cn.watsontech.snapagent.core.tool;

import java.util.Map;

public interface ToolProvider {
    /** 工具唯一名，技能 frontmatter 的 tools 字段引用此名 */
    String name();

    /** 注入 LLM 工具定义的 JSON Schema 字符串（Anthropic tool 格式） */
    String schema();

    /**
     * 执行工具调用。
     * @param args 从 LLM tool_use block 解析的参数
     * @param ctx  请求级上下文（taskId, userId, audit）
     * @return 不可变结果，永不返回 null
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
```

### 5.2 完整示例：自定义 HTTP 健康检查工具

```java
package com.example.myapp.tools;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

@Component
public class HttpHealthCheckToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "http_health_check";
    }

    @Override
    public String schema() {
        return "{\"name\":\"http_health_check\","
            + "\"description\":\"Check HTTP endpoint health (read-only GET).\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{\"url\":{\"type\":\"string\","
            + "\"description\":\"Absolute HTTP(S) URL to check\"}},"
            + "\"required\":[\"url\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String url = args.get("url") != null ? args.get("url").toString() : null;
        if (url == null || url.isEmpty()) {
            return ToolResult.error("missing required parameter: url", 0);
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            String content = "HTTP " + code + " from " + url;
            return ToolResult.success(content, 1, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error("request failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }
}
```

无需任何额外注册 —— `@Component` 让 Spring 扫描到它，`ToolDispatcher` 通过 `ObjectProvider<ToolProvider>.orderedStream()` 自动收集。在技能 frontmatter 中引用 `tools: [http_health_check]` 即可。

### 5.3 上下文与结果

**`ToolContext`**（不可变，请求级）：
```java
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;  // 可能为 null
}
```

**`ToolResult`**（不可变，工厂方法构造）：
```java
ToolResult.success(content, rowCount, durationMs);   // 成功
ToolResult.truncated(content, rowCount, durationMs); // 成功但被截断
ToolResult.error(message, durationMs);               // 失败（content 为 null）
```
字段包括 `content`、`rowCount`、`truncated`、`durationMs`、`error`。

**`AuditCallback`**：工具执行后由 `ToolDispatcher` 回调，用于审计记录：
```java
void onToolExecuted(String toolName, Map<String, Object> args, ToolResult result);
```

### 5.4 JSON Schema 约定

`schema()` 返回的是 Anthropic tool-use 格式的 JSON 字符串（不是 JSON 对象，是 String）。关键字段：

- `name`：与 `name()` 一致
- `description`：LLM 据此判断何时调用此工具
- `input_schema`：JSON Schema，`type: object` + `properties` + `required`

参考内置 `JdbcQueryToolProvider` 的 schema 写法（多环境模式额外暴露 `env` 参数）：

```json
{
  "name": "mysql_query",
  "description": "Execute a read-only SQL query.",
  "input_schema": {
    "type": "object",
    "properties": {
      "sql": {"type": "string", "description": "SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query"},
      "env": {"type": "string", "description": "Environment name (e.g. sit/uat). Empty=use default."}
    },
    "required": ["sql"]
  }
}
```

---

## 6. 自定义 Skill

### 6.1 Skill Markdown 格式

技能是一个 Markdown 文件，由 YAML frontmatter + 正文组成。`SkillLoader` 用 SnakeYAML 的 `SafeConstructor` 解析 frontmatter（防止反序列化攻击）。

**frontmatter 必填字段**：`name`、`description`。
**frontmatter 可选字段**：`tools`（工具名列表）、`inputs`（输入参数）、`shortcuts`（快捷按钮）、`required-permission`（技能级权限码）。

```markdown
---
name: my-diagnose
description: "自定义诊断技能：根据症状定位问题根因。当用户说'排查XXX问题'时触发。"
tools: [mysql_query, log_read, http_health_check]
inputs:
  - key: symptom
    label: "症状描述"
    required: true
    type: string
    default: ""
  - key: env
    label: "环境"
    required: false
    type: enum
    options: [sit, uat, prod]
    default: sit
shortcuts:
  - label: "🔍 慢查询"
    message: "排查最近1小时的慢查询"
  - label: "⚠️ 错误日志"
    message: "查看最近1小时的ERROR日志"
required-permission: ops:diagnose
---

# 自定义诊断

## Step 1: 理解症状
读取用户输入的 `{symptom}`，明确：
- 受影响的实体（订单/用户/商品）
- 时间范围
- 异常表现

## Step 2: 查日志
调用 `log_read` 读取应用日志，过滤 ERROR 级别...
```

### 6.2 frontmatter 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `name` | 是 | string | 技能唯一标识，与文件名无关 |
| `description` | 是 | string | 描述（含触发场景，供 LLM/前端展示） |
| `tools` | 否 | list&lt;string&gt; | 依赖的工具名列表；空表示纯 LLM 技能 |
| `inputs` | 否 | list | 输入参数定义 |
| `inputs[].key` | 是 | string | 参数键（模板中用 `{key}` 引用） |
| `inputs[].label` | 否 | string | 前端展示标签（默认=key） |
| `inputs[].required` | 否 | boolean | 是否必填（默认 false） |
| `inputs[].type` | 否 | string | `string`/`enum`/`date`/`number`/`boolean`（默认 string） |
| `inputs[].options` | 否 | list&lt;string&gt; | enum 类型的选项 |
| `inputs[].default` | 否 | string | 默认值 |
| `shortcuts` | 否 | list | 前端快捷按钮 |
| `shortcuts[].label` | 是 | string | 按钮文案 |
| `shortcuts[].message` | 是 | string | 点击后发送的消息 |
| `required-permission` | 否 | string | 技能级权限码（空=继承全局 `security.required-permission`） |

### 6.3 技能加载方式

SnapAgent 采用两层 Skill 系统：

- **内置技能（builtin）**：从 `snap-agent.builtin-skills-dir`（默认 `classpath*:/docs/skills/`）加载，只读，打包在 JAR 中。`source="builtin"`。
- **上传技能（custom）**：从 `snap-agent.upload-skills-dir`（默认 `/tmp/snap-agent-skills`）加载，读写，重启持久化。`source="custom"`。

**合并逻辑**：custom 按 `name` 覆盖同名 builtin（`overridesBuiltin=true`）；删除 custom 后 builtin 自动恢复。

**目录技能规则**：
- 子目录含 `SKILL.md` → 整个目录是一个技能，仅解析 `SKILL.md`，其他文件（`.md`/非`.md`）为附属文件被跳过。
- 子目录无 `SKILL.md` → 组织目录，递归处理其下的文件/子目录。

### 6.4 打包 vs 运行时上传

**方式一：随宿主 JAR 打包**（推荐用于稳定的内置业务技能）

把 `.md` 文件放在宿主 `src/main/resources/docs/skills/` 下，构建后进入 classpath，被 `ClasspathSkillScanner` 扫描为 `source="host"`（注意：同名的 SnapAgent JAR 内置版本优先，宿主版本会被跳过 + WARN）。

```text
my-app/
└─ src/main/resources/docs/skills/
   ├─ order-diagnose.md          # 独立技能
   └─ inventory-check/
      ├─ SKILL.md                 # 目录技能（只解析此文件）
      └─ reference.md            # 附属文件（被跳过）
```

**方式二：运行时上传**（用于频繁迭代的技能）

通过 REST API 上传到 `upload-skills-dir`：

```bash
# 上传单个技能文件
curl -X POST http://localhost:8080/snap-agent/skills/upload \
  -F "file=@order-diagnose.md"

# 上传目录技能（多个文件 + dirName）
curl -X POST http://localhost:8080/snap-agent/skills/upload-folder \
  -F "dirName=inventory-check" \
  -F "files=@SKILL.md" \
  -F "files=@reference.md"
```

上传后 `SkillHotReloader`（默认开启）监听目录变化，自动触发 `SkillRegistry.refresh()`，无需重启。

**方式三：直接放文件**

直接把 `.md` 文件拷贝到 `upload-skills-dir` 目录，热重载会自动发现。

### 6.5 完整示例：参考内置 health-check 技能

```markdown
---
name: health-check
description: "Performs a basic health check of the application — verifies database connectivity and key configuration. Use when the user asks 'is the system healthy?' or 'check health'."
tools: [mysql_query]
---

# Health Check

## Step 1: Verify Database Connectivity
Use the `mysql_query` tool to run a simple query:
```sql
SELECT 1 AS ok
```
If this fails, report that the database is unreachable.

## Step 2: Check Table Counts
Check if key tables exist and have data:
```sql
SELECT TABLE_NAME, TABLE_ROWS
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME
LIMIT 20
```

## Step 3: Report Summary
Summarize the health status:
- Database connectivity: OK / FAILED
- Table count summary
- Any anomalies detected
```

---

## 7. 安全集成

### 7.1 SecurityGateway SPI

SnapAgent 不自行实现认证，仅通过 `SecurityGateway` SPI 读取宿主已认证的 Principal 并校验权限：

```java
public interface SecurityGateway {
    /** 返回当前已认证用户 ID，未认证返回 null */
    String currentUserId();
    /** 校验权限码；空码返回 true（等价于不校验） */
    boolean hasPermission(String code);
}
```

默认实现按 classpath 自动选择（`@ConditionalOnClass` + `@ConditionalOnMissingBean`）：

- **`SpringSecurityAdapter`**：当 `SecurityContextHolder` 在 classpath 时装配。`currentUserId()` 读 `SecurityContextHolder` 的 Authentication principal，经 `PrincipalResolver` 解析；`hasPermission()` 遍历 `Authentication.getAuthorities()` 做**精确匹配**（非通配）。
- **`ShiroAdapter`**：当 `org.apache.shiro.SecurityUtils` 在 classpath 时装配（fallback）。

### 7.2 常见坑：企业项目权限不在 GrantedAuthority

**问题**：`SpringSecurityAdapter.hasPermission()` 遍历 `Authentication.getAuthorities()` 做精确匹配。许多企业项目（如 scpdrp-saas）把权限存在 Principal 的自定义字段（如 `LoginUser.permissionList`）而非 `GrantedAuthority` → `hasPermission()` 返回 false → `authorized: false` → 所有请求 403。

**解决方案**：宿主声明自定义 `SecurityGateway` Bean（继承 `SpringSecurityAdapter`，覆盖 `hasPermission`）。由于 `@ConditionalOnMissingBean`，宿主 Bean 优先：

```java
package com.example.myapp.security;

import cn.watsontech.snapagent.boot2x.security.SpringSecurityAdapter;
import cn.watsontech.snapagent.core.security.PrincipalResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CustomSecurityGateway extends SpringSecurityAdapter {

    public CustomSecurityGateway(PrincipalResolver principalResolver) {
        super(principalResolver);
    }

    @Override
    public boolean hasPermission(String code) {
        if (code == null || code.isEmpty()) {
            return true;  // 空权限码 = 不校验
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser)) {
            return false;
        }
        LoginUser user = (LoginUser) auth.getPrincipal();
        List<String> permissions = user.getPermissionList();  // 从自定义字段读权限
        return permissions != null && permissions.contains(code);
    }
}
```

### 7.3 权限校验流程

Controller 的 `requireAuth()` 方法对所有非 SSE 端点统一校验：

1. `securityGateway.currentUserId()` 为 null → 401 UNAUTHORIZED。
2. `securityGateway.hasPermission(required-permission)` 为 false → 403 FORBIDDEN。
3. 通过则继续。

**匿名访问**：SnapAgent 无 `allow-anonymous` 开关。将 `snap-agent.security.required-permission` 设为空字符串即可（`hasPermission("")` 返回 true），但认证仍由宿主框架完成。

**技能级权限**：在 frontmatter 设 `required-permission: ops:diagnose`，Controller 在 POST `/runs` 时校验该技能权限；为空时继承全局 `security.required-permission`。

### 7.4 SSE 流式端点的鉴权

浏览器 `EventSource` API 不支持自定义 Header，因此 SSE 端点 `GET /runs/{id}/stream` 的鉴权方式特殊：

- 该端点在宿主安全过滤链中设为 `permitAll`（不强制认证）。
- 前端通过 `?token=base64(user:pass)` query param 传递凭证。
- Controller 解码 token，提取 `user` 部分，跳过 ownership check。
- 未带 token 时回退到 `SecurityGateway.currentUserId()`。

```javascript
// 前端示例
const token = btoa(`${username}:${password}`);
const es = new EventSource(`/snap-agent/runs/${taskId}/stream?token=${token}`);
```

### 7.5 Filter 与路径

`SnapAgentFilter` 注册为 `FilterRegistrationBean`，URL pattern 为 `{base-path}/*`，order 为 `Integer.MAX_VALUE - 10`（确保晚于宿主安全过滤链）。它只负责把已认证 principal 注入 `AgentRequestContext`，不做认证拦截 —— 认证由宿主安全框架完成。

---

## 8. 多 LLM 对接

SnapAgent 通过 `snap-agent.llm.api-type` 切换 LLM 客户端实现。`SnapAgentAutoConfiguration.llmClient()` Bean 方法根据 `api-type` 路由：

```java
if ("openai".equalsIgnoreCase(apiType)) {
    return new OpenAiLlmClient(baseUrl, apiKey, authToken, proxyUrl, timeoutSeconds);
}
return new AnthropicLlmClient(baseUrl, apiKey, authToken, proxyUrl, timeoutSeconds);
```

### 8.1 Anthropic（默认）

Anthropic 客户端 POST 到 `{base-url}/v1/messages`，使用 Anthropic 流式 SSE 协议（`message_start`/`content_block_delta`/`message_delta` 等事件），`x-api-key` 头认证。

```yaml
snap-agent:
  llm:
    api-type: anthropic          # 或省略（默认）
    base-url: https://api.anthropic.com
    api-key: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-6
    max-tokens: 8192
    timeout-seconds: 120
```

### 8.2 OpenAI 兼容（通义/文心/智谱/vLLM/Ollama）

OpenAI 客户端 POST 到 `{base-url}/v1/chat/completions`，使用 OpenAI 流式 chunk 协议，`Authorization: Bearer {token}` 认证。兼容 OpenAI、Azure OpenAI 及所有 OpenAI 兼容网关。

**通义千问（阿里 DashScope）**：
```yaml
snap-agent:
  llm:
    api-type: openai
    base-url: https://dashscope.aliyuncs.com/compatible-mode
    auth-token: ${DASHSCOPE_API_KEY}
    model: qwen-max
```

**智谱 GLM**：
```yaml
snap-agent:
  llm:
    api-type: openai
    base-url: https://open.bigmodel.cn/api/paas/v4
    auth-token: ${ZHIPU_API_KEY}
    model: glm-4
```

**自建 OpenAI 兼容网关（如 vLLM/Ollama）**：
```yaml
snap-agent:
  llm:
    api-type: openai
    base-url: http://llm-gateway.internal:8000
    auth-token: ${INTERNAL_LLM_TOKEN}
    model: qwen2.5-72b-instruct
```

### 8.3 代理网关

如需通过 HTTP 代理访问 LLM（如 cc-switch 等网关），设 `proxy-url`：

```yaml
snap-agent:
  llm:
    proxy-url: http://proxy.internal:8080
```

`AnthropicLlmClient` / `OpenAiLlmClient` 均支持 `proxyUrl` 构造参数，用 OkHttp 的 `Proxy(Type.HTTP, ...)` 配置。

### 8.4 完全自定义 LlmClient

如需对接非 Anthropic/OpenAI 协议的 LLM（如原生文心/通义非兼容模式），实现 `LlmClient` SPI 并声明为 Spring Bean，`@ConditionalOnMissingBean` 会让自定义实现优先：

```java
@Component
public class MyCustomLlmClient implements LlmClient {
    // 实现 stream(LlmRequest, LlmEventSink) + cancel(taskId) 等
}
```

---

## 9. 部署清单

上线前逐项确认：

- [ ] **Maven 依赖**：已添加 `snap-agent-spring-boot-2x-starter` 依赖，且 `okhttp` 已显式引入（Starter 中为 optional）
- [ ] **`application.yml` 配置**：`snap-agent.enabled=true`；LLM `base-url`/`api-key` 或 `auth-token` 已配置（建议用 `${ENV}` 环境变量注入）
- [ ] **技能已就绪**：内置技能满足需求，或 custom 技能已上传/打包到 `docs/skills/`
- [ ] **SecurityGateway 配置**：如企业项目权限不在 `GrantedAuthority`，已声明自定义 `SecurityGateway` Bean 覆盖 `hasPermission`
- [ ] **权限码**：`snap-agent.security.required-permission` 与宿主权限体系一致；需匿名访问时置空
- [ ] **知识库目录**：如使用业务知识注入，已配 `snap-agent.knowledge.sources[].dir` 指向 Markdown 目录
- [ ] **代码根目录**：如使用代码工具，`snap-agent.code.project-root` 指向宿主项目根（绝对路径），`enabled=true`
- [ ] **可观测端点**：如使用运营诊断，已配 `metrics.base-url` / `log-search.base-url` / `trace.base-url` 及认证头
- [ ] **成本预算**：如使用成本核算，已设 `snap-agent.cost.budgets.*` 与 `pricing.*`
- [ ] **主动监控**：如使用巡检/告警，已配 `snap-agent.patrol.*` / `snap-agent.alert.*`
- [ ] **告警推送**：如使用异常报告推送，已配 `snap-agent.alert.push.email.*` 或 `snap-agent.alert.push.webhook.*`（v1.1 新增）
- [ ] **多 Pod 协调锁**：多 Pod 部署时已实现自定义 `PatrolLockProvider` Bean（Redis / k8s lease / DB 行锁），见 §10（v1.1 新增）
- [ ] **持久化存储**：如需持久化巡检报告/会话历史/Issue，已实现自定义 `PatrolReportStore` 等 SPI Bean（v1.1 新增）
- [ ] **basePath 不冲突**：`snap-agent.base-path`（默认 `/snap-agent`）不与宿主已有路由冲突
- [ ] **JDBC 驱动**：已在宿主引入对应数据库的 JDBC 驱动（如 `mysql-connector-java`）
- [ ] **日志路径**：`snap-agent.logs.allowed-paths` 已配置允许读取的日志目录（或确认 `logging.file.name` 可被自动解析）
- [ ] **冒烟测试**：启动后访问 `GET {base-path}/skills` 能返回技能列表，用示例技能跑通一次完整对话
- [ ] **锚点问答**：如需页面区域锚点问答，已引入 `<script src="/snap-agent/anchor.js" defer>` 并在页面区域标注 `data-snap-anchor`（详见[锚点问答接入指南](anchor-feature-guide.md)）

---

## 10. 可替换 SPI 清单（v1.1 更新）

SnapAgent 的所有核心组件均以 SPI 接口形式暴露，宿主可按需替换。所有自定义实现
+ `@Component`（或 `@Bean`）即可让 `@ConditionalOnMissingBean` 让位给自定义 bean。

### 10.1 SPI 总览

| SPI 接口 | 默认实现 | 装配条件 | 用途 |
|---------|---------|---------|------|
| `LlmClient` | `AnthropicLlmClient` | `snap-agent.llm.api-type=anthropic` | LLM 流式调用 |
| `ToolProvider` | 多个内置 | `@Component` 即发现 | 工具实现 |
| `SecurityGateway` | `SpringSecurityAdapter` | `@ConditionalOnMissingBean` | 权限校验 |
| `PrincipalResolver` | `SpringPrincipalResolver` | `@ConditionalOnMissingBean` | 用户身份解析 |
| `SystemPromptExtender` | `ProjectContextExtender` / `KnowledgeInjector` | `@ConditionalOnMissingBean` | system prompt 注入 |
| `ConversationStore` | `FileConversationStore` | `@ConditionalOnMissingBean` | 会话历史持久化 |
| `IssueStore` | `FileIssueStore` | `@ConditionalOnMissingBean` | Issue 持久化 |
| `IssueTracker` | `NoopIssueTracker` | `@ConditionalOnMissingBean` | 外部 Issue 跟踪 |
| `KnowledgeSource` | `MarkdownKnowledgeSource` | `@ConditionalOnMissingBean` | 知识源 |
| `KnowledgeSearcher` | `SimpleKeywordSearcher` | `@ConditionalOnMissingBean` | 知识检索算法 |
| `CostStore` | `FileCostStore` | `@ConditionalOnMissingBean` | 成本记录持久化 |
| `CostTracker` | `DefaultCostTracker` | `@ConditionalOnMissingBean` | 成本追踪 |
| `WorkflowEngine` | `SimpleWorkflowEngine` | `@ConditionalOnMissingBean` | 工作流引擎 |
| `PatrolScheduler` | `ScheduledPatrolScheduler` | `snap-agent.patrol.enabled=true` | 巡检调度 |
| `PatrolReportStore` | `InMemoryPatrolReportStore` | `snap-agent.patrol.enabled=true` | 巡检报告存储（v1.1 SPI） |
| `PatrolLockProvider` | `NoopPatrolLockProvider` | `snap-agent.patrol.enabled=true` | 多 Pod 巡检锁（v1.1 新增） |
| `AlertConverger` | `InMemoryAlertConverger` | `snap-agent.alert.enabled=true` | 告警收敛 |
| `AnomalyEventListener` | `DefaultAnomalyEventListener` | `snap-agent.patrol.enabled=true` | 异常事件处理 |
| `BugfixSuggester` | `TemplateBugfixSuggester` | `@ConditionalOnMissingBean` | 修复建议生成 |
| `AlertPushChannel` | `WebhookAlertPushChannel` + `EmailAlertPushChannel` | `snap-agent.alert.push.*` | 异常报告推送（v1.1 新增，支持多 bean 同时生效） |

### 10.2 PatrolLockProvider — 多 Pod 协调

多 Pod 部署（如 K8s 多副本）时，同一巡检任务在每个 Pod 都会触发，会导致
重复执行与重复告警。实现 `PatrolLockProvider` 即可解决：

```java
@Component
public class RedisPatrolLockProvider implements PatrolLockProvider {
    private final StringRedisTemplate redis;
    private final String podName = System.getenv().getOrDefault("MY_POD_NAME", "pod-0");

    @Override
    public boolean tryAcquire(String patrolId, long ttlSeconds) {
        String key = "patrol:lock:" + patrolId;
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(key, podName, Duration.ofSeconds(ttlSeconds)));
    }

    @Override
    public void release(String patrolId) {
        // 用 Lua 脚本验证 owner 后再删，避免误释放他人锁
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('del', KEYS[1]) else return 0 end";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList("patrol:lock:" + patrolId), podName);
    }

    @Override
    public String type() { return "redis"; }
}
```

> TTL 默认 300 秒（`snap-agent.patrol.lock-ttl-seconds`），覆盖单次巡检最长耗时；
> Pod 宕机时锁会在 TTL 后自动失效，其它 Pod 在下个 cron 周期接管。

### 10.3 AlertPushChannel — 自定义推送渠道

实现 `AlertPushChannel` + `@Component` 即可与默认的 Webhook/Email 渠道一同生效
（所有 `AlertPushChannel` bean 被收集为 `List<AlertPushChannel>`，异常报告同时推送）：

```java
@Component
public class DingTalkAlertPushChannel implements AlertPushChannel {
    @Override
    public void push(PatrolReport report, AnomalyEvent event) {
        if (report == null || !report.isAnomalyDetected()) return;
        // 构造钉钉 markdown 消息，POST 到 webhook URL
    }
    @Override
    public String type() { return "dingtalk"; }
}
```

### 10.4 PatrolReportStore — 持久化巡检报告

默认 `InMemoryPatrolReportStore` 容量有限且重启丢失，生产环境可替换为 DB 实现：

```java
@Component
public class JdbcPatrolReportStore implements PatrolReportStore {
    private final JdbcTemplate jdbc;
    // CREATE TABLE patrol_report (id VARCHAR PRIMARY KEY, patrol_id VARCHAR,
    //   task_id VARCHAR, user_id VARCHAR, skill_name VARCHAR,
    //   triggered_at BIGINT, status VARCHAR, summary TEXT,
    //   anomaly_detected BOOLEAN)
    // ...
}
```

### 10.5 邮件推送的可选依赖

`EmailAlertPushChannel` 依赖 `spring-context-support` + `javax.mail`，在 starter pom
中标记为 `<optional>true</optional>`。启用方式：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

引入后，配置 `snap-agent.alert.push.email.enabled=true` + `to[]=ops@example.com`
即可启用邮件推送。未引入时 `@ConditionalOnClass` 自动跳过装配，宿主无感知。
