---
name: snap-agent-bridge-system
description: Bridge 桥接 — Issue Bridge + LLM Bridge、SSE 通信、多宿主隔离、Chrome Extension
version: 2.0.0
modules:
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# Bridge 桥接系统

## 1. 架构

解决容器/K8s 环境无法直连内网服务的问题，利用浏览器网络代理 HTTP 请求。

```
SnapAgent Server → SSE → Browser Extension → fetch() → 内网服务（禅道/GitLab/LLM API）
```

## 2. Issue Bridge

| 组件 | 模块 | 职责 |
|------|------|------|
| `IssueBridgeService` | boot2x/bridge | SSE 管理、pending 请求 |
| `BridgeHttpExecutor` | boot2x/issue | HTTP 执行路由 |
| `DirectHttpExecutor` | boot2x/issue | 直连模式（默认）|
| `BridgeClientStatus` | boot2x/issue | 扩展状态 DTO |

路由策略：`bridge.enabled=false` → 直连；`bridge.enabled=true` + 扩展已激活 → Bridge 代理。

## 3. LLM Bridge

| 组件 | 模块 | 职责 |
|------|------|------|
| `LlmBridgeService` | boot2x/bridge | LLM SSE 管理 |
| `BridgeLlmClient` | boot2x/llm | LlmClient 实现，通过 bridge 调用 |
| `LlmBridgeController` | boot2x/web | REST: `/bridge/llm/*` |
| `BridgeLlmResponse` | boot2x/bridge | 响应 DTO |

## 4. REST 端点

| 端点 | 说明 |
|------|------|
| `GET /bridge/stream` | Issue Bridge SSE |
| `POST /bridge/result` | Issue Bridge 结果回传 |
| `GET /bridge/status` | Bridge 状态 |
| `GET /bridge/llm/stream` | LLM Bridge SSE |
| `POST /bridge/llm/result` | LLM Bridge 结果回传 |

## 5. 配置

```yaml
snap-agent:
  bridge:
    enabled: false
    request-timeout-ms: 30000
    allowed-host-patterns:
      - "*.sfcloud.local"
```
