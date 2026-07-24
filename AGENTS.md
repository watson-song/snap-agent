# AGENTS.md — AI Agent 工作指南

> 本文件为 AI 编码助手（Claude Code / Copilot / Cursor 等）在本仓库工作时的行为规范。

## 项目定位

SnapAgent 是一个**嵌入式 AI 技能框架** — 一键为 Spring Boot 应用接入 Agent 能力。用 Markdown 定义技能，用自然语言驱动，内置工具可扩展。当前版本 (v0.1) 以数据库诊断为验证场景，但框架设计是通用的：代码分析、运营诊断、异常监控、Bugfix 推送等都是未来方向（详见 `docs/ROADMAP.md`）。

## 模块结构

```
snap-agent-core/                    # SPI 层：纯接口 + 执行循环（无 Spring 依赖）
  ├── agent/                          # AgentExecutor, AgentTask, TaskStore, RateLimiter
  ├── llm/                            # LlmClient SPI, Message, ToolDef, LlmEventSink
  ├── skill/                          # SkillRegistry, SkillLoader, SkillMeta, InputSpec
  ├── tool/                           # ToolDispatcher, ToolProvider SPI, ToolResult
  └── security/                       # SecurityGateway, PrincipalResolver SPI

snap-agent-spring-boot-2x-starter/  # Spring Boot 2.x 自动装配
  ├── autoconfig/                     # SnapAgentAutoConfiguration, SnapAgentProperties
  ├── llm/                            # AnthropicLlmClient (OkHttp + SSE streaming)
  ├── web/                            # SnapAgentController, SnapAgentFilter, InternalTaskController
  ├── tool/                           # JdbcQueryToolProvider, RedisReadToolProvider, SqlGuard
  ├── security/                       # SpringSecurityAdapter, ShiroAdapter, DefaultPrincipalResolver
  ├── routing/                        # PeerRouter, K8sApiPeerRouter, StaticPeerRouter, PeerSseRelay
  └── resources/static/snap-agent/  # SPA UI (index.html, app.js, md.js, style.css)

snap-agent-demo/                    # 独立演示模块（不在父 pom 中）
  ├── src/main/java/.../demo/         # DemoApplication, SecurityConfig, DataSourceConfig, EchoToolProvider
  └── src/main/resources/skills/      # 示例 Skill .md 文件
```

## 构建规则

1. **构建顺序**：先 `mvn clean install` 父项目（core + starter），再 `cd snap-agent-demo && mvn clean package`
2. **Java 8**：源码必须兼容 Java 8（无 `var`、无 `Stream.toList()`、无 `Text Blocks`）
3. **测试基线**：1253+ tests（core 254 + starter 1253），jacoco 行覆盖率门槛为 **ratchet 模式**（core ≥ 0.72 / starter ≥ 0.73，即当前实测值向下取整，只许升不许降）。目标：随新测试补齐逐步升回 0.85。注意门槛在 `verify` 阶段才检查，日常 `mvn test` 不会触发，发版前必须跑 `mvn clean verify`
4. **Stale JAR 问题**：Spring Boot fat JAR 嵌套 starter JAR，改了 starter 代码后必须 `mvn clean install` starter 再重新 `mvn clean package` demo

## 代码约定

- **Java**: `javax.servlet`（非 `jakarta`），`@ConditionalOnProperty` 控制 Bean 装配
- **前端**: 纯 vanilla JS，无构建工具，无 npm 依赖。`app.js` 改动后需递增 `index.html` 中的 `?v=N` 缓存破坏参数
- **测试**: JUnit 5 + AssertJ + Mockito，测试类与源码同包结构。详见 `docs/TEST_GUIDELINES.md` — **TDD 驱动开发，贯彻到底**
- **Git**: 提交信息用英文或中英混合，Conventional Commits 格式，不要 `--no-verify`

## TDD 驱动开发规范

> **核心原则：每一行产品代码都有对应的测试代码。测试是设计工具，不是覆盖率工具。**
> 完整规范见 `docs/TEST_GUIDELINES.md`

### 红绿循环 (必须遵守)

```
Red:   先写测试 → 运行 → 失败（功能未实现）
Green: 写最小实现 → 运行 → 通过
Refactor: 重构 → 运行 → 仍通过
```

### Bugfix 规则

修复 Bug 时**必须**：
1. 先写复现测试 — 在修复前，写一个测试能稳定复现该 Bug（红灯）
2. 再修复代码 — 最小改动让测试通过（绿灯）
3. 提交信息引用测试类名

### Feature 规则

新增功能时**必须**：
1. 先写失败测试 — 断言期望行为（红灯）
2. 再实现功能 — 让测试通过（绿灯）
3. 补充边界测试 — null/空/超长/并发/异常路径

### 测试分层

| 层级 | 特征 | 适用 |
|------|------|------|
| Unit | @ExtendWith(MockitoExtension.class)，standalone MockMvc，无 Spring 上下文 | 核心逻辑：Service/Executor/Loader/Registry/Guard |
| Integration | standalone MockMvc + 真实组件链，或 @SpringBootTest | Controller 端点、多组件协作、条件装配 |
| E2E | standalone MockMvc 模拟完整请求链 | 端到端关键路径 |
| Frontend Unit | Vitest + jsdom，eval 加载非模块化 JS | DOM 操作、工具函数 |
| Frontend E2E | Playwright + page.route() mock API | UI 交互、SSE 渲染 |

### 禁止项

- 禁止先写实现再补测试
- 禁止 @SpringBootTest 用于纯逻辑测试（用 standalone MockMvc）
- 禁止测试间共享状态（每个测试 @BeforeEach 重置）
- 禁止 mock 被测对象本身（mock 依赖，不 mock 被测类）
- 禁止 --no-verify 旁路（仅限生产事故紧急修复）

### 覆盖率

- JaCoCo ratchet 模式：core ≥ 0.72 / starter ≥ 0.73，只升不降
- 每个 public 方法至少 1 正常路径 + 1 边界/错误路径
- Controller 端点必须有：正常 200 + 错误（400/404/403/429）+ 认证（401/403）
- 发版前必须 `mvn clean verify`

### Git Hooks

安装：`./scripts/install-hooks.sh`

- **pre-commit**: bugfix/feature 提交必须有测试文件变更，否则阻止提交
- **commit-msg**: 强制 Conventional Commits 格式（feat/fix/test/docs/refactor/chore/build）
- **main 分支禁止 WIP 提交**

### TDD 规格文件

测试规格在 `docs/tdd/01-agent-engine/` 至 `docs/tdd/12-codegraph/`，每个模块包含已有测试覆盖、E2E 关键路径、测试缺口（GAP）。新增功能前先更新对应 TDD 规格文件。

## 安全红线

- **SqlGuard** 是最关键的安全组件：只允许 SELECT/SHOW/DESCRIBE/EXPLAIN/WITH，强制 LIMIT，拒绝多语句和所有写操作
- 修改 SqlGuard 必须同时更新 `SqlGuardTest`（参数化测试覆盖所有拒绝/通过场景）
- `SecurityGateway` SPI 让宿主应用控制鉴权，库本身不做认证
- 内部 Pod 间端点 `/snap-agent-internal/**` 仅校验共享 token，宿主必须放行此路径

## SSE 流式架构

- `AgentTask.streamQueue`（LinkedBlockingQueue, capacity 2000）是实时 SSE 推送的核心
- `SnapAgentController` 先重放已有 transcript（最多 200 条），再轮询 queue 推送实时事件
- `done` 和 `error` transcript 事件在 SSE 中被跳过：`done` 作为终止 SSE 事件单独发送，`error` 会触发 EventSource 内置 error 事件导致自动重连
- `task_error` 是自定义 SSE 事件名，用于传递任务错误信息

## 关键设计决策

- **仅内存状态**：TaskStore 是 ConcurrentHashMap，无持久化，重启即丢失
- **线程池共享**：`snapAgentExecutor`（core=2, max=4, queue=10）同时用于 Agent 执行和 SSE 流推送
- **ToolProvider 自动发现**：所有 `ToolProvider` bean 被 `ToolDispatcher` 收集，自定义工具加 `@Component` 即可
- **跨 Pod 路由**：降级链 k8s-api → headless-dns → static → none，peer URL 为 `http://{ip}:{port}`（根路径，不含 basePath）

## 调试技巧

- 前端问题：先查浏览器 Console 是否加载了正确版本的 `app.js`（看 `console.log` 版本标记）
- SSE 断连：查服务端日志 `Stream error for task ... : Broken pipe`，通常是客户端关闭连接
- LLM 超时：检查 `snap-agent.llm.timeout-seconds` 和网络到 LLM API 的连通性
- 任务卡住：检查线程池是否耗尽（`snapAgentExecutor` queue 满会拒绝新任务）

## AI 协作输出约束(backend LLM 限制)

当前 backend LLM API 对**输出大报文 size 有限制、对请求超时有限制**。当需要输出大报文(长设计文档 / 大文件 / 多图 Markdown)时,**选择多次分段输出**,不要一次性塞进单条回复:

- ✅ 大文件用 `Write` 建首段 + 文末留 `<!-- CONTINUE -->` 锚点,后续 `Edit` 替换锚点追加新段(锚点 = 下一段起点,最后一段删锚点)
- ✅ 单条工具调用(Write/Edit)内容控制在 size 上限内,超长会触发超时/截断
- ✅ 先出骨架 + 锚点,再逐段填肉,每段独立成稿可验证
- ⚠️ 不要硬塞——一次性大报文会被 backend 截断/超时,反而返工
