# TDD需求规格说明书 — 锚点注入模式 (Anchor Injection)

> 版本: 2.0 | 模块: 05-anchor-inject | 基于 TEMPLATE.md

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
- **业务背景**: 宿主页面某些区域需在加载时由 SnapAgent 自动生成 HTML 注入，而非用户点击问答。
- **用户价值**: 页面加载即呈现 AI 内容，零交互成本；缓存命中 < 5ms 返回。
- **成功指标**: 缓存命中率 > 70%；首屏注入 < 3s；stripThinking 正确率 100%。

### 1.2 范围边界
- **包含**: `AnchorInjectionOrchestrator`、`AnchorInjectionCache`、`stripThinking`、`AnthropicLlmClient` skipThinking、客户端 `anchor.js` inject。
- **不包含**: 锚点 Q&A 模式、preprocess 预摘要、skill classifier。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | LLM 输出 thinking 前缀致 HTML 解析异常 | 中 | 高 | stripThinking + skipThinking 双防护 |
| R2 | 缓存 TTL 配置不当 | 低 | 中 | min/max TTL 强制约束 + 7天上限 |
| R3 | 工作流引擎未配置时注入失败 | 低 | 中 | 抛 WORKFLOW_NOT_FOUND |

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

### US-3: LLM 思考过程过滤
```gherkin
作为 系统开发者
我希望 自动剥离 LLM 输出的 thinking 前缀
以便 注入的 HTML 干净
```
**AC:**
```gherkin
AC4: Given LLM 输出 "Let me think...<div>x</div>"
  When stripThinking 执行
  Then 返回 "<div>x</div>"
```

### US-4: 技能 body 作为 system prompt
```gherkin
作为 技能开发者
我希望 注入时用 skill body 作为 system prompt
以便 精确控制生成风格
```
**AC:**
```gherkin
AC5: Given skill body 非空
  When executeSkill 执行
  Then LlmRequest.systemPrompt = skill body
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

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 加载 | US-1 | 零交互获取 | 完成率>95% | - |
| 缓存 | US-2 | 秒级返回 | 命中率>70% | US-1 |
| 质量 | US-3 | 干净HTML | 100% | US-1 |
| 定制 | US-4 | 精确控制 | 100% | US-1 |
| 容错 | US-5 | 不崩坏 | 100% | US-1 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | 缓存未命中执行skill | P0 | AC1 | 单元 |
| UC-02 | 缓存命中直接返回 | P0 | AC2 | 单元 |
| UC-03 | 不同用户缓存隔离 | P0 | AC3 | 单元 |
| UC-04 | stripThinking剥离 | P0 | AC4 | 单元 |
| UC-05 | skill body做prompt | P0 | AC5 | 单元 |
| UC-06 | TTL=0不缓存 | P1 | - | 单元 |
| UC-07 | skill优先workflow | P1 | - | 单元 |
| UC-08 | skill不存在抛异常 | P0 | - | 单元 |
| UC-09 | workflow执行注入 | P1 | - | 单元 |
| UC-10 | 客户端fallback | P1 | AC6,7 | 单元 |

### 3.2 详细用例 (Gherkin)

```gherkin
@priority:high @type:unit
功能: AnchorInjectionOrchestrator 注入编排

  场景: 缓存未命中时执行skill并缓存
    Given skillRegistry 存在 skill "announcement" 且 body 非空
    And llmClient.stream onThought 输出 "<div class=\"notice\">Hello!</div>"
    And InjectionRequest(anchorName="公告", pageUrl="/dashboard", skillId="announcement", cacheTtl=3600)
    When orchestrator.inject("user001", req)
    Then html contains "<div class=\"notice\">Hello!</div>" 且 cached == false
    And 再次调用 cached=true 且 LLM 不再调用

  场景: 缓存命中直接返回不调LLM
    Given 缓存已有 key "user001:announcement:公告:/page"
    When orchestrator.inject("user001", req)
    Then cached == true 且 llmClient.stream 调用次数为 0

  场景: TTL=0时不缓存
    Given InjectionRequest(cacheTtl=0)
    When 连续调用两次
    Then 两次 cached=false，LLM 调两次

  场景: 不同用户缓存key不同
    Given 用户A和B请求同一锚点
    When 先后注入
    Then LLM 调两次

  场景: skill body非空时作为systemPrompt
    Given skill "announcement" body="你是公告助手"
    When executeSkill 执行
    Then LlmRequest.systemPrompt == "你是公告助手"
    And maxTokens == min(injectionMaxTokens, 1024)

  场景: skill body为空时用默认prompt
    Given skill body 为空串
    When executeSkill 执行
    Then systemPrompt 为 "你是 SnapAgent 内容生成助手..."

  场景大纲: stripThinking剥离推理前缀
    Given LLM 原始输出 <raw>
    When stripThinking(raw)
    Then 返回 <expected>
    例子:
      | raw | expected | 说明 |
      | "Let me think...<div>x</div>" | "<div>x</div>" | 英文前缀 |
      | "让我想想<p>hi</p>" | "<p>hi</p>" | 中文前缀 |
      | "<div>direct</div>" | "<div>direct</div>" | 无前缀 |
      | "<!DOCTYPE html>" | "<!DOCTYPE html>" | DOCTYPE |
      | null | null | null |
      | "" | "" | 空串 |

  场景: inject模式下skipThinking跳过thinking_delta
    Given LlmRequest.tools 为空
    When AnthropicLlmClient 解析SSE
    Then skipThinking=true，thinking_delta 不触发 onThought
    And text_delta 正常触发

  场景: skill优先于workflow
    Given InjectionRequest(skillId="s1", workflowId="w1")
    When inject 执行
    Then LLM 被调用，workflowEngine 不被调用

  场景: skill不存在抛异常
    Given skillRegistry.get("x") 返回 null
    When inject with skillId="x"
    Then 抛 IllegalArgumentException 含 "SKILL_NOT_FOUND"

  场景: 无skill无workflow抛异常
    Given InjectionRequest(skillId=null, workflowId=null)
    When inject
    Then 抛异常含 "INVALID_INPUT"
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
// inject — 缓存查询→执行→缓存→返回
InjectionResult inject(String userId, InjectionRequest req);
// stripThinking — 剥离HTML前的thinking文本 (static)
// skipThinking: tools为空时跳过thinking_delta (AnthropicLlmClient)
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
```

---

## 6. 错误处理

| 错误码 | 级别 | HTTP | 描述 |
|--------|------|------|------|
| SKILL_NOT_FOUND | ERROR | 404 | skillId不存在 |
| WORKFLOW_NOT_FOUND | ERROR | 404 | workflow不存在/引擎未配置 |
| INVALID_INPUT | WARN | 400 | 无skillId/workflowId |
| INJECTION_FAILED | ERROR | 500 | LLM调用失败 |

```gherkin
场景: LLM onError触发RuntimeException
  When stream 回调 onError("timeout")
  Then executeSkill 抛 RuntimeException 含 "INJECTION_FAILED"
```

---

## 7. 非功能需求

```yaml
性能: 缓存命中P95<5ms | 冷启动P95<3s | 命中率>70% | stripThinking<1ms
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 数量 | 覆盖 |
|----------|------|------|
| `AnchorInjectionOrchestratorTest` | 8 | skill执行、缓存命中/未命中、TTL=0、用户隔离、workflow、skill优先、skill/workflow不存在 |
| `AnchorInjectionCacheTest` | 8 | put/get、missing、过期、per-entry TTL、max TTL、invalidateAll、size、custom maxSize |
| `SnapAgentControllerInjectTest` | 6 | 200、cached、400缺anchorName、400无source、503未配置、500 skill不存在 |

**总结**: Orchestrator.inject 主流程+异常全覆盖；Cache 全方法覆盖；stripThinking/skipThinking 无直接测试。

### 8.3 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | `stripThinking` 无独立参数化测试 | P0 | 英文/中文前缀、null、空串、DOCTYPE |
| GAP-2 | `AnthropicLlmClient.skipThinking` 无测试 | P0 | tools空跳过thinking_delta、非空传递 |
| GAP-3 | `InjectionRequest.fromMap`/`hasSource`/`getSourceId` 无测试 | P1 | Map解析、cacheTtl默认、source优先级 |
| GAP-4 | `buildInjectionPrompt` 无测试 | P1 | prompt含pageUrl/anchorName/userId |
| GAP-5 | `extractHtmlFromWorkflowResult` 空结果路径 | P1 | null/空stepResults、空report |
| GAP-6 | LLM onError→INJECTION_FAILED 路径 | P1 | Mock onError验证异常 |
| GAP-7 | `resolveEffectiveTtl` 边界值 | P1 | TTL=0/<min/>max/正常 |
| GAP-8 | `anchor.js` inject 无JS测试 | P2 | 需JS测试框架或E2E |

### 8.4 Mock策略
```yaml
Mock: LlmClient(doAnswer模拟stream), SkillRegistry, WorkflowEngine, SecurityGateway
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| LlmClient | 已完成 | INJECTION_FAILED |
| SkillRegistry | 已完成 | SKILL_NOT_FOUND |
| WorkflowEngine | 可选 | WORKFLOW_NOT_FOUND |
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

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-20-host-page-anchor-qa-design.md`
- `.../anchor/AnchorInjectionOrchestrator.java`、`AnchorInjectionCache.java`、`InjectionCacheEntry.java`
- `.../llm/AnthropicLlmClient.java` (skipThinking)、`.../static/snap-agent/anchor.js`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| Inject Mode | 页面加载时自动生成HTML注入 |
| stripThinking | 剥离LLM输出HTML前的推理文本 |
| skipThinking | SSE解析跳过thinking_delta（tools为空时） |
| InjectionCache | Caffeine LRU + per-entry TTL缓存 |
