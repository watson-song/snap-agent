# SnapAgent 完整测试计划 (Test Plan)

> 版本: 1.0 | 日期: 2026-07-26
> 覆盖范围: 12 个 TDD 模块 + E2E 用例 + Demo API 全端点
> 测试层级: Unit (Mockito) → Integration (MockMvc + Spring) → E2E (Chrome DevTools + Shell)

---

## 0. 测试环境

| 项目 | 值 |
|------|-----|
| JDK | Corretto 1.8.0_492 |
| Maven | 3.9.15 |
| Spring Boot | 2.5.15 |
| Demo 端口 | 8080 |
| Demo Base Path | /snap-agent |
| 认证 | HTTP Basic (demo/demo) |
| 权限 | snap-agent:access |
| LLM | aliyun/glm-5.2 (via claudecode.sf-express.com) |
| Build | `JAVA_HOME="..." mvn test -pl <module> -f pom.xml` |

### 模块依赖关系

```
snap-agent-core (0.6.0-SNAPSHOT) — 546 tests
  └── snap-agent-spring-boot-2x-starter — 1361 tests
        └── snap-agent-demo
```

---

## 1. 测试矩阵总览

| 模块 | TDD UC 数 | Unit Tests | Integration Tests | E2E Tests | 优先级 |
|------|----------|------------|-------------------|-----------|--------|
| 01-agent-engine | 54+6 | 54 | 6 | 4 | P0 |
| 02-skill-system | 21+10 | 21 | 10 | 3 | P0 |
| 03-tool-dispatcher | 26+2 | 26 | 2 | 2 | P0 |
| 04-anchor-qa | 15+5 | 15 | 5 | 3 | P0 |
| 05-anchor-inject | 12+4 | 12 | 4 | 3 | P1 |
| 06-knowledge | 10+5 | 10 | 5 | 2 | P1 |
| 07-workflow | 8+3 | 8 | 3 | 2 | P1 |
| 08-patrol-alert | 12+5 | 12 | 5 | 3 | P1 |
| 09-plugin-mcp | 9+0 | 9 | 0 | 3 | P1 |
| 10-cost-security | 25+5 | 25 | 5 | 4 | P0 |
| 11-host-integration | 30+12 | 30 | 12 | 5 | P0 |
| 12-codegraph | 35+0 | 35 | 0 | 2 | P1 |
| **总计** | **257+57** | **257** | **57** | **36** | — |

> 实际构建测试数: **1907** (snap-agent-core: 546 + snap-agent-spring-boot-2x-starter: 1361)

---

## 2. 模块测试详情

### 2.1 Module 01: Agent Engine (P0)

#### 2.1.1 Unit Tests (54)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~04 | GraphState immutable/get/default/nextTurn/serialize | GraphStateTest | ✅已有 |
| UC-05~07 | Node.execute/Edge/ConditionalEdge/EdgeCondition | StateGraphTest | ✅已有 |
| UC-08~11 | StateGraph chain build/conditional/entryPoint/compile validation | StateGraphTest | ✅已有 |
| UC-12~18 | GraphExecutor: SUCCEEDED/loop/PAUSED/FAILED/CANCELLED/TIMEOUT/degrade | GraphExecutorTest | ✅已有 |
| UC-19~23 | CheckpointStore save/load/list/delete + Sqlite/Redis | GraphRuntimeTest | ✅已有 |
| UC-24~26 | ReActGraphFactory topology/AdvisorNode/empty | ReActGraphFactoryTest | ✅已有 |
| UC-27~30 | EntryNode prompt order/inputs/injection defense/Advisor chain | GraphRuntimeTest | ✅已有 |
| UC-31~35 | AgentNode streaming/tools/RAG/structured output/max_tokens | GraphRuntimeTest | ✅已有 |
| UC-36~39 | ToolsNode success/truncation/exception/parallel | GraphRuntimeTest | ✅已有 |
| UC-40~43 | ShouldContinue end_turn/tool_use/max_tokens/error | GraphRuntimeTest | ✅已有 |
| UC-44~48 | @ToolApproval interrupt/PAUSED/resume approved/resume rejected/no pause | GraphRuntimeTest | ⚠需补 |
| UC-49~54 | TaskStatus PENDING→RUNNING→SUCCEEDED/PAUSED/terminal | TaskStatusTest | ✅已有 |

#### 2.1.2 Integration Tests (6)

| UC ID | 场景 | 端点 | 状态 |
|-------|------|------|------|
| UC-R1 | POST /runs → 202 async | POST /snap-agent/runs | ✅已有 |
| UC-R2 | GET /runs/{id}/stream → SSE | GET /snap-agent/runs/{id}/stream | ✅已有 |
| UC-R3 | POST /runs/{id}/resume → HITL | POST /snap-agent/runs/{id}/resume | ⚠GAP |
| UC-R4 | POST /runs/{id}/interrupt | POST /snap-agent/runs/{id}/interrupt | ⚠GAP |
| UC-R5 | GET /runs/{id}/checkpoints | GET /snap-agent/runs/{id}/checkpoints | ⚠GAP |
| UC-R6 | POST /runs/{id}/replay | POST /snap-agent/runs/{id}/replay | ⚠GAP |

#### 2.1.3 E2E Tests (Chrome DevTools)

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-AE-1 | 创建任务并查看 SSE 流 | 1. POST /snap-agent/runs (skillId=health-check, inputs={message:"hello"}) 2. 获取 taskId 3. GET /snap-agent/runs/{id}/stream | SSE 事件含 thought/done, status=SUCCEEDED |
| E2E-AE-2 | 任务列表与详情 | 1. GET /snap-agent/runs 2. GET /snap-agent/runs/{id} | 列表含 taskId, 详情含 transcript |
| E2E-AE-3 | 取消运行中任务 | 1. POST /snap-agent/runs 2. POST /snap-agent/runs/{id}/cancel | status=CANCELLED |
| E2E-AE-4 | HITL 完整流程 | 1. POST /runs (skill with @ToolApproval) 2. 等待 PAUSED 3. POST /runs/{id}/resume (humanInput="approved") | PAUSED→RUNNING→SUCCEEDED |

---

### 2.2 Module 02: Skill System (P0)

#### 2.2.1 Unit Tests (21)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~04 | Frontmatter parse: valid/missing name/missing desc/missing close | SkillLoaderTest | ✅已有 |
| UC-05~07 | InputSpec: enum+options/shortcuts/required-permission | InputSpecTest | ✅已有 |
| UC-08~10 | Two-tier: builtin+custom merge/override/delete restore | SkillRegistryTest | ✅已有 |
| UC-11 | Tool missing → UNAVAILABLE | SkillRegistryTest | ✅已有 |
| UC-12~14 | Directory: SKILL.md only/recurse/nested | SkillLoaderTest | ✅已有 |
| UC-15~18 | Refresh: new file/counts/concurrent/duplicate last-wins | SkillRegistryTest | ✅已有 |
| UC-19~21 | Graph runtime: body→EntryNode/tools subset/UNAVAILABLE blocks | SkillRegistryTest | ⚠需补 |

#### 2.2.2 Integration Tests (10)

| UC ID | 场景 | 端点 | 状态 |
|-------|------|------|------|
| UC-R1 | GET /skills list | GET /snap-agent/skills | ✅已有 |
| UC-R2 | GET /skills with source | GET /snap-agent/skills?source=custom | ✅已有 |
| UC-R3 | GET /skills with shortcuts | GET /snap-agent/skills | ✅已有 |
| UC-R4 | POST /skills/refresh | POST /snap-agent/skills/refresh | ✅已有 |
| UC-R5 | DELETE /skills/{name} custom | DELETE /snap-agent/skills/{name} | ✅已有 |
| UC-R6 | DELETE /skills/{name} builtin → 403 | DELETE /snap-agent/skills/{name} | ✅已有 |
| UC-R7 | DELETE /skills/{name} not found → 404 | DELETE /snap-agent/skills/{name} | ✅已有 |
| UC-R8 | POST /skills/upload | POST /snap-agent/skills/upload | ⚠GAP |
| UC-R9 | POST /skills/upload-folder | POST /snap-agent/skills/upload-folder | ⚠GAP |
| UC-R10 | GET /skills exposes requiredPermission | GET /snap-agent/skills | ⚠GAP |

#### 2.2.3 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-SK-1 | 查看技能列表 | GET /snap-agent/skills (Basic auth) | 200, 含 health-check, allocation-plan-diagnose 等 |
| E2E-SK-2 | 刷新技能 | POST /snap-agent/skills/refresh | 200, 返回刷新结果 |
| E2E-SK-3 | 技能详情与工具校验 | GET /snap-agent/skills → 验证 UNAVAILABLE skill 的 unavailableReason | 字段存在且非空 |

---

### 2.3 Module 03: Tool Dispatcher (P0)

#### 2.3.1 Unit Tests (26)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~02 | @Tool basic/returnDirect | ToolAnnotationTest | ✅已有 |
| UC-03~04 | @ToolParam required/missing throws | ToolAnnotationTest | ✅已有 |
| UC-05~06 | ToolCallback execute success/exception | ToolCallbackRegistryImplTest | ✅已有 |
| UC-07~10 | Registry register/duplicate/system/unregister/toJson | ToolCallbackRegistryImplTest | ✅已有 |
| UC-11~14 | ToolCallbacks.from multi/no-tool/missing-param/non-String | ToolCallbacksTest | ✅已有 |
| UC-15~17 | ToolsNode batch/unknown/truncation | GraphRuntimeTest | ✅已有 |
| UC-18~21 | @ToolApproval interrupt/resume approved/rejected/no-pause | GraphRuntimeTest | ⚠需补 |
| UC-22 | ToolResult 3 states | ToolResultTest | ✅已有 |
| UC-23 | Registry subset | ToolCallbackRegistryImplTest | ✅已有 |
| UC-24~26 | Plugin hot-load/unload/conflict | PluginAutoWrappingTest | ✅已有 |

#### 2.3.2 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-TL-1 | 查看工具列表 | GET /snap-agent/tools | 200, 含 echo, mysql_query 等 |
| E2E-TL-2 | 工具详情 | GET /snap-agent/tools/{name} | 200 含 input_schema / 404 |

---

### 2.4 Module 04: Anchor Q&A (P0)

#### 2.4.1 Unit Tests (15)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~04 | auto mode: 202+graph/tool_use/empty 400/end_turn | AnchorE2ETest | ✅已有 |
| UC-05~08 | off mode: linear/HtmlOutputConverter/no tools/summary cache | AnchorE2ETest | ✅已有 |
| UC-09~12 | inject mode: linear/template/cache hit/content | AnchorE2ETest | ✅已有 |
| UC-13~15 | Pre-summary parallel/cache/classifier degradation | AnchorE2ETest | ⚠需补 |

#### 2.4.2 Integration Tests (5)

| UC ID | 场景 | 端点 | 状态 |
|-------|------|------|------|
| UC-R1 | POST /anchor/preprocess | POST /snap-agent/anchor/preprocess | ⚠GAP |
| UC-R2 | POST /anchor/inject | POST /snap-agent/anchor/inject | ⚠GAP |
| UC-R3 | GET /anchor/config | GET /snap-agent/anchor/config | ✅已有 |
| UC-R4 | auto mode full flow | POST /snap-agent/runs (skillId=auto) | ✅已有 |
| UC-R5 | off mode full flow | POST /snap-agent/runs (skillId=auto, mode=off) | ⚠GAP |

#### 2.4.3 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-AQ-1 | 锚点配置 | GET /snap-agent/anchor/config | 200, 含 enabled, maxContextChars |
| E2E-AQ-2 | 锚点预处理 | POST /snap-agent/anchor/preprocess (anchorName, pageUrl, question) | 200, 含 summary/classification |
| E2E-AQ-3 | 锚点注入 | POST /snap-agent/anchor/inject (anchorName, pageUrl) | 200, HTML fragment |

---

### 2.5 Module 05: Anchor Injection (P1)

#### 2.5.1 Unit Tests (12)

| UC ID | 测试名称 | 状态 |
|-------|---------|------|
| UC-01~02 | Page-load inject + cache hit | ⚠需补 |
| UC-03 | Per-user cache isolation | ⚠需补 |
| UC-04 | HtmlOutputConverter stripThinking + sanitize | ⚠需补 |
| UC-05 | Skill body as system prompt | ⚠需补 |
| UC-06~07 | Failure fallback / no fallback | ⚠需补 |
| UC-08 | TTL=0 disables caching | ⚠需补 |
| UC-09~10 | Error response / no skill → 400 | ⚠需补 |
| UC-11 | Daily-tips multiple anchors | ⚠需补 |
| UC-12 | Conditional injection (selector match) | ⚠需补 |

#### 2.5.2 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-AI-1 | 注入 announcement | POST /anchor/inject (skill=announcement) | HTML 含 snap-inject class |
| E2E-AI-2 | 注入 daily-tips | POST /anchor/inject (skill=daily-tips) | HTML 含 snap-inject class |
| E2E-AI-3 | 注入失败 fallback | POST /anchor/inject (invalid skill) | 返回 fallback HTML 或空 |

---

### 2.6 Module 06: Knowledge / RAG (P1)

#### 2.6.1 Unit Tests (10)

| UC ID | 测试名称 | 状态 |
|-------|---------|------|
| UC-01~03 | InMemoryVectorStore add/search/clear | ✅已有 (InMemoryVectorStoreTest) |
| UC-04~05 | ModularRag retrieve/augment | ✅已有 (ModularRagTest) |
| UC-06~07 | EmbeddingModel mock + dimension | ⚠需补 |
| UC-08~10 | RetrievalAugmentationAdvisor before/after/empty | ⚠需补 |

#### 2.6.2 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-KN-1 | RAG enabled 运行 | POST /runs (skillId=auto, rag.enabled=true) | SSE 含 rag.context 注入 |
| E2E-KN-2 | RAG disabled 运行 | POST /runs (skillId=auto, rag.enabled=false) | SSE 无 rag.context |

---

### 2.7 Module 07: Workflow (P1)

#### 2.7.1 Unit Tests (8)

| UC ID | 测试名称 | 状态 |
|-------|---------|------|
| UC-01~02 | Workflow parse + step chain | ⚠需补 |
| UC-03~04 | Conditional branching + context passing | ⚠需补 |
| UC-05~06 | Full-diagnose 4-step chain | ⚠需补 |
| UC-07~08 | Error handling + timeout | ⚠需补 |

#### 2.7.2 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-WF-1 | 查看工作流列表 | GET /snap-agent/workflows | 200, 含 full-diagnose |
| E2E-WF-2 | 执行工作流 | POST /snap-agent/workflows/full-diagnose/run | 202, 返回 taskId |

---

### 2.8 Module 08: Patrol & Alert (P1)

#### 2.8.1 Unit Tests (12)

| UC ID | 测试名称 | 状态 |
|-------|---------|------|
| UC-01~03 | Patrol task CRUD + toggle | ⚠需补 |
| UC-04~05 | Patrol report generation + list | ⚠需补 |
| UC-06~07 | Alert convergence + list | ⚠需补 |
| UC-08~09 | Bugfix suggestion generation | ⚠需补 |
| UC-10~12 | Issue closure: create/verify/close | ⚠需补 |

#### 2.8.2 Integration Tests (5)

| UC ID | 场景 | 端点 | 状态 |
|-------|------|------|------|
| UC-R1 | POST /patrol/tasks | POST /snap-agent/patrol/tasks | ⚠GAP |
| UC-R2 | GET /patrol/tasks | GET /snap-agent/patrol/tasks | ⚠GAP |
| UC-R3 | GET /patrol/reports | GET /snap-agent/patrol/reports | ⚠GAP |
| UC-R4 | GET /alerts | GET /snap-agent/alerts | ⚠GAP |
| UC-R5 | POST /alerts/{id}/resolve | POST /snap-agent/alerts/{id}/resolve | ⚠GAP |

#### 2.8.3 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-PA-1 | 巡检任务列表 | GET /snap-agent/patrol/tasks | 200 |
| E2E-PA-2 | 告警列表 | GET /snap-agent/alerts | 200 |
| E2E-PA-3 | 问题列表与详情 | GET /snap-agent/issues, GET /snap-agent/issues/{id} | 200 |

---

### 2.9 Module 09: Plugin & MCP (P1)

#### 2.9.1 Unit Tests (9)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01 | JAR upload + scan + register (ToolCallback[]) | PluginUploaderTest | ✅已有 |
| UC-02 | pluginId path traversal / special chars / duplicate | PluginUploaderTest | ✅已有 |
| UC-03 | pluginOverrides routing + HTTP validation | PluginOverridesTest | ✅已有 |
| UC-04 | Annotation priority + YAML fallback | PluginMetadataScannerTest | ✅已有 |
| UC-05 | MCP SSE handshake + naming + execute | McpToolProviderTest | ✅已有 |
| UC-06 | cleanupPlugin + system protection | PluginUploaderTest | ✅已有 |
| UC-07 | PluginConfigExtractor | PluginConfigExtractorTest | ✅已有 |
| UC-08 | ToolCallbacks.from reflection | ToolCallbacksFromTest | ✅已有 |
| UC-09 | Plugin ToolCallback == builtin @Tool equivalence | PluginAutoWrappingTest | ✅已有 |

#### 2.9.2 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-PL-1 | 插件列表 | GET /snap-agent/tools/plugins | 200 |
| E2E-PL-2 | 上传插件 | POST /snap-agent/tools/plugins/upload (JAR) | 200/400 |
| E2E-PL-3 | 删除插件 (system → 403) | DELETE /snap-agent/tools/plugins/{id} | 403 for system, 200 for custom |

---

### 2.10 Module 10: Cost & Security (P0)

#### 2.10.1 Unit Tests (25)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~03 | CostBudgetAdvisor before/after/exception isolation | ⚠需补 |
| UC-04~05 | Budget exceeded InterruptException + resume | ⚠需补 |
| UC-06~08 | RateLimiter concurrent/hourly/releaseRejected/null | RateLimiterTest | ✅已有 |
| UC-09~10 | SqlGuard whitelist/blacklist/LIMIT/multi-statement/empty | ⚠需补 |
| UC-11~12 | CodePathGuard path whitelist/traversal/resolve | ⚠需补 |
| UC-13~14 | SecurityGateway 401/403 + Adapters | SecuritySpiTest | ✅已有 |
| UC-15~17 | MicrometerObservationAdvisor metrics/traces/noop | ⚠需补 |
| UC-18~21 | SafeGuardAdvisor before/after/whitelist/exception | ⚠需补 |
| UC-22~25 | AuditAdvisor record LLM/Tool/exception/order+masking | ⚠需补 |

#### 2.10.2 Integration Tests (5)

| UC ID | 场景 | 端点 | 状态 |
|-------|------|------|------|
| UC-R1 | GET /cost/summary | GET /snap-agent/cost/summary | ⚠GAP |
| UC-R2 | GET /cost/users/{id}/summary | GET /snap-agent/cost/users/{id}/summary | ⚠GAP |
| UC-R3 | GET /cost/skills/{name}/summary | GET /snap-agent/cost/skills/{name}/summary | ⚠GAP |
| UC-R4 | GET /cost/records | GET /snap-agent/cost/records | ⚠GAP |
| UC-R5 | GET /audit/records | GET /snap-agent/audit | ⚠GAP |

#### 2.10.3 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-CS-1 | 未认证访问 → 401 | GET /snap-agent/skills (no auth) | 401 |
| E2E-CS-2 | 无权限访问 → 403 | GET /snap-agent/skills (auth without permission) | 403 |
| E2E-CS-3 | 限流 → 429 | 连续 POST /snap-agent/runs 超限 | 429 + Retry-After |
| E2E-CS-4 | 成本查询 | GET /snap-agent/cost/summary | 200 含 total/breakdown |

---

### 2.11 Module 11: Host Integration (P0)

#### 2.11.1 Unit Tests (30)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~04 | AutoConfig: enabled=false/true/api-key empty/jdbc.enabled=false | AutoConfigurationTest | ✅已有 |
| UC-05~06 | Properties defaults + set values | SnapAgentPropertiesTest | ✅已有 |
| UC-07~09 | Filter: inject userId/non-snap-agent/finally clear | SnapAgentFilterTest | ✅已有 |
| UC-10~14 | Controller: /skills/runs/stream/resume/checkpoints | SnapAgentControllerTest | ✅已有 |
| UC-15~16 | Security: Spring Security auto / Shiro | AutoConfigurationTest | ✅已有 |
| UC-17~19 | Routing: Noop/Static/PeerSseRelay | StaticPeerRouterTest等 | ✅已有 |
| UC-20 | Static resource serving | ⚠GAP |
| UC-21~30 | 2.x: RAG/Plugin/Checkpoint/VectorStore/Embedding/Memory/Cost | AutoConfigurationTest | ✅已有 |

#### 2.11.2 Integration Tests (12)

| UC ID | 场景 | 端点 | 状态 |
|-------|------|------|------|
| UC-R1~5 | Conversations: create/list/detail/download/delete | POST/GET/DELETE /conversations | ✅已有 |
| UC-R6~12 | Plugins: list/detail/upload/delete/enable/disable/default | CRUD /tools/plugins | ✅已有 |

#### 2.11.3 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-HI-1 | 认证配置 | GET /snap-agent/auth-config (no auth) | 200 公开 |
| E2E-HI-2 | 用户信息 | GET /snap-agent/user-info (Basic auth) | 200 含 authenticated=true |
| E2E-HI-3 | 静态资源 | GET /snap-agent/index.html | 200 HTML |
| E2E-HI-4 | 内部探针 | GET /snap-agent-internal/tasks/nope/probe (with token) | 404 |
| E2E-HI-5 | 内部探针 (无 token) | GET /snap-agent-internal/tasks/nope/probe (no token) | 401 |

---

### 2.12 Module 12: Code Graph (P1)

#### 2.12.1 Unit Tests (35)

| UC ID | 测试名称 | 测试类 | 状态 |
|-------|---------|--------|------|
| UC-01~09 | Builder: class/method/field/empty/null/immutable/extends/implements/CALLS/package filter | SimpleCodeGraphBuilderTest | ✅已有 |
| UC-10~23 | Index: findByName/case-insensitive/empty/null/findCallChain/depth/not-found/cycle/reverse/impact/getOutgoing/getIncoming/getNode/nodeCount | InMemoryCodeGraphIndexTest | ✅已有 |
| UC-24~30 | @Tool: call_chain/reverse_chain/impact_analysis/find/unknown/missing-param/maxDepth | CodeGraphToolsTest | ✅已有 |
| UC-31~35 | ToolCallbacks.from/tooDefinitionsJson/unregister/fuzzy/no-match | CodeGraphToolsTest | ✅已有 |

#### 2.12.2 E2E Tests

| E2E ID | 场景 | 操作步骤 | 验证点 |
|--------|------|---------|--------|
| E2E-CG-1 | 代码图谱工具在工具列表 | GET /snap-agent/tools | 含 call_chain/reverse_chain/impact_analysis/find |
| E2E-CG-2 | 通过 run 调用 call_chain | POST /snap-agent/runs (skillId=auto, tool=call_chain) | SSE 含 tool_call 结果 |

---

## 3. E2E 测试场景 (Chrome DevTools)

### 3.1 浏览器 E2E 测试流程

以下测试通过 Chrome DevTools MCP 工具执行，验证 Demo 全链路。

### 3.2 测试用例清单

#### TC-01: 访问 SnapAgent 控制台 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/index.html
  2. Take snapshot of page
验证:
  - Page title 含 "SnapAgent"
  - 页面含技能列表/运行/工具等 UI 元素
```

#### TC-02: 技能列表展示 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/skills (Basic auth: demo:demo)
  2. Take snapshot
验证:
  - HTTP 200
  - Response 含 skills 数组
  - 含 health-check, announcement, daily-tips, welcome-card
  - 含 allocation-plan-diagnose, replenishment-strategy-diagnose
  - 每个 skill 有 name, description, availability, source
```

#### TC-03: 工具列表展示 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/tools
  2. Take snapshot
验证:
  - HTTP 200
  - 含 echo, mysql_query, redis_get, log_read, code_read 等工具
  - 每个工具有 name, description, input_schema
```

#### TC-04: 模型列表 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/models
验证:
  - HTTP 200
  - 含 allowed models 列表 + default model
```

#### TC-05: 用户信息 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/user-info
验证:
  - HTTP 200
  - authenticated=true, authorized=true
  - userId 非空, username="demo"
```

#### TC-06: 认证配置 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/auth-config
验证:
  - HTTP 200 (公开无需认证)
  - 含 authHeader, authCookie 配置
```

#### TC-07: 锚点配置 (P0)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/anchor/config
验证:
  - HTTP 200 (公开无需认证)
  - 含 enabled, maxContextChars
```

#### TC-08: 创建运行任务 (P0)
```
步骤:
  1. POST http://localhost:8080/snap-agent/runs
     Body: {"skillId":"health-check","inputs":{"message":"hello world"}}
  2. 验证 202 response
  3. 获取 taskId
  4. GET /snap-agent/runs/{taskId}
验证:
  - 202 含 taskId, streamUrl
  - GET 详情 200 含 status, transcript
```

#### TC-09: SSE 流监听 (P0)
```
步骤:
  1. POST /snap-agent/runs (skillId=health-check)
  2. GET /snap-agent/runs/{id}/stream (Accept: text/event-stream)
验证:
  - Content-Type: text/event-stream
  - 事件含 thought / tool_call / tool_result / done
  - 最终 status=SUCCEEDED
```

#### TC-10: 任务列表查询 (P0)
```
步骤:
  1. GET /snap-agent/runs?page=0&size=10
验证:
  - 200 含 content 数组, totalElements, totalPages
```

#### TC-11: 取消任务 (P1)
```
步骤:
  1. POST /snap-agent/runs (long-running skill)
  2. POST /snap-agent/runs/{id}/cancel
验证:
  - Cancel 200
  - GET /snap-agent/runs/{id} status=CANCELLED
```

#### TC-12: 报告查看 (P1)
```
步骤:
  1. POST /snap-agent/runs (skillId=health-check)
  2. 等待完成
  3. GET /snap-agent/runs/{id}/report
验证:
  - 200 含报告内容
```

#### TC-13: Transcript 查看 (P1)
```
步骤:
  1. POST /snap-agent/runs
  2. GET /snap-agent/runs/{id}/transcript
验证:
  - 200 含 events 数组
```

#### TC-14: 会话 CRUD (P1)
```
步骤:
  1. POST /snap-agent/conversations (body: {id:"test-1", title:"Test", messages:[...], skillId:"health-check"})
  2. GET /snap-agent/conversations
  3. GET /snap-agent/conversations/test-1
  4. DELETE /snap-agent/conversations/test-1
验证:
  - Create 200, List 200 含 test-1, Detail 200, Delete 200
```

#### TC-15: 会话下载 (P2)
```
步骤:
  1. POST /snap-agent/conversations (with messages)
  2. GET /snap-agent/conversations/{id}/download
验证:
  - 200 Content-Type: text/markdown
  - Body 含 messages 内容
```

#### TC-16: 巡检任务列表 (P1)
```
步骤:
  1. GET /snap-agent/patrol/tasks
验证:
  - 200 含 tasks 列表
```

#### TC-17: 巡检报告列表 (P1)
```
步骤:
  1. GET /snap-agent/patrol/reports
验证:
  - 200 含 reports 列表
```

#### TC-18: 告警列表 (P1)
```
步骤:
  1. GET /snap-agent/alerts
验证:
  - 200 含 alerts 列表
```

#### TC-19: 问题列表 (P1)
```
步骤:
  1. GET /snap-agent/issues
  2. GET /snap-agent/issues/recent-runs
验证:
  - 200 含 issues 列表 / recent-runs 列表
```

#### TC-20: 成本摘要 (P1)
```
步骤:
  1. GET /snap-agent/cost/summary
  2. GET /snap-agent/cost/records?page=0&size=10
验证:
  - 200 含 total / breakdown / records
```

#### TC-21: 工作流列表 (P1)
```
步骤:
  1. GET /snap-agent/workflows
  2. GET /snap-agent/workflows/full-diagnose
验证:
  - 200 含 workflows 列表 / detail
```

#### TC-22: 插件列表 (P1)
```
步骤:
  1. GET /snap-agent/tools/plugins
验证:
  - 200 含 plugins 列表 (可能为空)
```

#### TC-23: 审计记录 (P1)
```
步骤:
  1. GET /snap-agent/audit?page=0&size=10
验证:
  - 200 含 audit records
```

#### TC-24: 安全 - 未认证 401 (P0)
```
步骤:
  1. GET http://localhost:8080/snap-agent/skills (no auth header)
验证:
  - 401 Unauthorized
```

#### TC-25: 安全 - 错误密码 401 (P0)
```
步骤:
  1. GET /snap-agent/skills (Basic auth: wrong:wrong)
验证:
  - 401 Unauthorized
```

#### TC-26: 内部探针 - 有 token (P0)
```
步骤:
  1. GET /snap-agent-internal/tasks/nope/probe (header: X-Skills-Agent-Internal-Token: e2e-internal-secret)
验证:
  - 404 (task not found, but token valid)
```

#### TC-27: 内部探针 - 无 token (P0)
```
步骤:
  1. GET /snap-agent-internal/tasks/nope/probe (no token header)
验证:
  - 401 Unauthorized
```

#### TC-28: 内部 SSE 流 (P1)
```
步骤:
  1. POST /snap-agent/runs (create task)
  2. GET /snap-agent-internal/tasks/{id}/stream (with token)
验证:
  - 200 text/event-stream
  - 含 transcript events
```

#### TC-29: 执行诊断技能 (P0)
```
步骤:
  1. POST /snap-agent/runs
     Body: {"skillId":"allocation-plan-diagnose","inputs":{"skuCode":"A001"}}
  2. GET /snap-agent/runs/{id}/stream
验证:
  - 202 taskId
  - SSE 流含 thought / tool_call (mysql_query) / done
  - 最终 status=SUCCEEDED 或 FAILED
```

#### TC-30: 执行 health-check 技能 (P0)
```
步骤:
  1. POST /snap-agent/runs
     Body: {"skillId":"health-check","inputs":{"message":"test connectivity"}}
  2. GET /snap-agent/runs/{id}/stream
验证:
  - 202
  - SSE 含 echo tool_call
  - status=SUCCEEDED
```

#### TC-31: 锚点预处理 (P1)
```
步骤:
  1. POST /snap-agent/anchor/preprocess
     Body: {"anchorName":"test-anchor","pageUrl":"http://example.com","question":"what is this?"}
验证:
  - 200 含 preprocessId, status
  - 或 400 (missing required field)
```

#### TC-32: 锚点注入 (P1)
```
步骤:
  1. POST /snap-agent/anchor/inject
     Body: {"anchorName":"test","pageUrl":"http://example.com","skillId":"announcement"}
验证:
  - 200 HTML fragment
  - 或 400 (missing field)
```

#### TC-33: 技能刷新 (P1)
```
步骤:
  1. POST /snap-agent/skills/refresh
验证:
  - 200 含 refresh 结果 (counts)
```

#### TC-34: 删除自定义技能 (P1)
```
步骤:
  1. DELETE /snap-agent/skills/test-upload (if exists)
  2. DELETE /snap-agent/skills/health-check (builtin)
验证:
  - custom: 200
  - builtin: 403
```

#### TC-35: 工作流执行 (P2)
```
步骤:
  1. POST /snap-agent/workflows/full-diagnose/run
     Body: {"trigger":{"service":"test"}}
验证:
  - 202 或 200 含 taskId / result
```

#### TC-36: 控制台 UI 渲染 (P1)
```
步骤:
  1. Navigate to http://localhost:8080/snap-agent/index.html
  2. Take screenshot
  3. Check for: navigation, skill list, run button, conversation panel
验证:
  - 页面正常渲染 (not blank/error)
  - 含 SnapAgent 标题
  - 有技能列表区域
  - 有运行/对话区域
```

---

## 4. 执行优先级

### Phase 1: P0 核心验证 (立即执行)
1. **TC-24**: 未认证 401
2. **TC-25**: 错误密码 401
3. **TC-02**: 技能列表
4. **TC-03**: 工具列表
5. **TC-04**: 模型列表
6. **TC-05**: 用户信息
7. **TC-08**: 创建运行任务
8. **TC-09**: SSE 流监听
9. **TC-30**: health-check 技能执行
10. **TC-26**: 内部探针 (有 token)
11. **TC-27**: 内部探针 (无 token)

### Phase 2: P0 补充 + P1 核心
12. **TC-01**: 控制台 UI
13. **TC-10**: 任务列表
14. **TC-14**: 会话 CRUD
15. **TC-29**: 诊断技能执行
16. **TC-36**: UI 截图
17. **TC-20**: 成本摘要
18. **TC-21**: 工作流列表
19. **TC-22**: 插件列表

### Phase 3: P1 补充
20. **TC-06**: 认证配置
21. **TC-07**: 锚点配置
22. **TC-11**: 取消任务
23. **TC-12**: 报告查看
24. **TC-13**: Transcript
25. **TC-16~19**: 巡检/告警/问题
26. **TC-31~32**: 锚点预处理/注入
27. **TC-33~34**: 技能刷新/删除

### Phase 4: P2 补充
28. **TC-15**: 会话下载
29. **TC-28**: 内部 SSE 流
30. **TC-35**: 工作流执行

---

## 5. 自动化测试执行命令

### 5.1 Unit + Integration Tests
```bash
# Core module
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" \
/opt/homebrew/Cellar/maven/3.9.15/bin/mvn test \
  -pl snap-agent-core -f pom.xml -DfailIfNoTests=false

# Starter module
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" \
/opt/homebrew/Cellar/maven/3.9.15/bin/mvn test \
  -pl snap-agent-spring-boot-2x-starter -f pom.xml -DfailIfNoTests=false

# Demo module
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" \
/opt/homebrew/Cellar/maven/3.9.15/bin/mvn test \
  -pl snap-agent-demo -f pom.xml -DfailIfNoTests=false
```

### 5.2 E2E Shell Scripts
```bash
# Docker-based E2E
cd snap-agent-demo && ./e2e-docker.sh

# Local JVM E2E
cd snap-agent-demo && ./e2e-local.sh
```

### 5.3 Chrome DevTools E2E
通过 MCP Chrome DevTools 工具执行 Section 3.2 中的 TC-01 ~ TC-36。

---

## 6. 测试数据准备

### 6.1 标准 Skill
```yaml
health-check:
  name: health-check
  description: Echo user message
  tools: [echo]
  inputs:
    - key: message
      required: true
      type: string
```

### 6.2 标准 Task
```json
{
  "skillId": "health-check",
  "inputs": {"message": "hello world"},
  "model": "aliyun/glm-5.2"
}
```

### 6.3 认证 Header
```
Authorization: Basic ZGVtbzpkZW1v
```

### 6.4 内部 Token
```
X-Skills-Agent-Internal-Token: e2e-internal-secret
```

---

## 7. Gap 分析与待补充测试

### 7.1 高优先级 Gap (P0)

| Gap ID | 模块 | 描述 | 建议 |
|--------|------|------|------|
| GAP-01 | 01 | HITL resume / interrupt / checkpoints / replay 端点 E2E | 需 Testcontainers + CheckpointStore |
| GAP-02 | 02 | skills/upload + upload-folder 端点集成测试 | MockMvc + MockMultipartFile |
| GAP-03 | 10 | CostBudgetAdvisor / SafeGuardAdvisor / AuditAdvisor / MicrometerObservationAdvisor 单元 | 新建测试类 |
| GAP-04 | 10 | Cost / Audit 端点 E2E | MockMvc 集成测试 |
| GAP-05 | 11 | 静态资源服务 E2E | @SpringBootTest + MockMvc |

### 7.2 中优先级 Gap (P1)

| Gap ID | 模块 | 描述 | 建议 |
|--------|------|------|------|
| GAP-06 | 04 | anchor/preprocess + inject 端点集成 | MockMvc |
| GAP-07 | 05 | Anchor Injection 全部单元测试 | 新建测试类 |
| GAP-08 | 07 | Workflow engine 单元测试 | 新建测试类 |
| GAP-09 | 08 | Patrol/Alert/Issue 单元+集成 | 新建测试类 |
| GAP-10 | 06 | EmbeddingModel + RetrievalAugmentationAdvisor 单元 | 新建测试类 |

### 7.3 低优先级 Gap (P2)

| Gap ID | 模块 | 描述 |
|--------|------|------|
| GAP-11 | 09 | MCP SSE 全流程 mock (MockWebServer) |
| GAP-12 | 12 | CodeGraph E2E via POST /runs |
| GAP-13 | 11 | Redis VectorStore + Ollama Embedding 集成 |

---

## 8. 测试通过标准

| 层级 | 通过标准 |
|------|---------|
| Unit | 所有 @Test 方法绿色通过, 覆盖率 > 80% |
| Integration | 所有 @SpringBootTest / MockMvc 场景通过 |
| E2E (Shell) | e2e-docker.sh / e2e-local.sh 全部断言通过 |
| E2E (Chrome) | TC-01~TC-36 中 P0 用例 100% 通过, P1 用例 ≥ 90% |

---

## 9. 变更历史

| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 1.0 | 2026-07-26 | Claude | 初始完整测试计划, 覆盖 12 TDD 模块 + 36 E2E 场景 |
