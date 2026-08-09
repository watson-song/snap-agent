---
name: snap-agent-bridge-system
description: Bridge 桥接系统 — Issue Bridge + LLM Bridge、SSE 通信、多宿主隔离
version: 1.0.0
modules:
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# Bridge 桥接系统

## 1. 架构概述

Bridge 解决容器/K8s 环境下无法直连内网服务的问题，利用用户浏览器的网络环境代理 HTTP 请求。

```
┌─────────────────────────────────────┐
│  SnapAgent Server (容器)             │
│  ┌─────────────────────────────    │
│  │ BridgeController              │    │
│  │  ├─ GET  /bridge/stream       │    │
│  │  ├─ POST /bridge/result       │    │
│  │  ─ GET  /bridge/status       │    │
│  └─────────────────────────────    │
│  ┌─────────────────────────────    │
│  │ LlmBridgeController           │    │
│  │  ├─ GET  /bridge/llm/stream   │    │
│  │  ├─ POST /bridge/llm/result   │    │
│  │  └─ POST /bridge/llm/status   │    │
│  └─────────────────────────────    │
─────────────────────────────────────┘
          │ SSE
          ▼
┌─────────────────────────────────────┐
│  Browser                            │
│  ┌─────────────────────────────    │
│  │ bridge-client.js              │    │
│  │  ─ EventSource → SSE           │    │
│  │  ─ POST /bridge/result         │    │
│  └─────────────────────────────    │
│  ┌─────────────────────────────    │
│  │ Chrome Extension              │    │
│  │  ─ background.js fetch()       │    │
│  │  ─ 无 CORS 限制                 │    │
│  └─────────────────────────────    │
─────────────────────────────────────┘
          │ fetch()
          ▼
┌─────────────────────────────────────┐
│  内网服务（禅道/GitLab/LLM API）     │
─────────────────────────────────────┘
```

## 2. Issue Bridge（v1.0）

### 组件

| 组件 | 职责 |
|------|------|
| `IssueBridgeService` | SSE 管理、pending 请求、状态追踪 |
| `BridgeHttpExecutor` | HTTP 执行抽象，路由决策 |
| `DirectHttpExecutor` | 直连模式（默认） |
| `BridgeClientStatus` | 扩展状态 DTO |

### 路由策略

```
bridge.enabled = false (默认)
  → DirectHttpExecutor only
  → 现有行为，零变化

bridge.enabled = true
  ├─ 扩展已激活 (installed + master ON + service ON)
  │   → BridgeHttpExecutor → SSE → 扩展 fetch()
  │   → 不自动降级到直连
  │
  └─ 扩展未激活
      → BridgeHttpExecutor → DirectHttpExecutor
      → 与 bridge.enabled=false 一致
```

### 服务类型

| serviceType | 绑定到 | 代理对象 |
|-------------|--------|----------|
| `issue-tracker` | AbstractHttpIssueTracker | 禅道/Jira/GitHub Issues |
| `vcs` | AbstractHttpVcsClient | GitLab/Bitbucket |
| `llm` | AbstractStreamingLlmClient | LLM 网关（v2） |

## 3. LLM Bridge（v1.0）

### 组件

| 组件 | 职责 |
|------|------|
| `LlmBridgeService` | LLM SSE 管理、pending 请求 |
| `BridgeLlmClient` | LlmClient 实现，通过 bridge 调用 |
| `LlmBridgeController` | REST 端点：`/bridge/llm/*` |

### 配置

```yaml
snap-agent:
  llm:
    api-type: bridge    # 使用浏览器 LLM
  bridge:
    enabled: true
    request-timeout-ms: 30000
```

### 多宿主隔离

浏览器插件按域名存储 LLM 配置：

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

### 前端客户端

```
llm-bridge-client.js
  ── EventSource('/snap-agent/bridge/llm/stream')
  ── 收到 llm-request → chrome.runtime.sendMessage
  ── 收到响应 → POST /snap-agent/bridge/llm/result
```

**Simulator 模式**（测试用）：
```
llm-bridge-simulator.js
  ── 监听 postMessage('snapagent-llm-request')
  ── 直接 fetch() LLM API
  ── POST 结果回 Server
```

## 4. Chrome 扩展

### 目录结构

```
chrome-extension/
├── manifest.json        # Manifest V3
├── background.js        # Service Worker — LLM + Issue/VCS 代理
├── content.js           # 页面注入 — 消息中继 + 指示灯
├── popup.html           # 控制面板 UI
── popup.js             # 配置读写（多宿主 LLM 配置）
├── popup.css            # 弹窗样式
└── icon.png             # 扩展图标
```

### manifest.json 关键配置

```json
{
  "manifest_version": 3,
  "permissions": ["storage", "activeTab", "scripting"],
  "host_permissions": [
    "<all_urls>",
    "https://api.anthropic.com/*",
    "https://*.openai.com/*"
  ]
}
```

## 5. 安全设计

### URL 白名单

```yaml
snap-agent:
  bridge:
    allowed-host-patterns:
      - "*.sfcloud.local"
      - "*.sf-express.com"
      - "localhost"
```

### 认证传递

- SSE 通道复用 SnapAgent 现有安全框架
- Token 在已认证通道中传输，不暴露在页面 DOM
- Extension 在 Service Worker 上下文中执行 fetch()

### API Key 存储

| 方案 | 位置 | 安全性 |
|------|------|--------|
| 扩展配置（推荐） | `chrome.storage.local` | ✅ 页面 JS 无法读取 |
| Server 配置 | `application.yml` | ❌ 暴露风险 |
