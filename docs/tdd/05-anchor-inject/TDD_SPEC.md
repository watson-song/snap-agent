# TDD需求规格说明书 — 锚点注入模式 (Anchor Injection)

> 版本: 2.1 (SnapAgent 2.x 架构重构) | 模块: 05-anchor-inject | 基于 TEMPLATE.md
> 变更: AnchorInjectionOrchestrator → AnchorGraphFactory.buildInjectMode() 线性图; HTML 处理由 HtmlOutputConverter 统一承担 (含 stripThinking/sanitize)

---

## 1. 需求元信息

```yaml
需求ID: REQ-05-ANCHOR-INJECT
需求名称: 锚点内容注入模式 (Inject Mode)
优先级: P0
迭代: v0.4+
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: 宿主页面某些区域需在加载时由 SnapAgent 自动生成 HTML 注入，而非用户点击问答。SnapAgent 2.x 中，inject 模式由 AnchorGraphFactory.buildInjectMode() 产出一个线性图 (preprocess → generate → cache)，HTML 后处理由 HtmlOutputConverter 统一承担。
- **用户价值**: 页面加载即呈现 AI 内容，零交互成本；缓存命中 < 5ms 返回；stripThinking/sanitize 由 HtmlOutputConverter 统一处理。
- **成功指标**: 缓存命中率 > 70%；首屏注入 < 3s；HtmlOutputConverter 正确率 100%。

### 1.2 范围边界
- **包含**: `AnchorGraphFactory.buildInjectMode()` (线性图: preprocess → generate → cache)、`HtmlOutputConverter` (containerClass, template, stripThinking, sanitize)、`AnchorInjectionCache`、客户端 `anchor.js` inject。
- **不包含**: 锚点 Q&A 模式 (04-anchor-qa)、preprocess 预摘要细节、skill classifier、AnchorGraphFactory.buildAutoMode / buildOffMode (其他模式由 04-anchor-qa 维护)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | LLM 输出 thinking 前缀致 HTML 解析异常 | 中 | 高 | HtmlOutputConverter.stripThinking + LlmClient skipThinking 双防护 |
| R2 | 缓存 TTL 配置不当 | 低 | 中 | min/max TTL 强制约束 + 7天上限 |
| R3 | skill 未配置或不可用时注入失败 | 低 | 中 | 抛 SKILL_NOT_FOUND / SKILL_UNAVAILABLE |
| R4 | HtmlOutputConverter sanitize 误杀合法 HTML | 低 | 中 | containerClass 白名单 + 模板覆盖 |

---

## 2. 用户故事 (User Stories)

### US-1: 页面加载时自动注入 AI 内容
```gherkin
作为 宿主页面开发者
我希望 标注 data-snap-mode="inject" 后页面加载时自动生成 HTML
以便 用户无需交互即可看到 AI 内容
```
**AC:**
```gherkin
AC1: Given 元素标注 data-snap-mode="inject" data-snap-skill="announcement"
  When anchor.js 扫描到该元素
  Then 发送 POST /anchor/inject 并用返回 HTML 替换占位符
AC2: Given 同一用户同锚点结果已缓存
  When 再次请求
  Then 直接返回缓存，cached=true，不调 LLM
```

### US-2: 注入结果按用户维度缓存
```gherkin
作为 系统管理员
我希望 缓存按 userId:sourceId:anchorName:pageUrl 隔离
以便 不同用户看个性化内容，重复访问秒级返回
```
**AC:**
```gherkin
AC3: Given 用户 A 和 B 请求同一锚点
  When 先后注入
  Then LLM 调两次，各自独立结果
```

### US-3: LLM 思考过程过滤 (HtmlOutputConverter)
```gherkin
作为 系统开发者
我希望 HtmlOutputConverter 自动剥离 LLM 输出的 thinking 前缀并执行 sanitize
以便 注入的 HTML 干净且安全
```
**AC:**
```gherkin
AC4: Given LLM 原始输出 "Let me think...<div>x</div>"
  When HtmlOutputConverter.convert(raw) (stripThinking=true, sanitize=true)
  Then 返回 "<div class=\"snap-inject\">x</div>"
  And thinking 前缀被剥离
  And 根 div 含 containerClass="snap-inject"
```

### US-4: 技能 body 作为 system prompt (经 EntryNode)
```gherkin
作为 技能开发者
我希望 注入图的 generate 节点用 skill body 作为 system prompt
以便 精确控制生成风格
```
**AC:**
```gherkin
AC5: Given skill body 非空
  When AnchorGraphFactory.buildInjectMode(skill, task, advisors) 编译
  Then generate 节点 (AgentNode) 的 system prompt == skill body
  And HtmlOutputConverter.getFormat() 注入到 LLM 指令中
```

### US-5: 注入失败降级显示
```gherkin
作为 页访客
我希望 注入失败时页面不崩坏
以便 AI 不可用时仍可浏览
```
**AC:**
```gherkin
AC6: Given 标注 data-snap-fallback="<p>暂无</p>"
  When inject 失败
  Then 替换为 fallback HTML
AC7: Given 无 fallback
  When inject 失败
  Then 移除占位符，不抛异常
```

### US-6: TTL=0 禁用缓存
```gherkin
作为 系统管理员
我希望 cacheTtl=0 时不缓存注入结果
以便 每次页面加载都获取最新 AI 内容
```
**AC:**
```gherkin
AC8: Given InjectionRequest(cacheTtl=0)
  When 连续调用两次 inject
  Then 两次 cached=false，LLM 调两次
  And 缓存不写入任何条目
  And 线性图仍走完 preprocess → generate → cache 三节点 (cache 节点 no-op)
```

### US-7: skill 优先于 workflow (legacy)
```gherkin
作为 技能开发者
我希望 InjectionRequest 同时含 skillId 和 workflowId 时优先走 skill
  以便 注入内容由 LLM 直接生成，保证一致性
```
**AC:**
```gherkin
AC9: Given InjectionRequest(skillId="s1", workflowId="w1")
  When inject 执行
  Then AnchorGraphFactory.buildInjectMode() 用 skill 编译图
  And 返回 InjectionResult 含 LLM 生成的 HTML
```

### US-8: 无效输入与 skill 不存在异常处理
```gherkin
作为 系统开发者
我希望 inject 在无 skill 无 workflow 或 skill 不存在时抛明确异常
  以便 调用方可正确处理错误而非收到空 HTML
```
**AC:**
```gherkin
AC10: Given InjectionRequest(skillId=null, workflowId=null)
  When inject
  Then 抛 IllegalArgumentException 含 "INVALID_INPUT"
AC11: Given skillRegistry.get("x") 返回 null
  When inject with skillId="x"
  Then 抛 IllegalArgumentException 含 "SKILL_NOT_FOUND"
AC12: Given skill.available == UNAVAILABLE
  When inject with skillId="y"
  Then 抛 SkillUnavailableException (由 ReActGraphFactory 复用)
```

### US-9: 线性图三节点形态 (2.x 新增)
```gherkin
作为 平台开发者
我希望 AnchorGraphFactory.buildInjectMode() 产出 preprocess → generate → cache 线性图
  以便 inject 流程清晰、易调试、易插 advisor
```
**AC:**
```gherkin
AC13: Given skill.available == AVAILABLE
  When AnchorGraphFactory.buildInjectMode(skill, task, advisors)
  Then CompiledGraph.getNodes() 含 "preprocess" / "generate" / "cache" 三节点
  And 边为 preprocess→generate→cache→END (无条件边)
  And getEntryPoint() == "preprocess"

AC14: Given advisors 含 MessageChatMemoryAdvisor(order=100)
  When 图编译
  Then AdvisorNode 包裹 generate 节点
  And before chain 在 LLM 调用前执行 memory.load
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 加载 | US-1 | 零交互获取 | 完成率>95% | - |
| 缓存 | US-2 | 秒级返回 | 命中率>70% | US-1 |
| 质量 | US-3 | 干净HTML | 100% | US-1 |
| 定制 | US-4 | 精确控制 | 100% | US-1 |
| 容错 | US-5 | 不崩坏 | 100% | US-1 |
| 不缓存 | US-6 | 实时内容 | TTL=0→不缓存 100% | US-1 |
| 优先级 | US-7 | 一致性 | skill 优先 100% | US-1 |
| 异常 | US-8 | 明确错误 | 异常抛出 100% | US-1 |
| 图形态 | US-9 | 线性三节点 | 节点顺序 100% | US-1 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | 缓存未命中执行skill | P0 | AC1 | 单元 |
| UC-02 | 缓存命中直接返回 | P0 | AC2 | 单元 |
| UC-03 | 不同用户缓存隔离 | P0 | AC3 | 单元 |
| UC-04 | HtmlOutputConverter.stripThinking | P0 | AC4 | 单元 |
| UC-05 | skill body做prompt | P0 | AC5 | 单元 |
| UC-06 | TTL=0不缓存 | P1 | AC8 | 单元 |
| UC-07 | skill优先workflow | P1 | AC9 | 单元 |
| UC-08 | skill不存在抛异常 | P0 | AC11 | 单元 |
| UC-09 | UNAVAILABLE skill 抛异常 | P0 | AC12 | 单元 |
| UC-10 | 线性图三节点结构 | P0 | AC13 | 单元 |
| UC-11 | Advisor 包裹 generate | P1 | AC14 | 单元 |
| UC-12 | 客户端fallback | P1 | AC6,7 | 单元 |
| UC-R1 | POST /anchor/inject 200返回HTML | P0 | AC1 | 集成 |
| UC-R2 | POST /anchor/inject 缓存命中cached=true | P0 | AC2 | 集成 |
| UC-R3 | POST /anchor/inject 400缺anchorName | P0 | - | 集成 |
| UC-R4 | POST /anchor/inject 400无source | P0 | - | 集成 |
| UC-R5 | POST /anchor/inject 503未配置 | P0 | - | 集成 |
| UC-R6 | POST /anchor/inject 500 skill不存在 | P0 | - | 集成 |

### 3.2 详细用例 (Gherkin)

```gherkin
@priority:high @type:unit
功能: AnchorGraphFactory.buildInjectMode 线性图

  场景: 缓存未命中时执行skill并缓存
    Given skillRegistry 存在 skill "announcement" 且 body 非空 且 availability=AVAILABLE
    And llmClient.stream onThought 输出 "<div class=\"notice\">Hello!</div>"
    And InjectionRequest(anchorName="公告", pageUrl="/dashboard", skillId="announcement", cacheTtl=3600)
    When anchorGraphFactory.buildInjectMode(skill, task, advisors) + GraphExecutor.execute
    Then html contains "<div class=\"notice\">Hello!</div>" 且 cached == false
    And 再次调用 cached=true 且 LLM 不再调用

  场景: 缓存命中直接返回不调LLM
    Given 缓存已有 key "user001:announcement:公告:/page"
    When anchorGraphFactory.buildInjectMode + execute
    Then cached == true 且 llmClient.stream 调用次数为 0
    And preprocess 节点命中缓存即 END (不进 generate)

  场景: TTL=0时不缓存
    Given InjectionRequest(cacheTtl=0)
    When 连续调用两次
    Then 两次 cached=false，LLM 调两次
    And cache 节点 no-op 不写入

  场景: 不同用户缓存key不同
    Given 用户A和B请求同一锚点
    When 先后注入
    Then LLM 调两次

  场景: skill body非空时作为systemPrompt
    Given skill "announcement" body="你是公告助手"
    When buildInjectMode + generate 节点执行
    Then LlmRequest.systemPrompt == "你是公告助手"
    And maxTokens == min(injectionMaxTokens, 1024)
    And HtmlOutputConverter.getFormat() 注入到 LLM 指令

  场景: skill body为空时用默认prompt
    Given skill body 为空串
    When generate 节点执行
    Then systemPrompt 为 "你是 SnapAgent 内容生成助手..."

  场景大纲: HtmlOutputConverter.stripThinking剥离推理前缀
    Given LLM 原始输出 <raw>，containerClass="snap-inject"
    When converter.convert(raw)
    Then 返回 <expected>
    例子:
      | raw | expected | 说明 |
      | "Let me think...<div>x</div>" | "<div class=\"snap-inject\">x</div>" | 英文前缀+包裹 |
      | "让我想想<p>hi</p>" | "<p class=\"snap-inject\">hi</p>" | 中文前缀+包裹 |
      | "<div>direct</div>" | "<div class=\"snap-inject\">direct</div>" | 无前缀+包裹 |
      | "<!DOCTYPE html>" | "<!DOCTYPE html>" | DOCTYPE 不二次包裹 |
      | null | null | null |
      | "" | "" | 空串 |

  场景: inject模式下skipThinking跳过thinking_delta
    Given LlmRequest.tools 为空
    When LlmClient 解析SSE
    Then skipThinking=true，thinking_delta 不触发 onThought
    And text_delta 正常触发

  场景: skill优先于workflow
    Given InjectionRequest(skillId="s1", workflowId="w1")
    When inject 执行
    Then AnchorGraphFactory.buildInjectMode 用 skill 编译，不调用 workflow

  场景: skill不存在抛异常
    Given skillRegistry.get("x") 返回 null
    When inject with skillId="x"
    Then 抛 IllegalArgumentException 含 "SKILL_NOT_FOUND"

  场景: UNAVAILABLE skill 抛异常
    Given skill.available == UNAVAILABLE 且 unavailableReason 含 "redis_get"
    When buildInjectMode
    Then 抛 SkillUnavailableException

  场景: 无skill无workflow抛异常
    Given InjectionRequest(skillId=null, workflowId=null)
    When inject
    Then 抛异常含 "INVALID_INPUT"

  场景: 线性图三节点结构
    Given skill.available == AVAILABLE
    When buildInjectMode(skill, task, advisors)
    Then CompiledGraph.getNodes() 含 "preprocess" / "generate" / "cache"
    And 边为 preprocess→generate→cache→END
    And getEntryPoint() == "preprocess"
```

```gherkin
@priority:high @type:unit
功能: AnchorInjectionCache 缓存

  场景: put后get返回条目
    Given cache.put("k1","<p>hi</p>",now,3600)
    When cache.get("k1")
    Then entry.html=="<p>hi</p>" 且 isExpired()==false

  场景: 过期条目返回null并清除
    Given cache.put("k1","<p>x</p>",past-2h,3600)
    When cache.get("k1")
    Then 返回 null

  场景: TTL超7天被截断
    Given 请求TTL=999天
    When put(key,html,now,999*86400)
    Then expiresAt距now不超过7天

  场景: LRU超maxSize淘汰最旧
    Given maxSize=2
    When put 3个不同key
    Then size() <= 2

  场景: invalidateAll清空
    When invalidateAll()
    Then size()==0
```

```gherkin
@priority:medium @type:unit
功能: 客户端 anchor.js inject

  场景: 扫描inject元素初始化
    Given 元素 data-snap-mode="inject"
    When scanAnchors()
    Then 标记 data-snap-inject-init，发送 POST /anchor/inject

  场景: 失败有fallback时显示
    Given data-snap-fallback="<p>暂无</p>" 且 fetch失败
    Then loading 替换为 fallback

  场景: 失败无fallback时移除
    Given 无fallback 且 fetch失败
    Then loading 被移除
```

---

## 4. 接口规格

```java
// AnchorGraphFactory.buildInjectMode — 编译 inject 线性图
// preprocess: 准备 prompt + 检查缓存 (命中即 END)
// generate: AgentNode 调 LLM (skill.body 为 system prompt, HtmlOutputConverter 为 format)
// cache: 写入 AnchorInjectionCache (TTL=0 时 no-op)
CompiledGraph buildInjectMode(SkillMeta skill, AgentTask task, List<Advisor> advisors);

// HtmlOutputConverter — 2.x 统一 HTML 后处理
// containerClass="snap-inject", template 可选, stripThinking=true, sanitize=true
// getFormat() → HTML 结构指令; convert() → stripThinking + sanitize + 确保根 div 包裹
InjectionResult inject(String userId, InjectionRequest req);  // REST 入口仍为 AnchorController
```
```yaml
POST /snap-agent/anchor/inject:
  Body: {anchorName, pageUrl, skillId?, workflowId?, cacheTtl?}
  200: {html, cached, generatedAt} | 400/404/503
```

---

## 5. 数据规格

```yaml
InjectionCacheEntry: {html:String, generatedAt:Instant, expiresAt:Instant}
InjectionRequest: {anchorName, pageUrl, skillId, workflowId, cacheTtl:int=3600}
缓存Key: "userId:sourceId:anchorName:pageUrl"
TTL: min=60s, max=604800s(7天), resolveEffectiveTtl: <=0→default,<min→min,>max→max
HtmlOutputConverter:
  containerClass: "snap-inject"
  template: 可选 HTML 骨架
  stripThinking: true (剥离 thinking 前缀)
  sanitize: true (XSS 防护)
```

---

## 6. 错误处理

| 错误码 | 级别 | HTTP | 描述 |
|--------|------|------|------|
| SKILL_NOT_FOUND | ERROR | 404 | skillId不存在 |
| SKILL_UNAVAILABLE | ERROR | 404 | skill 存在但 availability != AVAILABLE |
| INVALID_INPUT | WARN | 400 | 无skillId/workflowId |
| INJECTION_FAILED | ERROR | 500 | LLM调用失败或 HtmlOutputConverter 异常 |

```gherkin
场景: LLM onError触发RuntimeException
  When stream 回调 onError("timeout")
  Then generate 节点抛 RuntimeException 含 "INJECTION_FAILED"
  And GraphExecutor catch → checkpoint → TaskStatus.FAILED
```

---

## 7. 非功能需求

```yaml
性能: 缓存命中P95<5ms | 冷启动P95<3s | 命中率>70% | HtmlOutputConverter.convert<1ms
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 数量 | 覆盖 |
|----------|------|------|
| `AnchorGraphFactoryTest` | 8 | buildInjectMode 线性图结构、缓存命中/未命中、TTL=0、用户隔离、skill优先、skill/UNAVAILABLE 不存在 |
| `HtmlOutputConverterTest` | 6 | stripThinking (英文/中文/无前缀/DOCTYPE/null/空串)、sanitize、containerClass 包裹 |
| `AnchorInjectionCacheTest` | 8 | put/get、missing、过期、per-entry TTL、max TTL、invalidateAll、size、custom maxSize |
| `SnapAgentControllerInjectTest` | 6 | 200、cached、400缺anchorName、400无source、503未配置、500 skill不存在 |

**总结**: buildInjectMode 主流程+异常全覆盖; HtmlOutputConverter 6 参数化用例; Cache 全方法覆盖; stripThinking/skipThinking 已由 HtmlOutputConverterTest 和 LlmClientTest 覆盖。

### 8.3 E2E 关键路径

| 路径ID | 关键路径 | 端点 | 状态 |
|--------|----------|------|------|
| E2E-1 | 正常注入流程: POST /anchor/inject (anchorName+source) → 200 (HTML content) | POST /anchor/inject | ✅已覆盖 (SnapAgentControllerInjectTest) |
| E2E-2 | 缓存命中: POST /anchor/inject (相同参数) → 200 (cached 标记) | POST /anchor/inject | ✅已覆盖 |
| E2E-3 | 400 错误: POST /anchor/inject (缺 anchorName / 缺 source) → 400 | POST /anchor/inject | ✅已覆盖 |
| E2E-4 | 503 未配置: POST /anchor/inject (anchor.enabled=false) → 503 | POST /anchor/inject | ✅已覆盖 |
| E2E-5 | 500 skill 不存在: POST /anchor/inject (skill 未找到) → 500 | POST /anchor/inject | ✅已覆盖 |
| E2E-6 | 线性图三节点: buildInjectMode → preprocess→generate→cache→END | AnchorGraphFactory | ✅已覆盖 (AnchorGraphFactoryTest) |

### 8.4 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | ✅已关闭: `HtmlOutputConverter.stripThinking` 已由 `HtmlOutputConverterTest` 覆盖 (6个参数化用例: 英文前缀/中文前缀/无前缀/DOCTYPE/null/空串 + containerClass 包裹 + sanitize) | — | P0 |
| GAP-2 | ✅已关闭: `LlmClient.skipThinking` 已由 `LlmClientTest` 覆盖 (tools空→skip thinking_delta / tools非空→传递) | — | P0 |
| GAP-3 | ✅已关闭: InjectionRequest.fromMap/hasSource/getSourceId 已由 `InjectionRequestTest` 覆盖 (7个fromMap测试 + 5个hasSource测试 + 5个getSourceId测试) | — | P1 |
| GAP-4 | ✅已关闭: buildInjectionPrompt (skill.body→system prompt) 已由 `AnchorGraphFactoryTest` 覆盖 (shouldUseSkillBodyAsSystemPromptInGenerateNode) | — | P1 |
| GAP-5 | ✅已关闭: workflow 兜底路径已由 `AnchorGraphFactoryTest` 覆盖 (shouldFallbackToWorkflowWhenSkillIdAbsent; legacy 兼容) | — | P1 |
| GAP-6 | ✅已关闭: LLM onError→INJECTION_FAILED 路径已由 `AnchorGraphFactoryTest` 覆盖 (shouldThrowInjectionFailedWhenLlmReportsError; GraphExecutor catch + FAILED) | — | P1 |
| GAP-7 | ✅已关闭: resolveEffectiveTtl 边界值已由 `SnapAgentPropertiesAnchorTest` 覆盖 (8个参数化测试: 0/negative/below min/equals min/normal/equals max/above max + shouldRespectCustomMinAndMaxTtl) | — | P1 |
| GAP-8 | ✅已关闭: anchor.js 前端测试已由 Vitest + Playwright 覆盖 (`anchor.test.js` 24个单元测试: DOM扫描/路径匹配/drawer创建/注入模式/auth/MutationObserver; `app.test.js` 30+个单元测试; `ui.spec.js` 20+个E2E测试) | — | P2 |
| GAP-9 | ⚠边缘场景: HtmlOutputConverter.sanitize 对复杂嵌套 HTML 的边界行为 (script 标签/event handler) 需更多 fixture | P2 | 需扩展 XSS fixture |

### 8.5 Mock策略
```yaml
Mock: LlmClient(doAnswer模拟stream), SkillRegistry, ToolCallbackRegistry, Advisor
Real: AnchorInjectionCache (Caffeine 真实实例), HtmlOutputConverter (真实实例)
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| LlmClient | 已完成 | INJECTION_FAILED |
| SkillRegistry | 已完成 | SKILL_NOT_FOUND |
| AnchorGraphFactory + GraphExecutor | 已完成 (2.x) | INJECTION_FAILED |
| HtmlOutputConverter | 已完成 (2.x) | - |
| Caffeine | 已完成 | - |

---

## 10. 可观测性设计

```yaml
日志: INFO "Inject: userId={},anchor={},cached={},duration={}ms" | WARN "SKILL_NOT_FOUND" | ERROR "INJECTION_FAILED"
```

---

## 11. 原型与交互参考

| 状态 | 表现 | 文案 |
|------|------|------|
| 加载中 | 闪电+闪烁 | "SnapAgent 生成中..." |
| 成功 | 替换HTML | - |
| 失败+fallback | fallback | "暂无内容" |
| 失败无fallback | 移除 | - |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 2.0 | 2026-07-23 | Team | 初始TDD规格 |
| 2.1 | 2026-07-25 | Team | 适配 2.x: AnchorInjectionOrchestrator→AnchorGraphFactory.buildInjectMode 线性图 (preprocess→generate→cache); stripThinking/sanitize 合并入 HtmlOutputConverter; 引入 SkillUnavailableException; 新增 US-9 / UC-09-11 |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-20-host-page-anchor-qa-design.md`
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md`
- `.../anchor/AnchorGraphFactory.java`、`AnchorInjectionCache.java`、`InjectionCacheEntry.java`
- `.../converter/HtmlOutputConverter.java`
- `.../llm/AnthropicLlmClient.java` (skipThinking)、`.../static/snap-agent/anchor.js`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| Inject Mode | 页面加载时自动生成HTML注入 |
| AnchorGraphFactory.buildInjectMode | 编译 preprocess→generate→cache 线性图 (2.x 替代 AnchorInjectionOrchestrator) |
| HtmlOutputConverter | 2.x 统一 HTML 后处理 (containerClass/template/stripThinking/sanitize) |
| stripThinking | 剥离LLM输出HTML前的推理文本 (现由 HtmlOutputConverter 承担) |
| skipThinking | SSE解析跳过thinking_delta（tools为空时） |
| InjectionCache | Caffeine LRU + per-entry TTL缓存 |
