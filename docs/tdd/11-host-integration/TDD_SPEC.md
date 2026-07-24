# TDD需求规格说明书 — 宿主项目集成 (Host Integration)

> 版本: 2.0 | 模块: 11-host-integration | 基于 TEMPLATE.md

---

## 1. 需求元信息

```yaml
需求ID: REQ-11-HOST-INTEGRATION
需求名称: Spring Boot Auto-Configuration 与宿主集成
优先级: P0
迭代: v0.1+ (持续演进)
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 以 Spring Boot Starter 方式嵌入宿主应用，需通过 auto-configuration 实现零代码侵入的自动装配，按 `snap-agent.*` 属性条件注册各模块 bean。
- **用户价值**: 宿主应用添加依赖 + 配置 `snap-agent.enabled=true` 即可用，`enabled=false` 时零 bean 零影响。
- **成功指标**: enabled=false 时容器无 SnapAgent bean；各模块 enabled 开关 100% 生效；所有 REST 端点可正常响应。

### 1.2 范围边界
- **包含**: `SnapAgentAutoConfiguration` (条件装配)、`SnapAgentProperties` (属性绑定)、`SnapAgentFilter` (请求过滤+身份注入)、`AgentRequestContext` (ThreadLocal 上下文)、`SnapAgentController` (REST 端点)、`InternalTaskController` (多 Pod 内部中继)、`PeerRouter` SPI + 实现 (Noop/Static/K8sApi/HeadlessDns)、`PeerSseRelay` (SSE 中继)、安全适配器 (SpringSecurityAdapter/ShiroAdapter/DefaultPrincipalResolver)、静态资源服务 (anchor.js/app.js/index.html)。
- **不包含**: 各模块内部逻辑 (各自有独立 TDD spec)、LLM 客户端实现 (01-agent-engine)、具体 ToolProvider 实现 (03-tool-dispatcher)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | 宿主已有同类型 bean 产生冲突 | 低 | 中 | @ConditionalOnMissingBean 保护 |
| R2 | 安全框架未检测到致 filter 不生效 | 低 | 高 | security.framework=auto 自动检测 |
| R3 | 多 Pod 路由配置错误致 SSE 中继失败 | 中 | 中 | NoopPeerRouter 默认放行 |
| R4 | ThreadLocal 未清理致身份泄漏 | 低 | 高 | SnapAgentFilter finally 块强制 clear |

---

## 2. 用户故事 (User Stories)

### US-1: 零代码自动装配
```gherkin
作为 宿主应用开发者
我希望 添加 starter 依赖并配置 snap-agent.enabled=true 即可自动装配所有核心 bean
以便 无需写任何 Java 代码即可集成 SnapAgent
```
**AC:**
```gherkin
AC1: Given snap-agent.enabled=true 且 snap-agent.llm.api-key 非空
  When Spring 容器初始化
  Then SkillRegistry, AgentExecutor, LlmClient, ToolDispatcher, TaskStore, RateLimiter 均存在
  And snapAgentExecutor 线程池 bean 存在
  And snapAgentFilter bean 存在
AC2: Given snap-agent.enabled=false (或未设置)
  When Spring 容器初始化
  Then 容器中无任何 SnapAgent bean
```

### US-2: 模块级条件开关
```gherkin
作为 系统管理员
我希望 通过 snap-agent.{module}.enabled=false 精确关闭不需要的模块
以便 只启用需要的功能，减少资源占用
```
**AC:**
```gherkin
AC3: Given snap-agent.enabled=true 且 snap-agent.jdbc.enabled=false
  When 容器初始化
  Then JdbcQueryToolProvider bean 不存在
  And 其他核心 bean 正常存在
AC4: Given snap-agent.knowledge.enabled=true
  When 容器初始化
  Then KnowledgeBase bean 存在
  And KnowledgeInjector bean 存在
```

### US-3: 属性绑定与默认值
```gherkin
作为 系统管理员
我希望 通过 application.yml 的 snap-agent.* 前缀配置所有参数
以便 无需改代码即可调整行为
```
**AC:**
```gherkin
AC5: Given 默认 SnapAgentProperties
  When 获取各属性
  Then enabled=false, basePath="/snap-agent", llm.model="claude-sonnet-4-6", agent.maxTurns=20
  And agent.maxConcurrentRunsPerUser=1, agent.maxRunsPerHour=20
AC6: Given 设置 snap-agent.llm.api-key=sk-test, snap-agent.agent.max-turns=10
  When 属性绑定
  Then llm.apiKey=="sk-test", agent.maxTurns==10
```

### US-4: 请求身份注入
```gherkin
作为 安全开发者
我希望 SnapAgentFilter 在 /snap-agent/** 请求中自动提取 userId 注入 AgentRequestContext
以便 Controller 无需重复获取身份
```
**AC:**
```gherkin
AC7: Given 请求 URI 以 /snap-agent/ 开头且用户已认证
  When SnapAgentFilter.doFilter 执行
  Then AgentRequestContext.getUserId() 返回当前用户 ID
  And 请求继续传递
AC8: Given 请求 URI 不以 /snap-agent/ 开头
  When SnapAgentFilter.doFilter 执行
  Then 不调用 SecurityGateway，直接放行
AC9: Given 请求处理完成 (无论成功/异常)
  When finally 块执行
  Then AgentRequestContext.clear() 被调用，ThreadLocal 被清理
```

### US-5: REST API 端点
```gherkin
作为 前端开发者
我希望 通过 /snap-agent/ 前缀的 REST API 操作 Agent
以便 前端可调用所有 Agent 功能
```
**AC:**
```gherkin
AC10: Given Agent 正常运行
  When GET /snap-agent/skills
  Then 返回已注册 Skill 列表
AC11: Given 用户提交 POST /snap-agent/runs
  When 请求包含 skillId 和 inputs
  Then 返回 taskId，异步执行诊断
AC12: Given 诊断运行中
  When GET /snap-agent/runs/{id}/stream
  Then 返回 SSE 流推送 thought/tool_call/tool_result 事件
```

### US-6: 安全框架自动适配
```gherkin
作为 安全开发者
我希望 SnapAgent 自动检测宿主安全框架 (Spring Security / Shiro) 并适配
以便 无需手动配置安全集成
```
**AC:**
```gherkin
AC13: Given security.framework="auto" 且 classpath 含 spring-security
  When 容器初始化
  Then SecurityGateway bean 类型为 SpringSecurityAdapter
AC14: Given security.framework="shiro"
  When 容器初始化
  Then SecurityGateway bean 类型为 ShiroAdapter
```

### US-7: 多 Pod 路由与 SSE 中继
```gherkin
作为 平台运维
我希望 多 Pod 部署时任务可路由到正确的 Pod，SSE 流跨 Pod 中继
以便 水平扩展时 Agent 功能正常
```
**AC:**
```gherkin
AC15: Given routing.mode="static" 且 static-peers 配置
  When PeerRouter.discoverPeers()
  Then 返回配置的 peer 列表
AC16: Given routing.mode="none"
  When PeerRouter.discoverPeers()
  Then 返回空列表 (本地执行)
AC17: Given routing.mode="static" 且 internal-token 配置
  When 容器初始化
  Then PeerSseRelay 和 InternalTaskController bean 存在
```

### US-8: 静态资源服务
```gherkin
作为 前端开发者
我希望 SnapAgent 自动服务 anchor.js/app.js/index.html 等静态资源
以便 前端无需额外配置即可加载 SnapAgent UI
```
**AC:**
```gherkin
AC18: Given snap-agent.enabled=true
  When GET /snap-agent/index.html
  Then 返回 SnapAgent 控制台 HTML
  And GET /snap-agent/anchor.js 可获取锚点脚本
```

### US-9: 知识注入到宿主 Agent 引擎 (跨模块集成)
```gherkin
作为 Agent 开发者
我希望 KnowledgeInjector 作为 SystemPromptExtender 被 AgentExecutor 自动调用
以便 宿主项目的 Agent 无需手动编码即可自动注入业务知识
```
**AC:**
```gherkin
AC19: Given snap-agent.knowledge.enabled=true 且知识库含匹配片段
  When AgentExecutor.buildSystemPrompt 调用
  Then system prompt 含"业务知识参考"section
  And KnowledgeInjector 通过 @ConditionalOnProperty 在 knowledge.enabled=false 时不注册
```

### US-10: 插件自动包装与注册
```gherkin
作为 插件开发者
我希望 ToolPlugin 实现 @Component 自动被 ToolPluginRegistry 发现和包装
以便 无需手动注册即可扩展工具
```
**AC:**
```gherkin
AC20: Given classpath 含 ToolPlugin 实现 @Component
  When 容器初始化
  Then ToolPluginRegistry 自动发现并注册该插件
  And 插件可通过 enable/disable 控制
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 装配 | US-1 零代码 | 零侵入 | bean 100% 装配 | - |
| 控制 | US-2 模块开关 | 精确管理 | enabled 100% 生效 | US-1 |
| 配置 | US-3 属性绑定 | 可调参 | 默认值 100% | US-1 |
| 请求 | US-4 身份注入 | 安全 | ThreadLocal 100% 清理 | US-1 |
| API | US-5 REST 端点 | 前端可用 | 端点 100% 响应 | US-1 |
| 安全 | US-6 框架适配 | 自动安全 | 检测 100% | US-1 |
| 扩展 | US-7 路由中继 | 水平扩展 | SSE 中继 100% | US-1 |
| 资源 | US-8 静态资源 | 前端加载 | 资源 200 OK | US-1 |
| 集成 | US-9 知识注入 | 跨模块 | knowledge.enabled 控制 | US-2 |
| 插件 | US-10 自动包装 | 可扩展 | 自动发现 100% | US-1 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | enabled=false 零 bean | P0 | AC2 | 集成 |
| UC-02 | enabled=true 全 bean 装配 | P0 | AC1 | 集成 |
| UC-03 | jdbc.enabled=false 不创建 | P0 | AC3 | 集成 |
| UC-04 | knowledge.enabled=true 创建 | P0 | AC4 | 集成 |
| UC-05 | 默认属性值 | P0 | AC5 | 单元 |
| UC-06 | 属性设值生效 | P0 | AC6 | 单元 |
| UC-07 | Filter 注入 userId | P0 | AC7 | 单元 |
| UC-08 | 非 snap-agent 路径放行 | P0 | AC8 | 单元 |
| UC-09 | Filter finally 清理 ThreadLocal | P0 | AC9 | 单元 |
| UC-10 | GET /skills | P0 | AC10 | 集成 |
| UC-11 | POST /runs 异步执行 | P0 | AC11 | 集成 |
| UC-12 | GET /runs/{id}/stream SSE | P0 | AC12 | 集成 |
| UC-13 | Spring Security 自动检测 | P0 | AC13 | 集成 |
| UC-14 | Shiro 适配 | P1 | AC14 | 单元 |
| UC-15 | Static peer 路由 | P1 | AC15 | 单元 |
| UC-16 | Noop peer 本地执行 | P1 | AC16 | 单元 |
| UC-17 | PeerSseRelay + InternalController | P1 | AC17 | 集成 |
| UC-18 | 静态资源服务 | P1 | AC18 | 集成 |
| UC-19 | KnowledgeInjector 条件注册 | P0 | AC19 | 集成 |
| UC-20 | 插件自动发现包装 | P1 | AC20 | 单元 |
| UC-R1 | POST /conversations 创建会话 | P1 | - | 集成 |
| UC-R2 | GET /conversations 会话列表 | P1 | - | 集成 |
| UC-R3 | GET /conversations/{id} 会话详情 | P1 | - | 集成 |
| UC-R4 | GET /conversations/{id}/download 下载会话 | P2 | - | 集成 |
| UC-R5 | DELETE /conversations/{id} 删除会话 | P1 | - | 集成 |
| UC-R6 | GET /tools/plugins 插件列表 | P1 | - | 集成 |
| UC-R7 | GET /tools/plugins/{id} 插件详情 | P1 | - | 集成 |
| UC-R8 | POST /tools/plugins/upload 上传插件 | P0 | - | 集成 |
| UC-R9 | DELETE /tools/plugins/{id} 删除插件 | P0 | - | 集成 |
| UC-R10 | POST /tools/plugins/{id}/enable 启用插件 | P1 | - | 集成 |
| UC-R11 | POST /tools/plugins/{id}/disable 禁用插件 | P1 | - | 集成 |
| UC-R12 | PUT /tools/plugins/{id}/default 设默认插件 | P1 | - | 集成 |

### 3.2 详细用例 (Gherkin)

```gherkin
@priority:high @type:integration
功能: SnapAgentAutoConfiguration 条件装配

  场景: enabled=false 时不创建任何 bean
    Given snap-agent.enabled=false
    When ApplicationContextRunner 初始化
    Then 不存在 SkillRegistry, AgentExecutor, LlmClient, ToolDispatcher, TaskStore, RateLimiter
    And 不存在 snapAgentExecutor, snapAgentFilter bean

  场景: enabled=true + api-key 非空时创建全部核心 bean
    Given snap-agent.enabled=true
    And snap-agent.llm.api-key=sk-test
    When 容器初始化
    Then SkillRegistry, AgentExecutor, LlmClient, ToolDispatcher, TaskStore, RateLimiter 均存在
    And AsyncTaskExecutor 存在
    And SqlGuard 存在
    And SecurityGateway 存在且类型为 SpringSecurityAdapter

  场景: api-key 为空时不创建 LlmClient
    Given snap-agent.enabled=true 且 snap-agent.llm.api-key 为空
    When 容器初始化
    Then LlmClient bean 不存在

  场景: jdbc.enabled=false 时不创建 JdbcQueryToolProvider
    Given snap-agent.enabled=true 且 snap-agent.jdbc.enabled=false
    When 容器初始化
    Then JdbcQueryToolProvider 不存在
    And 其他核心 bean 正常存在

  场景: knowledge.enabled=true 时创建 KnowledgeBase
    Given snap-agent.enabled=true 且 snap-agent.knowledge.enabled=true
    When 容器初始化
    Then KnowledgeBase 存在

  场景: patrol.enabled=true + alert.enabled=true 时创建巡检 bean
    Given snap-agent.enabled=true 且 snap-agent.patrol.enabled=true 且 snap-agent.alert.enabled=true
    When 容器初始化
    Then PatrolScheduler 和 AlertConverger 存在

  场景: routing.mode=none 时创建 NoopPeerRouter
    Given snap-agent.enabled=true 且 snap-agent.routing.mode=none
    When 容器初始化
    Then PeerRouter 存在且类型为 NoopPeerRouter

  场景: routing.mode=static 时创建 StaticPeerRouter
    Given snap-agent.enabled=true 且 snap-agent.routing.mode=static
    And snap-agent.routing.static-peers[0]=http://10.0.0.1:8080
    When 容器初始化
    Then PeerRouter 存在且类型为 StaticPeerRouter
    And discoverPeers() 返回 ["http://10.0.0.1:8080"]

  场景: routing.mode=static + internal-token 时创建 PeerSseRelay
    Given snap-agent.enabled=true 且 routing.mode=static 且 internal-token=secret
    When 容器初始化
    Then PeerSseRelay 和 InternalTaskController 存在
```

```gherkin
@priority:high @type:unit
功能: SnapAgentProperties 默认值与设值

  场景: 默认值验证
    Given new SnapAgentProperties()
    Then enabled=false, basePath="/snap-agent"
    And builtinSkillsDir="classpath*:/docs/skills/"
    And llm.apiType="anthropic", llm.model="claude-sonnet-4-6", llm.maxTokens=8192
    And agent.maxTurns=20, agent.maxConcurrentRunsPerUser=1, agent.maxRunsPerHour=20
    And jdbc.enabled=true, redis.enabled=true, mcp.enabled=false
    And security.framework="auto", security.requiredPermission="snap-agent:access"

  场景: 设值生效
    Given props.setEnabled(true), props.setBasePath("/custom")
    And props.getLlm().setApiKey("sk-test"), props.getAgent().setMaxTurns(10)
    Then enabled=true, basePath="/custom", apiKey="sk-test", maxTurns=10
```

```gherkin
@priority:high @type:unit
功能: SnapAgentFilter 请求身份注入

  场景: snap-agent 路径注入 userId
    Given 请求 URI="/snap-agent/runs" 且 SecurityGateway.currentUserId()="user-1"
    When doFilter 执行
    Then AgentRequestContext.getUserId()=="user-1"
    And chain.doFilter 被调用

  场景: 非 snap-agent 路径直接放行
    Given 请求 URI="/api/orders"
    When doFilter 执行
    Then SecurityGateway 未被调用
    And chain.doFilter 直接调用

  场景: 请求完成后清理 ThreadLocal
    Given 请求处理完成 (含异常)
    When finally 块执行
    Then AgentRequestContext.clear() 被调用
    And ThreadLocal 中无残留 userId
```

```gherkin
@priority:high @type:unit
功能: AgentRequestContext ThreadLocal 上下文

  场景: set/get/clear
    Given AgentRequestContext.setUserId("user-1")
    When getUserId()
    Then 返回 "user-1"
    When clear()
    Then getUserId() 返回 null

  场景: 线程隔离
    Given 线程A setUserId("user-A") 且 线程B setUserId("user-B")
    When 各自 getUserId()
    Then A 返回 "user-A", B 返回 "user-B"
```

```gherkin
@priority:high @type:unit
功能: PeerRouter 路由实现

  场景: NoopPeerRouter 返回空
    When NoopPeerRouter.discoverPeers()
    Then 返回空列表
    And mode()=="none"

  场景: StaticPeerRouter 返回配置列表
    Given peers=["http://10.0.0.1:8080", "http://10.0.0.2:8080"]
    When StaticPeerRouter.discoverPeers()
    Then 返回完整列表
    And mode()=="static"

  场景: K8sApiPeerRouter 通过 API 发现 pod
    Given k8s API 返回 2 个 pod IP
    When K8sApiPeerRouter.discoverPeers()
    Then 返回 ["http://pod-ip1:8080", "http://pod-ip2:8080"]
    And mode()=="k8s-api"

  场景: HeadlessDnsPeerRouter 通过 DNS 解析
    Given DNS 返回 3 个 A 记录
    When HeadlessDnsPeerRouter.discoverPeers()
    Then 返回 3 个 peer URL
    And mode()=="headless-dns"
```

```gherkin
@priority:high @type:unit
功能: 安全适配器

  场景: SpringSecurityAdapter 获取当前用户
    Given SecurityContext 含 Authentication principal="admin"
    When SpringSecurityAdapter.currentUserId()
    Then 返回 "admin"

  场景: DefaultPrincipalResolver 解析身份
    Given SecurityGateway.currentUserId()="user-1"
    When DefaultPrincipalResolver.resolve()
    Then 返回 UserInfo(userId="user-1")
```

---

## 4. 接口规格

```java
// SnapAgentAutoConfiguration — @ConditionalOnProperty(prefix="snap-agent", name="enabled", havingValue="true")
// 各模块: @ConditionalOnProperty(prefix="snap-agent.{module}", name="enabled", havingValue="true")
// LlmClient: @ConditionalOnExpression("${snap-agent.llm.api-key:} != ''")
// PeerRouter: 按 routing.mode 选择实现 (Noop/Static/K8sApi/HeadlessDns)
// SnapAgentFilter: @ConditionalOnBean(SecurityGateway.class), order=LOWEST_PRECEDENCE-10

// SnapAgentProperties — @ConfigurationProperties(prefix="snap-agent")
// 嵌套: Llm, Agent, Jdbc, Redis, Code, Patrol, Knowledge, Mcp, Cost, Workflows, Security, Logs, Routing

// SnapAgentFilter.doFilter — 仅 /snap-agent/** 请求注入 userId
// AgentRequestContext — ThreadLocal<String> userId, set/get/clear

// SnapAgentController — @RequestMapping("/snap-agent"), 暴露 REST API
// InternalTaskController — @RequestMapping("/snap-agent/internal"), 多 Pod 中继
```

```yaml
spring.factories:
  EnableAutoConfiguration: SnapAgentAutoConfiguration

snap-agent.* 配置前缀:
  enabled: false (默认关闭)
  base-path: /snap-agent
  llm: {api-type, api-key, base-url, model, max-tokens, timeout-seconds, streaming, proxy-url, allowed-models}
  agent: {max-turns, task-timeout-minutes, executor, max-concurrent-runs-per-user, max-runs-per-hour, max-result-rows, max-tool-result-chars, transcript-event-limit}
  jdbc: {enabled, datasource-bean-name}
  redis: {enabled, redis-template-bean-name, max-key-count}
  code: {enabled, project-root}
  patrol: {enabled}
  alert: {enabled}
  knowledge: {enabled}
  mcp: {enabled, servers}
  cost: {enabled}
  workflows: {enabled}
  code-graph: {enabled}
  security: {framework, required-permission, filter-order, principal-resolver-class, audit-log}
  logs: {enabled, allowed-paths, max-lines, max-file-bytes}
  routing: {mode, static-peers, k8s-service-name, port, internal-token}
```

---

## 5. 数据规格

```yaml
SnapAgentProperties:
  enabled: boolean (default false)
  basePath: String (default "/snap-agent")
  嵌套组: Llm, Agent, Jdbc, Redis, Code, Patrol, Knowledge, Mcp, Cost, Workflows, Security, Logs, Routing

AgentRequestContext:
  userId: ThreadLocal<String>
  生命周期: per-request, filter finally 清理

PeerRouter:
  mode: String (none/static/k8s-api/headless-dns)
  discoverPeers(): List<String> (peer URL list)
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 | 行为 |
|--------|------|------|------|
| API_KEY_EMPTY | WARN | llm.api-key 为空 | LlmClient 不创建 |
| MODULE_DISABLED | INFO | 模块 enabled=false | 对应 bean 不创建 |
| FILTER_ORDER_CONFLICT | WARN | filter-order 与宿主冲突 | 使用 LOWEST_PRECEDENCE-10 |
| THREAD_LOCAL_LEAK | ERROR | ThreadLocal 未清理 | filter finally 强制 clear |

```gherkin
场景: api-key 为空时降级
  Given snap-agent.enabled=true 但 llm.api-key 为空
  When 容器初始化
  Then LlmClient 不创建，其他 bean 正常
  And 日志记录 WARN
```

---

## 7. 非功能需求

```yaml
性能: auto-config 初始化 < 2s | filter 执行 < 1ms | 静态资源 200 OK < 50ms
可靠性: enabled=false 零 bean 零影响 | ThreadLocal 100% 清理 | @ConditionalOnMissingBean 防冲突
可测试性: ApplicationContextRunner 条件装配测试 | Properties 单元测试 | MockMvc 端点测试
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 数量 | 覆盖 |
|----------|------|------|
| `SnapAgentAutoConfigurationTest` | 25 | enabled=false/true、api-key 空/非空、jdbc/redis/code/patrol/knowledge/code-graph/cost/workflows 模块开关、routing 模式选择 (none/static/k8s-api/headless-dns)、PeerSseRelay+InternalController 条件 |
| `SnapAgentPropertiesTest` | 20 | 所有默认值、所有嵌套组设值生效 (Llm/Agent/Jdbc/Redis/Mcp/Security/Logs) |
| `SnapAgentFilterTest` | 5 | snap-agent 路径注入、非 snap-agent 放行、finally 清理、basePath 自定义 |
| `AgentRequestContextTest` | 3 | set/get/clear、线程隔离 |
| `SnapAgentControllerTest` | 38 | skills/runs/stream/transcript/report/cancel/audit/conversations/patrol/alerts/issues/cost/workflows/tools/plugins 全端点 |
| `SnapAgentControllerSecurityTest` | 20 | 认证/权限检查、429 限流、503 线程池满、500 异常 |
| `InternalTaskControllerTest` | 10 | 内部任务中继、SSE 转发、token 验证 |
| `NoopPeerRouterTest` | 2 | discoverPeers 返回空、mode |
| `StaticPeerRouterTest` | 5 | 配置列表返回、mode |
| `K8sApiPeerRouterTest` | 16 | API 发现 pod、标签选择、端口、异常处理 |
| `HeadlessDnsPeerRouterTest` | 12 | DNS 解析、多 A 记录、异常 |
| `PeerSseRelayTest` | 9 | SSE 中继、事件转发、连接管理 |
| `SpringSecurityAdapterTest` | 11 | 当前用户获取、null 处理、角色检查 |
| `ShiroAdapterTest` | 8 | Shiro 适配、Subject 获取 |
| `DefaultPrincipalResolverTest` | 17 | 解析身份、SPI 路径、fallback |
| `PluginAutoWrappingTest` | 4 | 插件自动包装、@Component 发现 |

**总结**: AutoConfiguration 条件装配全覆盖 (25 测试); Properties 默认值+设值全覆盖 (20 测试); Controller 全端点覆盖 (58 测试含安全); Routing 四种模式全覆盖 (44 测试); 安全适配器全覆盖 (36 测试)。

### 8.3 E2E 关键路径

| 路径ID | 关键路径 | 端点/组件 | 状态 |
|--------|----------|-----------|------|
| E2E-1 | AutoConfiguration 条件装配: snap-agent.enabled=false → 无 bean / enabled=true + api-key 空 → 无 LlmClient | SnapAgentAutoConfiguration | ✅已覆盖 (SnapAgentAutoConfigurationTest 25测试) |
| E2E-2 | 会话 CRUD: POST /conversations → GET /conversations → GET /conversations/{id} → DELETE /conversations/{id} | POST/GET/DELETE /conversations | ✅已覆盖 (ConversationEndpointTest) |
| E2E-3 | SSE 跨 Pod 中继: POST /runs (Pod A) → InternalTaskController (Pod B) → SSE relay → 客户端 | GET /snap-agent-internal/tasks/{id}/stream | ⚠未实现 (GAP-6 P2 需分布式环境) |
| E2E-4 | 静态资源服务: GET /snap-agent/ → 200 (static HTML/JS/CSS) | GET /snap-agent/** | ⚠未实现 (GAP-2 P2 需 Spring 上下文) |
| E2E-5 | 安全 401/403 → 审计: POST /runs (无认证) → 401 + AuditRecord → POST /runs (无权限) → 403 + AuditRecord | POST /runs | ✅已覆盖 (SnapAgentControllerSecurityTest 20测试) |
| E2E-6 | ThreadLocal 清理: POST /runs → filter doFilter → finally clear AgentRequestContext | SnapAgentFilter | ✅已覆盖 (SnapAgentFilterTest 5测试) |
| E2E-7 | PeerRouter 四模式: Noop(空) / Static(列表) / K8sApi(pod发现) / HeadlessDns(DNS解析) | PeerRouter SPI | ✅已覆盖 (35测试) |

### 8.4 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | ⚠环境约束: K8s API 实际调用需 K8s mock server 或集成集群环境 | P2 | 需 K8s 集成环境 |
| GAP-2 | ⚠框架约束: 静态资源服务由 Spring MVC ResourceHandler 提供，standalone MockMvc 不包含，需 @WebMvcTest 或 @SpringBootTest | P2 | 需 Spring 上下文测试 |
| GAP-3 | ✅已关闭: security.framework=auto 检测逻辑已由 `SnapAgentAutoConfigurationTest` 覆盖 (shouldCreateSecurityGatewayWhenSpringSecurityOnClasspath: Spring Security+Shiro同时在test classpath时验证SpringSecurityAdapter优先) | — | P1 |
| GAP-4 | ✅已关闭: 会话历史 API 边界场景已由 `ConversationEndpointTest` 覆盖 (POST/GET/DELETE /conversations + 404/503 boundary + FileConversationStoreTest 空会话) | — | P2 |
| GAP-5 | ✅已关闭: PluginUploader 安全验证已由 `PluginUploaderTest` 覆盖 (shouldRejectPluginIdWithPathTraversal/shouldRejectPluginIdWithSpecialCharacters/shouldThrowWhenPluginIdAlreadyRegistered/shouldThrowWhenProviderClassNotFound/shouldThrowWhenProviderInstantiationFails/shouldWrapIOExceptionWhenSavingTempFile) | — | P1 |
| GAP-6 | ⚠环境约束: 多 Pod SSE 中继端到端需双 Pod 集群环境 | P2 | 需分布式集成环境 |
| GAP-7 | `SnapAgentProperties.Routing` 嵌套属性完整设值测试 | P3 | Routing 组独立验证 |
| GAP-8 | ⚠功能缺失: `conversation.enabled` 属性在源码中不存在（ConversationStore 仅用 @ConditionalOnMissingBean），需先添加条件注解再测试 | P2 | 功能未实现 |

### 8.5 Mock策略
```yaml
集成测试: ApplicationContextRunner + AutoConfigurations.of()
Mock: LlmClient, SkillRegistry, AgentExecutor, ToolDispatcher (ApplicationContextRunner 注入)
Web: MockMvc 或 @WebMvcTest
Security: mock SecurityContext, Shiro Subject
K8s: mock KubernetesClient
DNS: mock InetAddress.getAllByName()
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| Spring Boot 2.x | 已完成 | 无 |
| snap-agent-core | 已完成 | 无 |
| Spring Security (可选) | classpath 检测 | 无 → DefaultPrincipalResolver |
| Shiro (可选) | classpath 检测 | 无 → 不创建 ShiroAdapter |
| JavaMailSender (可选) | @ConditionalOnClass | 无 → 不创建 EmailChannel |
| DataSource (可选) | @ConditionalOnBean | 无 → jdbc.enabled=false |
| RedisTemplate (可选) | @ConditionalOnBean | 无 → redis.enabled=false |

---

## 10. 可观测性设计

```yaml
日志: INFO "SnapAgent auto-configured: enabled={}, llm={}, security={}" | WARN "api-key empty, LlmClient not created" | DEBUG "Filter: {} -> userId={}"
指标: bean 创建计数 | filter 请求数 | 端点调用量
```

---

## 11. 原型与交互参考

| 资源 | 路径 | 说明 |
|------|------|------|
| 控制台 | /snap-agent/index.html | SnapAgent 管理界面 |
| 锚点脚本 | /snap-agent/anchor.js | 页面锚点问答/注入 |
| 应用 JS | /snap-agent/app.js | 控制台前端逻辑 |
| 样式 | /snap-agent/style.css | 控制台样式 |
| Markdown 渲染 | /snap-agent/md.js | Markdown 渲染器 |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 2.0 | 2026-07-24 | Team | 初始 TDD 规格 |

### 12.2 参考文档
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/SnapAgentAutoConfiguration.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/SnapAgentProperties.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../web/SnapAgentFilter.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../web/SnapAgentController.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../routing/PeerRouter.java`
- `snap-agent-spring-boot-2x-starter/src/main/resources/META-INF/spring.factories`
- `docs/ROADMAP.md` — 设计原则 (零影响、Skill 驱动)

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| AutoConfiguration | Spring Boot 自动装配，条件注册 bean |
| ConditionalOnProperty | 属性条件注解，enabled=false 时不创建 bean |
| SnapAgentFilter | 请求过滤器，注入 userId 到 ThreadLocal |
| AgentRequestContext | ThreadLocal 请求上下文 |
| PeerRouter | 多 Pod 路由 SPI，按 mode 选择实现 |
| PeerSseRelay | SSE 跨 Pod 中继器 |
| SecurityGateway | 安全网关 SPI，适配 Spring Security/Shiro |
