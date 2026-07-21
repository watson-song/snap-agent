# Host Page Anchor Q&A Design Spec

> v0.11 Feature: 宿主应用页面锚点问答 — 用户点击锚点图标即可对当前页面区域内容发起 LLM 问答

## Problem

SnapAgent 当前集成模型只在 `/snap-agent/` 路径下挂载独立 SPA，用户必须切到 SnapAgent 主界面才能提问。但实际场景中，用户在浏览宿主应用的 docs/help/业务页面时，常常想"针对当前看到的内容提问"——例如：

- 文档站：用户读到"订单状态"区块，想问"已发货但未签收一般要多久"
- 运维仪表盘：用户看到某指标异常，想问"这个 QPS 下降是什么原因"
- 业务表单：用户填写表单时，想问"这个字段填什么格式"

现有方案要求用户复制内容、切到 SnapAgent SPA、粘贴、提问——上下文割裂，体验差。

## Solution: 宿主页面锚点 + 右侧抽屉

宿主应用引入一行 `<script>` 标签：

```html
<script src="/snap-agent/anchor.js" defer></script>
```

脚本加载后扫描页面 DOM，在指定区域插入锚点图标。用户点击锚点图标 → 右侧抽屉滑出 → 在抽屉里发起针对该区域内容的 LLM 问答。问答复用 SnapAgent 现有的 `POST /runs` + SSE + `ConversationStore` 基础设施，仅新增"页面上下文注入"环节。

## Design Decisions (grill-me 访谈结论)

通过 grill-me 访谈完成 18 项核心决策。以下按依赖关系组织。

### Q1 — 核心用例定位：通用机制 + MVP 聚焦 docs/help

机制设计为通用——支持业务页面诊断、docs/help 页面解释、运维页面解读等所有场景。但 MVP 优先交付 docs/help 页面场景（上下文提取最简单），验证机制后再扩展到业务/运维场景。

### Q2 — 注入方式：宿主手动加 `<script>` 标签

不走服务端 Filter HTML 响应注入；宿主在需要锚点的页面模板加：
```html
<script src="/snap-agent/anchor.js" defer></script>
```
符合 SnapAgent "嵌入式库" 定位，避免 HTML 改造的部署复杂度。

### Q3 — 锚点放置策略：混合（显式标注 + 自动发现降级）

**优先路径**：宿主开发者用 `data-snap-anchor` 属性显式标注：
```html
<section data-snap-anchor="订单状态区块">
  <!-- ... -->
</section>
```

**降级路径**：若页面无任何 `data-snap-anchor` 标注，自动扫描 `main` 区域内的 `<section>`、带 `id` 的 `<h2>`/`<h3>` 等语义元素。

降级范围限定 `main` 区域，避免误扫页脚/导航/弹窗等无关 DOM。

### Q4 — 聊天 UI 呈现：右侧抽屉（移动端降级为底部抽屉）

- 桌面端：右侧滑出 380-420px 宽度抽屉
- 移动端（`<768px`）：从底部滑入 70vh 高度的底部抽屉
- 抽屉容器注入 `<body>` 末尾的 Shadow DOM，避免样式污染宿主页面

业界主流模式（Intercom、Crisp、HubSpot Chat），用户认知成本低，支持"边看边问"。

### Q5 — 上下文提取：客户端 HTML→Markdown（Turndown）

`anchor.js` 用 [Turndown](https://github.com/mixmark-io/turndown)（~7KB gzip）把锚点区域的 DOM 转成 Markdown：
- `<h2>` → `##`、`<table>` → Markdown 表格、`<code>` → `` `code` ``
- 保留结构语义，token 效率比原始 HTML 高 60-70%
- LLM 训练语料含大量 Markdown，理解力远强于原始 HTML

### Q6 — 技能路由：智能路由（LLM 分类器 + 降级通用 LLM）

不要求宿主配置技能映射；服务端通过 LLM 分类器判断"问题 + 上下文 → skillId"。
- 置信度 ≥ 0.5 → 路由到对应技能
- 置信度 < 0.5 或无匹配技能 → 降级为通用 LLM 直答（仍带页面上下文）

分类器 prompt 包含可用技能列表（`skillRegistry.list()` 的 name + description）+ 用户问题 + 锚点内容摘要（前 500 字）。

### Q7 — 用户认证：复用现有 `auth-config` token 策略

`anchor.js` 完全复用 `app.js` 现有 token 获取流程：
1. 调 `GET /snap-agent/auth-config` 拿 `{authHeader, authCookie, authLocalStorageKey}`
2. `getAuthToken()` 优先从 `localStorage` 取，其次 Cookie，最后降级为同源 Cookie 自动携带
3. `authHeaders()` 把 token 加到 fetch 请求头

宿主无需为锚点功能新增任何认证配置——已为 SnapAgent 主 SPA 配过的 token 策略自动继承。

### Q8 — 页面启用范围：默认启用 + 黑名单路径禁用

- 默认所有引入脚本的页面都启用锚点扫描
- 宿主可在 YAML 配置黑名单路径禁用：
  ```yaml
  snap-agent:
    anchor:
      disabled-paths:
        - "/payment/**"
        - "/admin/security/**"
  ```
- 黑名单通过 `GET /snap-agent/anchor/config` 一次性拉取并缓存到 `sessionStorage`

### Q9 — DOM 扫描时机：DOMContentLoaded + MutationObserver + 宿主钩子

适配 SPA 动态渲染场景：
- 初始 `DOMContentLoaded` 扫描一次
- `MutationObserver` 监听 `main` 区域子树变更（`childList` + `subtree`，不监听属性变更），800ms debounce 后增量扫描
- 暴露 `window.__SNAP_AGENT_RESCAN__()` 全局函数让宿主主动触发立即扫描（跳过 debounce）
- 已扫描锚点加 `data-snap-anchor-processed` 标记避免重复处理

单次扫描目标 < 5ms（限定 `main` 区域，元素数通常 < 200）。

### Q10 — 对话状态：复用现有 `POST /runs` + SSE + `ConversationStore`

**关键决定**：接口/后端跟现有聊天逻辑保持一致，只是在问问题时加入当前页面内容作为上下文。

- `anchor.js` 调 `POST /snap-agent/runs` 创建对话任务，请求体加一个 `anchor` 字段
- SSE 流式、`conversationId` 管理、`ConversationStore` 持久化、`/conversations` 列表加载 — 全部走现有路径
- 服务端在 `AgentExecutor` 执行前把 `anchor.content` 作为上下文 prepend 到用户消息

### Q11 — 分类器实现位置：服务端单次调用（`skillId="auto"`）

- `anchor.js` 调 `POST /snap-agent/runs` 时 `skillId` 字段填 `"auto"`
- 服务端检测到 `auto` + `anchor` 字段时，先调分类器，再路由到对应技能执行
- 单次 HTTP 请求完成"分类 + 执行 + 流式返回"
- SSE 首个 `meta` event 附加 `classifierConfidence` 字段供客户端展示
- 分类器失败 → 降级为通用 LLM 直答

### Q12 — 锚点图标视觉位置：混合（标题左侧 + 区域右上角）

覆盖所有锚点元素形态：
- **标题场景**（`<h2>`/`<h3>`）：图标放标题前（GitHub Docs / MDN 风格），桌面端 hover 显示，移动端常驻
- **非标题场景**（表格/表单/列表）：区域右上角 24×24 圆形按钮，常驻显示

图标用 SVG 内联（`currentColor` 继承宿主文字颜色），Shadow DOM 隔离样式。移动端触控目标 32×32。

### Q13 — 长上下文处理：服务端两段式 LLM 摘要

长页面单段内容可能超 20K tokens，直接发给 LLM 既费 token 又影响理解。

**两段式流程**：
1. **摘要器调用**：服务端用完整内容调 LLM 生成 1500 字摘要
2. **主回答调用**：用摘要 + 用户问题调主技能 LLM 生成答案

**短内容跳过**：内容 < 4000 字符时直接用原文，跳过摘要调用（节省 ~60% 摘要调用量）。

### Q14 — 延迟优化：并行 + 预摘要 + 降级 fallback

Q13 把单次问答的 LLM 调用数从 1 次变成 3 次（分类器 + 摘要器 + 主回答），首 token 延迟可能 8-15 秒。优化策略：

1. **并行分类器 + 摘要器**：两者无依赖关系，用 `CompletableFuture` 并行调度（分类器用问题+内容首段，摘要器用完整内容）
2. **预摘要**：用户**点击锚点图标**的瞬间，客户端立即发 `POST /snap-agent/anchor/preprocess` 请求，服务端开始执行摘要器 + 分类器
3. **5 秒 abort**：客户端 5 秒内未提交问题则 abort preprocess 请求
4. **摘要缓存**：`AnchorSummaryCache` 基于 Caffeine LRU，key=`SHA256(content)`，TTL 10 分钟
5. **降级 fallback**：摘要未就绪 → 串行执行 + 进度提示；网络/LLM 失败 → 错误 + 重试

常态首 token 延迟 2-3 秒。

### Q15 — 抽屉内聊天 UI 实现：完全独立 `anchor.js`

不复用 iframe（样式融入问题严重），不重构 `app.js`（95KB 单文件耦合严重）。

- `anchor.js` 单文件（~600 行 JS + 内联 CSS，~15KB gzip）放在 `static/snap-agent/anchor.js`
- 依赖库：`marked.min.js`（~25KB）+ `highlight.min.js`（~10KB）+ `turndown.min.js`（~7KB），全部放在 `static/snap-agent/vendor/`
- 复用 API 契约层（`loadAuthConfig`、`getAuthToken`、`authHeaders`、EventSource SSE 解析），UI 自建
- Shadow DOM 隔离样式，主题可配（`light`/`dark`/`auto`，默认 `auto` 跟随 `prefers-color-scheme`）

### Q16 — 权限控制：完全继承现有 `/user-info` 检查

`anchor.js` 加载时调 `GET /snap-agent/user-info`：
- `authenticated=false` → 静默不渲染图标
- `authorized=false` → 静默不渲染图标
- 通过 → 进入 DOM 扫描流程

无新增配置项；公开 docs 站点的匿名降级场景由服务端 `SecurityGateway` 现有逻辑处理（userId 为空时按匿名模式 + IP 限流）。

### Q17 — 错误处理：与现有聊天框错误处理完全一致

复用 `app.js` 现有错误模式：

| 场景 | 处理方式 |
|------|---------|
| 401 未登录 | `showAuthPrompt('登录失效', '请先登录系统后再访问 SnapAgent')` |
| 403 无权限 | `showAuthPrompt('无权限', '当前账号无 SnapAgent 访问权限')` |
| 429 限流 | 追加 transcript: `⏱ 频率限制：当前已有任务在运行，请等待完成后再发起新请求` |
| 网络 fetch 失败 | 追加 transcript: `请求失败: ${e.message}` |
| SSE error 事件 | finalize 当前 streaming thought 为 partial + 保存对话 + 追加 `— 连接断开，请重新发送消息继续 —` |
| SSE task_error 事件 | 追加 transcript: `data.text || '任务执行出错'` |
| done 非 SUCCEEDED | TIMEOUT: `⚠ 任务超时`、FAILED: `❌ 任务失败`、CANCELLED: `— 已取消 —` |

**关键含义**：分类器/摘要器失败对客户端不可见——服务端在 `AnchorOrchestrator` 内部 catch 失败并自动降级（Q6 通用 LLM 直答、Q13 短内容跳过摘要），降级后的输出走正常 SSE 流。

### Q18 — MVP 范围：渐进式（v1 核心 + v1.1 增强）

**v1（4.5 周）— 核心功能**：
- ✅ 客户端 `anchor.js`：Shadow DOM 抽屉 + Turndown + marked + highlight + SSE 解析
- ✅ 客户端：`data-snap-anchor` 扫描 + main 区域降级自动发现 + MutationObserver
- ✅ 客户端：复用 `auth-config` + `user-info` + `POST /runs` + SSE
- ✅ 客户端：混合图标放置（标题左侧 + 区域右上角）
- ✅ 服务端：`POST /runs` 扩展 `anchor` 字段 + `skillId: "auto"` 路由
- ✅ 服务端：`AnchorOrchestrator`（分类器 + 摘要器 + 主回答并行调度）
- ✅ 服务端：`AnchorSummaryCache`（Caffeine LRU）
- ✅ 服务端：`POST /anchor/preprocess` + `GET /anchor/config` 端点
- ✅ 预摘要（Q14 关键功能，不能省）

**v1.1（2 周）— 增强**：
- 黑名单路径配置项暴露
- `window.__SNAP_AGENT_RESCAN__()` 钩子 + 文档
- 主题切换 UI（抽屉顶部 light/dark/auto 按钮 + localStorage 持久化）
- MutationObserver debounce 调优 + 摘要缓存命中率监控

**推迟到 v2+**：
- 服务端分类器 LRU 缓存
- 客户端 `sessionStorage` 对话持久化
- 独立 `anchorAuthorized` 权限位
- 分阶段时间线调试 UI

## Changes

### 1. 新增配置类 `SnapAgentProperties.Anchor`

`snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentProperties.java`

```java
public static class Anchor {
    /** Master switch. Default true — anchor feature enabled. */
    private boolean enabled = true;

    /** Model name for the skill classifier. Empty = use main LLM model. */
    private String classifierModel = "";

    /** Confidence threshold; below this, fall back to general LLM. */
    private double classifierConfidenceThreshold = 0.5;

    /** Blacklist paths where anchors are disabled. Default empty = all pages enabled. */
    private List<String> disabledPaths = new ArrayList<>();

    /** Max characters of anchor content sent to LLM (after Turndown). */
    private int maxContextChars = 8000;

    /** Pre-summary on anchor click (latency optimization). */
    private boolean preprocessEnabled = true;

    /** Abort preprocess if user doesn't submit question within this timeout (ms). */
    private long preprocessTimeoutMs = 5000;

    /** Summary cache TTL in seconds (Caffeine LRU). */
    private long summaryCacheTtlSeconds = 600;

    /** Skip summarizer for content shorter than this (chars). */
    private int summaryThresholdChars = 4000;

    // getters / setters ...
}
```

YAML 配置示例：
```yaml
snap-agent:
  anchor:
    enabled: true
    classifier-model: ""                        # 空=用主模型；可填小模型降本
    classifier-confidence-threshold: 0.5
    disabled-paths: []                          # v1 默认空 = 全页面启用
    max-context-chars: 8000
    preprocess-enabled: true
    preprocess-timeout-ms: 5000
    summary-cache-ttl-seconds: 600
    summary-threshold-chars: 4000
```

### 2. 新增服务端组件

#### `AnchorOrchestrator`

协调分类器 + 摘要器 + 主回答的并行调度。

```java
public class AnchorOrchestrator {

    private final LlmClient llmClient;
    private final AnchorSummaryCache summaryCache;
    private final AnchorContextSummarizer summarizer;
    private final AnchorSkillClassifier classifier;
    private final SkillRegistry skillRegistry;
    private final SnapAgentProperties.Anchor props;

    /**
     * Preprocess: triggered on anchor click. Runs classifier + summarizer in parallel,
     * returns preprocessId for later correlation with the actual /runs request.
     */
    public PreprocessResult preprocess(AnchorContext anchor, String userQuestion) {
        CompletableFuture<String> summaryFuture = CompletableFuture.supplyAsync(() -> {
            if (anchor.getContent().length() < props.getSummaryThresholdChars()) {
                return anchor.getContent();  // short content, skip summary
            }
            return summarizer.summarize(anchor.getContent());
        });
        CompletableFuture<ClassifyResult> classifyFuture = CompletableFuture.supplyAsync(() ->
            classifier.classify(userQuestion, anchor.getContent()));
        // Both run in parallel; results cached under preprocessId
        String preprocessId = UUID.randomUUID().toString();
        // ... store futures in cache, return immediately
        return new PreprocessResult(preprocessId);
    }

    /**
     * Execute: called by POST /runs when skillId="auto" and anchor field present.
     * Picks up pre-computed summary + skillId from preprocess cache, or runs serially.
     */
    public void executeWithAnchor(AnchorContext anchor, String userQuestion,
                                    String conversationId, LlmEventSink sink) {
        // 1. Get summary (from cache or compute)
        String context = summaryCache.getOrCreate(anchor.getContent(),
            () -> summarizer.summarize(anchor.getContent()));

        // 2. Get skillId (from cache or classify)
        ClassifyResult classify = classifier.classify(userQuestion, anchor.getContent());

        // 3. Build augmented prompt
        String augmentedMessage = buildAugmentedMessage(context, anchor, userQuestion);

        // 4. Route to skill or fallback to general LLM
        if (classify.getConfidence() >= props.getClassifierConfidenceThreshold()
                && skillRegistry.findSkill(classify.getSkillId()).isPresent()) {
            // invoke skill
        } else {
            // fallback to general LLM with anchor context
        }
    }
}
```

#### `AnchorSummaryCache`

基于 Caffeine LRU 的摘要缓存。

```java
public class AnchorSummaryCache {
    private final Cache<String, String> cache;

    public AnchorSummaryCache(SnapAgentProperties.Anchor props) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofSeconds(props.getSummaryCacheTtlSeconds()))
            .build();
    }

    /** Get cached summary or compute via supplier. */
    public String getOrCreate(String content, Supplier<String> supplier) {
        String key = sha256(content);
        return cache.get(key, k -> supplier.get());
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
```

#### `AnchorContextSummarizer`

LLM 摘要器（输入完整内容 → 1500 字摘要）。

```java
public class AnchorContextSummarizer {
    private final LlmClient llmClient;
    private final SnapAgentProperties.Anchor props;

    public String summarize(String content) {
        if (content.length() < props.getSummaryThresholdChars()) {
            return content;  // short content, skip
        }
        String prompt = "请把以下页面内容摘要为 1500 字以内的中文，保留关键信息、表格结构、数字指标、代码片段的关键逻辑：\n\n"
            + content;
        // call LLM (non-streaming, just collect all text)
        StringBuilder sb = new StringBuilder();
        llmClient.stream(LlmRequest.builder()
            .model(props.getClassifierModel())  // small model for summary
            .maxTokens(2000)
            .addUserMessage(prompt)
            .build(),
            new LlmEventSink() {
                @Override public void onToken(String text) { sb.append(text); }
                @Override public void onComplete() {}
            }, "summary-" + UUID.randomUUID());
        return sb.toString();
    }
}
```

#### `AnchorSkillClassifier`

LLM 分类器（输入问题 + 内容首段 → skillId + confidence）。

```java
public class AnchorSkillClassifier {
    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final SnapAgentProperties.Anchor props;

    public ClassifyResult classify(String userQuestion, String content) {
        List<SkillInfo> skills = skillRegistry.listAvailable();
        String skillsList = skills.stream()
            .map(s -> "- " + s.getName() + ": " + s.getDescription())
            .collect(Collectors.joining("\n"));

        String contentSnippet = content.substring(0, Math.min(500, content.length()));
        String prompt = "用户在浏览页面时针对某段内容提问。请判断应该路由到以下哪个技能处理。\n\n"
            + "可用技能：\n" + skillsList + "\n\n"
            + "用户问题：" + userQuestion + "\n\n"
            + "内容摘要（前 500 字）：" + contentSnippet + "\n\n"
            + "请返回 JSON: {\"skillId\": \"<技能名或null>\", \"confidence\": 0.0-1.0}";

        // call LLM, parse JSON response
        // ...
        return new ClassifyResult(skillId, confidence);
    }
}
```

### 3. 扩展 `SnapAgentController`

#### `POST /runs` 扩展支持 `anchor` 字段

`SnapAgentController.java` 现有 `createRun` 方法签名保持不变；解析请求体时检测 `anchor` 字段：

```java
@PostMapping("/runs")
public ResponseEntity<Object> createRun(@RequestBody Map<String, Object> body) {
    String userId = securityGateway.currentUserId();
    // ... existing auth checks ...

    String skillId = body.get("skillId") instanceof String
        ? (String) body.get("skillId") : null;

    // NEW: extract anchor context if present
    Map<String, Object> anchorMap = body.get("anchor") instanceof Map
        ? (Map<String, Object>) body.get("anchor") : null;
    AnchorContext anchor = anchorMap != null ? AnchorContext.fromMap(anchorMap) : null;

    // NEW: if skillId == "auto" and anchor present, use AnchorOrchestrator
    if ("auto".equals(skillId) && anchor != null) {
        // Augment user message with anchor content
        Map<String, Object> inputs = (Map<String, Object>) body.get("inputs");
        String augmentedMessage = anchorOrchestrator.augmentMessage(
            anchor, (String) inputs.get("message"));
        inputs.put("message", augmentedMessage);

        // Resolve skillId via classifier (or use pre-computed from preprocess)
        ClassifyResult classify = anchorOrchestrator.classify(
            (String) inputs.get("message"), anchor.getContent());
        if (classify.getConfidence() >= props.getClassifierConfidenceThreshold()
                && skillRegistry.findSkill(classify.getSkillId()).isPresent()) {
            skillId = classify.getSkillId();
        } else {
            skillId = "general";  // fallback skill (or null for direct LLM)
        }
    }

    // ... existing task creation logic with resolved skillId + augmented message ...
}
```

#### `POST /anchor/preprocess` 新增端点

```java
@PostMapping("/anchor/preprocess")
public ResponseEntity<Object> preprocessAnchor(@RequestBody Map<String, Object> body) {
    ResponseEntity<Object> authError = requireAuth();
    if (authError != null) return authError;

    Map<String, Object> anchorMap = (Map<String, Object>) body.get("anchor");
    String userQuestion = (String) body.get("question");  // can be null
    AnchorContext anchor = AnchorContext.fromMap(anchorMap);

    PreprocessResult result = anchorOrchestrator.preprocess(anchor, userQuestion);
    return ResponseEntity.ok(Map.of(
        "preprocessId", result.getPreprocessId(),
        "status", "started"
    ));
}
```

#### `GET /anchor/config` 新增端点（公开）

```java
@GetMapping("/anchor/config")
public ResponseEntity<Object> getAnchorConfig() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("enabled", props.getAnchor().isEnabled());
    config.put("disabledPaths", props.getAnchor().getDisabledPaths());
    return ResponseEntity.ok(config);
}
```

### 4. 新增客户端 `anchor.js`

路径：`snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/anchor.js`

结构（~600 行）：

```javascript
// SnapAgent Anchor — page-section Q&A via right-side drawer
// Reuses /snap-agent/auth-config, /user-info, /runs, SSE protocols
(function() {
  'use strict';

  const BASE = '/snap-agent';
  let authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  let anchorConfig = { enabled: true, disabledPaths: [] };
  let drawerEl = null;

  // ===== Auth (copied from app.js, contract layer) =====
  async function loadAuthConfig() { /* ... same as app.js ... */ }
  function getAuthToken() { /* ... same as app.js ... */ }
  function authHeaders(headers) { /* ... same as app.js ... */ }

  // ===== Config =====
  async function loadAnchorConfig() {
    const resp = await fetch(`${BASE}/anchor/config`);
    if (resp.ok) anchorConfig = await resp.json();
  }

  async function checkUserStatus() {
    const resp = await fetch(`${BASE}/user-info`, { headers: authHeaders() });
    if (!resp.ok) return false;
    const info = await resp.json();
    return info.authenticated && info.authorized;
  }

  // ===== DOM Scan (Q3 + Q9) =====
  function scanAnchors() {
    const scope = document.querySelector('main') || document.body;

    // Explicit annotations first
    const annotated = scope.querySelectorAll('[data-snap-anchor]:not([data-snap-anchor-processed])');
    annotated.forEach(el => injectIcon(el, el.getAttribute('data-snap-anchor'), 'corner'));

    // Fallback auto-discover in main
    if (annotated.length === 0) {
      const headings = scope.querySelectorAll('h2[id], h3[id], section');
      headings.forEach(el => {
        if (!el.hasAttribute('data-snap-anchor-processed')) {
          const name = el.id || el.tagName.toLowerCase();
          injectIcon(el, name, el.tagName.match(/H[23]/) ? 'heading' : 'corner');
        }
      });
    }
  }

  function injectIcon(targetEl, name, mode) {
    targetEl.setAttribute('data-snap-anchor-processed', '');
    const icon = document.createElement('button');
    icon.className = 'snap-anchor-icon snap-anchor-icon-' + mode;
    icon.innerHTML = '<svg>...</svg>';
    icon.title = '针对此内容提问';
    icon.addEventListener('click', () => openDrawer(targetEl, name));
    if (mode === 'heading') {
      targetEl.insertBefore(icon, targetEl.firstChild);
    } else {
      targetEl.style.position = 'relative';
      targetEl.appendChild(icon);
    }
  }

  // ===== MutationObserver (Q9) =====
  function setupMutationObserver() {
    const scope = document.querySelector('main') || document.body;
    let timer = null;
    const observer = new MutationObserver(() => {
      clearTimeout(timer);
      timer = setTimeout(scanAnchors, 800);
    });
    observer.observe(scope, { childList: true, subtree: true });
  }

  // Expose rescan hook (Q9 — v1.1 enhancement, but reserve namespace now)
  window.__SNAP_AGENT_RESCAN__ = function() { scanAnchors(); };

  // ===== Drawer (Q4 + Q15) =====
  function openDrawer(anchorEl, name) {
    if (!drawerEl) {
      drawerEl = createDrawer();
      document.body.appendChild(drawerEl);
    }
    const content = turndownService.turndown(anchorEl.outerHTML);
    // Truncate if exceeds maxContextChars
    const truncated = content.length > 8000;
    const truncatedContent = truncated ? content.substring(0, 8000) : content;

    drawerEl.activeAnchor = {
      name: name,
      content: truncatedContent,
      truncated: truncated,
      originalLength: content.length,
      pageUrl: location.pathname + location.search
    };

    // Trigger preprocess (Q14)
    triggerPreprocess(drawerEl.activeAnchor);

    drawerEl.classList.add('open');
  }

  function createDrawer() {
    const host = document.createElement('div');
    host.id = 'snap-anchor-drawer-host';
    const shadow = host.attachShadow({ mode: 'open' });
    shadow.innerHTML = `
      <style>
        :host { position: fixed; top: 0; right: 0; width: 400px; height: 100vh;
                transform: translateX(100%); transition: transform 200ms ease;
                z-index: 999999; }
        :host(.open) { transform: translateX(0); }
        /* ... drawer styles ... */
      </style>
      <div class="drawer">
        <header class="drawer-header">
          <span class="drawer-title">💬 锚点问答</span>
          <button class="drawer-close">✕</button>
        </header>
        <div class="drawer-anchor-info"></div>
        <div class="drawer-messages"></div>
        <div class="drawer-input">
          <textarea placeholder="针对当前内容提问..."></textarea>
          <button class="drawer-send">发送</button>
        </div>
      </div>
    `;
    // wire up event listeners (send, close, SSE)
    // ...
    return host;
  }

  // ===== Send Question (Q10 + Q11 + Q14) =====
  async function sendQuestion(question) {
    const anchor = drawerEl.activeAnchor;
    const resp = await fetch(`${BASE}/runs`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        skillId: 'auto',  // Q11
        inputs: { message: question },
        anchor: anchor    // Q10
      })
    });
    const data = await resp.json();
    if (data.taskId) subscribeStream(data.taskId);
  }

  // ===== SSE Stream (copied from app.js, contract layer) =====
  function subscribeStream(taskId) {
    const es = new EventSource(`${BASE}/runs/${taskId}/stream`);
    es.addEventListener('thought', e => { /* render streaming token */ });
    es.addEventListener('tool_call', e => { /* render tool call */ });
    es.addEventListener('tool_result', e => { /* render tool result */ });
    es.addEventListener('done', e => { /* finalize, save conversation */ });
    es.addEventListener('error', e => { /* show error in drawer */ });
  }

  // ===== Init =====
  (async function() {
    await loadAuthConfig();
    const ok = await checkUserStatus();
    if (!ok) return;  // Q16 — silent disable

    await loadAnchorConfig();
    if (!anchorConfig.enabled) return;
    if (isPathBlacklisted(location.pathname, anchorConfig.disabledPaths)) return;

    // Load Turndown (Q5)
    await loadScript(`${BASE}/vendor/turndown.min.js`);

    scanAnchors();
    setupMutationObserver();
  })();
})();
```

### 5. 新增依赖库

下载到 `snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/vendor/`：
- `turndown.min.js` — https://github.com/mixmark-io/turndown (~7KB gzip)
- `marked.min.js` — https://github.com/markedjs/marked (~25KB gzip)
- `highlight.min.js` — https://github.com/highlightjs/highlight.js (~10KB gzip，按需加载语言包)

## Payload Contracts

### `POST /snap-agent/runs`（扩展）

```json
{
  "skillId": "auto",
  "inputs": { "message": "为什么这个订单还没到？" },
  "anchor": {
    "name": "订单状态区块",
    "content": "## 订单状态\n当前状态：已发货\n物流单号：SF1234567890\n...",
    "truncated": true,
    "originalLength": 52180,
    "meta": {
      "headings": ["订单状态", "物流轨迹", "签收信息"],
      "tableCount": 3,
      "codeBlockCount": 2
    },
    "pageUrl": "/order/detail?id=123"
  }
}
```

### `POST /snap-agent/anchor/preprocess`（新增）

Request:
```json
{
  "anchor": {
    "name": "订单状态区块",
    "content": "## 订单状态\n...",
    "pageUrl": "/order/detail?id=123"
  },
  "question": null  // may be null if user hasn't typed yet
}
```

Response:
```json
{
  "preprocessId": "prep_abc123",
  "status": "started"
}
```

### `GET /snap-agent/anchor/config`（新增，公开）

```json
{
  "enabled": true,
  "disabledPaths": []
}
```

## Implementation Roadmap

### v1（4.5 周）— 核心功能

| 阶段 | 工作量 | 内容 |
|------|--------|------|
| 客户端 anchor.js | ~2 周 | Shadow DOM 抽屉 + Turndown + marked + highlight + SSE 解析 + 混合图标 + MutationObserver |
| 服务端扩展 | ~2 周 | `SnapAgentProperties.Anchor` + `POST /runs` 扩展 + `AnchorOrchestrator` + `AnchorSummaryCache` + `AnchorContextSummarizer` + `AnchorSkillClassifier` + 2 个新端点 |
| 联调测试 | ~0.5 周 | E2E docs/help 页面场景 + 性能基线（首 token 延迟、缓存命中率） |

### v1.1（2 周）— 增强

- 黑名单路径配置项暴露（`GET /anchor/config` 返回完整配置）
- `window.__SNAP_AGENT_RESCAN__()` 钩子 + 文档
- 主题切换 UI（抽屉顶部 light/dark/auto 按钮 + localStorage 持久化）
- MutationObserver debounce 调优 + 摘要缓存命中率监控

### v2+（未排期）

- 服务端分类器 LRU 缓存（Q11 Option 4）
- 客户端 `sessionStorage` 对话持久化（Q10 Option 3）
- 独立 `anchorAuthorized` 权限位（Q16 Option 2）
- 分阶段时间线调试 UI（Q17 Option 4）
- 宿主 YAML URL→技能映射（Q6 Option 2）

## Open Questions

- 锚点图标的 SVG 选型（💬 气泡 / 📍 锚 / ❓ 问号）—— 待 UI 设计稿
- 分类器模型选型：MVP 用主模型（如 claude-sonnet-4-6）还是配一个轻量小模型降本
- 摘要器输出长度：默认 1500 字，是否需要按内容长度动态调整
- `general` fallback skill：是否需要预置一个无工具的通用聊天技能，还是复用现有某个技能
