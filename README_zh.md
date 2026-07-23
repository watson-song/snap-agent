# SnapAgent

> 嵌入式 AI 技能框架 — 一键为 Spring Boot 应用接入 Agent 能力，用 Markdown 定义技能，用自然语言驱动。

[![Java 8](https://img.shields.io/badge/Java-8-orange.svg)](https://adoptium.net/)
[![Spring Boot 2.5.15](https://img.shields.io/badge/Spring%20Boot-2.5.15-green.svg)](https://spring.io/projects/spring-boot)
[![Version](https://img.shields.io/badge/version-0.5.0-blue.svg)]()
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | [中文](README_zh.md)

## 为什么叫 SnapAgent

> Snap = 快照 + 喀嚓（拟声词）

- **Snap** — 像"喀嚓"一声拍照，**一键**嵌入、**即拍即得**。没有脚手架、没有生成器、没有独立服务，加一个依赖就完成接入。
- **Agent** — 核心：LLM 多轮推理 + 工具调用 + 技能驱动。
- 合起来：**SnapAgent** = 即插即用的 AI 技能代理。读音干脆利落，不带任何业务领域色彩。

## 这是什么

SnapAgent 是一个**嵌入式 AI 技能框架**。它让你在任何现有或新的 Spring Boot 项目中，通过添加一个 Maven 依赖 + 几行配置，立刻获得：

- **Agent 对话能力** — 用户用自然语言提问，Agent 理解意图、调用工具、流式输出结果
- **技能系统** — 用 Markdown 编写技能文件，定义 Agent 在特定场景下的行为流程，丢进目录即生效
- **工具生态** — 内置数据库只读查询、Redis 读取等工具；实现 `ToolProvider` 接口即可扩展任意工具
- **开箱即用 UI** — Chat 风格 SPA，SSE 实时流式输出，无需前端构建
- **安全护栏** — `SqlGuard` 强制只读、限流、审计；`SecurityGateway` SPI 对接宿主鉴权

### 不只是查库

"查数据库"只是当前内置的一个工具。框架的设计是**技能驱动 + 工具可扩展**的通用 Agent 平台：

| 场景 | 怎么用 |
|------|--------|
| **业务数据诊断** | 编写 Skill 引导 Agent 查库分析，如"某 SKU 为什么没生成补货策略" |
| **运营诊断** | Skill 定义运营排查流程，Agent 按流程查指标、看趋势、定位异常 |
| **代码分析** | 开发 `CodeReaderToolProvider`，让 Agent 读懂项目代码，回答"这个接口在哪实现" |
| **线上监控** | 开发 `MetricsToolProvider`，Agent 实时查 Prometheus/Grafana 指标，分析健康度 |
| **异常诊断 & Bugfix** | 开发 `LogAnalysisToolProvider`，Agent 分析异常日志，定位根因，推送修复建议 |
| **任何自定义场景** | 实现 `ToolProvider` + 编写 Skill Markdown，Agent 即刻获得新能力 |

## 快速开始

### 1. 安装

**方式 A — 一键安装（从 GitHub Release 下载预构建 JAR）：**

```bash
bash <(curl -sL https://github.com/watson-song/snap-agent/releases/download/v0.5.0/install.sh)
```

**方式 B — 从源码构建：**

```bash
git clone https://github.com/watson-song/snap-agent.git && cd snap-agent && mvn clean install -DskipTests
```

### 2. 添加依赖

```xml
<dependency>
    <groupId>cn.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>0.5.0</version>
</dependency>
```

### 3. 配置

```yaml
snap-agent:
  enabled: true
  llm:
    base-url: https://api.anthropic.com
    api-key: ${LLM_API_KEY}
    model: claude-sonnet-4-6
  jdbc:
    enabled: true          # 数据库只读查询
  redis:
    enabled: true          # Redis 只读查询
```

### 4. 提供只读 DataSource（如启用 JDBC 工具）

```java
@Bean
public DataSource snapAgentReadOnlyDataSource() {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl("jdbc:mysql://your-db:3306/your_schema");
    ds.setUsername("readonly_user");
    ds.setPassword("readonly_pass");
    ds.setPoolName("snap-agent-jdbc");
    ds.setReadOnly(true);
    return ds;
}
```

### 5. 编写第一个 Skill

在 `upload-skills-dir` 目录（默认 `/tmp/snap-agent-skills`）下创建 `my-skill.md`。技能可以是独立的 `.md` 文件，也可以是包含 `SKILL.md` + 辅助文件的目录：

```markdown
---
name: my-skill
description: "描述这个技能做什么、何时使用。LLM 根据这段描述决定何时激活此技能。"
---

# 技能流程

## Step 1: 收集信息
向用户确认必要参数...

## Step 2: 执行分析
使用 query_database 工具查询数据...
或使用你自定义的工具...

## Step 3: 输出结论
...
```

### 6. 启动 & 使用

打开 `http://localhost:8080/snap-agent/`，选择 Skill，输入问题，Agent 开始工作。

### 7. 启用页面锚点问答（可选，v0.4）

在需要支持上下文问答的宿主页面引入锚点脚本：

```html
<script src="/snap-agent/anchor.js" defer></script>
```

用 `data-snap-anchor` 标注页面区域：

```html
<section data-snap-anchor="商品概览" data-snap-skill="auto">
  <h2>商品概览</h2>
  <table>...</table>
</section>
```

用户点击紫色 💬 图标 → 右侧抽屉滑出 → 针对该区域内容发起 LLM 问答，无需离开当前页面。抽屉显示内容摘要 subtitle、当前技能名 + 描述、右侧两角圆角。

**关键步骤**：如果宿主应用没有 Spring Security / Shiro，需要提供一个 `SecurityGateway` Bean，否则 `anchor.js` 会静默不渲染图标：

```java
@Configuration
public class MySecurityGateway implements SecurityGateway {
    @Override public String currentUserId() { return "demo-user"; }
    @Override public boolean hasPermission(String code) { return true; }
}
```

完整接入流程（SPA 兼容、移动端、配置）见 [锚点问答接入指南](docs/site/integration/zh/anchor-feature-guide.md)。

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                   宿主 Spring Boot App                     │
│                                                          │
│   ┌──────────────────────────────────────────────────┐    │
│   │            SnapAgent Framework                  │    │
│   │                                                  │    │
│   │   ┌────────────┐    ┌──────────────────┐        │    │
│   │   │  Web UI    │    │  AgentExecutor   │        │    │
│   │   │  (SPA+SSE) │────│  (Turn Loop)     │        │    │
│   │   └────────────┘    └────────┬─────────┘        │    │
│   │   ┌────────────┐    ┌────────┴─────────┐        │    │
│   │   │ SkillReg   │    │  LLM Client      │        │    │
│   │   │ (.md→Skill)│    │  (Streaming)     │        │    │
│   │   └────────────┘    └──────────────────┘        │    │
│   │   ┌──────────────────────────────────────┐      │    │
│   │   │       ToolDispatcher (SPI)            │      │    │
│   │   │  ┌────────────┐ ┌──────────────┐     │      │    │
│   │   │  │ JDBC Query │ │ Redis Read   │     │      │    │
│   │   │  │ + SqlGuard │ │              │     │      │    │
│   │   │  └────────────┘ └──────────────┘     │      │    │
│   │   │  ┌────────────┐ ┌──────────────┐     │      │    │
│   │   │  │ Code Reader│ │ Log Analyzer │     │      │    │
│   │   │  │ (planned)  │ │ (planned)    │     │      │    │
│   │   │  └────────────┘ └──────────────┘     │      │    │
│   │   │  ┌────────────────────────────────┐  │      │    │
│   │   │  │  Your Custom ToolProvider     │  │      │    │
│   │   │  │  (just @Component)            │  │      │    │
│   │   │  └────────────────────────────────┘  │      │    │
│   │   └──────────────────────────────────────┘      │    │
│   │                                                  │    │
│   │   snap-agent-core (SPI) ────────────────────────│    │
│   └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

**核心设计：**

- **Skill（技能）** = Markdown 文件，定义 Agent 在特定场景的行为流程。YAML frontmatter 描述元数据，body 描述步骤。LLM 根据用户问题自动选择匹配的 Skill。
- **Tool（工具）** = `ToolProvider` SPI 实现，给 Agent 提供与外部系统交互的能力。内置 JDBC/Redis，可无限扩展。
- **Agent（执行器）** = `AgentExecutor` 多轮循环：LLM 思考 → 调用工具 → 获取结果 → 继续思考 → 输出结论。全程 SSE 流式推送。
- **Framework（框架）** = Starter 自动装配一切：Controller、Filter、线程池、安全适配、跨 Pod 路由。`enabled=false` 时零影响。

## 核心特性

| 特性 | 说明 |
|------|------|
| **技能驱动** | 两层：内置 (classpath) + 可上传 (文件系统，重启后持久化)。Markdown 定义 Agent 行为，无需写代码，丢文件即生效 |
| **工具可扩展** | `ToolProvider` SPI + `@Component` 自动发现，内置 JDBC/Redis |
| **SSE 实时流** | token 级推送思考过程，用户看着 Agent 一步步推导 |
| **安全护栏** | SqlGuard 只读强制 + 限流 + 审计 transcript |
| **安全适配** | 自动检测 Spring Security / Shiro，或自定义 `PrincipalResolver` |
| **零影响** | `enabled=false` 时不创建任何 Bean、Filter、线程池 |
| **跨 Pod 路由** | K8s 多实例 SSE 中继，自动发现持有任务的 Pod |
| **Chat SPA** | 开箱即用 Web UI，Markdown 渲染，无构建工具 |
| **页面锚点问答**（v0.4） | 宿主页面引入 `<script src="/snap-agent/anchor.js">` + 用 `data-snap-anchor` 标注区域 → 用户点击 💬 → 右侧抽屉即时问答，支持智能技能路由、预摘要缓存、Shadow DOM 隔离 |

## 配置参考

```yaml
snap-agent:
  enabled: true                          # 总开关，默认 false
  base-path: /snap-agent                 # URL 前缀
  builtin-skills-dir: classpath:/docs/skills/    # 只读，打包在 JAR 中
  upload-skills-dir: /tmp/snap-agent-skills       # 读写，重启后持久化
  llm:
    base-url: https://api.anthropic.com
    api-key: ""                          # 推荐用环境变量 ${LLM_API_KEY}
    auth-token: ""                       # 或 Auth Token
    model: claude-sonnet-4-6
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true
  agent:
    max-turns: 20                        # Agent 最大对话轮数
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 1
    max-runs-per-hour: 20
    max-result-rows: 1000                # 工具结果行数上限
  jdbc:
    enabled: true
    datasource-bean-name: snapAgentReadOnlyDataSource
  redis:
    enabled: true
    redis-template-bean-name: redisTemplate
  security:
    framework: auto                      # auto / spring-security / shiro / custom
    audit-log: true
  routing:
    mode: none                           # none / static / k8s-api / headless-dns
  anchor:                                # v0.4：页面锚点问答
    enabled: true                        # 默认 true；false 关闭 anchor.js 和 AnchorOrchestrator
    disabled-paths:                      # 黑名单路径（不扫描）
      - "/payment/**"
    max-context-chars: 8000              # 单次发送给 LLM 的最大字符数
    preprocess-enabled: true             # 点击锚点时预摘要 + 预分类
    summary-threshold-chars: 4000        # 短内容跳过摘要
    summary-cache-ttl-seconds: 600       # Caffeine LRU 缓存 TTL
    classifier-model: ""                 # 空字符串 = 用默认模型；可用便宜模型降本
    classifier-confidence-threshold: 0.5 # 低于此值降级为通用 LLM 直答
```

## 扩展：自定义工具

```java
@Component
public class HttpCallToolProvider implements ToolProvider {
    @Override
    public String name() { return "http_call"; }

    @Override
    public String schema() {
        return "{\"name\":\"http_call\","
             + "\"description\":\"Calls an HTTP endpoint\","
             + "\"input_schema\":{\"type\":\"object\","
             + "\"properties\":{\"url\":{\"type\":\"string\"}},"
             + "\"required\":[\"url\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        // 你的逻辑
        return ToolResult.success("result", 0, 42);
    }
}
```

工具自动被 `ToolDispatcher` 收集，LLM 可在 Skill 执行中按需调用。

## API 端点

| Method | Path | 说明 |
|--------|------|------|
| GET | `/snap-agent/` | Web UI (SPA) |
| GET | `/snap-agent/anchor.js` | 锚点问答脚本（静态资源，v0.4） |
| GET | `/snap-agent/anchor/config` | 锚点功能配置（公开） |
| POST | `/snap-agent/anchor/preprocess` | 点击锚点时预摘要 + 预分类 |
| GET | `/snap-agent/skills` | 列出所有技能 |
| GET | `/snap-agent/models` | 列出可用模型 |
| GET | `/snap-agent/tools` | 列出可用工具 |
| POST | `/snap-agent/runs` | 创建 Agent 任务（用 `skillId: "auto"` + `anchor` 字段触发锚点问答） |
| GET | `/snap-agent/runs/{id}` | 查询任务状态 |
| GET | `/snap-agent/runs/{id}/stream` | SSE 实时流 |
| GET | `/snap-agent/runs/{id}/transcript` | 完整 transcript |
| POST | `/snap-agent/skills/refresh` | 刷新技能目录 |
| POST | `/snap-agent/skills/upload` | 上传 .md / .zip |
| DELETE | `/snap-agent/skills/{name}` | 删除自定义技能（如覆盖了内置技能则恢复内置） |

## 路线图

### v0.1-alpha

- 嵌入式框架核心：AgentExecutor 多轮循环 + LLM 流式 + SSE 推送
- Skill Markdown 系统：YAML frontmatter + 步骤式 body
- 内置工具：JDBC 只读查询 (SqlGuard) + Redis 只读
- 安全适配：Spring Security / Shiro 自动检测
- 跨 Pod SSE 中继：K8s API / Headless DNS / Static
- Chat SPA + Markdown 渲染

### v0.2 — 框架增强

- MCP 协议接入：远程工具以 `mcp__{server}__{tool}` 注册
- 任务取消：`POST /runs/{id}/cancel`
- 报告导出：`GET /runs/{id}/report?format=md`
- Skill 热重载：WatchService 监听文件变更
- 审计持久化：ring-buffer → 可选 DB 表

### v0.3 — 代码理解能力

- **CodeReaderToolProvider**：让 Agent 读取项目源码，回答"这个接口在哪实现"
- **项目上下文注入**：Agent 启动时扫描项目结构，作为系统提示注入 LLM
- **Skill 自动生成**：Agent 分析项目代码 + 数据库结构，自动生成候选 Skill

### v0.4 — 页面锚点问答 + 运营诊断能力（当前版本）

- **页面锚点问答**：宿主页面引入 `anchor.js` → 用户点击 💬 图标 → 右侧抽屉即时问答
  - 智能技能路由（`AnchorSkillClassifier`）+ 置信度降级
  - 预摘要 + 缓存（`AnchorContextSummarizer` + Caffeine LRU）降低首 token 延迟
  - Shadow DOM 隔离、右侧圆角、内容摘要 subtitle、技能信息条
  - `data-snap-anchor` + `data-snap-skill="auto|<name>|off"` 标注 API
- **运营诊断 Skill**：`ops-health-check`、`slow-query-analysis`、`error-spike-investigation`、`config-diff`
- **MetricsToolProvider**：对接 Prometheus / Grafana，Agent 实时查询指标
- **LogAnalysisToolProvider**：对接 ELK / Loki，Agent 分析日志模式、定位异常
- **TraceAnalysisToolProvider**：对接 Jaeger / SkyWalking，Agent 分析调用链瓶颈

### v0.5 — 主动监控 & 智能推送

- **异常监听**：对接消息队列（Kafka/RabbitMQ），消费异常事件，自动触发诊断
- **健康巡检**：定时执行健康检查 Skill，发现异常自动生成诊断报告
- **Bugfix 推送**：Agent 诊断后结合代码分析，生成修复建议（diff 级别），推送到 Issue/工单系统
- **告警收敛**：同类异常聚合，减少噪声告警，附带根因分析

### v0.6 — 平台化

- Spring Boot 3.x 支持（`jakarta.servlet`）
- OpenAI / 其他 LLM 适配器
- 多环境数据源切换（sit/uat/prod）
- 任务历史 & 搜索
- Skill 市场：社区共享 Skill 模板
- 插件化工具市场：开箱即用的 ToolProvider 扩展包

## 测试

```bash
# 全量测试 + 覆盖率
mvn clean verify

# 仅 core 模块
mvn test -pl snap-agent-core

# 仅 SqlGuard 测试
mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=SqlGuardTest

# 仅锚点 E2E 测试
mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=AnchorE2ETest
```

## 文档

- [用户手册（英文）](docs/site/manual/en/user-manual.md) | [中文](docs/site/manual/zh/user-manual.md)
- [宿主集成指南（英文）](docs/site/integration/en/host-integration-guide.md) | [中文](docs/site/integration/zh/host-integration-guide.md)
- [锚点问答接入指南（英文）](docs/site/integration/en/anchor-feature-guide.md) | [中文](docs/site/integration/zh/anchor-feature-guide.md)
- [系统架构总览（英文）](docs/site/architecture/en/system-architecture.md) | [中文](docs/site/architecture/zh/system-architecture.md)

## 技术栈

- Java 8 / Spring Boot 2.5.15 / javax.servlet
- OkHttp 3 (LLM streaming) / Jackson / HikariCP
- JUnit 5 / AssertJ / Mockito / JaCoCo (覆盖率 >= 85%)
- Vanilla JS + SSE (前端，无构建工具)

## License

Apache License 2.0 — 见 [LICENSE](LICENSE)。
