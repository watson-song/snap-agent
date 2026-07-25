# TDD需求规格说明书 — Anchor Q&A 锚点问答模式

> 版本: 3.0 | 模块: snap-agent-core (anchor) / snap-agent-spring-boot-2x-starter | 基于 TEMPLATE.md
> 对应设计: `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` Section 4 (Anchor 三种图模式)

---

## 1. 需求元信息

```yaml
需求ID: REQ-04-ANCHOR-QA
需求名称: AnchorGraphFactory 三模式图 (auto/off/inject) + 预摘要并行 + HtmlOutputConverter
优先级: P0
迭代: v2.x
负责人: anchor-team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 2.x 重构为统一图架构 (StateGraph + Node + GraphExecutor)。Anchor 模块从旧 `AnchorOrchestrator` 升级为 `AnchorGraphFactory`，三种图模式对接不同场景: `auto` 复用 ReAct 图 (有工具)、`off` 线性图 (无工具, 纯上下文)、`inject` 线性图 (生成 HTML 片段缓存)。预摘要与分类在 `AnchorPreprocessNode` 内用 `CompletableFuture` 并行执行。
- **用户价值**: 宿主页面引入一行 `<script src="/snap-agent/anchor.js" defer>` 后，点击锚点即可在右侧抽屉发起针对当前内容的 LLM 问答，减少 70% 上下文切换成本；auto 模式可调用工具，off 模式纯上下文问答首 token < 2s，inject 模式自动生成结构化 HTML 注入宿主。
- **成功指标**: 首 token 延迟 < 3s（缓存命中）/ < 8s（未命中）；摘要缓存命中率 > 60%；分类器降级路径可观测；HtmlOutputConverter 输出 100% 经 sanitize + container div 包裹。

### 1.2 范围边界
- **包含**: `AnchorGraphFactory` (三模式图构建), `AnchorPreprocessNode` (CompletableFuture 并行: summary + classify), `AnchorSummaryCache` (Caffeine, 10min TTL, per-user), `AnchorSkillClassifier` (降级 default category), `HtmlOutputConverter` (stripThinking / sanitize / container div / template), 三种图模式 (`auto`/`off`/`inject`) + `POST /runs` 扩展 `skillId="auto"|"off"|"inject"` + `anchor`, `POST /anchor/preprocess`, `GET /anchor/config`, SSE 流式。
- **不包含**: 客户端 `anchor.js` Shadow DOM UI 与样式 (US-8 仅约束 Turndown DOM→Markdown 转换契约), 黑名单路径精细配置 (v2.1), 具体向量数据库实现 (属 06-knowledge 模块)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 |
|--------|------|------|------|----------|
| R1 | 三模式图执行差异 (auto 循环 ReAct / off 线性 / inject 线性+缓存)，首 token 8-15s | 高 | 高 | 并行 CompletableFuture + 预摘要 + 10min 缓存复用 |
| R2 | 分类器 malformed JSON 或低置信度 | 中 | 中 | 降级为 default category "general"，不抛异常 |
| R3 | 摘要器 LLM 失败/超时 | 中 | 中 | 返回原 content，跳过摘要，缓存不写入 |
| R4 | inject 模式生成 HTML 未通过 sanitize → XSS 风险 | 中 | 高 | HtmlOutputConverter 强制 sanitize + container div 包裹 |
| R5 | preprocess Future 未完成时图执行被调用 | 中 | 中 | resolveSummary/resolveClassify 降级 inline 重算 |

**关键假设**: 客户端已用 Turndown 将 DOM 转 Markdown (US-8)；短内容 (< 4000 字符) 跳过摘要 (US-9)；`skillId="auto"` 走 ReActGraphFactory + anchor context 作为虚拟 skill body；`off`/`inject` 模式使用 `HtmlOutputConverter` 输出。

---

## 2. 用户故事 (User Stories)

### US-1: auto 模式图 — ReAct 图 + anchor context 作为虚拟 skill
```gherkin
作为 宿主页面浏览者
我希望 skillId="auto" 时 AnchorGraphFactory 构建复用 ReActGraphFactory 的图，anchor context 作为 skill body
以便 LLM 能结合区块内容 + 工具（jdbc_query 等）回答，不切页面即可获得 80% 以上问题的即时解答
```
**AC:**
```gherkin
AC1: Given anchor.enabled=true 且 AnchorGraphFactory 已配置
  When POST /runs skillId="auto" + anchor + inputs.message 非空
  Then 返回 202 + {taskId, streamUrl} 且异步执行 ReAct 图 (entry → agent ↔ tools → END)
  And agent_node 的 system prompt 含 anchor context 作为 skill body
AC2: Given ReAct 图执行中 LLM 调用 tool_use
  When ShouldContinue 条件路由
  Then 路由到 tools 节点执行工具后回到 agent (循环)
AC3: Given inputs.message 为空
  When POST /runs skillId="auto"
  Then 返回 400 + INVALID_INPUT
AC4: Given auto 模式图执行完成 (LLM end_turn)
  When ShouldContinue 路由
  Then 路由到 END，TaskStatus=SUCCEEDED
```

### US-2: off 模式图 — 线性 summarize → answer，无工具，HtmlOutputConverter
```gherkin
作为 宿主页面浏览者
我希望 skillId="off" 时 AnchorGraphFactory 构建线性图（summarize → answer），LLM 仅基于区块内容直答，不调用工具
以便 对简单内容解释类问题获得 < 2s 首 token 延迟，且输出经 HtmlOutputConverter 转换
```
**AC:**
```gherkin
AC1: Given skillId="off" + anchor
  When AnchorGraphFactory.create("off")
  Then 返回 CompiledGraph 线性图: entry → summarize_node → answer_node → END
  And 图节点集合不含 tools 节点
AC2: Given answer_node 使用 HtmlOutputConverter(stripThinking=true, sanitize=true, containerClass="snap-inject")
  When LLM 输出 "</think>\n<p>答案</p>"
  Then convert 后输出含 "<div class=\"snap-inject\">...</div>" 包裹且无  标签
AC3: Given off 模式图执行
  When 节点流转
  Then 不触发 onToolUse/onToolResult 事件，仅 onThought 流式推送
AC4: Given summarize_node 复用 AnchorSummaryCache
  When 缓存命中
  Then 直接返回缓存摘要，LLM 摘要调用次数=0
```

### US-3: inject 模式图 — 线性 preprocess → generate HTML → cache
```gherkin
作为 宿主页面开发者
我希望 skillId="inject" 时 AnchorGraphFactory 构建线性图（preprocess → generate → cache），生成 HTML 片段注入宿主页面
以便 自动化场景（如巡检报告、issue 闭环）可生成结构化 HTML 片段并缓存复用
```
**AC:**
```gherkin
AC1: Given skillId="inject" + anchor
  When AnchorGraphFactory.create("inject")
  Then 返回 CompiledGraph 线性图: entry → preprocess_node → generate_node → cache_node → END
AC2: Given generate_node 使用 HtmlOutputConverter 含 template 骨架
  When LLM 输出 "片段内容"
  Then convert 后输出为 template 包裹 + 片段内容 + container div
AC3: Given cache_node 执行
  When HTML 片段生成完成
  Then 写入 AnchorHtmlCache (key=anchor.content hash, TTL=30min) 且 state["inject.cached"]=true
AC4: Given 同 anchor.content 二次执行 inject 模式
  When cache_node 检查缓存
  Then 命中缓存且 generate_node 不再调用 LLM
```

### US-4: 预摘要并行调度 — CompletableFuture in AnchorPreprocessNode
```gherkin
作为 系统优化
我希望 点击锚点瞬间触发 preprocess（summary + classify 并行 CompletableFuture），用户提交问题时复用结果
以便 首 token 延迟从 8s 降至 2-3s
```
**AC:**
```gherkin
AC1: Given AnchorPreprocessNode.execute(state)
  When 调用
  Then 启动 CompletableFuture.supplyAsync(summaryTask) 和 CompletableFuture.supplyAsync(classifyTask) 并行执行
  And state["preprocess.summaryFuture"] 和 state["preprocess.classifyFuture"] 各持有 Future 引用
AC2: Given 两个 Future 完成
  When state["preprocess.summaryFuture"].join()
  Then 返回 SummaryResult 且不阻塞 classify
AC3: Given POST /anchor/preprocess body 含 anchor + question
  When 调用
  Then 返回 200 + {preprocessId, status:"started"}，后台 Future 异步执行
AC4: Given content.length <= 4000 (短内容)
  When summaryTask 执行
  Then 直接返回原 content 且 llmClient.stream 从未被调用（US-9 关联）
AC5: Given body 无 anchor 字段
  When POST /anchor/preprocess
  Then 返回 400 + MISSING_ANCHOR
```

### US-5: 摘要缓存 — per-user, TTL, Caffeine
```gherkin
作为 成本控制
我希望 相同 content 的摘要结果 10 分钟内复用缓存（per-user 维度），Caffeine TTL=10min
以便 减少 60% 摘要 LLM 调用量
```
**AC:**
```gherkin
AC1: Given AnchorSummaryCache (Caffeine, expireAfterWrite=10min, key=userId+contentHash)
  When cache 为空
  Then getOrCreate(userId, content, supplier) 调用 supplier.get() once 且 cache.size 增 1
AC2: Given 同 userId+content 已缓存
  When getOrCreate
  Then supplier 从未调用且返回首次结果
AC3: Given 缓存写入 10 分钟后
  When getOrCreate
  Then supplier 重新被调用（TTL 过期，重新计算）
AC4: Given 不同 userId 相同 content
  When getOrCreate
  Then supplier 各被调用（per-user 隔离，避免跨用户泄露上下文）
AC5: Given AnchorSummaryCache.invalidateAll()
  When 调用
  Then cache.size == 0
```

### US-6: 分类器降级 — classify fails → default category, no error
```gherkin
作为 系统鲁棒性
我希望 分类器失败、malformed JSON、低置信度时降级为 default category "general"，不抛异常
以便 用户始终能获得回答，分类器失败对客户端不可见
```
**AC:**
```gherkin
AC1: Given threshold=0.5
  When classify 返回 confidence=0.3
  Then isMatch()==false 且 skillId 回退为 "general"
AC2: Given LLM 返回 "not a json at all"
  When classify
  Then 返回 ClassifyResult.defaultCategory() (skillId="general", confidence=0.0) 且不抛异常
AC3: Given llmClient.stream 抛 RuntimeException
  When classify("q", "c")
  Then 返回 ClassifyResult.defaultCategory() 且不向上抛出，preprocess Future 正常完成
AC4: Given 分类降级
  When 后续图执行
  Then SSE 流不中断，用户感知不到分类失败
AC5: Given LLM 返回 '{"skillId":"patrol","confidence":0.9}'
  When classify (threshold=0.5)
  Then result.skillId == "patrol" 且 isMatch() == true
```

### US-7: HtmlOutputConverter 集成 — stripThinking, sanitize, container div
```gherkin
作为 安全与渲染
我希望 off/inject 模式的 LLM 输出经 HtmlOutputConverter 处理: stripThinking 过滤思考前缀，sanitize 防 XSS，container div 包裹
以便 注入宿主页面的 HTML 安全且结构正确
```
**AC:**
```gherkin
AC1: Given HtmlOutputConverter(stripThinking=true, sanitize=true, containerClass="snap-inject")
  When LLM 输出 "</think>reasoning\n</thinking>\n<p>answer</p>"
  Then convert 后输出不含  标签和 "reasoning" 文本
AC2: Given LLM 输出含 "<script>alert(1)</script>"
  When sanitize=true
  Then convert 后输出移除 <script> 标签
AC3: Given LLM 输出无根 div
  When convert
  Then 输出被 "<div class=\"snap-inject\">...</div>" 包裹
AC4: Given HtmlOutputConverter(template="<section class=\"anchor-card\">{content}</section>")
  When convert
  Then 输出含 template 骨架 + 片段内容
AC5: Given stripThinking=false
  When LLM 输出 "</think>..."
  Then convert 后保留  标签（用于调试模式）
```

### US-8: 客户端 DOM → Markdown 转换 — Turndown (unchanged)
```gherkin
作为 宿主页面前端
我希望 客户端 anchor.js 使用 Turndown 将选中 DOM 区块转为 Markdown 后传服务端
以便 服务端收到结构化 Markdown content，无需自行解析 HTML
```
**AC:**
```gherkin
AC1: Given 客户端选中 <h2>标题</h2><p>段落</p>
  When Turndown.turndown(dom)
  Then 转为 "## 标题\n\n段落"
AC2: Given Turndown 输出 Markdown content
  When POST /anchor/preprocess
  Then 服务端 AnchorContext.content 为 Markdown 字符串
AC3: Given DOM 含 <table>
  When Turndown
  Then 转为 Markdown 表格语法
AC4: Given DOM 含 <pre><code>
  When Turndown
  Then 转为 Markdown 代码块语法
```

### US-9: 短内容跳过 — < 4000 chars skips summarization
```gherkin
作为 性能优化
我希望 anchor.content 长度 < 4000 字符时跳过摘要 LLM 调用，直接复用原 content
以便 短内容问答无需预摘要，首 token 延迟降至 < 2s
```
**AC:**
```gherkin
AC1: Given content.length=3999
  When AnchorPreprocessNode summaryTask
  Then 返回原 content 且 llmClient.stream 从未被调用
AC2: Given content.length=4000
  When summaryTask
  Then 触发 LLM 摘要（边界含，>= 4000 触发）
AC3: Given content.length=4001
  When summaryTask
  Then 触发 LLM 摘要
AC4: Given content=null
  When summaryTask
  Then 返回 null 且不抛 NPE
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 客户端转换 | US-8 | Turndown DOM→Markdown | 100% Markdown 输入 | - |
| 预摘要 | US-4 | 并行调度降延迟 | 首 token < 3s | US-8 |
| 缓存 | US-5 | 复用摘要降成本 | 命中率 > 60% | US-4 |
| 短内容跳过 | US-9 | 跳过 LLM 调用 | 短内容 < 2s | US-4 |
| 分类降级 | US-6 | 客户端不可见 | 降级率可观测 | US-4 |
| auto 图 | US-1 | 带工具智能问答 | 80% 问题解决 | US-4 |
| off 图 | US-2 | 纯上下文问答 | 首 token < 2s | US-7 |
| inject 图 | US-3 | HTML 注入自动化 | HTML 缓存复用 | US-7 |
| HTML 转换 | US-7 | 安全 + 结构正确 | 100% sanitize | - |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | AnchorGraphFactory.create("auto") 构建 ReAct 图 | P0 | US-1 | 单元 |
| UC-02 | auto 模式 anchor context 作为 skill body 注入 | P0 | US-1 | 单元 |
| UC-03 | POST /runs skillId="auto" + anchor 创建 task | P0 | US-1 | 集成 |
| UC-04 | AnchorGraphFactory.create("off") 构建线性图 | P0 | US-2 | 单元 |
| UC-05 | off 模式 HtmlOutputConverter 输出 | P0 | US-2 | 单元 |
| UC-06 | off 模式无工具事件 | P1 | US-2 | 单元 |
| UC-07 | AnchorGraphFactory.create("inject") 构建线性图 | P0 | US-3 | 单元 |
| UC-08 | inject 模式 HtmlOutputConverter + template | P0 | US-3 | 单元 |
| UC-09 | inject 模式 cache_node 写入 AnchorHtmlCache | P1 | US-3 | 单元 |
| UC-10 | AnchorPreprocessNode 并行 CompletableFuture | P0 | US-4 | 单元 |
| UC-11 | POST /anchor/preprocess 正常流程 | P0 | US-4 | 集成 |
| UC-12 | POST /anchor/preprocess 入参校验 | P0 | US-4 | 集成 |
| UC-13 | AnchorSummaryCache miss/hit/TTL | P0 | US-5 | 单元 |
| UC-14 | AnchorSummaryCache per-user 隔离 | P1 | US-5 | 单元 |
| UC-15 | AnchorSkillClassifier 正常 + 降级 | P0 | US-6 | 单元 |
| UC-16 | HtmlOutputConverter stripThinking + sanitize + container | P0 | US-7 | 单元 |
| UC-17 | HtmlOutputConverter template 骨架 | P1 | US-7 | 单元 |
| UC-18 | 客户端 Turndown DOM→Markdown 转换契约 | P1 | US-8 | 单元 |
| UC-19 | 短内容 < 4000 跳过摘要 | P0 | US-9 | 单元 |
| UC-20 | preprocess 缓存命中复用 | P0 | US-5 | 集成 |
| UC-R1 | GET /anchor/config 锚点配置 (enabled=true) | P0 | - | 集成 |
| UC-R2 | GET /anchor/config disabled 时返回 false | P0 | - | 集成 |
| UC-R3 | GET /anchor/config 不需要认证 | P1 | - | 集成 |
| UC-R4 | POST /anchor/preprocess 200 返回 | P0 | US-4 | 集成 |
| UC-R5 | POST /anchor/preprocess 400 缺 anchor | P0 | US-4 | 集成 |
| UC-R6 | POST /anchor/preprocess 400 缺 anchorName | P0 | US-4 | 集成 |
| UC-R7 | POST /anchor/preprocess 401 未登录 | P0 | - | 集成 |
| UC-R8 | POST /runs anchor 模式禁用返回 409 | P1 | US-1 | 集成 |

### 3.2 详细用例 (Gherkin)

#### UC-01: AnchorGraphFactory.create("auto") 构建 ReAct 图
```gherkin
@priority:high @type:unit
功能: auto 模式图构建

  场景: create("auto") 返回 ReAct 图形态
    Given ReActGraphFactory 已注入
    When AnchorGraphFactory.create("auto", anchorContext)
    Then 返回 CompiledGraph
    And 图节点含 entry、agent、tools
    And 条件边 ShouldContinue 从 agent 路由到 tools 或 END
    And entry 节点的 skill body 来自 anchorContext.content

  场景: auto 图 anchor context 作为虚拟 skill body
    Given anchor = AnchorContext("订单状态", "已发货\nSF123", "/order/1")
    When create("auto", anchor)
    Then EntryNode 构建的 system prompt 含 "页面 \"/order/1\"" 和 "区块内容：\n已发货\nSF123"
    And skill body 部分非空
```

#### UC-02: auto 模式 anchor context 作为 skill body 注入
```gherkin
@priority:high @type:unit
功能: anchor context 作为虚拟 skill body

  场景: system prompt 拼接 anchor 字段
    Given anchor = AnchorContext("订单状态", "已发货\nSF123", "/order/1")
    When EntryNode.execute(state)
    Then state["system.prompt"] 含 pageUrl、name、content、question 四部分
    And state["system.prompt"] 含 anchor content 作为 skill body

  场景: truncated + pageUrl=null 边界
    Given anchor.truncated=true, originalLength=52180, pageUrl=null
    When EntryNode.execute
    Then system prompt 含 "内容已截断，原始长度 52180 字符" 和 "页面 \"(unknown)\""
```

#### UC-03: POST /runs skillId="auto" + anchor 创建 task
```gherkin
@priority:high @type:integration
功能: auto 模式任务创建

  场景: auto+anchor 返回 202 + taskId
    Given anchor.enabled=true 且 rateLimiter.tryAcquire=true
    And body = {skillId:"auto", inputs:{message:"what is this?"}, anchor:{name:"test-section", content:"some content", pageUrl:"/p"}}
    When POST /runs
    Then 返回 202 且 $.taskId 存在 且 $.streamUrl 存在

  场景: anchor 禁用时返回 409
    Given anchor.enabled=false
    And body = {skillId:"auto", inputs:{message:"q"}, anchor:{name,content,pageUrl}}
    When POST /runs
    Then 返回 409 + ANCHOR_DISABLED

  场景: 缺 inputs.message 返回 400
    Given body = {skillId:"auto", inputs:{}, anchor:{name,content,pageUrl}}
    When POST /runs
    Then 返回 400 + INVALID_INPUT
```

#### UC-04: AnchorGraphFactory.create("off") 构建线性图
```gherkin
@priority:high @type:unit
功能: off 模式图构建

  场景: create("off") 返回线性图 (summarize → answer)
    Given HtmlOutputConverter 已配置
    When AnchorGraphFactory.create("off", anchorContext)
    Then 返回 CompiledGraph
    And 图节点含 entry、summarize_node、answer_node
    And 图节点集合不含 tools
    And 边: entry → summarize_node → answer_node → END

  场景: off 模式 answer_node 使用 HtmlOutputConverter
    When create("off", anchor)
    Then answer_node 关联的 StructuredOutputConverter instanceof HtmlOutputConverter
    And converter.containerClass == "snap-inject"
    And converter.stripThinking == true
    And converter.sanitize == true
```

#### UC-05: off 模式 HtmlOutputConverter 输出
```gherkin
@priority:high @type:unit
功能: off 模式 LLM 输出转换

  场景: LLM 输出经 HtmlOutputConverter 转换
    Given HtmlOutputConverter(stripThinking=true, sanitize=true, containerClass="snap-inject")
    When LLM 输出 "</think>\n<p>答案</p>"
    Then convert 后输出含 "<div class=\"snap-inject\">" 包裹
    And 输出不含  标签

  场景: 不触发工具事件
    Given off 模式图执行
    When LLM 流式输出
    Then 仅触发 onThought 事件
    And onToolUse/onToolResult 从未被调用
```

#### UC-06: off 模式无工具事件
```gherkin
@priority:medium @type:unit
功能: off 模式事件流

  场景: SSE 仅推送 thought
    Given off 模式图执行且 LLM 输出 "Hello World"
    When 节点流转
    Then onThought("Hello") 和 onThought("World") 按序被调用
    And onToolUse 从未被调用
```

#### UC-07: AnchorGraphFactory.create("inject") 构建线性图
```gherkin
@priority:high @type:unit
功能: inject 模式图构建

  场景: create("inject") 返回线性图 (preprocess → generate → cache)
    Given HtmlOutputConverter + template 已配置
    When AnchorGraphFactory.create("inject", anchorContext)
    Then 返回 CompiledGraph
    And 图节点含 entry、preprocess_node、generate_node、cache_node
    And 边: entry → preprocess_node → generate_node → cache_node → END

  场景: inject 模式 generate_node 使用 HtmlOutputConverter + template
    When create("inject", anchor)
    Then generate_node 关联的 converter instanceof HtmlOutputConverter
    And converter.template 非空
```

#### UC-08: inject 模式 HtmlOutputConverter + template
```gherkin
@priority:high @type:unit
功能: inject 模式 HTML 生成

  场景: template 骨架包裹 LLM 片段输出
    Given HtmlOutputConverter(template="<section class=\"anchor-card\">{content}</section>")
    When LLM 输出 "<p>片段内容</p>"
    Then convert 后输出含 "<section class=\"anchor-card\">" 包裹
    And 输出含 "<p>片段内容</p>"
    And 输出含 container div 包裹
```

#### UC-09: inject 模式 cache_node 写入 AnchorHtmlCache
```gherkin
@priority:medium @type:unit
功能: inject 模式 HTML 缓存

  场景: HTML 片段写入缓存
    Given cache_node 执行且 generate_node 输出 html 片段
    When cache_node.execute(state)
    Then AnchorHtmlCache.put(contentHash, htmlFragment, TTL=30min) 被调用
    And state["inject.cached"] == true

  场景: 二次执行命中缓存
    Given 同 anchor.content 二次执行 inject 模式
    When cache_node 检查缓存
    Then 命中缓存且 generate_node 不再调用 LLM
```

#### UC-10: AnchorPreprocessNode 并行 CompletableFuture
```gherkin
@priority:high @type:unit
功能: preprocess 并行调度

  场景: 启动 summary + classify 并行 Future
    Given AnchorPreprocessNode.execute(state)
    When 调用
    Then CompletableFuture.supplyAsync(summaryTask) 被启动
    And CompletableFuture.supplyAsync(classifyTask) 被启动
    And state["preprocess.summaryFuture"] 和 state["preprocess.classifyFuture"] 各持有 Future

  场景: 两个 Future 完成
    Given 两个 Future 启动
    When state["preprocess.summaryFuture"].join()
    Then 返回 SummaryResult 且不阻塞 classify
```

#### UC-11: POST /anchor/preprocess 正常流程
```gherkin
@priority:high @type:integration
功能: preprocess 端点

  场景: 完整 preprocess 返回 preprocessId
    Given skillRegistry.all() 返回 [SkillMeta("patrol","运维巡检",AVAILABLE)]
    And LLM mock: prompt 含 "可用技能" → onThought '{"skillId":"patrol","confidence":0.9}'
    And LLM mock: prompt 含 "摘要" → onThought "content summary"
    And anchor.content length=200 (超 threshold=100)
    When POST /anchor/preprocess body={anchor:{name,content,pageUrl}, question:"why QPS drop?"}
    Then 返回 200 且 $.preprocessId 存在 且 $.status == "started"
```

#### UC-12: POST /anchor/preprocess 入参校验
```gherkin
@priority:high @type:integration
功能: preprocess 入参校验

  场景大纲: anchor 必填字段缺失
    Given body = <body>
    When POST /anchor/preprocess
    Then 返回 400
    例子:
      | body                          | 说明       |
      | {question:"q"}                | 缺 anchor  |
      | {anchor:{content:"c"}}         | 缺 name    |
      | {anchor:{name:"n"}}           | 缺 content |
      | {anchor:{name:"",content:""}} | 空值       |
```

#### UC-13: AnchorSummaryCache miss/hit/TTL
```gherkin
@priority:high @type:unit
功能: 摘要缓存行为

  场景: 首次 miss + 二次 hit
    Given cache 为空
    When getOrCreate("u1", "content", supplier)
    Then supplier.get() 被调用 once 且返回 supplier 结果
    Given 同 userId+content 已缓存
    When getOrCreate("u1", 同 content, supplier)
    Then supplier.get() 从未被调用且返回与首次相同的结果

  场景: 不同 content 分别计算
    When getOrCreate("u1", "c1", s1) 然后 getOrCreate("u1", "c2", s2)
    Then s1.get() 和 s2.get() 各被调用 once 且 cache.size() == 2

  场景: invalidateAll 清空
    Given cache 有 2 条
    When invalidateAll()
    Then cache.size() == 0

  场景: TTL 10min 过期
    Given 缓存写入时间 T
    When T + 10min + 1s 时 getOrCreate
    Then supplier 重新被调用
```

#### UC-14: AnchorSummaryCache per-user 隔离
```gherkin
@priority:medium @type:unit
功能: per-user 缓存隔离

  场景: 不同 userId 相同 content 分别计算
    Given cache 为空
    When getOrCreate("u1", "content", s1) 然后 getOrCreate("u2", "content", s2)
    Then s1.get() 和 s2.get() 各被调用 once
    And 两条缓存 key 不同 (含 userId)
```

#### UC-15: AnchorSkillClassifier 正常 + 降级
```gherkin
@priority:high @type:unit
功能: 分类器解析与降级

  场景: 正常 JSON 解析
    Given LLM 返回 '{"skillId":"patrol","confidence":0.9,"reason":"ops"}'
    When classify("q", "content")
    Then result.skillId == "patrol" 且 confidence == 0.9 且 isMatch() == true (threshold=0.5)

  场景: skillId="null" 转换
    Given LLM 返回 '{"skillId":"null","confidence":0.1}'
    When classify
    Then result.skillId == null 且 isMatch() == false

  场景: malformed JSON 降级
    Given LLM 返回 "not a json at all"
    When classify
    Then 返回 ClassifyResult.defaultCategory() (skillId="general", confidence=0.0)

  场景: LLM 异常降级
    Given llmClient.stream 抛 RuntimeException
    When classify("q", "c")
    Then 返回 ClassifyResult.defaultCategory() 且不向上抛出

  场景: 低置信度降级
    Given threshold=0.5
    When classify 返回 confidence=0.3
    Then isMatch()==false 且 skillId 回退为 "general"

  场景大纲: JSON 提取边界
    Given LLM 返回 <raw>
    When extractJson(raw)
    Then 结果 = <expected>
    例子:
      | raw                       | expected            |
      | "no braces"               | null                |
      | "{only start"             | null                |
      | "text {\"a\":1} trailing"  | "{\"a\":1}"         |
      | "{\"a\":1}{\"b\":2}"       | "{\"a\":1}{\"b\":2}" |
```

#### UC-16: HtmlOutputConverter stripThinking + sanitize + container
```gherkin
@priority:high @type:unit
功能: HTML 输出转换

  场景: stripThinking 过滤思考前缀
    Given HtmlOutputConverter(stripThinking=true)
    When LLM 输出 "</think>reasoning\n</thinking>\n<p>answer</p>"
    Then convert 后输出不含  标签和 "reasoning"

  场景: sanitize 移除 script 标签
    Given sanitize=true
    When LLM 输出 "<script>alert(1)</script><p>ok</p>"
    Then convert 后输出移除 <script> 标签
    And 输出含 "<p>ok</p>"

  场景: container div 包裹
    Given containerClass="snap-inject"
    When LLM 输出 "<p>answer</p>"
    Then convert 后输出被 "<div class=\"snap-inject\"><p>answer</p></div>" 包裹

  场景: stripThinking=false 保留思考标签（调试模式）
    Given stripThinking=false
    When LLM 输出 "..."

  场景: getFormat 返回 HTML 结构指令
    Given HtmlOutputConverter
    When getFormat()
    Then 返回字符串含 "HTML" 或 "html" 关键字
```

#### UC-17: HtmlOutputConverter template 骨架
```gherkin
@priority:medium @type:unit
功能: HTML 输出 template

  场景: template 包裹片段
    Given HtmlOutputConverter(template="<section class=\"anchor-card\">{content}</section>")
    When LLM 输出 "<p>片段</p>"
    Then convert 后输出含 "<section class=\"anchor-card\">" 包裹
    And 输出含 "<p>片段</p>"
```

#### UC-18: 客户端 Turndown DOM→Markdown 转换契约
```gherkin
@priority:medium @type:unit
功能: DOM 转 Markdown 契约

  场景: 基础 DOM 转 Markdown
    Given 客户端 dom = <h2>标题</h2><p>段落</p>
    When Turndown.turndown(dom)
    Then 转为 "## 标题\n\n段落"

  场景: 表格转换
    Given dom 含 <table>
    When Turndown
    Then 转为 Markdown 表格语法

  场景: 代码块转换
    Given dom 含 <pre><code>code</code></pre>
    When Turndown
    Then 转为 Markdown 代码块语法
```

#### UC-19: 短内容 < 4000 跳过摘要
```gherkin
@priority:high @type:unit
功能: 短内容跳过摘要

  场景大纲: 内容长度边界
    Given content.length = <length>
    When summaryTask 执行
    Then <expected>
    例子:
      | length | expected |
      | 3999 | 返回原 content 且 llmClient.stream 从未被调用 |
      | 4000 | 触发 LLM 摘要（边界含） |
      | 4001 | 触发 LLM 摘要 |
      | null | 返回 null 且不抛 NPE |
```

#### UC-20: preprocess 缓存命中复用
```gherkin
@priority:high @type:integration
功能: 预摘要缓存复用

  场景: 同 content 二次 preprocess 命中缓存
    Given 长 content（超 threshold）
    When 第一次 POST /anchor/preprocess 然后 等待 500ms
    And 第二次 POST /anchor/preprocess（同 content）
    Then 第二次 summary LLM 调用数 == 第一次（缓存命中不新增）
```

---

## 4. 接口规格 (API Specs)

```yaml
POST /snap-agent/runs (扩展): 新增 anchor object + skillId="auto"|"off"|"inject"
  Request: {skillId:"auto"|"off"|"inject", inputs:{message:string}, anchor:{name,content,pageUrl,truncated,originalLength,meta}}
  Response: 202 {taskId,status,streamUrl} | 400 INVALID_INPUT | 409 ANCHOR_DISABLED | 429 RATE_LIMITED
  TestCases: auto+anchor→202, off+anchor→202, inject+anchor→202, 缺message→400, anchor禁用→409
POST /snap-agent/anchor/preprocess (认证required):
  Request: anchor(required) + question(可null)
  Response: 200 {preprocessId,status:"started"} | 400 | 401 | 503
GET /snap-agent/anchor/config (公开):
  Response: 200 {enabled:boolean, disabledPaths:[string], modes:["auto","off","inject"]}
```

内部接口:
```java
// AnchorGraphFactory — 三模式图构建
CompiledGraph create(String mode, AnchorContext anchor);  // mode = "auto" | "off" | "inject"
// AnchorPreprocessNode — 并行预摘要
GraphState execute(GraphState state, ExecutionContext ctx);  // 启动 summary+classify CompletableFuture
// AnchorSummaryCache — per-user, TTL=10min
SummaryResult getOrCreate(String userId, String content, Supplier<SummaryResult> supplier);
void invalidateAll();
// AnchorSkillClassifier — 降级 default category
ClassifyResult classify(String query, String content);  // 失败返回 ClassifyResult.defaultCategory()
// HtmlOutputConverter (StructuredOutputConverter<String>)
String convert(String llmOutput);  // stripThinking + sanitize + container div
String getFormat();
```

---

## 5. 数据规格 (Data Specs)

```yaml
实体: AnchorContext
字段: name(String非null非空) | content(String非null非空, Markdown) | truncated(boolean) | originalLength(long) | meta(Map,LinkedHashMap不可变) | pageUrl(String可null)
约束: fromMap 缺 name 或 content 返回 null | meta 为 null 时转 emptyMap
测试数据: {name:"订单状态区块", content:"## 订单状态\n已发货", pageUrl:"/order/detail?id=123"}
边界: content:""→fromMap null | content>8000→客户端截断 | content>=4000→触发摘要 (US-9)
实体: SummaryResult
字段: content(String, Markdown摘要) | source(String="llm"|"cache"|"skip-short"|"fallback-original") | truncated(boolean)
实体: ClassifyResult
字段: skillId(String可null) | confidence(double[0,1]) | reason(String) | isMatch(boolean, threshold=0.5)
常量: ClassifyResult.defaultCategory() → skillId="general", confidence=0.0
实体: HtmlOutputConverter
字段: containerClass(String="snap-inject") | template(String可null) | stripThinking(boolean=true) | sanitize(boolean=true)
缓存: AnchorSummaryCache(Caffeine, expireAfterWrite=10min, key=userId+SHA256(content))
缓存: AnchorHtmlCache(Caffeine, expireAfterWrite=30min, key=SHA256(content))
```

---

## 6. 错误处理规格 (Error Handling)

| 错误码 | 级别 | 描述 | 用户提示 | 告警策略 |
|--------|------|------|----------|----------|
| E401 | WARN | preprocess 缺 anchor | missing or invalid 'anchor' field | 不告警 |
| E402 | WARN | anchor 缺 name/content | anchor must have non-empty 'name' and 'content' | 不告警 |
| E403 | ERROR | AnchorGraphFactory 未配置 | anchor graph factory not configured | 告警 |
| E404 | ERROR | anchor 功能禁用 | anchor Q&A feature is not enabled | 不告警 |
| E405 | WARN | 分类器失败 | (客户端不可见，降级 default category) | 连续 5 次告警 |
| E406 | WARN | 摘要器失败 | (客户端不可见，返回原文，缓存不写入) | 连续 5 次告警 |
| E407 | ERROR | HtmlOutputConverter sanitize 失败 | (兜底返回原输出 + ERROR 日志) | 告警 |
| E408 | WARN | inject 模式 cache 写入失败 | (客户端不可见，不缓存) | 不告警 |

**降级策略**: 分类器失败→ClassifyResult.defaultCategory()→"general" 走通用 LLM；摘要器失败→返回原 content（缓存不写入）；preprocess Future 未完成→resolveSummary 走 cache/inline 重算；HtmlOutputConverter sanitize 失败→兜底返回原输出 + ERROR 日志。

```gherkin
场景: 分类器对客户端不可见
  When classify 抛异常
  Then 返回 ClassifyResult.defaultCategory() 且图执行正常，SSE 流不中断
场景: 摘要器失败返回原文
  Given content.length > 4000
  When summarizer.summarize(content) 且 LLM 抛异常
  Then 返回原 content 且缓存不写入（下次重新计算）
场景: HtmlOutputConverter sanitize 兜底
  Given sanitize 抛异常
  When convert
  Then 返回原 LLM 输出 + ERROR 日志（不中断图执行）
```

---

## 7. 非功能需求 (NFR)

- **性能**: 首 token 缓存命中<3s / 未命中<8s；摘要缓存命中率>60%；preprocess 响应<100ms；AnchorSummaryCache getOrCreate < 1ms（缓存命中）
- **安全**: POST /anchor/preprocess 需认证；GET /anchor/config 公开；POST /runs 继承 securityGateway；anchor.content 经 Turndown 防 XSS；HtmlOutputConverter sanitize 强制启用；AnchorSummaryCache per-user 隔离防上下文泄露
- **可测试性**: AnchorGraphFactory 构造注入 ReActGraphFactory/HtmlOutputConverter 可 Mock；AnchorContext.fromMap 纯函数；AnchorSummaryCache 不依赖 Spring；AnchorSkillClassifier extractJson/extractJsonField 为 package-private static；HtmlOutputConverter 纯逻辑无 Spring 依赖

---

## 8. 测试策略 (Test Strategy)

| 测试ID | 类型 | 描述 | 优先级 |
|--------|------|------|--------|
| UT-401~402 | 单元 | AnchorGraphFactory.create("auto") 构建 ReAct 图 + anchor context 作为 skill body | P0 |
| UT-403 | 单元 | AnchorGraphFactory.create("off") 构建线性图 (summarize→answer) | P0 |
| UT-404 | 单元 | AnchorGraphFactory.create("inject") 构建线性图 (preprocess→generate→cache) | P0 |
| UT-405~406 | 单元 | AnchorContext augmentMessage 拼接/truncated/fromMap | P0 |
| UT-407~408 | 单元 | AnchorPreprocessNode 并行 CompletableFuture + Future 完成 | P0 |
| UT-409~411 | 单元 | AnchorSummaryCache miss/hit/TTL/per-user/invalidate | P0/P1 |
| UT-412~415 | 单元 | AnchorSkillClassifier 正常/malformed/extractJson边界/异常降级/低置信度 | P0/P1 |
| UT-416~418 | 单元 | HtmlOutputConverter stripThinking/sanitize/container/template | P0 |
| UT-419 | 单元 | 短内容 < 4000 跳过摘要边界 | P0 |
| UT-420 | 单元 | inject 模式 cache_node 写入缓存 + 二次命中 | P1 |
| IT-401~403 | E2E | GET /anchor/config(默认/未授权/disabledPaths) | P0/P1 |
| IT-404~407 | E2E | POST /anchor/preprocess(正常/短内容/缺anchor/未授权) | P0 |
| IT-408~410 | E2E | POST /runs auto+anchor / off+anchor / inject+anchor (202/禁用4xx) | P0 |
| IT-411 | E2E | preprocess 缓存命中复用 | P0 |

**Mock 策略**: Mock LlmClient(doAnswer 按 prompt 分流)/SkillRegistry/SecurityGateway/GraphExecutor/TaskStore/ToolCallbackRegistry/RateLimiter/TaskExecutor；不Mock AnchorGraphFactory/AnchorPreprocessNode/AnchorSummaryCache/AnchorSkillClassifier/HtmlOutputConverter/AnchorContext/ClassifyResult

---

## 9. 依赖与前置条件

外部依赖: Caffeine cache(已完成) / LlmClient SPI(已完成, 失败降级) / SkillRegistry(已完成)
内部依赖:
- snap-agent-core: StateGraph/Node/CompiledGraph/GraphExecutor/GraphState/ReActGraphFactory (Section 1)
- snap-agent-core: StructuredOutputConverter/HtmlOutputConverter (Section 2)
- snap-agent-core: Advisor/AdvisorNode (Section 2)
- snap-agent-spring-boot-2x-starter: SnapAgentProperties.Anchor / SnapAgentController / AgentTask / TaskStore / TranscriptEvent (已完成)

---

## 10. 可观测性设计

日志: preprocessId, anchorName, contentLength, summarySource(llm|cache|skip-short|fallback-original), classifySkillId, classifyConfidence, graphMode(auto|off|inject), mainLatencyMs, htmlCacheHit
指标: anchor_preprocess_count{mode} / anchor_summary_cache_hit_total / miss_total / anchor_classify_confidence_histogram / anchor_classify_fallback_total / anchor_main_first_token_latency_seconds{mode} / anchor_html_cache_hit_total / anchor_html_sanitize_reject_total
追踪: MicrometerObservationAdvisor span "snap-agent.anchor.preprocess" / "snap-agent.anchor.graph"

---

## 11. 原型与交互参考

| 流程 | 说明 |
|------|------|
| 锚点点击 | 客户端 anchor.js 触发 POST /anchor/preprocess → 抽屉滑出 |
| 提问 (auto) | POST /runs skillId="auto" + anchor → ReAct 图 → SSE 流式回传 thought/tool 事件 |
| 提问 (off) | POST /runs skillId="off" + anchor → 线性图 → SSE 流式回传 thought 事件，输出经 HtmlOutputConverter |
| 提问 (inject) | POST /runs skillId="inject" + anchor → 线性图 → 生成 HTML 片段缓存 → 返回 cached htmlUrl |
| 抽屉渲染 | Shadow DOM 隔离样式，thought event 流式渲染 token；inject 模式直接 innerHTML 注入 sanitize 后的 HTML |

---

## 12. 附录

### 12.1 已有测试覆盖

| 测试文件 | 测试数 | 覆盖点 |
|----------|--------|--------|
| (2.x 重构后旧测试已废弃，新测试待编写) | - | - |

**结论**: 2.x 架构重构删除旧 `AnchorOrchestrator` 抽象，所有 2.x 测试为新增。优先实现 UT-401~420 (单元) 和 IT-401~411 (E2E)。

### 12.2 E2E 关键路径

| 路径ID | 关键路径 | 端点 | 状态 |
|--------|----------|------|------|
| E2E-1 | 完整 auto QA 流程: GET /anchor/config → POST /anchor/preprocess (返回 preprocessId) → POST /runs (skillId="auto"+anchor, preprocessId 复用) → SSE stream → done | GET /anchor/config, POST /anchor/preprocess, POST /runs, GET /runs/{id}/stream | ⚠未实现 (G-401) |
| E2E-2 | "off" 模式: POST /runs (skillId="off", 无工具) → SSE stream → 纯上下文问答 → HtmlOutputConverter 输出 | POST /runs | ⚠未实现 (G-402) |
| E2E-3 | "inject" 模式: POST /runs (skillId="inject") → 线性图执行 → HTML 片段缓存 → 二次请求命中缓存 | POST /runs | ⚠未实现 (G-403) |
| E2E-4 | 400 错误路径: POST /runs (auto, 缺 inputs.message) → 400 | POST /runs | ⚠未实现 (G-404) |
| E2E-5 | 429 限流: POST /runs 超并发/配额 → 429 | POST /runs | ⚠未实现 (G-405) |
| E2E-6 | SSE 断言: POST /runs → SSE stream → 验证 thought/tool_call/done 事件序列和内容 | GET /runs/{id}/stream | ⚠未实现 (G-406) |
| E2E-7 | preprocess 缓存命中: POST /anchor/preprocess (相同 content) → 二次调用 summary LLM 调用数不增 | POST /anchor/preprocess | ⚠未实现 (G-407) |
| E2E-8 | HtmlOutputConverter sanitize: POST /runs (off, LLM 输出含 <script>) → SSE 输出不含 <script> | POST /runs | ⚠未实现 (G-408) |

### 12.3 测试缺口

| 缺口ID | 描述 | 优先级 | 建议测试 |
|--------|------|--------|----------|
| G-401 | `AnchorGraphFactory.create("auto"/"off"/"inject")` 三模式图构建无单测 | P0 | UT-401~404 |
| G-402 | `AnchorGraphFactory.create("off"/"inject")` 线性图节点序列与边无单测 | P0 | UT-403~404 |
| G-403 | `AnchorPreprocessNode` 并行 CompletableFuture 启动与 Future 完成无单测 | P0 | UT-407~408 |
| G-404 | `AnchorSummaryCache` miss/hit/TTL/per-user 隔离无单测 | P0/P1 | UT-409~411 |
| G-405 | `AnchorSkillClassifier` 正常/malformed/extractJson边界/异常降级/低置信度无单测 | P0/P1 | UT-412~415 |
| G-406 | `HtmlOutputConverter` stripThinking/sanitize/container/template 无单测 | P0 | UT-416~418 |
| G-407 | `HtmlOutputConverter.getFormat()` HTML 结构指令返回值无单测 | P1 | UT-416 |
| G-408 | 短内容 < 4000 跳过摘要边界无单测 | P0 | UT-419 |
| G-409 | `inject` 模式 `cache_node` 写入缓存与二次命中无单测 | P1 | UT-420 |
| G-410 | `AnchorContext.augmentMessage` 拼接/truncated/fromMap 边界无单测 | P0 | UT-405~406 |
| G-411 | `POST /runs` skillId="off"/"inject" 无 E2E 覆盖 | P0 | IT-408~410 |
| G-412 | `POST /runs` skillId="auto" 缺 inputs.message 返回 400 无覆盖 | P1 | IT-408 |
| G-413 | `POST /runs` rate limit 429 无覆盖 | P2 | IT-408 |
| G-414 | SSE stream for anchor 无端到端断言 | P1 | IT-411 |
| G-415 | preprocess 缓存命中复用无 E2E 覆盖 | P0 | IT-411 |
| G-416 | HtmlOutputConverter sanitize 端到端 (LLM 输出含 <script> → 输出移除) 无 E2E | P1 | IT-411 |

> 环境限制 (G-413): anchor 专属的 maxConcurrentPerUser=1 并发限流场景需要多线程并发请求验证。standalone 单元测试是单线程的，通用 429 路径已由通用 shouldReturn429WhenRateLimited 覆盖，但 anchor 专属并发场景需要 @SpringBootTest + 多线程或 CountDownLatch。

> 环境限制 (G-414): SSE 是 SseEmitter 异步推送，standalone MockMvc 的 asyncReturn 机制无法完整模拟 EventSource 客户端消费。需要真实 SSE 连接（@SpringBootTest + WebTestClient 或 Playwright E2E）。

### 12.4 参考文档
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` (Section 4 Anchor 三种图模式, Section 2 StructuredOutputConverter/HtmlOutputConverter)
- `docs/tdd/TEMPLATE.md`
- `snap-agent-core/.../graph/` (StateGraph, Node, CompiledGraph, ReActGraphFactory — 2.x 新增)
- `snap-agent-core/.../converter/` (HtmlOutputConverter — 2.x 新增)
- `snap-agent-core/.../anchor/` (AnchorGraphFactory, AnchorPreprocessNode — 2.x 新增)

### 12.5 术语表

| 术语 | 定义 |
|------|------|
| AnchorGraphFactory | 2.x 替代旧 AnchorOrchestrator，按 mode 构建三种图 (auto/off/inject) |
| auto 模式 | skillId="auto"，复用 ReActGraphFactory 构建 ReAct 图，anchor context 作为虚拟 skill body，LLM 可调用工具 |
| off 模式 | skillId="off"，线性图 (summarize → answer)，无工具，纯上下文问答，HtmlOutputConverter 输出 |
| inject 模式 | skillId="inject"，线性图 (preprocess → generate → cache)，生成 HTML 片段缓存，HtmlOutputConverter + template 输出 |
| AnchorPreprocessNode | 启动 CompletableFuture 并行执行 summary + classify 的图节点 |
| AnchorSummaryCache | per-user, Caffeine, expireAfterWrite=10min 的摘要缓存 |
| AnchorSkillClassifier | 分类器，失败/malformed JSON/低置信度时降级为 default category "general" |
| HtmlOutputConverter | StructuredOutputConverter 实现，stripThinking + sanitize + container div 包裹 + 可选 template |
| AnchorHtmlCache | inject 模式 HTML 片段缓存，Caffeine, TTL=30min |
| summaryThresholdChars | 内容长度阈值（默认 4000），>= 则触发摘要 LLM 调用 |
| classifierConfidenceThreshold | 分类器置信度阈值（默认 0.5），低于则降级 default category |
