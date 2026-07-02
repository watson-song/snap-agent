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
3. **测试基线**：351 tests（core 130 + starter 221），jacoco 行覆盖率 ≥ 0.85
4. **Stale JAR 问题**：Spring Boot fat JAR 嵌套 starter JAR，改了 starter 代码后必须 `mvn clean install` starter 再重新 `mvn clean package` demo

## 代码约定

- **Java**: `javax.servlet`（非 `jakarta`），`@ConditionalOnProperty` 控制 Bean 装配
- **前端**: 纯 vanilla JS，无构建工具，无 npm 依赖。`app.js` 改动后需递增 `index.html` 中的 `?v=N` 缓存破坏参数
- **测试**: JUnit 5 + AssertJ + Mockito，测试类与源码同包结构
- **Git**: 提交信息用英文或中英混合，不要 `--no-verify`

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
