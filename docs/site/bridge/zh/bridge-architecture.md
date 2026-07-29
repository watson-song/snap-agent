# SnapAgent 浏览器网络桥接架构

> 版本：v2.0 | 更新日期：2026-07-29

## 1. 概述

SnapAgent 浏览器网络桥接（Bridge）是一种可选的网络代理机制，利用用户浏览器的网络环境，将 SnapAgent 服务端对外部系统（禅道、GitLab 等）的 HTTP 请求通过浏览器转发，解决容器/K8s 环境下无法直连内网服务的问题。

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **零侵入** | `bridge.enabled=false`（默认）时，所有代码路径逐字节不变 |
| **选择性激活** | 需显式配置 `bridge.enabled=true` 开启 |
| **状态优先路由** | 扩展已安装 + 主开关 ON + 服务代理 ON → 直接走桥接，不尝试直连 |
| **不自动降级** | 桥接请求超时或失败时直接报错，不静默降级到直连 |

---

## 2. 架构拓扑

```
┌─────────────────────────────────────────────────────────────────────┐
│  SnapAgent Server (容器/K8s)                                         │
│                                                                      │
│  IssueClosureService / FixExecutionService (不变)                    │
│    └── IssueTracker / VcsClient (不变, BeanPostProcessor 透明包装)     │
│          └── AbstractHttpIssueTracker                                │
│                └── HttpExecutor ← 可注入抽象                          │
│                      ├── DirectHttpExecutor (默认, 原逻辑)             │
│                      └── BridgeHttpExecutor (桥接模式)                │
│                                                                        │
│  BridgeController (新增)                                              │
│    GET  /bridge/stream   → SSE 通道                                   │
│    POST /bridge/result   → 接收前端回传                               │
│    POST /bridge/status-update → 前端上报扩展状态                      │
│    GET  /bridge/status   → 桥接状态查询                               │
└──────────────────────────────┼──────────────────────────────────────┘
                               │ SSE push
┌──────────────────────────────┼──────────────────────────────────────┐
│  Browser                      │                                       │
│  SnapAgent Frontend (index.html)                                      │
│    bridge-client.js (条件加载)                                        │
│      EventSource('/bridge/stream') → dispatch → POST /bridge/result   │
│                                                                        │
│  Chrome Extension (可选, 独立安装)                                    │
│    background.js: fetch() 无 CORS 限制                                │
│    content.js: 与 SnapAgent 页面通信 + 指示灯                         │
│    popup.html: 主开关 + 服务开关                                      │
│                                                                        │
│  外部系统 (内网)                                                      │
│    禅道 / GitLab / Jira                                               │
└──────────────────────────────────────────────────────────────────────┘
```

### 层级说明

| 层 | 组件 | 职责 |
|-----|------|------|
| 接入层 | SnapAgent Web UI + Chrome Extension | SSE 连接、请求分发、CORS-free 代理 |
| 网关层 | BridgeController | REST 端点、SSE 通道管理 |
| 桥接核心层 | IssueBridgeService + BridgeHttpExecutor + DirectHttpExecutor | 状态追踪、路由决策、HTTP 执行 |
| 业务服务层 | IssueClosureService / FixExecutionService | 业务逻辑不变，透明使用桥接 |
| 外部系统 | 禅道 / GitLab / LLM | 桥接模式经扩展访问，直连模式经容器网络访问 |

---

## 3. Chrome Extension 结构

```
snap-agent-bridge-extension/
├── manifest.json          # Manifest V3 配置
├── background.js           # Service Worker — fetch 代理核心
├── content.js              # 注入 SnapAgent 页面 — 消息中继 + 指示灯
├── popup.html              # 控制面板 — 主开关 + 服务开关
├── popup.js                # 配置读写 (chrome.storage.local)
├── popup.css               # 弹窗样式
└── icon.png                # 扩展图标
```

### manifest.json 关键配置

- **Manifest V3**: 使用 Service Worker 而非 Background Page
- **permissions**: `storage`（配置存储）、`activeTab`、`scripting`
- **host_permissions**: `<all_urls>` — 允许代理任意目标 URL
- **content_scripts**: 匹配所有页面，在 `document_idle` 时注入

### background.js — 代理核心

Service Worker 监听两类消息：
1. `snapagent-fetch`: 执行 HTTP 代理请求，返回 response
2. `snapagent-get-config`: 返回当前扩展配置

代理流程：接收请求 → 检查主开关 → 检查服务开关 → `fetch()` 无 CORS 限制 → 返回结果

### content.js — 页面通信

- 注入代理指示灯 DOM（🟢 已连接 / 🔴 未连接）
- `postMessage` 与 SnapAgent 页面双向通信
- `SNAP_AGENT_BRIDGE_READY` 信号通知页面扩展已安装
- `SNAP_AGENT_BRIDGE_CONFIG_CHANGED` 配置变更通知
- 监听 `chrome.storage.onChanged` 实时同步配置

### popup.html — 控制面板

- **主开关 (Master Switch)**: 全局开启/关闭桥接
- **服务开关**: 按服务类型独立控制
  - `Issue Tracker` — 禅道/Jira/GitHub Issues 代理
  - `VCS` — GitLab/Bitbucket 代理
- **状态显示**: 当前连接状态、pending 请求数

### 默认配置

```javascript
var DEFAULT_CONFIG = {
    masterEnabled: true,
    services: {
        'issue-tracker': true,
        'vcs': true
    }
};
```

---

## 4. 多服务代理模型

每个 `BridgeHttpExecutor` 实例绑定一个 `serviceType` 字符串，标识它代理的是哪类服务：

| serviceType | 绑定到 | 代理对象 | 版本支持 |
|-------------|--------|----------|---------|
| `issue-tracker` | `AbstractHttpIssueTracker` 子类 | 禅道/Jira/GitHub Issues | v1 |
| `vcs` | `AbstractHttpVcsClient` 子类 | GitLab/Bitbucket | v1 |
| `llm` | `AbstractStreamingLlmClient` 子类 | LLM 网关（流式） | v2 (chunked relay) |
| `plugin-{name}` | 插件自定义 `HttpExecutor` | 用户上传的 plugin | v1 (插件自选接入) |

Chrome Extension 根据 `serviceType` + 自身配置决定是否代理该请求。

### SSE proxy-request 事件

```json
{
  "id": "req-a1b2c3d4",
  "serviceType": "issue-tracker",
  "url": "http://zentao.sfcloud.local/api.php/v1/products/1/bugs",
  "method": "POST",
  "headers": { "Authorization": "Token xxx" },
  "body": { "title": "..." }
}
```

---

## 5. 路由策略

```
                   ┌──────────────────────────────────┐
                   │ bridge.enabled = false (默认)    │
                   │ → DirectHttpExecutor only         │
                   │ → 现有行为, 零变化               │
                   └──────────────────────────────────┘

                   ┌──────────────────────────────────┐
                   │ bridge.enabled = true            │
                   │                                  │
  扩展已激活 ──────┤ → BridgeHttpExecutor             │
  (installed +      │   → isBridgeActive() = true      │
  master ON +       │   → proxyRequest() via SSE      │
  service ON)       │   → Extension fetch() (无 CORS) │
                   │   → 成功或超时报错               │
                   │   → 不自动降级到直连             │
                   │                                  │
  扩展未激活 ──────┤ → BridgeHttpExecutor             │
  (未安装 /         │   → isBridgeActive() = false     │
  master OFF /      │   → DirectHttpExecutor (直连)    │
  service OFF)      │   → 与 bridge.enabled=false 一致 │
                   └──────────────────────────────────┘
```

### 非侵入性验证

| 配置 | bridge.enabled | 效果 |
|------|----------------|------|
| 不配置 `bridge` 段 | (缺失=false) | 全部使用 DirectHttpExecutor，行为不变 |
| `bridge.enabled: false` | false | 全部使用 DirectHttpExecutor，行为不变 |
| `bridge.enabled: true` + 扩展未激活 | true | BridgeHttpExecutor 内部走直连，行为一致 |
| `bridge.enabled: true` + 扩展已激活 | true | BridgeHttpExecutor 直接走 SSE 桥接 |

---

## 6. 数据流详解

### 6.1 直连模式（bridge.enabled=false）

```
用户点击"创建外部 Issue"
  → Frontend: POST /snap-agent/runs/{taskId}/issue
  → SnapAgentController.createIssue()
  → IssueClosureService.createExternalIssue()
  → ZentaoIssueTracker.createIssue()
  → AbstractHttpIssueTracker.jsonRequest()
  → DirectHttpExecutor.execute()           ← 直连
  → HttpURLConnection → 禅道 API
  → 返回 {"id": 42}
```

### 6.2 桥接模式（扩展已激活）

```
前提: 扩展已安装 + 主开关 ON + issue-tracker 代理 ON

用户点击"创建外部 Issue"
  → Frontend: POST /snap-agent/runs/{taskId}/issue
  → IssueClosureService.createExternalIssue()
  → ZentaoIssueTracker.createIssue()
  → AbstractHttpIssueTracker.jsonRequest()
  → BridgeHttpExecutor.execute()
      → bridgeService.isBridgeActive("issue-tracker") → true
      → bridgeService.proxyRequest()     ← 直接走桥接, 不尝试直连
      → SSE 推送给已连接的前端
           ↓
  → Frontend (bridge-client.js) 收到 SSE event
  → bridge-client.js.dispatch()
      → Chrome Extension sendMessage()
      → Extension background.js: fetch(zentaoUrl)  ← 用户网络
      → 禅道 API 返回 {"id": 42}
      → Extension 返回给 content.js
      → content.js 返回给 bridge-client.js
  → Frontend: POST /snap-agent/bridge/result
  → IssueBridgeService.handleResult()
  → CompletableFuture 完成
  → BridgeHttpExecutor 返回 HttpResponse
  → ZentaoIssueTracker 返回 "42"
  → IssueClosure 状态更新
```

### 6.3 桥接未激活（bridge.enabled=true, 扩展未激活）

```
ZentaoIssueTracker.createIssue()
  → BridgeHttpExecutor.execute()
      → bridgeService.isBridgeActive("issue-tracker") → false
      → directExecutor.execute()        ← 直连, 与 bridge.enabled=false 一致
```

---

## 7. REST API

### 新增端点

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/snap-agent/bridge/stream` | SSE 通道, 服务端推送代理请求 |
| POST | `/snap-agent/bridge/result` | 前端回传代理结果 |
| POST | `/snap-agent/bridge/status-update` | 前端上报扩展状态 |
| GET | `/snap-agent/bridge/status` | 桥接状态查询 |

### 现有端点变更

`GET /snap-agent/info` 响应新增字段:
```json
{
  "bridgeEnabled": false
}
```

---

## 8. 配置属性

```yaml
snap-agent:
  bridge:
    enabled: false                    # 默认关闭, 零影响
    request-timeout-ms: 30000         # 单次桥接请求超时
    sse-connection-timeout-ms: 0     # SSE 连接保活 (0=永不超时)
    allowed-host-patterns:            # 目标 URL 白名单 (安全)
      - "*.sfcloud.local"
      - "*.sf-express.com"
      - "localhost"
```

---

## 9. 安全设计

### URL 白名单

`IssueBridgeService.proxyRequest()` 在发送 SSE 事件前校验目标 URL，只有匹配 `allowed-host-patterns` 的请求才会被代理。

### 认证传递

- SSE 通道复用 SnapAgent 现有安全框架（Basic Auth / Bearer Token / Cookie）
- Token 在已认证的 SSE 通道中传输，不暴露在页面 DOM 中
- Chrome Extension 的 `background.js` 在 Service Worker 上下文中执行 `fetch()`，页面 JS 无法读取
- Extension 侧也做一次域名白名单检查

### 请求超时

- 桥接请求: 30 秒（可配置 `request-timeout-ms`）
- SSE 连接: 无超时（保持长连接，由客户端/网络断连自动清理）
- 扩展未激活时走直连，使用 DirectHttpExecutor 默认超时

---

## 10. 安装与使用

### Chrome Extension 安装

1. 下载 `snap-agent-bridge-extension` 目录或 `.zip` 文件
2. 打开 Chrome，访问 `chrome://extensions/`
3. 开启"开发者模式"
4. 点击"加载已解压的扩展程序"，选择扩展目录
5. 在 SnapAgent 页面上确认指示灯变绿（🟢 已连接）

### 配置步骤

1. 点击 Chrome 工具栏中的 SnapAgent Bridge 图标
2. 开启主开关（Master Switch）
3. 按需开启服务代理（Issue Tracker / VCS）
4. 在 `application.yml` 中配置 `snap-agent.bridge.enabled=true`
5. 配置 `allowed-host-patterns` 白名单

---

## 11. 部署场景

### 场景 A: 直连可达（不使用桥接）

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.internal.com
      token: xxx
      product-id: 1
```

**行为**: DirectHttpExecutor 直接调用禅道 API。零额外步骤。

### 场景 B: 容器网络隔离，使用桥接

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.sfcloud.local  # 内网地址, 容器不可达
      token: xxx
      product-id: 1
  bridge:
    enabled: true
    allowed-host-patterns:
      - "*.sfcloud.local"
```

**行为**: 用户安装 Chrome Extension 并开启主开关 → 请求经浏览器代理到内网禅道。

### 场景 C: 桥接已开启但扩展未激活

```yaml
snap-agent:
  bridge:
    enabled: true  # 开了桥接, 但扩展未安装
```

**行为**: BridgeHttpExecutor 检测 `isBridgeActive()=false` → 走 DirectHttpExecutor 直连。与 `bridge.enabled=false` 行为一致。

---

## 12. 使用案例

### 案例 1: K8s 容器创建禅道 Bug

**场景**: SnapAgent 部署在 K8s 集群中，禅道部署在内网，容器无法直接访问。

**流程**:
1. 用户在 SnapAgent UI 中完成问题诊断
2. 点击"创建外部 Issue"
3. SnapAgent 服务端通过 SSE 将请求推送到前端
4. Chrome Extension 在用户浏览器中执行 `fetch()` 调用禅道 API
5. 禅道创建 Bug，返回 ID
6. 结果回传到服务端，IssueClosure 记录外部 Issue ID 和来源

**优势**: 无需修改 K8s 网络策略，无需 VPN，用户浏览器天然有内网访问权限。

### 案例 2: 内网 GitLab 创建修复 PR

**场景**: SnapAgent 自动诊断后需要通过 GitLab API 创建修复分支和 MR。

**流程**:
1. FixExecutionService 调用 GitLabVcsClient
2. BridgeHttpExecutor 检测 `isBridgeActive("vcs")=true`
3. 请求经 SSE → Chrome Extension → GitLab API
4. PR 创建成功，结果回传

### 案例 3: 混合模式 — 部分服务直连，部分桥接

**场景**: 禅道在内网（需要桥接），但 LLM 网关在公网（可直连）。

**配置**:
```yaml
snap-agent:
  bridge:
    enabled: true
    allowed-host-patterns:
      - "*.sfcloud.local"
```

**扩展配置**:
- Issue Tracker: ON（禅道走桥接）
- VCS: ON（GitLab 走桥接）

**行为**: 禅道/GitLab 请求走桥接，LLM 请求不受影响（LlmClient 使用 OkHttp，v2 实现后再接入桥接）。
