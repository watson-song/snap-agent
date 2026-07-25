# TDD需求规格说明书 — 宿主项目集成 (Host Integration)

> 版本: 2.1 (SnapAgent 2.x 架构重构) | 模块: 11-host-integration | 基于 TEMPLATE.md
> 变更: 新增 GraphExecutor / ReActGraphFactory / CheckpointStore / VectorStore / EmbeddingModel / ChatMemory / ChatMemoryRepository 等 bean; 新增 snap-agent.checkpoint/vectorstore/embedding/rag/memory/cost 配置组; 模块级开关更新

---

## 1. 需求元信息

```yaml
需求ID: REQ-11-HOST-INTEGRATION
需求名称: Spring Boot Auto-Configuration 与宿主集成
优先级: P0
迭代: v0.1+ (2.x 持续演进)
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 以 Spring Boot Starter 方式嵌入宿主应用，需通过 auto-configuration 实现零代码侵入的自动装配，按 `snap-agent.*` 属性条件注册各模块 bean。SnapAgent 2.x 引入图运行时 (GraphExecutor + ReActGraphFactory) 与 Spring AI 风格 SPI (Advisor / VectorStore / Embedding / RAG / ChatMemory / StructuredOutput)，需对应新增 bean 与配置组。
- **用户价值**: 宿主应用添加依赖 + 配置 `snap-agent.enabled=true` 即可用，`enabled=false` 时零 bean 零影响；新增模块 (checkpoint/vectorstore/embedding/rag/memory/cost) 全部默认关闭，按需启用。
- **成功指标**: enabled=false 时容器无 SnapAgent bean；各模块 enabled 开关 100% 生效；所有 REST 端点可正常响应；2.x 新增 bean 在对应开关开启时 100% 装配。

### 1.2 范围边界
- **包含**: `SnapAgentAutoConfiguration` (条件装配)、`SnapAgentProperties` (属性绑定)、`SnapAgentFilter` (请求过滤+身份注入)、`AgentRequestContext` (ThreadLocal 上下文)、`SnapAgentController` (REST 端点)、`InternalTaskController` (多 Pod 内部中继)、`PeerRouter` SPI + 实现 (Noop/Static/K8sApi/HeadlessDns)、`PeerSseRelay` (SSE 中继)、安全适配器 (SpringSecurityAdapter/ShiroAdapter/DefaultPrincipalResolver)、静态资源服务 (anchor.js/app.js/index.html)、2.x 新增: `GraphExecutor` / `ReActGraphFactory` / `CheckpointStore` (Sqlite/Redis) / `VectorStore` (Redis/Jdbc) / `EmbeddingModel` (OpenAI/Ollama) / `ChatMemory` + `ChatMemoryRepository` / `Advisor` 链 / `CostBudgetAdvisor`。
- **不包含**: 各模块内部逻辑 (各自有独立 TDD spec)、LlmClient 实现 (01-agent-engine)、具体 @Tool 工具实现 (03-tool-dispatcher)、Advisor 链内部实现 (10-cost-security)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | 宿主已有同类型 bean 产生冲突 | 低 | 中 | @ConditionalOnMissingBean 保护 |
| R2 | 安全框架未检测到致 filter 不生效 | 低 | 高 | security.framework=auto 自动检测 |
| R3 | 多 Pod 路由配置错误致 SSE 中继失败 | 中 | 中 | NoopPeerRouter 默认放行 |
| R4 | ThreadLocal 未清理致身份泄漏 | 低 | 高 | SnapAgentFilter finally 块强制 clear |
| R5 | 2.x 新模块 (checkpoint/vectorstore) 默认开启致资源浪费 | 中 | 中 | 所有新模块默认 enabled=false，显式开启 |
| R6 | CheckpointStore SQLite 文件路径不可写 | 低 | 中 | 启动期 IO 探测 + 降级到 InMemory |
| R7 | EmbeddingModel 远端不可用致图编译失败 | 中 | 中 | RAG advisor 在 Embedding 缺失时跳过检索 |

---

## 2. 用户故事 (User Stories)

### US-1: 零代码自动装配
```gherkin
作为 宿主应用开发者
我希望 添加 starter 依赖并配置 snap-agent.enabled=true 即可自动装配所有核心 bean (含 2.x 图运行时)
以便 无需写任何 Java 代码即可集成 SnapAgent
```
**AC:**
```gherkin
AC1: Given snap-agent.enabled=true 且 snap-agent.llm.api-key 非空
  When Spring 容器初始化
  Then SkillRegistry, LlmClient, TaskStore, RateLimiter, ToolCallbackRegistry 均存在
  And GraphExecutor + ReActGraphFactory bean 存在 (2.x)
  And snapAgentExecutor 线程池 bean 存在
  And snapAgentFilter bean 存在
AC2: Given snap-agent.enabled=false (或未设置)
  When Spring 容器初始化
  Then 容器中无任何 SnapAgent bean (含 2.x 图运行时)
```

### US-2: 模块级条件开关 (含 2.x 新模块)
```gherkin
作为 系统管理员
我希望 通过 snap-agent.{module}.enabled=false 精确关闭不需要的模块 (含 2.x 新增 checkpoint/vectorstore/embedding/rag/memory/cost)
以便 只启用需要的功能，减少资源占用
```
**AC:**
```gherkin
AC3: Given snap-agent.enabled=true 且 snap-agent.jdbc.enabled=false
  When 容器初始化
  Then JdbcQueryToolProvider bean 不存在
  And 其他核心 bean 正常存在
AC4: Given snap-agent.knowledge.enabled=true (legacy) 或 snap-agent.rag.enabled=true (2.x)
  When 容器初始化
  Then VectorStore + EmbeddingModel + RetrievalAugmentationAdvisor bean 存在
  And RetrievalAugmentationAdvisor.order == 200
AC5: Given snap-agent.checkpoint.type=sqlite (默认) 且 snap-agent.enabled=true
  When 容器初始化
  Then SqliteCheckpointStore bean 存在且 GraphExecutor 注入成功
AC6: Given snap-agent.memory.type=in-memory (默认) 且 snap-agent.enabled=true
  When 容器初始化
  Then ChatMemory + ChatMemoryRepository bean 存在
  And MessageChatMemoryAdvisor bean 存在且 order=100
AC7: Given snap-agent.cost.enabled=true
  When 容器初始化
  Then CostBudgetAdvisor bean 存在且 order=300
```

### US-3: 属性绑定与默认值
```gherkin
作为 系统管理员
我希望 通过 application.yml 的 snap-agent.* 前缀配置所有参数 (含 2.x 新增配置组)
以便 无需改代码即可调整行为
```
**AC:**
```gherkin
AC8: Given 默认 SnapAgentProperties
  When 获取各属性
  Then enabled=false, basePath="/snap-agent", llm.model="claude-sonnet-4-6", agent.maxTurns=20
  And agent.maxConcurrentRunsPerUser=1, agent.maxRunsPerHour=20
  And checkpoint.type="sqlite", checkpoint.sqlite.path="snap-agent-checkpoints.db"
  And vectorstore.enabled=false, vectorstore.type="redis"
  And embedding.provider="openai", embedding.model="text-embedding-3-small"
  And rag.enabled=false, rag.topK=4, rag.similarityThreshold=0.75
  And memory.type="in-memory", memory.maxMessages=20
  And cost.enabled=false, cost.maxTokensPerRun=100000
AC9: Given 设置 snap-agent.llm.api-key=sk-test, snap-agent.agent.max-turns=10
  When 属性绑定
  Then llm.apiKey=="sk-test", agent.maxTurns==10
AC10: Given 设置 snap-agent.checkpoint.type=redis, snap-agent.checkpoint.redis.ttl-seconds=604800
  When 属性绑定
  Then checkpoint.type=="redis", checkpoint.redis.ttlSeconds==604800
```

### US-4: 请求身份注入
```gherkin
作为 安全开发者
我希望 SnapAgentFilter 在 /snap-agent/** 请求中自动提取 userId 注入 AgentRequestContext
以便 Controller 无需重复获取身份
```
**AC:**
```gherkin
AC11: Given 请求 URI 以 /snap-agent/ 开头且用户已认证
  When SnapAgentFilter.doFilter 执行
  Then AgentRequestContext.getUserId() 返回当前用户 ID
  And 请求继续传递
AC12: Given 请求 URI 不以 /snap-agent/ 开头
  When SnapAgentFilter.doFilter 执行
  Then 不调用 SecurityGateway，直接放行
AC13: Given 请求处理完成 (无论成功/异常)
  When finally 块执行
  Then AgentRequestContext.clear() 被调用，ThreadLocal 被清理
```

### US-5: REST API 端点
```gherkin
作为 前端开发者
我希望 通过 /snap-agent/ 前缀的 REST API 操作 Agent (含 2.x 新增 checkpoint/resume 端点)
以便 前端可调用所有 Agent 功能
```
**AC:**
```gherkin
AC14: Given Agent 正常运行
  When GET /snap-agent/skills
  Then 返回已注册 Skill 列表
AC15: Given 用户提交 POST /snap-agent/runs
  When 请求包含 skillId 和 inputs
  Then 返回 taskId，异步执行诊断 (经 ReActGraphFactory 编译图)
AC16: Given 诊断运行中
  When GET /snap-agent/runs/{id}/stream
  Then 返回 SSE 流推送 thought/tool_call/tool_result 事件
AC17: Given 任务 PAUSED (HITL)
  When POST /snap-agent/runs/{id}/resume
  Then GraphExecutor.resume() 从 checkpoint 恢复执行
AC18: Given 任务运行中
  When GET /snap-agent/runs/{id}/checkpoints
  Then 返回历史 checkpoint 列表
```

### US-6: 安全框架自动适配
```gherkin
作为 安全开发者
我希望 SnapAgent 自动检测宿主安全框架 (Spring Security / Shiro) 并适配
以便 无需手动配置安全集成
```
**AC:**
```gherkin
AC19: Given security.framework="auto" 且 classpath 含 spring-security
  When 容器初始化
  Then SecurityGateway bean 类型为 SpringSecurityAdapter
AC20: Given security.framework="shiro"
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
AC21: Given routing.mode="static" 且 static-peers 配置
  When PeerRouter.discoverPeers()
  Then 返回配置的 peer 列表
AC22: Given routing.mode="none"
  When PeerRouter.discoverPeers()
  Then 返回空列表 (本地执行)
AC23: Given routing.mode="static" 且 internal-token 配置
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
AC24: Given snap-agent.enabled=true
  When GET /snap-agent/index.html
  Then 返回 SnapAgent 控制台 HTML
  And GET /snap-agent/anchor.js 可获取锚点脚本
```

### US-9: 知识注入到图运行时 (2.x 重构)
```gherkin
作为 Agent 开发者
我希望 RetrievalAugmentationAdvisor 作为 Advisor 在 agent 节点前后自动调用
以便 宿主项目的 Agent 无需手动编码即可自动注入业务知识
```
**AC:**
```gherkin
AC25: Given snap-agent.rag.enabled=true 且 VectorStore 含匹配片段
  When ReActGraphFactory.build(skill, task, advisors) 编译
  Then Advisor 链含 RetrievalAugmentationAdvisor(order=200)
  And agent 节点执行前 state["rag.context"] 非空
  And RetrievalAugmentationAdvisor 通过 @ConditionalOnProperty 在 rag.enabled=false 时不注册
```

### US-10: 插件自动包装与注册 (2.x 重构)
```gherkin
作为 插件开发者
我希望 ToolPlugin 实现 @Component 自动被 ToolPluginRegistry 发现，经 ToolCallbacks.from() 包装为 ToolCallback[] 注册到 ToolCallbackRegistry
以便 无需手动注册即可扩展工具
```
**AC:**
```gherkin
AC26: Given classpath 含 ToolPlugin 实现 @Component 且类含 @Tool 注解方法
  When 容器初始化
  Then ToolPluginRegistry 自动发现并注册该插件
  And ToolCallbacks.from(pluginInstance) 返回 ToolCallback[]
  And ToolCallbackRegistry.register(callback) 被调用
  And 插件可通过 enable/disable 控制
```

### US-11: CheckpointStore 装配 (2.x 新增)
```gherkin
作为 平台运维
我希望 通过 snap-agent.checkpoint.type 切换 SQLite/Redis checkpoint 存储
以便 开发环境零依赖，生产环境复用宿主 Redis
```
**AC:**
```gherkin
AC27: Given snap-agent.checkpoint.type=sqlite (默认)
  When 容器初始化
  Then SqliteCheckpointStore bean 存在
  And GraphExecutor 注入 SqliteCheckpointStore
AC28: Given snap-agent.checkpoint.type=redis 且宿主 RedisTemplate 存在
  When 容器初始化
  Then RedisCheckpointStore bean 存在 (TTL=7天)
  And @ConditionalOnMissingBean(CheckpointStore.class) 保护
AC29: Given snap-agent.checkpoint.type=redis 但宿主无 RedisTemplate
  When 容器初始化
  Then 降级到 SqliteCheckpointStore，日志 WARN "Redis unavailable, fallback to Sqlite"
```

### US-12: VectorStore + Embedding 装配 (2.x 新增)
```gherkin
作为 平台运维
我希望 通过 snap-agent.vectorstore.type 和 snap-agent.embedding.provider 切换实现
以便 按部署环境选择存储和嵌入模型
```
**AC:**
```gherkin
AC30: Given snap-agent.vectorstore.enabled=true 且 snap-agent.vectorstore.type=redis
  And snap-agent.embedding.provider=openai 且 OPENAI_API_KEY 非空
  When 容器初始化
  Then RedisVectorStore + OpenAIEmbeddingModel bean 存在
  And RetrievalAugmentationAdvisor 注入两者
AC31: Given snap-agent.vectorstore.enabled=true 但 snap-agent.embedding.provider 缺失
  When 容器初始化
  Then 抛 IllegalStateException "embedding provider required when vectorstore enabled"
AC32: Given snap-agent.embedding.provider=ollama 且 snap-agent.embedding.ollama.base-url=http://ollama:11434
  When 容器初始化
  Then OllamaEmbeddingModel bean 存在
```

### US-13: ChatMemory 装配 (2.x 新增)
```gherkin
作为 平台运维
我希望 通过 snap-agent.memory.type 切换 in-memory/jdbc ChatMemory
以便 开发零依赖，生产可持久化
```
**AC:**
```gherkin
AC33: Given snap-agent.memory.type=in-memory (默认)
  When 容器初始化
  Then InMemoryChatMemoryRepository + MessageWindowChatMemory(maxMessages=20) bean 存在
  And MessageChatMemoryAdvisor(order=100) bean 存在
AC34: Given snap-agent.memory.type=jdbc 且宿主 DataSource 存在
  When 容器初始化
  Then JdbcChatMemoryRepository bean 存在 (表 snap_agent_chat_memory)
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 装配 | US-1 零代码 | 零侵入 | bean 100% 装配 (含图运行时) | - |
| 控制 | US-2 模块开关 | 精确管理 | enabled 100% 生效 (含 2.x 新模块) | US-1 |
| 配置 | US-3 属性绑定 | 可调参 | 默认值 100% (含新配置组) | US-1 |
| 请求 | US-4 身份注入 | 安全 | ThreadLocal 100% 清理 | US-1 |
| API | US-5 REST 端点 | 前端可用 | 端点 100% 响应 (含 checkpoint/resume) | US-1 |
| 安全 | US-6 框架适配 | 自动安全 | 检测 100% | US-1 |
| 扩展 | US-7 路由中继 | 水平扩展 | SSE 中继 100% | US-1 |
| 资源 | US-8 静态资源 | 前端加载 | 资源 200 OK | US-1 |
| 集成 | US-9 RAG 注入 | 跨模块 | rag.enabled 控制 | US-2 |
| 插件 | US-10 自动包装 | 可扩展 | 自动发现 100% (产出 ToolCallback[]) | US-1 |
| Checkpoint | US-11 | 状态持久化 | type 切换 100% | US-1 |
| VectorStore | US-12 | 向量检索 | enabled 控制 100% | US-2 |
| Memory | US-13 | 对话记忆 | type 切换 100% | US-1 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | enabled=false 零 bean | P0 | AC2 | 集成 |
| UC-02 | enabled=true 全 bean 装配 (含 GraphExecutor + ReActGraphFactory) | P0 | AC1 | 集成 |
| UC-03 | jdbc.enabled=false 不创建 | P0 | AC3 | 集成 |
| UC-04 | rag.enabled=true 创建 RAG bean | P0 | AC4/AC25 | 集成 |
| UC-05 | 默认属性值 (含 2.x 新组) | P0 | AC8 | 单元 |
| UC-06 | 属性设值生效 | P0 | AC9/AC10 | 单元 |
| UC-07 | Filter 注入 userId | P0 | AC11 | 单元 |
| UC-08 | 非 snap-agent 路径放行 | P0 | AC12 | 单元 |
| UC-09 | Filter finally 清理 ThreadLocal | P0 | AC13 | 单元 |
| UC-10 | GET /skills | P0 | AC14 | 集成 |
| UC-11 | POST /runs 异步执行 | P0 | AC15 | 集成 |
| UC-12 | GET /runs/{id}/stream SSE | P0 | AC16 | 集成 |
| UC-13 | POST /runs/{id}/resume (2.x) | P0 | AC17 | 集成 |
| UC-14 | GET /runs/{id}/checkpoints (2.x) | P1 | AC18 | 集成 |
| UC-15 | Spring Security 自动检测 | P0 | AC19 | 集成 |
| UC-16 | Shiro 适配 | P1 | AC20 | 单元 |
| UC-17 | Static peer 路由 | P1 | AC21 | 单元 |
| UC-18 | Noop peer 本地执行 | P1 | AC22 | 单元 |
| UC-19 | PeerSseRelay + InternalController | P1 | AC23 | 集成 |
| UC-20 | 静态资源服务 | P1 | AC24 | 集成 |
| UC-21 | RetrievalAugmentationAdvisor 条件注册 | P0 | AC25 | 集成 |
| UC-22 | 插件自动发现包装 (ToolCallback[]) | P1 | AC26 | 单元 |
| UC-23 | CheckpointStore sqlite 装配 | P0 | AC27 | 集成 |
| UC-24 | CheckpointStore redis 装配 | P1 | AC28 | 集成 |
| UC-25 | CheckpointStore redis 降级 | P1 | AC29 | 集成 |
| UC-26 | VectorStore+Embedding redis+openai | P0 | AC30 | 集成 |
| UC-27 | VectorStore 缺 embedding 报错 | P1 | AC31 | 集成 |
| UC-28 | Embedding ollama | P1 | AC32 | 集成 |
| UC-29 | ChatMemory in-memory | P0 | AC33 | 集成 |
| UC-30 | ChatMemory jdbc | P1 | AC34 | 集成 |
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
功能: SnapAgentAutoConfiguration 条件装配 (含 2.x 图运行时)

  场景: enabled=false 时不创建任何 bean
    Given snap-agent.enabled=false
    When ApplicationContextRunner 初始化
    Then 不存在 SkillRegistry, GraphExecutor, ReActGraphFactory, LlmClient, ToolCallbackRegistry, TaskStore, RateLimiter
    And 不存在 snapAgentExecutor, snapAgentFilter bean
    And 不存在 CheckpointStore, VectorStore, EmbeddingModel, ChatMemory, ChatMemoryRepository bean

  场景: enabled=true + api-key 非空时创建全部核心 bean
    Given snap-agent.enabled=true
    And snap-agent.llm.api-key=sk-test
    When 容器初始化
    Then SkillRegistry, GraphExecutor, ReActGraphFactory, LlmClient, ToolCallbackRegistry, TaskStore, RateLimiter 均存在
    And AsyncTaskExecutor 存在
    And SqlGuard 存在
    And SecurityGateway 存在且类型为 SpringSecurityAdapter

  场景: api-key 为空时不创建 LlmClient
    Given snap-agent.enabled=true 且 snap-agent.llm.api-key 为空
    When 容器初始化
    Then LlmClient bean 不存在
    And GraphExecutor 仍存在但图执行将抛 IllegalStateException

  场景: jdbc.enabled=false 时不创建 JdbcQueryToolProvider
    Given snap-agent.enabled=true 且 snap-agent.jdbc.enabled=false
    When 容器初始化
    Then JdbcQueryToolProvider 不存在
    And 其他核心 bean 正常存在

  场景: rag.enabled=true 时创建 RAG bean
    Given snap-agent.enabled=true 且 snap-agent.rag.enabled=true
    And snap-agent.vectorstore.enabled=true 且 snap-agent.embedding.provider=openai
    When 容器初始化
    Then VectorStore + EmbeddingModel + RetrievalAugmentationAdvisor 存在
    And RetrievalAugmentationAdvisor.order == 200

  场景: checkpoint.type=sqlite (默认)
    Given snap-agent.enabled=true 且 snap-agent.checkpoint.type=sqlite
    When 容器初始化
    Then SqliteCheckpointStore 存在
    And GraphExecutor 注入 SqliteCheckpointStore

  场景: checkpoint.type=redis + RedisTemplate 存在
    Given snap-agent.enabled=true 且 snap-agent.checkpoint.type=redis
    And 容器含 RedisTemplate bean
    When 容器初始化
    Then RedisCheckpointStore 存在 (TTL=7天)
    And @ConditionalOnMissingBean(CheckpointStore.class) 保护

  场景: memory.type=in-memory (默认)
    Given snap-agent.enabled=true 且 snap-agent.memory.type=in-memory
    When 容器初始化
    Then InMemoryChatMemoryRepository + MessageWindowChatMemory(maxMessages=20) bean 存在
    And MessageChatMemoryAdvisor(order=100) bean 存在

  场景: cost.enabled=true
    Given snap-agent.enabled=true 且 snap-agent.cost.enabled=true
    When 容器初始化
    Then CostBudgetAdvisor bean 存在且 order=300

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

  场景: checkpoint.type=redis 但无 RedisTemplate → 降级
    Given snap-agent.enabled=true 且 snap-agent.checkpoint.type=redis
    And 容器不含 RedisTemplate bean
    When 容器初始化
    Then 降级到 SqliteCheckpointStore
    And 日志 WARN "Redis unavailable, fallback to Sqlite"

  场景: vectorstore.enabled=true 但缺 embedding.provider → 报错
    Given snap-agent.enabled=true 且 snap-agent.vectorstore.enabled=true
    And snap-agent.embedding.provider 为空
    When 容器初始化
    Then 抛 IllegalStateException 含 "embedding provider required"
```

```gherkin
@priority:high @type:unit
功能: SnapAgentProperties 默认值与设值 (含 2.x 新组)

  场景: 默认值验证
    Given new SnapAgentProperties()
    Then enabled=false, basePath="/snap-agent"
    And builtinSkillsDir="classpath*:/docs/skills/"
    And llm.apiType="anthropic", llm.model="claude-sonnet-4-6", llm.maxTokens=8192
    And agent.maxTurns=20, agent.maxConcurrentRunsPerUser=1, agent.maxRunsPerHour=20
    And jdbc.enabled=true, redis.enabled=true, mcp.enabled=false
    And security.framework="auto", security.requiredPermission="snap-agent:access"
    And checkpoint.type="sqlite", checkpoint.sqlite.path="snap-agent-checkpoints.db"
    And vectorstore.enabled=false, vectorstore.type="redis"
    And embedding.provider="openai", embedding.model="text-embedding-3-small"
    And rag.enabled=false, rag.topK=4, rag.similarityThreshold=0.75
    And memory.type="in-memory", memory.maxMessages=20
    And cost.enabled=false, cost.maxTokensPerRun=100000

  场景: 设值生效
    Given props.setEnabled(true), props.setBasePath("/custom")
    And props.getLlm().setApiKey("sk-test"), props.getAgent().setMaxTurns(10)
    And props.getCheckpoint().setType("redis"), props.getCheckpoint().getRedis().setTtlSeconds(604800)
    Then enabled=true, basePath="/custom", apiKey="sk-test", maxTurns=10
    And checkpoint.type=="redis", checkpoint.redis.ttlSeconds==604800
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

// 2.x 新增 AutoConfiguration 类:
//   GraphRuntimeAutoConfiguration — GraphExecutor, ReActGraphFactory (默认启用)
//   CheckpointAutoConfiguration — SqliteCheckpointStore (默认) / RedisCheckpointStore (type=redis)
//   VectorStoreAutoConfiguration — RedisVectorStore / JdbcVectorStore (vectorstore.enabled=false 默认)
//   EmbeddingAutoConfiguration — OpenAIEmbeddingModel / OllamaEmbeddingModel (与 VectorStore 一起启用)
//   RagAutoConfiguration — RetrievalAugmentationAdvisor + QueryTransformer + DocumentRetriever + QueryAugmenter (rag.enabled=false 默认)
//   MemoryAutoConfiguration — ChatMemory + ChatMemoryRepository + MessageChatMemoryAdvisor (默认 in-memory)
//   CostAutoConfiguration — CostBudgetAdvisor (cost.enabled=false 默认)

// SnapAgentProperties — @ConfigurationProperties(prefix="snap-agent")
// 嵌套: Llm, Agent, Jdbc, Redis, Code, Patrol, Knowledge, Mcp, Cost, Workflows, Security, Logs, Routing,
//       Checkpoint, VectorStore, Embedding, Rag, Memory (2.x 新增后五个)

// SnapAgentFilter.doFilter — 仅 /snap-agent/** 请求注入 userId
// AgentRequestContext — ThreadLocal<String> userId, set/get/clear

// SnapAgentController — @RequestMapping("/snap-agent"), 暴露 REST API (含 2.x POST /runs/{id}/resume + GET /runs/{id}/checkpoints)
// InternalTaskController — @RequestMapping("/snap-agent/internal"), 多 Pod 中继
```

```yaml
spring.factories:
  EnableAutoConfiguration:
    - SnapAgentAutoConfiguration
    - GraphRuntimeAutoConfiguration
    - CheckpointAutoConfiguration
    - VectorStoreAutoConfiguration
    - EmbeddingAutoConfiguration
    - RagAutoConfiguration
    - MemoryAutoConfiguration
    - CostAutoConfiguration

snap-agent.* 配置前缀:
  enabled: false (默认关闭)
  base-path: /snap-agent
  llm: {api-type, api-key, base-url, model, max-tokens, timeout-seconds, streaming, proxy-url, allowed-models}
  agent: {max-turns, task-timeout-minutes, executor, max-concurrent-runs-per-user, max-runs-per-hour, max-result-rows, max-tool-result-chars, transcript-event-limit}
  jdbc: {enabled, datasource-bean-name}
  redis: {enabled, redis-template-bean-name, max-key-count}
  code: {enabled, project-root}
  patrol: {enabled, issue-closure-node}
  alert: {enabled}
  knowledge: {enabled}            # legacy
  mcp: {enabled, servers}
  cost: {enabled, max-tokens-per-run, max-cost-per-run}
  workflows: {enabled}
  code-graph: {enabled}
  security: {framework, required-permission, filter-order, principal-resolver-class, audit-log}
  logs: {enabled, allowed-paths, max-lines, max-file-bytes}
  routing: {mode, static-peers, k8s-service-name, port, internal-token}
  # 2.x 新增:
  checkpoint:
    type: sqlite (默认) | redis
    sqlite: {path: snap-agent-checkpoints.db}
    redis: {redis-template-bean-name, ttl-seconds: 604800}
  vectorstore:
    enabled: false (默认)
    type: redis | jdbc
    redis: {redis-template-bean-name, index-key: snap-agent-vectors}
    jdbc: {datasource-bean-name, table-name: snap_agent_vectors}
  embedding:
    provider: openai | ollama
    model: text-embedding-3-small (openai 默认)
    openai: {api-key, base-url}
    ollama: {base-url: http://ollama:11434, model: nomic-embed-text}
  rag:
    enabled: false (默认)
    top-k: 4
    similarity-threshold: 0.75
    filter-expression: (可选)
  memory:
    type: in-memory (默认) | jdbc
    max-messages: 20
    jdbc: {datasource-bean-name, table-name: snap_agent_chat_memory}
```

---

## 5. 数据规格

```yaml
SnapAgentProperties:
  enabled: boolean (default false)
  basePath: String (default "/snap-agent")
  嵌套组: Llm, Agent, Jdbc, Redis, Code, Patrol, Knowledge, Mcp, Cost, Workflows, Security, Logs, Routing,
          Checkpoint, VectorStore, Embedding, Rag, Memory (2.x 新增后五个)

AgentRequestContext:
  userId: ThreadLocal<String>
  生命周期: per-request, filter finally 清理

PeerRouter:
  mode: String (none/static/k8s-api/headless-dns)
  discoverPeers(): List<String> (peer URL list)

CheckpointStore (2.x 新增):
  type: sqlite | redis
  threadId 格式: "run:{runId}" 或 "patrol:{patrolId}:{runId}"
  ttl: 7 天 (redis)

ChatMemory (2.x 新增):
  type: in-memory | jdbc
  maxMessages: 20 (滑动窗口)
  conversationId: 等同 runId (或 userId:sessionId)
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 | 行为 |
|--------|------|------|------|
| API_KEY_EMPTY | WARN | llm.api-key 为空 | LlmClient 不创建 |
| MODULE_DISABLED | INFO | 模块 enabled=false | 对应 bean 不创建 |
| FILTER_ORDER_CONFLICT | WARN | filter-order 与宿主冲突 | 使用 LOWEST_PRECEDENCE-10 |
| THREAD_LOCAL_LEAK | ERROR | ThreadLocal 未清理 | filter finally 强制 clear |
| REDIS_UNAVAILABLE | WARN | checkpoint.type=redis 但无 RedisTemplate | 降级到 SqliteCheckpointStore |
| EMBEDDING_MISSING | ERROR | vectorstore.enabled=true 但缺 embedding.provider | 启动失败 |
| VECTORSTORE_DISABLED | INFO | rag.enabled=true 但 vectorstore.enabled=false | RetrievalAugmentationAdvisor 检索返回空 |

```gherkin
场景: api-key 为空时降级
  Given snap-agent.enabled=true 但 llm.api-key 为空
  When 容器初始化
  Then LlmClient 不创建，其他 bean 正常
  And 日志记录 WARN

场景: redis 不可用时 checkpoint 降级
  Given snap-agent.checkpoint.type=redis 但容器无 RedisTemplate
  When 容器初始化
  Then 降级到 SqliteCheckpointStore
  And 日志 WARN "Redis unavailable, fallback to Sqlite"

场景: vectorstore 启用但缺 embedding
  Given snap-agent.vectorstore.enabled=true 且 snap-agent.embedding.provider 为空
  When 容器初始化
  Then 抛 IllegalStateException
  And 错误信息含 "embedding provider required"
```

---

## 7. 非功能需求

```yaml
性能: auto-config 初始化 < 2s | filter 执行 < 1ms | 静态资源 200 OK < 50ms | 2.x 新增模块装配 < 500ms (各自)
可靠性: enabled=false 零 bean 零影响 | ThreadLocal 100% 清理 | @ConditionalOnMissingBean 防冲突 | 新模块默认关闭
可测试性: ApplicationContextRunner 条件装配测试 | Properties 单元测试 | MockMvc 端点测试
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 数量 | 覆盖 |
|----------|------|------|
| `SnapAgentAutoConfigurationTest` | 35 | enabled=false/true、api-key 空/非空、jdbc/redis/code/patrol/knowledge/code-graph/cost/workflows 模块开关、routing 模式选择 (none/static/k8s-api/headless-dns)、PeerSseRelay+InternalController 条件、2.x 新增: checkpoint.type=sqlite/redis/降级、vectorstore+embedding 组合、rag.enabled、memory.type=in-memory/jdbc、cost.enabled |
| `SnapAgentPropertiesTest` | 28 | 所有默认值、所有嵌套组设值生效 (Llm/Agent/Jdbc/Redis/Mcp/Security/Logs/Routing + 2.x 新增 Checkpoint/VectorStore/Embedding/Rag/Memory/Cost) |
| `SnapAgentFilterTest` | 5 | snap-agent 路径注入、非 snap-agent 放行、finally 清理、basePath 自定义 |
| `AgentRequestContextTest` | 3 | set/get/clear、线程隔离 |
| `SnapAgentControllerTest` | 42 | skills/runs/stream/transcript/report/cancel/audit/conversations/patrol/alerts/issues/cost/workflows/tools/plugins + 2.x: /runs/{id}/resume, /runs/{id}/checkpoints, /runs/{id}/interrupt, /runs/{id}/replay 全端点 |
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
| `PluginAutoWrappingTest` | 5 | 插件自动包装、@Component 发现、ToolCallbacks.from() 反射、ToolCallbackRegistry 注册 |

**总结**: AutoConfiguration 条件装配全覆盖 (35 测试, 含 2.x 新增模块); Properties 默认值+设值全覆盖 (28 测试, 含新配置组); Controller 全端点覆盖 (62 测试含安全 + 2.x resume/checkpoints); Routing 四种模式全覆盖 (35 测试); 安全适配器全覆盖 (36 测试)。

### 8.3 E2E 关键路径

| 路径ID | 关键路径 | 端点/组件 | 状态 |
|--------|----------|-----------|------|
| E2E-1 | AutoConfiguration 条件装配: snap-agent.enabled=false → 无 bean / enabled=true + api-key 空 → 无 LlmClient | SnapAgentAutoConfiguration | ✅已覆盖 (SnapAgentAutoConfigurationTest 35测试) |
| E2E-2 | 会话 CRUD: POST /conversations → GET /conversations → GET /conversations/{id} → DELETE /conversations/{id} | POST/GET/DELETE /conversations | ✅已覆盖 (ConversationEndpointTest) |
| E2E-3 | SSE 跨 Pod 中继: POST /runs (Pod A) → InternalTaskController (Pod B) → SSE relay → 客户端 | GET /snap-agent-internal/tasks/{id}/stream | ⚠未实现 (GAP-6 P2 需分布式环境) |
| E2E-4 | 静态资源服务: GET /snap-agent/ → 200 (static HTML/JS/CSS) | GET /snap-agent/** | ⚠未实现 (GAP-2 P2 需 Spring 上下文) |
| E2E-5 | 安全 401/403 → 审计: POST /runs (无认证) → 401 + AuditRecord → POST /runs (无权限) → 403 + AuditRecord | POST /runs | ✅已覆盖 (SnapAgentControllerSecurityTest 20测试) |
| E2E-6 | ThreadLocal 清理: POST /runs → filter doFilter → finally clear AgentRequestContext | SnapAgentFilter | ✅已覆盖 (SnapAgentFilterTest 5测试) |
| E2E-7 | PeerRouter 四模式: Noop(空) / Static(列表) / K8sApi(pod发现) / HeadlessDns(DNS解析) | PeerRouter SPI | ✅已覆盖 (35测试) |
| E2E-8 | HITL resume: POST /runs → PAUSED → POST /runs/{id}/resume → 继续执行 → SUCCEEDED | POST /runs/{id}/resume | ⚠未实现 (GAP-9 P1 需 Testcontainers + CheckpointStore) |
| E2E-9 | Checkpoint 回放: GET /runs/{id}/checkpoints → POST /runs/{id}/replay → 从指定 checkpoint 回放 | GET/POST /runs/{id}/* | ⚠未实现 (GAP-10 P2) |

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
| GAP-9 | ⚠E2E缺失: HITL resume 端到端 (POST /runs/{id}/resume + checkpoint 恢复) 无 E2E 覆盖 — 见 E2E-8 | P1 | 需 Testcontainers 集成测试 |
| GAP-10 | ⚠E2E缺失: Checkpoint 回放 (POST /runs/{id}/replay) 无 E2E 覆盖 — 见 E2E-9 | P2 | 需 E2E 集成测试 |
| GAP-11 | ✅已关闭: 2.x 新增模块 (checkpoint/vectorstore/embedding/rag/memory/cost) 条件装配已由 `SnapAgentAutoConfigurationTest` 覆盖 (35个测试，含 sqlite/redis 切换、降级、vectorstore+embedding 组合、rag.enabled、memory.type 切换、cost.enabled) | — | P0 |
| GAP-12 | ⚠环境约束: RedisVectorStore 真实 Redis 调用需 Testcontainers + redis 镜像 | P2 | 需 Testcontainers 集成环境 |
| GAP-13 | ⚠边缘场景: OllamaEmbeddingModel 真实调用需本地 Ollama 或 mock HTTP server | P3 | 需 mock HTTP 集成 |

> 环境限制 (GAP-6): 跨 Pod SSE 中继需要 2+ Pod 实例，通过 K8s API/DNS 发现 peer 并转发 SSE 事件。standalone 单元测试只能 mock PeerRouter 和 PeerSseRelay，真实跨 Pod 通信需要 K8s 集群或 Testcontainers + 多实例环境。PeerSseRelayTest 已覆盖单实例 mock 场景，K8sApiPeerRouterTest 覆盖 K8s API 调用，但端到端跨 Pod SSE 流未验证。

### 8.5 Mock策略
```yaml
集成测试: ApplicationContextRunner + AutoConfigurations.of()
Mock: LlmClient, SkillRegistry, GraphExecutor, ReActGraphFactory, ToolCallbackRegistry (ApplicationContextRunner 注入)
Web: MockMvc 或 @WebMvcTest
Security: mock SecurityContext, Shiro Subject
K8s: mock KubernetesClient
DNS: mock InetAddress.getAllByName()
Redis: Testcontainers + redis 镜像 (2.x 新增模块)
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
| RedisTemplate (可选) | @ConditionalOnBean | 无 → checkpoint 降级 Sqlite / vectorstore 不创建 |
| sqlite-jdbc (2.x 可选) | starter 层可选依赖 | 无 → 仅 redis 实现 |
| Testcontainers (2.x 测试) | 测试 scope | 无 → 跳过真实 Redis/JDBC 集成测试 |

---

## 10. 可观测性设计

```yaml
日志: INFO "SnapAgent auto-configured: enabled={}, llm={}, security={}" | WARN "api-key empty, LlmClient not created" | DEBUG "Filter: {} -> userId={}"
      INFO "Graph runtime configured: checkpoint={}, vectorstore={}, memory={}, rag={}, cost={}"
指标: bean 创建计数 | filter 请求数 | 端点调用量 | checkpoint_save_total{result} | embedding_call_total{provider}
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
| 2.1 | 2026-07-25 | Team | 适配 2.x: 新增 GraphExecutor/ReActGraphFactory/CheckpointStore/VectorStore/EmbeddingModel/ChatMemory/ChatMemoryRepository/Advisor/CostBudgetAdvisor bean; 新增 snap-agent.checkpoint/vectorstore/embedding/rag/memory/cost 配置组; Controller 新增 POST /runs/{id}/resume + GET /runs/{id}/checkpoints + POST /runs/{id}/interrupt + POST /runs/{id}/replay; 新增 US-11/12/13 (CheckpointStore/VectorStore/ChatMemory 装配); AgentExecutor→GraphExecutor+ReActGraphFactory, ToolDispatcher→ToolCallbackRegistry, KnowledgeInjector→RetrievalAugmentationAdvisor |

### 12.2 参考文档
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/SnapAgentAutoConfiguration.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/SnapAgentProperties.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/GraphRuntimeAutoConfiguration.java` (2.x 新增)
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/CheckpointAutoConfiguration.java` (2.x 新增)
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/VectorStoreAutoConfiguration.java` (2.x 新增)
- `snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/MemoryAutoConfiguration.java` (2.x 新增)
- `snap-agent-spring-boot-2x-starter/src/main/java/.../web/SnapAgentFilter.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../web/SnapAgentController.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../routing/PeerRouter.java`
- `snap-agent-spring-boot-2x-starter/src/main/resources/META-INF/spring.factories`
- `docs/ROADMAP.md` — 设计原则 (零影响、Skill 驱动)
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md`

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
| GraphExecutor | 2.x 图执行器，替代 AgentExecutor |
| ReActGraphFactory | 2.x 图工厂，build(skill, task, advisors) 编译 ReAct 图 |
| CheckpointStore | 2.x checkpoint 存储 SPI (Sqlite/Redis) |
| VectorStore | 2.x 向量存储 SPI (Redis/Jdbc) |
| EmbeddingModel | 2.x 嵌入模型 SPI (OpenAI/Ollama) |
| ChatMemory | 2.x 对话记忆 SPI (in-memory/jdbc) |
| RetrievalAugmentationAdvisor | 2.x RAG Advisor，替代 KnowledgeInjector |
| CostBudgetAdvisor | 2.x 成本预算 Advisor (order=300) |
