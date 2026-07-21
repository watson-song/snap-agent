# 锚点问答功能接入指南

> SnapAgent v0.4 — 让宿主应用页面区域支持"点击锚点 → 即时问答"

## 功能简介

SnapAgent 锚点问答功能让宿主应用页面上的任意区域都可以挂载"锚点图标"。用户点击锚点图标后，右侧滑出抽屉，可在抽屉里针对该区域内容发起 LLM 问答——无需切换到 SnapAgent 主 SPA，上下文不丢失。

**典型场景**：
- 文档站：用户读到"订单状态"区块，想问"已发货但未签收一般要多久"
- 运维仪表盘：用户看到某指标异常，想问"这个 QPS 下降是什么原因"
- 业务表单：用户填写表单时，想问"这个字段填什么格式"
- 后台管理列表：用户看到某 SKU 行异常，想问"这个 SKU 为什么没生成补货策略"

### 抽屉 UI 组成

抽屉打开后包含三个可见区域：

1. **标题栏**：左侧显示锚点名称（`data-snap-anchor` 属性值）+ 内容摘要 subtitle（前 80 字符的单行摘要），右侧关闭按钮。抽屉右侧两角圆角（`border-radius:0 16px 16px 0`）。
2. **技能信息条**（可选）：若锚点声明了 `data-snap-skill`，显示当前使用的技能名 + 描述。
   - `data-snap-skill="auto"` → 显示"智能路由 (Auto)" + "根据锚点内容和问题自动匹配最合适的技能"
   - 指定技能名 → 调 `GET /snap-agent/skills` 拉取技能 displayName 和 description
3. **对话区**：显示用户消息、AI 回复、错误消息；底部输入框 + 发送按钮。

## 架构概览

```
┌─ 宿主应用页面 ────────────────────────────────────────────┐
│  <main>                                                    │
│    <section data-snap-anchor="订单状态区块">  ┌──┐         │
│      ...页面内容...                          │💬│ ← 锚点   │
│    </section>                                 └──┘   图标  │
│                                                            │
│  <script src="/snap-agent/anchor.js" defer></script>       │
└─────────────────────────────┬──────────────────────────────┘
                              │ 点击锚点
                              ▼
┌─ 右侧抽屉（Shadow DOM，右侧两角圆角） ─────────────────────┐
│  💬 讟单状态区块                                  ✕ 关闭    │
│  ## 订单状态 / 当前状态：已发货 / 物流单号：SF...  ← 摘要   │
│  ──────────────────────────────────────────────────────── │
│  ⚡ 智能路由 (Auto)                                        │
│  根据锚点内容和问题自动匹配最合适的技能                     │
│  ──────────────────────────────────────────────────────── │
│  用户：这个订单为什么还没到？                              │
│  AI：根据物流信息...                                      │
│  ──────────────────────────────────────────────────────── │
│  [输入框]                                       [发送]    │
└──────────────────────────────────────────────────────────┘
```

## 接入步骤

### 步骤 1：宿主引入脚本

在宿主应用的全局页面模板（如 Thymeleaf layout、Vue/React 根组件）中添加：

```html
<script src="/snap-agent/anchor.js" defer></script>
```

脚本加载后会自动：
1. 调用 `GET /snap-agent/auth-config` 获取 token 策略（与 SnapAgent 主 SPA 共享）
2. 调用 `GET /snap-agent/user-info` 校验权限（未授权则静默禁用，不渲染任何图标）
3. 调用 `GET /snap-agent/anchor/config` 获取黑名单路径配置
4. 扫描 `main` 区域内的锚点元素，注入锚点图标

### 步骤 2：提供 SecurityGateway Bean（关键）

`anchor.js` 启动时会调 `GET /snap-agent/user-info` 检查授权状态。若宿主未提供任何 `SecurityGateway` 实现，接口会返回 `authenticated: false, message: "security not configured"`，`anchor.js` 将**静默不渲染任何图标**。

**两种解决方式：**

**方式 A — 实现自定义 SecurityGateway（推荐）**

```java
@Configuration
public class MySecurityGateway implements SecurityGateway {
    @Override
    public String currentUserId() {
        // 从 Spring Security / Shiro / 自定义 Session 中取出当前用户 ID
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof UserDetails
                ? ((UserDetails) principal).getUsername()
                : "anonymous";
    }

    @Override
    public boolean hasPermission(String code) {
        // 返回 true 表示当前用户有访问 SnapAgent 的权限
        // 可以接入 RBAC、ACL 等权限系统
        return true;
    }
}
```

**方式 B — 匿名降级（公开文档站）**

```yaml
snap-agent:
  security:
    required-permission: ""   # 空字符串 = 允许匿名访问
```

同时需要提供一个返回固定用户 ID 的 `SecurityGateway`：

```java
@Configuration
public class AnonymousSecurityGateway implements SecurityGateway {
    @Override
    public String currentUserId() { return "anonymous"; }
    @Override
    public boolean hasPermission(String code) { return true; }
}
```

### 步骤 3：标注锚点区域（推荐）

在需要锚点问答的页面区域加 `data-snap-anchor` 属性：

```html
<section data-snap-anchor="订单状态区块">
  <h2>订单状态</h2>
  <p>当前状态：已发货</p>
  <p>物流单号：SF1234567890</p>
  <!-- ... -->
</section>

<section data-snap-anchor="物流轨迹" data-snap-skill="patrol">
  <h2>物流轨迹</h2>
  <!-- ... -->
</section>

<section data-snap-anchor="SKU 概览" data-snap-skill="auto">
  <!-- data-snap-skill="auto" 显式启用智能路由 -->
</section>
```

属性说明：

| 属性 | 必填 | 说明 |
|------|------|------|
| `data-snap-anchor` | 是 | 锚点名称（展示给用户，显示在抽屉标题栏） |
| `data-snap-skill` | 否 | 指定 SnapAgent 技能名；`auto`（默认）= 智能路由；不填 = 智能路由；`off` = 关闭（仅展示内容，不发起问答） |

### 步骤 4：自动发现降级（可选）

如果页面未标注任何 `data-snap-anchor`，脚本会自动扫描 `main` 区域内的语义元素：
- `<section>`
- 带 `id` 的 `<h2>`、`<h3>`

无需任何标注，文档类页面开箱即用。

### 步骤 5：配置（可选）

在 `application.yml` 中调整锚点功能行为：

```yaml
snap-agent:
  anchor:
    enabled: true                              # 总开关，默认 true
    disabled-paths:                            # 黑名单路径（不扫描锚点）
      - "/payment/**"
      - "/admin/security/**"
    max-context-chars: 8000                    # 单次发送给 LLM 的最大字符数
    preprocess-enabled: true                   # 点击锚点时预摘要（降低首 token 延迟）
    preprocess-timeout-ms: 5000                # 预摘要超时（用户未在 5 秒内提问则取消）
    summary-threshold-chars: 4000              # 短内容跳过摘要（直接用原文）
    summary-cache-ttl-seconds: 600             # 摘要缓存 TTL（Caffeine LRU，默认 10 分钟）
    classifier-model: ""                       # 分类器使用的模型（空 = 用默认模型）
    classifier-confidence-threshold: 0.5       # 低于此值降级为通用 LLM 直答
```

完整配置参考见下方 [§配置参考](#配置参考)。

## 工作原理

### 用户交互流程

1. 用户访问宿主页面
2. `anchor.js` 加载，完成认证检查与配置拉取
3. 脚本扫描 DOM，在标注区域注入锚点图标（标题左侧 / 区域右上角）
4. 用户点击锚点图标 → 右侧抽屉滑出（Shadow DOM 隔离样式）
5. **抽屉标题栏**：显示锚点名称 + 内容摘要（前 80 字符）
6. **技能信息条**：若 `data-snap-skill` 非 `off`，显示技能名 + 描述
7. 客户端立即发 `POST /snap-agent/anchor/preprocess`，服务端 `AnchorOrchestrator` 并行启动：
   - **LLM 摘要器** (`AnchorContextSummarizer`)：把页面区域内容压缩为 ≤1500 字摘要
   - **LLM 分类器** (`AnchorSkillClassifier`)：根据问题 + 内容首段判断该路由到哪个 SnapAgent 技能
8. 用户在抽屉里输入问题，点击发送
9. `anchor.js` 调 `POST /snap-agent/runs`（`skillId: "auto"` + `anchor` 字段 + 可选 `preprocessId`）
10. 服务端 `AnchorOrchestrator.executeWithAnchor()`：
    - 从预摘要结果或缓存中取摘要（长内容自动摘要，短内容直接用原文）
    - 从预分类结果取 `ClassifyResult`（置信度 ≥ 阈值则路由到对应技能，否则降级为通用 LLM 直答）
    - 构建增强用户消息：页面 URL + 锚点名 + 截断标记 + 区块内容（或摘要）+ 用户提问
    - 调 `LlmClient.stream()` 流式输出到 SSE
11. SSE 流式返回 token 到抽屉，对话完成

### 上下文提取

客户端用内置 HTML→Markdown 转换器把锚点区域的 DOM 转成 Markdown：

| HTML | Markdown |
|------|----------|
| `<h1>` / `<h2>` / `<h3>` / `<h4>` | `#` / `##` / `###` / `####` |
| `<strong>` / `<b>` | `**text**` |
| `<em>` / `<i>` | `*text*` |
| `<code>` | `` `code` `` |
| `<pre>` | ` ```code``` ` |
| `<a href="...">` | `[text](url)` |
| `<ul>` / `<ol>` / `<li>` | `- item` |
| `<table>` | Markdown 表格（首行后加 `|---|` 分隔行） |
| `<br>` | `\n` |

脚本无需外部依赖（**不依赖 Turndown**），实现内置于 `anchor.js`。Markdown 比 HTML 节省 60-70% tokens，且 LLM 对 Markdown 的理解力更强。

### 预摘要与缓存

服务端 `AnchorContextSummarizer` 在内容超过 `summary-threshold-chars`（默认 4000）时调用 LLM 生成摘要：

- 短内容（≤4000 字符）：直接用原文，不调 LLM
- 长内容：调 LLM 压缩为 ≤1500 字摘要，保留关键信息（标题、数据、状态值、表格结构）
- LLM 失败：返回原文作为降级

`AnchorSummaryCache` 用 Caffeine LRU + TTL 缓存摘要：

- Key：内容的 SHA-256 哈希（避免大字符串作为 key）
- TTL：`summary-cache-ttl-seconds`（默认 600 秒 / 10 分钟）
- 容量：默认 256 条
- 相同内容再次点击时直接命中缓存，无需重新调用 LLM

### 智能路由分类

`AnchorSkillClassifier` 用 LLM 判断"问题 + 内容 → skillId"：

1. 构造 prompt，列出所有 `AVAILABLE` 状态的技能（name + description）
2. LLM 返回 JSON：`{"skillId": "...", "confidence": 0.0..1.0, "reason": "..."}`
3. 置信度 ≥ `classifier-confidence-threshold`（默认 0.5）且 `skillId` 非空 → 路由到对应技能
4. 置信度 < 阈值或无匹配 → 降级为通用 LLM 直答（仍带页面上下文，调用 `LlmClient.stream()` 直接回答）

分类器使用的模型可单独配置：`classifier-model`（空字符串 = 用 `snap-agent.llm.model`）。失败时自动降级，用户无感知。

### 抽屉 UI 细节

**Shadow DOM 隔离**：抽屉用 `attachShadow({mode: 'open'})` 创建，所有样式不污染宿主页面，也不受宿主页面 CSS 影响。

**标题栏 subtitle**：抽屉打开时，自动把捕获的 Markdown 内容的前 80 字符（单行、去换行）作为 subtitle 显示在锚点名称下方，让用户快速确认上下文是否正确。

**技能信息条**：
- `data-snap-skill="auto"` 或不填：显示"智能路由 (Auto)" + 描述
- `data-snap-skill="<skill-name>"`：调 `GET /snap-agent/skills` 拉取技能 displayName + description
- `data-snap-skill="off"`：隐藏技能信息条，仅展示内容，不发起问答

**圆角设计**：抽屉容器 `border-radius:0 16px 16px 0;overflow:hidden` —— 左侧两角直角（贴合页面右边缘），右侧两角 16px 圆角，视觉上与宿主页面形成层次。

## 权限模型

锚点功能完全继承 SnapAgent 现有权限检查：

- **未提供 SecurityGateway Bean** → `user-info` 返回 `authenticated: false` → `anchor.js` 静默不渲染图标
- **已认证但无 `snap-agent:access` 权限** → `user-info` 返回 `authorized: false` → `anchor.js` 静默不渲染图标
- **已认证且授权** → 锚点正常启用

公开文档站点可走匿名降级（`snap-agent.security.required-permission: ""` + 自定义 `SecurityGateway` 返回固定用户 ID），按 IP 限流。

## SPA 适配

`anchor.js` 兼容 Vue / React / Angular 等单页应用：

- `DOMContentLoaded` 初始扫描
- `MutationObserver` 监听 `main` 区域子树变更，800ms debounce 后增量扫描
- 暴露 `window.__SNAP_AGENT_RESCAN__()` 全局函数，宿主可主动触发立即扫描

```javascript
// Vue Router 示例
router.afterEach(() => {
  window.__SNAP_AGENT_RESCAN__ && window.__SNAP_AGENT_RESCAN__();
});
```

## 移动端

- 桌面端：右侧抽屉（400px 宽）
- 移动端（<768px）：底部抽屉（70vh 高度）
- 锚点图标移动端常驻显示，尺寸 28×28 符合触控目标

## 错误处理

与 SnapAgent 主 SPA 完全一致：

| HTTP | 错误码 | 抽屉内表现 |
|------|--------|-----------|
| 401 | `UNAUTHORIZED` | "Login required" |
| 403 | `FORBIDDEN` | "Permission denied" |
| 429 | `RATE_LIMITED` | "Rate limited. Please try again later." |
| 其他 !ok | - | "Request failed (status)" |
| 网络错误 | - | "Network error: ..." |
| SSE `task_error` | - | "Error: ..." |
| SSE 断开 | - | 保留已生成的部分回复 + "[Connection lost. Resend to retry.]" |

## 常见问题

### Q: 锚点图标没出现？

按顺序排查：

1. **检查浏览器控制台是否有错误**
2. **检查 `user-info` 接口**：浏览器访问 `GET /snap-agent/user-info`
   - 若返回 `authenticated: false` 或 `message: "security not configured"` → 未提供 `SecurityGateway` Bean，参考 [步骤 2](#步骤-2提供-securitygateway-bean关键)
   - 若返回 `authorized: false` → 用户没有 `snap-agent:access` 权限，或 `required-permission` 配置错误
3. **检查 `anchor/config` 接口**：`GET /snap-agent/anchor/config` 应返回 `{"enabled": true, "disabledPaths": [...]}`
4. **检查路径黑名单**：确认当前 URL 不在 `snap-agent.anchor.disabled-paths` 中
5. **检查 DOM**：确认页面有 `<main>` 元素，或有 `data-snap-anchor` 标注的元素

### Q: 首次回答很慢？

- 长内容（>4000 字符）会触发摘要器，多一次 LLM 调用
- 预摘要功能利用"用户思考时间"（点击锚点 → 用户输入问题这段空档），正常情况下首 token 延迟 2-3 秒
- 若持续慢，检查 LLM 配置：
  ```yaml
  snap-agent:
    llm:
      timeout-seconds: 120    # LLM 超时时间
      model: claude-sonnet-4-6
    anchor:
      summary-threshold-chars: 4000   # 降低此值可让更多内容走原文路径
  ```
- 摘要缓存命中后，相同内容的二次问答秒级响应

### Q: 如何禁用某些页面的锚点？

```yaml
snap-agent:
  anchor:
    disabled-paths:
      - "/payment/**"
      - "/admin/security/**"
```

路径匹配规则：
- `/payment/**`：匹配 `/payment` 和 `/payment/anything`
- `/payment`：匹配 `/payment` 和 `/payment/anything`（前缀匹配）

### Q: 如何让锚点使用特定技能？

```html
<section data-snap-anchor="运维指标" data-snap-skill="patrol">
  <!-- ... -->
</section>
```

不指定 `data-snap-skill` 或设为 `auto` 时由智能路由分类器自动判断。设为 `off` 则不发起问答。

### Q: 如何完全关闭锚点功能？

```yaml
snap-agent:
  anchor:
    enabled: false
```

关闭后 `anchor.js` 不再扫描 DOM、不渲染图标，`AnchorOrchestrator` 不会被装配（在 `SnapAgentAutoConfiguration` 中检查 `isEnabled() && llmClient != null`），所有锚点相关端点返回 `503 ANCHOR_DISABLED`。

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/snap-agent/anchor.js` | `GET` | 静态资源：锚点脚本（无需认证） |
| `/snap-agent/anchor/config` | `GET` | 公开：返回 `{enabled, disabledPaths}` 给客户端 |
| `/snap-agent/anchor/preprocess` | `POST` | 需认证：点击锚点时预摘要 + 预分类，返回 `{preprocessId, status}` |
| `/snap-agent/runs` | `POST` | 扩展：请求体加 `anchor` 字段 + `skillId: "auto"` 触发智能路由 |

### POST /snap-agent/runs（锚点模式）

**请求体：**

```json
{
  "skillId": "auto",
  "inputs": {
    "message": "这个分类有多少个 SKU？"
  },
  "anchor": {
    "name": "商品概览",
    "content": "## 商品概览\n\n| 一级分类 | SKU 数量 | 品牌数 |\n|---|---|---|\n| 烘焙类 | 3 | 1 |",
    "truncated": false,
    "originalLength": 0,
    "pageUrl": "/skus"
  },
  "preprocessId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**响应（202 Accepted）：**

```json
{
  "taskId": "sa_1784556364221_60c600a7a559",
  "status": "PENDING",
  "streamUrl": "/snap-agent/runs/sa_1784556364221_60c600a7a559/stream"
}
```

**审计日志**：`action=RUN_ANCHOR_QA, details={skillId=auto, anchor=商品概览, taskId=...}`

后续 SSE 流与普通 Skill 运行完全一致，见 [用户手册 §10 REST API 参考](../manual/zh/user-manual.md#10-rest-api-参考)。

## 配置参考

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `snap-agent.anchor.enabled` | `true` | 总开关。关闭后不装配 `AnchorOrchestrator`，`anchor.js` 不扫描 DOM |
| `snap-agent.anchor.disabled-paths` | `[]` | 黑名单路径列表（Ant-style），不扫描这些路径下的锚点 |
| `snap-agent.anchor.max-context-chars` | `8000` | 单次发送给 LLM 的最大字符数。超长内容会被截断，`AnchorContext.truncated=true` |
| `snap-agent.anchor.preprocess-enabled` | `true` | 是否启用预摘要 + 预分类。关闭后所有计算延迟到 `POST /runs` 时同步执行 |
| `snap-agent.anchor.preprocess-timeout-ms` | `5000` | 预摘要超时（毫秒）。用户未在 5 秒内提问则取消预计算 |
| `snap-agent.anchor.summary-threshold-chars` | `4000` | 短内容跳过摘要（直接用原文）。降低此值可让更多内容走原文路径，减少 LLM 调用 |
| `snap-agent.anchor.summary-cache-ttl-seconds` | `600` | 摘要缓存 TTL（Caffeine LRU，10 分钟）。相同内容二次点击直接命中缓存 |
| `snap-agent.anchor.classifier-model` | `""` | 分类器使用的模型。空 = 用 `snap-agent.llm.model`。可用便宜的小模型降低成本 |
| `snap-agent.anchor.classifier-confidence-threshold` | `0.5` | 低于此值降级为通用 LLM 直答。调高 = 更保守（更倾向通用直答），调低 = 更激进（更多走技能路由） |

完整 YAML 示例：

```yaml
snap-agent:
  anchor:
    enabled: true
    disabled-paths:
      - "/payment/**"
      - "/admin/security/**"
    max-context-chars: 8000
    preprocess-enabled: true
    preprocess-timeout-ms: 5000
    summary-threshold-chars: 4000
    summary-cache-ttl-seconds: 600
    classifier-model: ""              # 用便宜模型如 gpt-3.5 可降低分类成本
    classifier-confidence-threshold: 0.5
```

## 自动装配

`SnapAgentAutoConfiguration` 在以下条件同时满足时自动装配 `AnchorOrchestrator` 并注入到 `SnapAgentController`：

1. `snap-agent.anchor.enabled=true`（默认 true）
2. `LlmClient` 已装配（即 `snap-agent.llm.base-url` 和认证信息已配置）

装配链：

```
LlmClient + SnapAgentProperties.Anchor
        │
        ▼
AnchorSummaryCache (Caffeine LRU + TTL)
        │
        ▼
AnchorContextSummarizer ──┐
                          ├──> AnchorOrchestrator ──> SnapAgentController.setAnchorOrchestrator()
AnchorSkillClassifier ────┘
```

日志可见：`AnchorOrchestrator wired (anchor feature enabled)`

## 后续阅读

- [设计 spec](../../../superpowers/specs/2026-07-20-host-page-anchor-qa-design.md)
- [宿主集成指南](./host-integration-guide.md)
- [用户手册 — 锚点问答](../manual/zh/user-manual.md)
- [系统架构](../architecture/zh/system-architecture.md)
