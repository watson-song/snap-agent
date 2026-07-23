# TDD需求规格说明书 — Anchor Q&A 锚点问答模式

> 版本: 2.0 | 模块: snap-agent-spring-boot-2x-starter / anchor | 状态: 开发中

---

## 1. 需求元信息

```yaml
需求ID: REQ-04-ANCHOR-QA
需求名称: AnchorOrchestrator Q&A + auto/off 双模路由 + SSE 流式
优先级: P0
迭代: Sprint v0.11
负责人: anchor-team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: 现有 SnapAgent 只在 `/snap-agent/` 挂独立 SPA，用户浏览宿主页面想"针对当前内容提问"必须复制内容切到 SPA，上下文割裂体验差。
- **用户价值**: 宿主引入一行 `<script src="/snap-agent/anchor.js" defer>` 后，点击锚点图标即可在右侧抽屉发起针对该区域内容的 LLM 问答，减少 70% 上下文切换成本。
- **成功指标**: 首 token 延迟 < 3s（缓存命中），摘要缓存命中率 > 60%，分类器降级路径可观测。

### 1.2 范围边界
- **包含**: `AnchorOrchestrator` 并行调度、`AnchorContext`、`AnchorContextSummarizer`、`AnchorSkillClassifier`、`AnchorSummaryCache`、`POST /runs` 扩展 `skillId="auto"|"off"` + `anchor`、`POST /anchor/preprocess`、`GET /anchor/config`、SSE 流式。
- **不包含**: 客户端 `anchor.js` DOM 扫描与 Shadow DOM UI、`POST /anchor/inject`（见 05-anchor-inject）、黑名单路径精细配置（v1.1）。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 |
|--------|------|------|------|----------|
| R1 | 分类器+摘要器+主回答 3 次 LLM 调用，首 token 8-15s | 高 | 高 | 并行 CompletableFuture + 预摘要 + 缓存 |
| R2 | 分类器 malformed JSON 或低置信度 | 中 | 中 | 降级为通用 LLM 直答 |
| R3 | 摘要器 LLM 失败/超时 | 中 | 中 | 返回原始 content，跳过摘要 |
| R4 | preprocess Future 未完成时 execute 被调用 | 中 | 中 | resolveSummary/resolveClassify 降级 inline 重算 |

**关键假设**: 客户端已用 Turndown 将 DOM 转 Markdown；短内容（< 4000）跳过摘要；`skillId="auto"` 走虚拟 skill + AgentExecutor（有工具），`skillId="off"` 走 AnchorOrchestrator 直连 LLM（无工具）。

---

## 2. 用户故事 (User Stories)

### US-1: auto 模式带工具智能问答
```gherkin
作为 宿主页面浏览者
我希望 点击锚点后在抽屉提问，LLM 能结合区块内容 + 工具（jdbc_query 等）回答
以便 不切页面即可获得 80% 以上问题的即时解答
```
**AC:** AC1: Given anchor.enabled=true 且 anchorOrchestrator 已配置 / When POST /runs skillId="auto" + anchor + inputs.message 非空 / Then 返回 202 + {taskId, streamUrl} 且异步执行虚拟 skill "anchor-auto"；AC2: Given inputs.message 为空 / When POST /runs / Then 返回 400 + INVALID_INPUT。

### US-2: off 模式纯上下文问答
```gherkin
作为 宿主页面浏览者
我希望 skillId="off" 时 LLM 仅基于区块内容直答，不调用工具
以便 对简单内容解释类问题获得 < 2s 首 token 延迟
```
**AC:** AC1: Given skillId="off" + anchor / When POST /runs / Then 创建 task 并异步调 executeWithAnchor(anchor, question, preprocessId, sink) 且 SUCCEEDED；AC2: Given mainSink 已注册 / When executeWithAnchor / Then onThought token 通过 SSE 回传，onToolUse/onToolResult 不触发。

### US-3: 预摘要并行调度
```gherkin
作为 系统优化
我希望 点击锚点瞬间触发 preprocess（summary + classify 并行），用户提交问题时复用结果
以便 首 token 延迟从 8s 降至 2-3s
```
**AC:** AC1: Given POST /anchor/preprocess body 含 anchor + question / When 调用 / Then 返回 200 + {preprocessId, status:"started"}；AC2: Given content.length <= threshold / When preprocess / Then 不调 LLM 摘要；AC3: Given body 无 anchor / When POST /anchor/preprocess / Then 返回 400。

### US-4: 摘要缓存命中
```gherkin
作为 成本控制
我希望 相同 content 的摘要结果 10 分钟内复用缓存
以便 减少 60% 摘要 LLM 调用量
```
**AC:** AC1: Given 缓存为空 / When getOrCreate(content, supplier) / Then supplier.get() 调用 once 且缓存 size 增 1；AC2: Given 同 content 已缓存 / When getOrCreate / Then supplier 从未调用且返回首次结果。

### US-5: 分类器降级
```gherkin
作为 系统鲁棒性
我希望 分类器失败、malformed JSON、低置信度时降级为通用 LLM 直答
以便 用户始终能获得回答，分类器失败对客户端不可见
```
**AC:** AC1: Given threshold=0.5 / When classify 返回 confidence=0.3 / Then isMatch()==false 且走通用 LLM；AC2: Given LLM 返回 "not a json" / When classify / Then 返回 ClassifyResult.noMatch()。

### US-6: GET /anchor/config 端点
```gherkin
作为 宿主页面前端
我希望 GET /anchor/config 返回 enabled 和 disabledPaths
以便 客户端知道哪些页面可显示锚点图标
```
**AC:** AC1: Given anchor.enabled=true / When GET /anchor/config / Then 返回 200 + {enabled:true, disabledPaths:[...]}；AC2: Given anchor.enabled=false / When GET /anchor/config / Then 返回 200 + {enabled:false}；AC3: Given 无认证 / When GET /anchor/config (公开端点) / Then 返回 200 (无需认证)。

### US-7: SSE 流式回传
```gherkin
作为 宿主页面浏览者
我希望 提问后通过 SSE 实时看到 LLM 输出 token
以便 不需等待完整回答即可开始阅读
```
**AC:** AC1: Given POST /runs 返回 streamUrl / When 客户端连接 SSE / Then 接收 thought 事件流式推送；AC2: Given LLM 输出 "Hello World" / When SSE 传输 / Then onThought 按序推送 "Hello" "World" 两个 token。

### US-8: 限流 429 防护
```gherkin
作为 系统运维
我希望 POST /runs 对单用户有并发和小时配额限制
以便 防止单用户耗尽 LLM 配额
```
**AC:** AC1: Given maxConcurrentPerUser=1 且用户 u1 已有一个运行中任务 / When POST /runs (同用户) / Then 返回 429 RATE_LIMITED；AC2: Given maxRunsPerHour=20 且 u1 已用 20 次 / When POST /runs / Then 返回 429。

### US-9: AnchorContext 数据模型
```gherkin
作为 系统开发者
我希望 AnchorContext 正确拼接 pageUrl/name/content/question 且处理截断
以便 LLM 收到结构化的区块上下文
```
**AC:** AC1: Given anchor = AnchorContext("订单状态", "已发货\nSF123", "/order/1") / When augmentMessage("什么时候到？") / Then 返回文本含 pageUrl、name、content、question 四部分；AC2: Given anchor.truncated=true, originalLength=52180 / When augmentMessage("q") / Then 返回文本含 "内容已截断，原始长度 52180 字符"；AC3: Given AnchorContext.fromMap({name:"n", content:"c"}) / When 构造 / Then 非null 且 truncated=false；AC4: Given AnchorContext.fromMap({name:"", content:""}) / When 构造 / Then 返回 null。

---

## 3. 功能规格 (Functional Specs)

### 3.2 详细用例 (Gherkin)

#### UC-01: POST /anchor/preprocess 正常流程
```gherkin
@priority:high @type:e2e
功能: preprocess 并行调度
  场景: 完整 preprocess 返回 preprocessId
    Given skillRegistry.all() 返回 [SkillMeta("patrol","运维巡检",AVAILABLE)]
    And LLM mock: prompt 含 "可用技能" → onThought '{"skillId":"patrol","confidence":0.9}'
    And LLM mock: prompt 含 "摘要" → onThought "content summary"
    And anchor.content length=200 (超 threshold=100)
    When POST /anchor/preprocess body={anchor:{name,content,pageUrl}, question:"why QPS drop?"}
    Then 返回 200 且 $.preprocessId 存在 且 $.status == "started"
```

#### UC-02: preprocess 入参校验
```gherkin
@priority:high @type:e2e
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

#### UC-03: POST /runs auto+anchor 创建 task
```gherkin
@priority:high @type:e2e
功能: auto 模式任务创建
  场景: auto+anchor 返回 202 + taskId
    Given anchor.enabled=true 且 rateLimiter.tryAcquire=true
    And body = {skillId:"auto", inputs:{message:"what is this?"}, anchor:{name:"test-section", content:"some content", pageUrl:"/p"}}
    When POST /runs
    Then 返回 202 且 $.taskId 存在 且 $.streamUrl 存在
  场景: anchor 禁用时返回 4xx
    Given anchor.enabled=false
    And body = {skillId:"auto", inputs:{message:"q"}, anchor:{name,content,pageUrl}}
    When POST /runs
    Then 返回 4xx
```

#### UC-04: off 模式直连 AnchorOrchestrator
```gherkin
@priority:high @type:unit
功能: off 模式 executeWithAnchor
  场景: off 模式异步调 executeWithAnchor + 流式回传
    Given skillId="off" 且 anchor 字段存在 且 preprocessId="prep_123"
    When createAnchorRun 执行
    Then taskExecutor.execute 提交 Runnable 且 Runnable 内部调 executeWithAnchor(anchor, question, "prep_123", sink)
    And task 最终 SUCCEEDED
    Given mainSink 已注册
    When executeWithAnchor 执行且 LLM 返回 token "Hello" "World"
    Then sink.onThought("Hello") 和 sink.onThought("World") 被调用
```

#### UC-05: AnchorContext.augmentMessage 与 fromMap
```gherkin
@priority:high @type:unit
功能: 上下文拼接与反序列化
  场景: 拼接 pageUrl + name + content + question
    Given anchor = AnchorContext("订单状态", "已发货\nSF123", "/order/1")
    When augmentMessage("什么时候到？")
    Then 返回文本含 "页面 \"/order/1\"" 且 "的 \"订单状态\" 区块" 且 "区块内容：\n已发货\nSF123" 且 "用户提问：什么时候到？"
  场景: truncated + pageUrl=null
    Given anchor.truncated=true, originalLength=52180
    When augmentMessage("q")
    Then 返回文本含 "内容已截断，原始长度 52180 字符"
    Given anchor.pageUrl=null
    When augmentMessage("q")
    Then 返回文本含 "页面 \"(unknown)\""
  场景大纲: fromMap 边界
    Given map = <map>
    When AnchorContext.fromMap(map)
    Then 结果 = <expected>
    例子:
      | map                                         | expected            |
      | null                                        | null               |
      | {}                                          | null               |
      | {name:"n"}                                  | null               |
      | {name:"", content:""}                       | null               |
      | {name:"n", content:"c"}                      | 非null             |
      | {name:"n", content:"c", truncated:true}     | 非null, truncated=true |
      | {name:"n", content:"c", originalLength:99}  | 非null, originalLength=99 |
```

#### UC-06: Summarizer 短内容跳过 + 失败降级
```gherkin
@priority:high @type:unit
功能: 摘要器降级
  场景: 短内容跳过 LLM 调用
    Given content.length <= summaryThresholdChars
    When summarizer.summarize(content)
    Then 返回原 content 且 llmClient.stream 从未被调用
  场景: LLM 失败/onError 返回原文 + null content
    Given content.length > threshold 且 llmClient.stream 抛 RuntimeException
    When summarizer.summarize(content)
    Then 返回原 content（不抛异常）
    Given llmClient.stream 调 sink.onError("boom")
    When summarizer.summarize(content)
    Then 返回原 content（accumulated 被清空）
    Given content=null
    When summarizer.summarize(null)
    Then 返回 null 且不抛 NPE
```

#### UC-07: Classifier 正常 + 降级
```gherkin
@priority:high @type:unit
功能: 分类器解析与降级
  场景: 正常 JSON 解析 + skillId="null" 转换
    Given LLM 返回 '{"skillId":"patrol","confidence":0.9,"reason":"ops"}'
    When classify("q", "content")
    Then result.skillId == "patrol" 且 confidence == 0.9 且 isMatch() == true (threshold=0.5)
    Given LLM 返回 '{"skillId":"null","confidence":0.1}'
    When classify
    Then result.skillId == null 且 isMatch() == false
  场景: malformed JSON 降级 + LLM 异常降级
    Given LLM 返回 "not a json at all"
    When classify
    Then 返回 ClassifyResult.noMatch() (skillId=null, confidence=0.0)
    Given llmClient.stream 抛 RuntimeException
    When classify("q", "c")
    Then 返回 ClassifyResult.noMatch() 且不向上抛出
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

#### UC-08: SummaryCache 缓存行为
```gherkin
@priority:high @type:unit
功能: 摘要缓存
  场景: 首次 miss + 二次 hit
    Given cache 为空
    When getOrCreate("content", supplier)
    Then supplier.get() 被调用 once 且返回 supplier 结果
    Given 同 content 已缓存
    When getOrCreate(同 content, supplier)
    Then supplier.get() 从未被调用且返回与首次相同的结果
  场景: 不同 content 分别计算 + invalidateAll
    When getOrCreate("c1", s1) 然后 getOrCreate("c2", s2)
    Then s1.get() 和 s2.get() 各被调用 once 且 cache.size() == 2
    Given cache 有 2 条
    When invalidateAll()
    Then cache.size() == 0
```

#### UC-09: preprocess 缓存命中复用
```gherkin
@priority:high @type:e2e
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
POST /snap-agent/runs (扩展): 新增 anchor object + skillId="auto"|"off"
  Response: 202 {taskId,status,streamUrl} | 400 INVALID_INPUT | 409 ANCHOR_DISABLED | 429 RATE_LIMITED
  TestCases: auto+anchor→202, off+anchor→202, 缺message→400, anchor禁用→409
POST /snap-agent/anchor/preprocess (新增, 认证required):
  Request: anchor(required) + question(可null)  Response: 200 {preprocessId,status:"started"} | 400 | 401 | 503
GET /snap-agent/anchor/config (新增, 公开): Response: 200 {enabled:boolean, disabledPaths:[string]}
```

---

## 5. 数据规格 (Data Specs)

```yaml
实体: AnchorContext
字段: name(String非null非空) | content(String非null非空, Markdown) | truncated(boolean) | originalLength(long) | meta(Map,LinkedHashMap不可变) | pageUrl(String可null)
约束: fromMap 缺 name 或 content 返回 null | meta 为 null 时转 emptyMap
测试数据: {name:"订单状态区块", content:"## 订单状态\n已发货", pageUrl:"/order/detail?id=123"}
边界: content:""→fromMap null | content>8000→客户端截断 | content>4000→触发摘要
```

---

## 6. 错误处理规格 (Error Handling)

| 错误码 | 级别 | 描述 | 用户提示 | 告警策略 |
|--------|------|------|----------|----------|
| E401 | WARN | preprocess 缺 anchor | missing or invalid 'anchor' field | 不告警 |
| E402 | WARN | anchor 缺 name/content | anchor must have non-empty 'name' and 'content' | 不告警 |
| E403 | ERROR | orchestrator 未配置 | anchor orchestrator not configured | 告警 |
| E404 | ERROR | anchor 功能禁用 | anchor Q&A feature is not enabled | 不告警 |
| E405 | WARN | 分类器失败 | (客户端不可见，降级通用 LLM) | 连续 5 次告警 |
| E406 | WARN | 摘要器失败 | (客户端不可见，返回原文) | 连续 5 次告警 |

**降级策略**: 分类器失败→ClassifyResult.noMatch()→通用 LLM；摘要器失败→返回原 content（缓存不写入）；preprocess Future 未完成→resolveSummary 走 cache/inline 重算。

```gherkin
场景: 分类器对客户端不可见
  When classify 抛异常
  Then 返回 ClassifyResult.noMatch() 且 executeWithAnchor 仍正常执行，SSE 流不中断
场景: 摘要器失败返回原文
  Given content.length > threshold
  When summarizer.summarize(content) 且 LLM 抛异常
  Then 返回原 content 且缓存不写入（下次重新计算）
```

---

## 7. 非功能需求 (NFR)

- **性能**: 首 token 缓存命中<3s / 未命中<8s；摘要缓存命中率>60%；preprocess 响应<100ms
- **安全**: POST /anchor/preprocess 需认证；GET /anchor/config 公开；POST /runs 继承 securityGateway；anchor.content 经 Turndown 防 XSS
- **可测试性**: AnchorOrchestrator 构造注入可 Mock；AnchorContext.fromMap 纯函数；SummaryCache 不依赖 Spring；Classifier extractJson/extractJsonField 为 package-private static

---

## 8. 测试策略 (Test Strategy)

| 测试ID | 类型 | 描述 | 优先级 |
|--------|------|------|--------|
| UT-401~402 | 单元 | AnchorContext augmentMessage 拼接/truncated | P0 |
| UT-403 | 单元 | AnchorContext fromMap 合法/边界 | P0 |
| UT-404~406 | 单元 | Summarizer 短内容跳过/LLM失败降级/null | P0/P1 |
| UT-407~410 | 单元 | Classifier 正常/malformed/extractJson边界/异常降级 | P0/P1 |
| UT-411~413 | 单元 | SummaryCache miss/hit/invalidate | P0/P2 |
| UT-414 | 单元 | ClassifyResult.isMatch 阈值 | P1 |
| IT-401~403 | E2E | GET /anchor/config(默认/未授权/disabledPaths) | P0/P1 |
| IT-404~407 | E2E | POST /anchor/preprocess(正常/短内容/缺anchor/未授权) | P0 |
| IT-408~409 | E2E | POST /runs auto+anchor(202/禁用4xx) | P0 |
| IT-410 | E2E | preprocess 缓存命中复用 | P0 |

**Mock 策略**: Mock LlmClient(doAnswer 按 prompt 分流)/SkillRegistry/SecurityGateway/AgentExecutor/TaskStore/ToolDispatcher/RateLimiter/TaskExecutor；不Mock AnchorOrchestrator/SummaryCache/Summarizer/Classifier/AnchorContext/ClassifyResult/PreprocessResult

---

## 9. 依赖与前置条件

外部依赖: Caffeine cache(已完成) / LlmClient SPI(已完成, 失败降级) / SkillRegistry(已完成)
内部依赖: SnapAgentProperties.Anchor / SnapAgentController / AgentTask / TaskStore / TranscriptEvent (已完成)

---

## 10. 可观测性设计

日志: preprocessId, anchorName, contentLength, summaryCached, classifySkillId, classifyConfidence, mainLatencyMs
指标: anchor_preprocess_count / anchor_summary_cache_hit_total / miss_total / anchor_classify_confidence_histogram / anchor_main_first_token_latency_seconds / anchor_classify_fallback_total

---

## 11. 原型与交互参考

| 流程 | 说明 |
|------|------|
| 锚点点击 | 客户端 anchor.js 触发 POST /anchor/preprocess → 抽屉滑出 |
| 提问 | POST /runs skillId="auto"|"off" + anchor → SSE 流式回传 |
| 抽屉渲染 | Shadow DOM 隔离样式，thought event 流式渲染 token |

---

## 12. 附录

### 12.1 已有测试覆盖

| 测试文件 | 测试数 | 覆盖点 |
|----------|--------|--------|
| `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/anchor/AnchorE2ETest.java` | 11 | GET /anchor/config（3）、POST /anchor/preprocess（5：正常/短内容/缺anchor/缺name/未授权）、POST /runs auto+anchor（2：禁用/启用）、preprocess 缓存命中复用（1） |

**结论**: E2E 层 controller 端点 + 预摘要缓存复用已覆盖。单元测试层（AnchorContext/Summarizer/Classifier/SummaryCache/Orchestrator）几乎空白，降级路径与边界值主要靠 E2E 间接覆盖，粒度不足。

### 12.2 测试缺口

| 缺口ID | 描述 | 优先级 | 建议测试 |
|--------|------|--------|----------|
| G-401 | `AnchorContext.augmentMessage` 无单测（仅 E2E 间接） | P0 | UT-401/402 |
| G-402 | `AnchorContext.fromMap` 边界（null/空/缺字段）无单测 | P0 | UT-403 |
| G-403 | `AnchorContext.needsSummary` 阈值边界无单测 | P1 | UT-403a |
| G-404~406 | `AnchorContextSummarizer` 短内容/LLM失败降级/null 无单测 | P0/P1 | UT-404~406 |
| G-407~410 | `AnchorSkillClassifier` 正常/malformed/extractJson边界/异常降级 无单测 | P0/P1 | UT-407~410 |
| G-411~413 | `AnchorSummaryCache` miss/hit/invalidate/hashKey 无单测 | P0/P1/P2 | UT-411~413a |
| G-413 | `ClassifyResult.isMatch` 阈值边界无单测 | P1 | UT-414 |
| G-414~416 | `AnchorOrchestrator` preprocess并行/resolve降级/await超时 无单测 | P1/P2 | UT-415~417 |
| G-417 | `SnapAgentController` "off" 模式无 E2E | P0 | IT-411 |
| G-418 | `POST /runs` auto 缺 inputs.message 返回 400 无覆盖 | P1 | IT-412 |
| G-419 | `POST /runs` rate limit 429 无覆盖 | P2 | IT-413 |
| G-420 | SSE stream for anchor 无端到端断言 | P1 | IT-414 |

### 12.3 参考文档
- `docs/superpowers/specs/2026-07-20-host-page-anchor-qa-design.md` | `docs/tdd/TEMPLATE.md`

### 12.4 术语表

| 术语 | 定义 |
|------|------|
| AnchorContext | 页面区块上下文（name+content+pageUrl+truncated+meta），客户端 Turndown 转 Markdown 后传服务端 |
| AnchorOrchestrator | 协调分类器 + 摘要器 + 主回答的并行调度组件 |
| preprocess | 点击锚点瞬间触发的预摘要 + 预分类，返回 preprocessId 供后续 /runs 复用 |
| auto 模式 | skillId="auto"，虚拟 skill + AgentExecutor，LLM 可调用工具 |
| off 模式 | skillId="off"，AnchorOrchestrator 直连 LLM，无工具，纯上下文问答 |
| summaryThresholdChars | 内容长度阈值（默认 4000），短于则跳过摘要 LLM 调用 |
| classifierConfidenceThreshold | 分类器置信度阈值（默认 0.5），低于则降级通用 LLM |
