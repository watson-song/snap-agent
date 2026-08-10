# SnapAgent 浏览器网络桥接架构

> 版本：v3.0 | 更新日期：2026-08-10

## 1. 概述

SnapAgent 浏览器网络桥接（Bridge）是一种可选的网络代理机制，利用用户浏览器的网络环境，将 SnapAgent 服务端对外部系统的请求通过浏览器转发，解决容器/K8s 环境下无法直连内网服务的问题。

### v3.0 新增功能

| 功能 | 说明 | 版本 |
|------|------|------|
| **Issue Bridge** | 代理 Issue Tracker（禅道/Jira）HTTP 请求 | v1.0 |
| **VCS Bridge** | 代理 VCS（GitLab/Bitbucket）HTTP 请求 | v1.0 |
| **LLM Bridge** | 代理 LLM API 调用，支持多宿主隔离 | v2.0 |
| **File Bridge** | 读取用户本地代码文件，支持目录授权 | v3.0 |

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **零侵入** | `bridge.enabled=false`（默认）时，所有代码路径逐字节不变 |
| **选择性激活** | 需显式配置 `bridge.enabled=true` 开启 |
| **状态优先路由** | 扩展已安装 + 主开关 ON + 服务代理 ON → 直接走桥接，不尝试直连 |
| **不自动降级** | 桥接请求超时或失败时直接报错，不静默降级到直连 |
| **用户授权** | File Bridge 需要用户主动授权本地目录 |

---

## 2. 架构拓扑

```
┌─────────────────────────────────────────────────────────────────────┐
│  SnapAgent Server (容器/K8s)                                         │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Bridge Services                                             │   │
│  │  ┌─────────────────┐  ──────────────┐  ┌───────────────┐  │   │
│  │  │ IssueBridge      │  │ LlmBridge    │  │ FileBridge    │  │   │
│  │  │ Service          │  │ Service      │  │ Service       │  │   │
│  │  └────────┬─────────┘  └──────┬───────┘  └───────┬───────┘  │   │
│  └───────────┼───────────────────┼───────────────────┼──────────┘   │
│              │                   │                   │               │
│  ┌───────────▼───────────────────▼───────────────────▼──────────┐  │
│  │  Bridge Controllers                                           │  │
│  │  ─────────────────┐  ┌──────────────┐  ┌───────────────┐  │  │
│  │  │ /bridge/stream   │  │ /bridge/llm/ │  │ /bridge/file/ │  │  │
│  │  │ /bridge/result   │  │ stream       │  │ stream        │  │  │
│  │  │ /bridge/status   │  │ /result      │  │ /result       │  │  │
│  │  └─────────────────┘  └──────────────┘  ───────────────┘  │  │
│  └─────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┼──────────────────────────────────────┘
                               │ SSE push
┌──────────────────────────────┼──────────────────────────────────────┐
│  Browser                      │                                       │
│  SnapAgent Frontend (index.html)                                      │
│    ┌──────────────────────────────────────────────────────────┐    │
│    │  Bridge Clients (条件加载)                                │    │
│    │  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │    │
│    │  │ bridge-      │  │ llm-bridge-  │  │ file-bridge-  │  │    │
│    │  │ client.js    │  │ client.js    │  │ client.js     │  │    │
│    │  └──────────────┘  └──────────────┘  └───────────────┘  │    │
│    └──────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Chrome Extension v1.1.0 (可选, 独立安装)                            │
│    background.js: fetch() 代理 + chrome.fileSystem 文件读取         │
│    content.js: 与 SnapAgent 页面通信 + 指示灯                        │
│    popup.html: 主开关 + 服务开关 + LLM 配置 + 目录授权              │
│                                                                      │
│  外部系统 / 本地文件                                                  │
│    禅道 / GitLab / LLM API / ~/IdeaProjects/**                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. 桥接服务详解

### 3.1 Issue Bridge (v1.0)

**用途**：代理 Issue Tracker（禅道/Jira/GitHub Issues）HTTP 请求

**流程**：
```
IssueClosureService → BridgeHttpExecutor → IssueBridgeService
  → SSE → 浏览器扩展 → fetch(内网URL) → 结果回传
```

**配置**：
```yaml
snap-agent:
  bridge:
    enabled: true
    request-timeout-ms: 30000
    allowed-host-patterns:
      - "*.sfcloud.local"
      - "*.sf-express.com"
```

---

### 3.2 VCS Bridge (v1.0)

**用途**：代理 VCS（GitLab/Bitbucket）HTTP 请求

**流程**：
```
VcsClient → BridgeHttpExecutor → IssueBridgeService
  → SSE → 浏览器扩展 → fetch(GitLab API) → 结果回传
```

---

### 3.3 LLM Bridge (v2.0)

**用途**：代理 LLM API 调用，支持多宿主隔离

**流程**：
```
BridgeLlmClient → LlmBridgeService → SSE → 浏览器扩展
  → fetch(LLM API) → 结果回传
```

**多宿主隔离**：
```javascript
// chrome.storage.local
{
  "llmConfigs": {
    "http://localhost:8090": { 
      apiKey: "sk-dev", 
      model: "claude-sonnet" 
    },
    "https://staging.sfcloud.local": { 
      apiKey: "sk-staging",
      model: "claude-opus"
    }
  }
}
```

**配置**：
```yaml
snap-agent:
  llm:
    api-type: bridge    # 使用浏览器 LLM
  bridge:
    enabled: true
```

---

### 3.4 File Bridge (v3.0) ✨ 新增

**用途**：读取用户本地代码文件

**流程**：
```
Agent → code_read tool → FileBridgeService → SSE → 浏览器
  → chrome.runtime.sendMessage → Chrome 扩展
  → chrome.fileSystem API → 读取授权目录内文件
  → 内容回传 → Server → Agent
```

**Chrome 扩展权限**：
```json
{
  "permissions": ["storage", "activeTab", "scripting", "fileSystem"],
  "host_permissions": ["<all_urls>"]
}
```

**用户授权流程**：
1. 安装 Chrome 扩展 v1.1.0+
2. 点击扩展图标 → 打开 Popup
3. 点击 "Choose Directory" → 选择 ~/IdeaProjects
4. 授权完成 → 显示 "Authorized: IdeaProjects"
5. Agent 调用 code_read → 通过 bridge 读取本地文件

**配置**：
```yaml
snap-agent:
  bridge:
    enabled: true  # File Bridge 需要 bridge.enabled=true
```

---

## 4. Chrome Extension v1.1.0

### 目录结构

```
chrome-extension/
├── manifest.json          # Manifest V3 + fileSystem 权限
├── background.js           # Service Worker — HTTP 代理 + 文件读取
├── content.js              # 注入 SnapAgent 页面 — 消息中继 + 指示灯
├── popup.html              # 控制面板 — 主开关 + 服务 + LLM + 目录授权
├── popup.js                # 配置读写 + 目录授权逻辑
├── popup.css               # 弹窗样式
└── icon.png                # 扩展图标
```

### manifest.json 关键配置

```json
{
  "manifest_version": 3,
  "permissions": [
    "storage",
    "activeTab",
    "scripting",
    "fileSystem"
  ],
  "host_permissions": ["<all_urls>"]
}
```

### background.js — 代理核心

Service Worker 监听消息类型：

| 消息类型 | 用途 | 处理函数 |
|----------|------|----------|
| `snapagent-fetch` | HTTP 代理请求 | `handleFetch()` |
| `snapagent-get-config` | 获取扩展配置 | 直接返回存储 |
| `snapagent-llm-request` | LLM API 调用 | `handleLlmRequest()` |
| `snapagent-save-llm-config` | 保存 LLM 配置 | `handleSaveLlmConfig()` |
| `snapagent-get-llm-config` | 获取 LLM 配置 | `handleGetLlmConfig()` |
| `snapagent-read-file` | 读取本地文件 | `handleReadLocalFile()` |
| `snapagent-choose-dir` | 选择代码目录 | `handleChooseDirectory()` |
| `snapagent-get-code-dir` | 获取已授权目录 | `handleGetCodeDirectory()` |

### popup.html — 控制面板

```
┌─────────────────────────────────────┐
│  SnapAgent Bridge                   │
├─────────────────────────────────────
│  Current Host: localhost:8090       │
├─────────────────────────────────────┤
│  ☐ Master Switch                    │
├─────────────────────────────────────┤
│  ☐ Issue Tracker                    │
│  ☐ VCS                              │
│  ☐ LLM                              │
├─────────────────────────────────────┤
│  LLM Configuration                  │
│  API Key: [sk-...]                  │
│  Base URL: [https://...]            │
│  Model: [claude-sonnet ▼]           │
│  [Save for this host]               │
├─────────────────────────────────────┤
│  Local Code Directory               │
│  Authorized Directory: IdeaProjects │
│  [Choose Directory]                 │
│   SnapAgent will read files here   │
─────────────────────────────────────┤
│  Active: issue-tracker, vcs, llm    │
└─────────────────────────────────────┘
```

---

## 5. 前端客户端

### bridge-client.js (Issue/VCS Bridge)

```javascript
// 连接 SSE
var es = new EventSource('/snap-agent/bridge/stream');

// 监听代理请求
es.addEventListener('proxy-request', function(event) {
    var req = JSON.parse(event.data);
    // 转发到 Chrome 扩展
    chrome.runtime.sendMessage(
        { type: 'snapagent-fetch', request: req },
        function(response) {
            // 回传结果
            fetch('/snap-agent/bridge/result', {
                method: 'POST',
                body: JSON.stringify(response)
            });
        }
    );
});
```

### llm-bridge-client.js (LLM Bridge)

```javascript
// 连接 LLM SSE
var es = new EventSource('/snap-agent/bridge/llm/stream');

// 监听 LLM 请求
es.addEventListener('llm-request', function(event) {
    var data = JSON.parse(event.data);
    chrome.runtime.sendMessage(
        { type: 'snapagent-llm-request', request: data },
        function(response) {
            fetch('/snap-agent/bridge/llm/result', {
                method: 'POST',
                body: JSON.stringify(response)
            });
        }
    );
});
```

### file-bridge-client.js (File Bridge) ✨ 新增

```javascript
// 连接 File SSE
var es = new EventSource('/snap-agent/bridge/file/stream');

// 监听文件读取请求
es.addEventListener('file-read', function(event) {
    var data = JSON.parse(event.data);
    chrome.runtime.sendMessage(
        { type: 'snapagent-read-file', filePath: data.filePath },
        function(response) {
            fetch('/snap-agent/bridge/file/result', {
                method: 'POST',
                body: JSON.stringify(response)
            });
        }
    );
});
```

---

## 6. 安全设计

### URL 白名单

```yaml
snap-agent:
  bridge:
    allowed-host-patterns:
      - "*.sfcloud.local"
      - "*.sf-express.com"
      - "localhost"
```

### 文件访问控制

| 控制点 | 说明 |
|--------|------|
| 用户主动授权 | 必须点击"选择目录" |
| 目录范围限制 | 只能读授权目录内文件 |
| 持久化权限 | 授权一次，后续无需重复 |
| 可扩展 | 用户可随时更改目录 |

### 认证传递

- SSE 通道复用 SnapAgent 现有安全框架
- Token 在已认证通道中传输，不暴露在页面 DOM
- Extension 在 Service Worker 上下文中执行 fetch()

---

## 7. 部署场景

### 场景 A: 直连可达（不使用桥接）

```yaml
snap-agent:
  bridge:
    enabled: false  # 默认
```

**行为**：所有请求直连，Bridge 代码零执行。

---

### 场景 B: 容器网络隔离，使用桥接

```yaml
snap-agent:
  bridge:
    enabled: true
    request-timeout-ms: 30000
    allowed-host-patterns:
      - "*.sfcloud.local"
  llm:
    api-type: bridge
```

**行为**：
1. 用户安装 Chrome 扩展
2. 配置 LLM API Key
3. 授权本地代码目录
4. 所有请求通过浏览器代理

---

### 场景 C: 仅使用 File Bridge

```yaml
snap-agent:
  bridge:
    enabled: true
```

**行为**：
- Issue/VCS/LLM 仍走直连
- 仅 code_read 工具走桥接
- 适合 Server 在远程但代码在本地的场景

---

## 8. 故障排查

### Q1: File Bridge 读不到文件？

**检查**：
1. Chrome 扩展是否安装 v1.1.0+
2. 是否已授权目录（Popup 中显示 "Authorized: xxx"）
3. 文件是否在授权目录内
4. bridge.enabled 是否为 true

**解决**：
```bash
# 检查 File Bridge 状态
curl http://localhost:8090/snap-agent/bridge/file/status
# 预期: {"active":true,"pending":0,"emitters":1}
```

---

### Q2: LLM Bridge 调用失败？

**检查**：
1. 扩展中 LLM toggle 是否开启
2. 当前宿主的 LLM 配置是否填写
3. API Key 是否有效

**解决**：
```bash
# 检查 LLM Bridge 状态
curl http://localhost:8090/snap-agent/bridge/llm/status
```

---

### Q3: 扩展指示灯不亮？

**原因**：content.js 未注入或 SnapAgent 页面未加载 bridge-client.js

**检查**：
1. 扩展是否启用
2. 页面是否刷新
3. Console 是否有错误

---

## 9. 版本历史

| 版本 | 日期 | 新增功能 |
|------|------|----------|
| v1.0 | 2026-07-29 | Issue Bridge + VCS Bridge |
| v2.0 | 2026-08-07 | LLM Bridge + 多宿主隔离 |
| v3.0 | 2026-08-10 | File Bridge + 目录授权 |

---

**文档维护**: SnapAgent Team  
**最后更新**: 2026-08-10
