# SnapAgent Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write 9 bilingual (Chinese + English) technical documentation files covering all SnapAgent v0.1-v1.0 modules, placed in `docs/site/`.

**Architecture:** Each document is self-contained Markdown describing the current v1.0 state (not design specs). Chinese is the primary language; English mirrors the same structure. Documents are verified against actual source code before commit.

**Tech Stack:** Markdown only. Source code references in `snap-agent-core/` and `snap-agent-spring-boot-2x-starter/`.

---

## File Structure

```
docs/site/
├── README.md                                    # Index page
├── architecture/{en,zh}/system-architecture.md  # Doc 1
├── search/{en,zh}/knowledge-search.md             # Doc 2
├── integration/{en,zh}/host-integration-guide.md # Doc 3
├── deployment/{en,zh}/multi-cluster-architecture.md # Doc 4
├── plugins/{en,zh}/tool-plugin-architecture.md   # Doc 5
├── workflow/{en,zh}/workflow-engine-architecture.md # Doc 6
├── issue/{en,zh}/issue-closure-architecture.md    # Doc 7
├── proactive/{en,zh}/proactive-monitoring-architecture.md # Doc 8
└── manual/{en,zh}/user-manual.md                  # Doc 9
```

---

### Task 0: Create directory structure

**Files:**
- Create: `docs/site/` and all subdirectories

- [ ] **Step 1: Create all directories**

```bash
mkdir -p docs/site/{architecture,search,integration,deployment,plugins,workflow,issue,proactive,manual}/{en,zh}
```

- [ ] **Step 2: Create README.md index**

Write `docs/site/README.md` with a table linking to all 9 documents in both languages.

- [ ] **Step 3: Commit**

```bash
git add docs/site/
git commit -m "docs: create docs/site/ directory structure with index"
```

---

### Task 1: System Architecture (Doc 1)

**Files:**
- Create: `docs/site/architecture/zh/system-architecture.md`
- Create: `docs/site/architecture/en/system-architecture.md`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/` (all packages)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/` (all packages)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java`
- Reference: `docs/embeed-skills-agent/01-architecture.md` (existing design doc for reference)

- [ ] **Step 1: Write Chinese version**

Content sections:
1. 模块拆分 (core + starter + client, javax/jakarta 不兼容原因)
2. 组件清单 (所有 v0.1-v1.0 组件, 按模块分组列表)
3. 数据流 (用户输入 → Skill选择 → LLM循环 → 工具分发 → SSE流式 → 报告)
4. AutoConfiguration 条件装配 (enabled=false 零影响证明)
5. SPI 扩展点 (所有接口 + 职责)
6. 知识编排层 (预注入 + per-turn注入 + 工具检索)
7. 依赖治理 (optional deps, BOM管控)
8. 两层 Skill 系统 (builtin classpath + uploadable filesystem)
9. 会话历史持久化 (ConversationStore SPI + FileConversationStore)
10. 安全权限模型 (SecurityGateway + PrincipalResolver + SpringSecurity/Shiro Adapter)

- [ ] **Step 2: Write English version** (mirror same structure)

- [ ] **Step 3: Commit**

```bash
git add docs/site/architecture/
git commit -m "docs: system architecture documentation (bilingual)"
```

---

### Task 2: Knowledge Search Algorithm (Doc 2)

**Files:**
- Create: `docs/site/search/zh/knowledge-search.md`
- Create: `docs/site/search/en/knowledge-search.md`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/knowledge/SimpleKeywordSearcher.java`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/knowledge/{KnowledgeBase,KnowledgeSearcher,KnowledgeFragment,KnowledgeSource,SearchResult}.java`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/knowledge/{MarkdownKnowledgeSource,KnowledgeInjector}.java`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/KnowledgeController.java`

- [ ] **Step 1: Write Chinese version**

Content sections:
1. 架构概览 (KnowledgeBase → KnowledgeSource → KnowledgeSearcher SPI)
2. 分词算法 (Latin空格分割+CJK 2-gram bigram, 混合文本处理, 代码示例)
3. 打分公式 (`score = (titleHits×2 + contentHits) / (queryTokenCount×2)`, clamp [0,1])
4. 检索示例 ("数据库"→1.0+0.33, "snapagent"→1.0+0.50, "zzzzzz"→0)
5. minScore 阈值机制 (配置项, KnowledgeBase.search() 过滤, 历史bug: 硬编码0.0已修复)
6. searchWithScores() 方法 (SearchResult类, 返回分数用于UI展示)
7. KnowledgeInjector 自动注入流程 (SystemPromptExtender SPI → 检索top-K → 注入system prompt)
8. REST API (GET /knowledge/status, GET /knowledge/search?q=xxx)
9. 已知限制 (英文大小写敏感, 无语义搜索, 无向量嵌入, 计划v0.7.2)
10. 扩展点 (自定义KnowledgeSearcher, 自定义KnowledgeSource)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/search/
git commit -m "docs: knowledge search algorithm documentation (bilingual)"
```

---

### Task 3: Host Integration Guide (Doc 3)

**Files:**
- Create: `docs/site/integration/zh/host-integration-guide.md`
- Create: `docs/site/integration/en/host-integration-guide.md`
- Reference: `docs/embeed-skills-agent/09-integration-guide.md` (existing, outdated)
- Reference: `docs/embeed-skills-agent/07-config-security.md` (existing config reference)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentProperties.java`

- [ ] **Step 1: Write Chinese version**

Content sections:
1. 5步快速接入 (pom → yml → 只读DataSource → 安全放行 → 可选PrincipalResolver)
2. 完整配置参考 (snap-agent.* 全量配置树, 每个字段说明)
3. 安全适配 (Spring Security/Shiro自动检测, 自定义SecurityGateway, 权限坑: principal vs GrantedAuthority)
4. 自定义扩展 (ToolProvider @Component, SystemPromptExtender, KnowledgeSearcher, CodeGraphBuilder, IssueTracker, CostStore, ConversationStore)
5. 多环境数据源配置 (datasources Map, env参数)
6. Skill 编写指南 (frontmatter, body, inputs, tools契约, availability)
7. 故障排查 (常见问题+解决方案)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/integration/
git commit -m "docs: host integration guide (bilingual)"
```

---

### Task 4: Multi-Cluster Deployment (Doc 4)

**Files:**
- Create: `docs/site/deployment/zh/multi-cluster-architecture.md`
- Create: `docs/site/deployment/en/multi-cluster-architecture.md`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/routing/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/InternalTaskController.java`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentProperties.java` (routing section)

- [ ] **Step 1: Write Chinese version**

Content sections:
1. 部署拓扑 (多Pod, 内存状态, 无共享session)
2. 跨Pod SSE中继架构 (请求→本地TaskStore→命中本地流/未命中→PeerSseRelay探测→中继)
3. 内部端点 (/skills-agent-internal/, 独立token, basePath之外)
4. PeerRouter 发现降级链 (k8s-api → headless-dns → static → none, 每级配置+RBAC要求)
5. 自我排除 (MY_POD_IP, K8s downward API)
6. 发现缓存 (TTL配置, stale-OK策略)
7. SSE中继实现 (探测200/404/401, 逐行解析转发, 15s心跳)
8. 生产最佳实践 (副本数, 资源限制, RBAC, SSE代理兼容, Docker限制)
9. 配置参考 (snap-agent.routing.* 全量)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/deployment/
git commit -m "docs: multi-cluster deployment architecture (bilingual)"
```

---

### Task 5: Tool Plugin Architecture (Doc 5)

**Files:**
- Create: `docs/site/plugins/zh/tool-plugin-architecture.md`
- Create: `docs/site/plugins/en/tool-plugin-architecture.md`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/ToolPlugin.java`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/tool/ToolPluginRegistry.java`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/tool/{ToolDispatcher,ToolProvider,ToolContext,ToolResult}.java`
- Reference: All tool provider files in `boot2x/tool/`

- [ ] **Step 1: Write Chinese version**

Content sections:
1. SPI 设计 (ToolPlugin接口: name/version/description/toolNames, 默认方法)
2. ToolPluginRegistry (收集所有ToolPlugin bean, 无条件装配, GET /tools/plugins)
3. 与 ToolProvider 的关系 (ToolProvider+@Component=自动发现(v0.1起), ToolPlugin=可选元数据层, 两者独立)
4. 内置工具清单 (v0.1-v1.0 全部ToolProvider列表, 每个工具的name/schema/用途)
5. ToolDispatcher 装配机制 (收集所有ToolProvider bean)
6. ToolResult 结构 (content/error/rows/truncated, 审计)
7. 自定义ToolPlugin开发指南 (完整代码示例)
8. 未来规划 (独立插件JAR, MCP协议插件, v1.0.1+)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/plugins/
git commit -m "docs: tool plugin architecture (bilingual)"
```

---

### Task 6: Workflow Engine Architecture (Doc 6)

**Files:**
- Create: `docs/site/workflow/zh/workflow-engine-architecture.md`
- Create: `docs/site/workflow/en/workflow-engine-architecture.md`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/workflow/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/workflow/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/resources/docs/workflows/full-diagnose.yml`

- [ ] **Step 1: Write Chinese version**

Content sections:
1. Core SPI (WorkflowStep/WorkflowDefinition/WorkflowResult/WorkflowEngine, 每个类字段说明)
2. YamlWorkflowLoader (SnakeYAML解析, loadAll+load, 文件系统目录读取)
3. SimpleWorkflowEngine (顺序执行, 条件求值, 输入引用解析, onFailure STOP/SKIP/RETRY)
4. 条件表达式语法 (`${step.result}`, `.contains()`, `.size>0`, `${trigger.xxx}`, 完整示例)
5. 输入引用解析 (`${step.result}`和`${trigger.field}`替换, 类型转换)
6. 失败处理策略 (STOP=终止, SKIP=跳过继续, RETRY=重试, 各场景使用建议)
7. REST API (GET /workflows, GET /workflows/{name}, POST /workflows/{name}/run)
8. 内置工作流 (full-diagnose.yml 逐步骤解析)
9. 配置参考 (snap-agent.workflows.{enabled,dir})
10. 未来规划 (循环, 人工审批, cron/事件触发, v1.0.1+)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/workflow/
git commit -m "docs: workflow engine architecture (bilingual)"
```

---

### Task 7: Issue Closure Architecture (Doc 7)

**Files:**
- Create: `docs/site/issue/zh/issue-closure-architecture.md`
- Create: `docs/site/issue/en/issue-closure-architecture.md`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/` (issue endpoints in SkillsAgentController)

- [ ] **Step 1: Write Chinese version**

Content sections:
1. Core SPI (IssueStatus状态机, IssueClosure不可变值对象+withXxx方法, IssueStore持久化SPI, IssueTracker外部跟踪器SPI)
2. Starter实现 (FileIssueStore JSON存储, NoopIssueTracker默认空实现, KnowledgeSedimentationExtractor知识提取)
3. IssueClosureService 编排服务 (proposeSolution→createExternalIssue→verify→close 4阶段)
4. 知识沉淀闭环 (诊断→方案→修复→验证→提取知识→存入KnowledgeBase→反馈循环)
5. REST API (POST /runs/{id}/solution, POST /runs/{id}/issue, GET /issues/{id}, POST /issues/{id}/verify, POST /issues/{id}/close)
6. 内置skills (solution-suggest.md, verify-fix.md)
7. 配置参考 (snap-agent.issue-closure.{enabled,system-user-id,storage-dir,tracker-type})
8. 扩展指南 (实现IssueTracker for Jira/GitHub, 自动PR创建, v0.9.1+)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/issue/
git commit -m "docs: issue closure architecture (bilingual)"
```

---

### Task 8: Proactive Monitoring Architecture (Doc 8)

**Files:**
- Create: `docs/site/proactive/zh/proactive-monitoring-architecture.md`
- Create: `docs/site/proactive/en/proactive-monitoring-architecture.md`
- Reference: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/proactive/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/proactive/` (all files)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/resources/docs/skills/auto-diagnose.md`

- [ ] **Step 1: Write Chinese version**

Content sections:
1. Core SPI (EventSource/EventConsumer/AnomalyEvent/PushChannel/DiagnosticReport, 每个接口/类说明)
2. AlertConverger (ConcurrentHashMap滑动窗口, key=service:type, windowMinutes去重, maxAlertsPerWindow防风暴)
3. TrendPredictor (最小二乘线性回归, predict()方法, exceedsThreshold检查, <3点返回null)
4. AnomalyEventListener (实现EventConsumer: 收敛→查auto-diagnose skill→AgentTask→异步执行→DiagnosticReport→push)
5. ScheduledHealthChecker 巡检任务 (daemon ScheduledExecutor, 定时ops-health-check skill, 异常关键词检测→push)
6. NoopEventSource (默认事件源, type="noop")
7. WebhookPushChannel (extends ObservabilityHttpClient, httpPost JSON)
8. 生命周期管理 (SmartLifecycle bean ProactiveLifecycle: start/stop)
9. 告警收敛设计 (滑动窗口去重, 防风暴, 收敛告警内容: count/first/last/rootCause)
10. 巡检任务设计 (定时cron, skill驱动, 异常检测关键词, severity分类, auto-push)
11. 趋势预测设计 (数据点收集, 线性回归, 阈值预测, 提前预警)
12. 配置参考 (snap-agent.proactive.* 全量)
13. 内置skill (auto-diagnose.md 6阶段)
14. REST API (GET /patrol/tasks, GET /patrol/reports, GET /alerts)
15. 扩展指南 (KafkaEventSource/RabbitMqEventSource, DingTalkPushChannel/JiraPushChannel, v0.5.1+)

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/proactive/
git commit -m "docs: proactive monitoring architecture (bilingual)"
```

---

### Task 9: User Manual (Doc 9)

**Files:**
- Create: `docs/site/manual/zh/user-manual.md`
- Create: `docs/site/manual/en/user-manual.md`
- Reference: `snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/app.js` (UI behavior)
- Reference: `snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/index.html` (UI structure)
- Reference: All previous docs (1-8) for feature descriptions

- [ ] **Step 1: Write Chinese version**

Content sections (Part 1 — 运维用户指南):
1. 访问SnapAgent (URL, 认证方式)
2. UI导览 (侧边栏/host+builtin分区/禁用skill切换/详情按钮, 顶栏/模型选择/用户信息/环境, 聊天区/欢迎页/消息/时间戳, 输入栏/快捷栏/表单/发送取消, 功能导航栏7个按钮)
3. 选择和运行Skill
4. 查看Agent思考过程 (SSE流式)
5. 工具调用和结果展示
6. 取消运行中的任务
7. 会话历史 (保存/加载/下载/删除)
8. 功能面板详解:
   - 🔧 工具&插件 (查看注册的工具和schema, 查看已安装插件)
   - 📋 工作流 (列表, 步骤详情, 手动运行, 监控执行, 条件求值, 失败处理)
   - 💰 成本看板 (7天汇总, 用户/Skill维度, 预算使用率, 计价模型)
   - 🐛 问题闭环 (提出方案, 创建Issue, 验证修复, 关闭+知识沉淀, 生命周期跟踪)
   - 🛡️ 巡检任务 (查看定时任务, 巡检报告, severity分类, 配置巡检)
   - 🔔 告警 (活跃告警, 收敛信息count/first/last, 解决告警, webhook配置)
   - 📚 知识库 (状态, 数据源, 实时搜索, 相关度分数, 自动注入说明)
9. 上传Skill (文件/文件夹/zip)
10. 模型选择和切换
11. 环境Profile感知

Content sections (Part 2 — 开发者指南):
1. 自定义ToolProvider开发 (完整代码示例)
2. 自定义SystemPromptExtender
3. 实现IssueTracker (Jira/GitHub, createIssue/updateStatus/getIssueUrl)
4. 实现CostStore (DB-backed)
5. 编写Workflow YAML (步骤定义, 条件语法, 输入引用, 失败策略, full-diagnose示例)
6. MCP集成 (外部工具服务器)
7. 自定义KnowledgeSource
8. ConversationStore替换 (DB-backed)
9. 自定义EventSource (Kafka/RabbitMQ异常事件)
10. 自定义PushChannel (钉钉/Jira/邮件)
11. 主动监控配置 (巡检定时, 告警收敛, 趋势预测, webhook)
12. 自定义CodeGraphBuilder (AST)
13. Skill Markdown编写最佳实践

- [ ] **Step 2: Write English version**

- [ ] **Step 3: Commit**

```bash
git add docs/site/manual/
git commit -m "docs: user manual — ops guide + developer guide (bilingual)"
```

---

## Self-Review

**1. Spec coverage:** All 9 topics from the spec have corresponding tasks. ✅

**2. Placeholder scan:** No TBD/TODO. Each task has concrete content sections referencing actual source files. ✅

**3. Consistency:** File paths match the spec's directory structure. Document numbering matches spec. ✅

## Execution Notes

- Tasks 1-8 are independent (different topics, different directories) — can be parallelized via subagents.
- Task 9 (User Manual) depends on all prior tasks for content references — should run last.
- Each task writes Chinese first, then English (Chinese is primary).
- Verify document content against actual source code (don't describe features that don't exist).
