# SnapAgent 浏览器网络桥接架构设计

> **版本**: v2.0 | **日期**: 2026-07-29 | **作者**: SnapAgent Team
> **v1.1 变更**: 扩展为多服务代理（IssueTracker + VcsClient + LlmClient + Plugin），扩展插件按服务开关代理，SnapAgent 页面代理指示灯，扩展下载入口
> **v2.0 变更**: 路由逻辑从"先直连后桥接"改为"扩展激活时直接桥接"——扩展已安装且主开关开且服务代理开 → 直接走桥接，不尝试直连；扩展未安装或未启用 → 直连。与项目 2.x 分支版本号对齐。

## 1. 背景与问题陈述

### 1.1 当前架构

SnapAgent 通过多个 SPI 对接外部系统，当前架构为**服务端直连模式**：

```
SnapAgent Server (容器/K8s)
  ├── IssueClosureService
  │     └── IssueTracker (ZentaoIssueTracker / GitHubIssueTracker / JiraIssueTracker)
  │           └── AbstractHttpIssueTracker.jsonRequest() → HttpURLConnection → 禅道/Jira/GitHub
  ├── FixExecutionService
  │     └── VcsClient (GitLabVcsClient / BitbucketVcsClient)
  │           └── AbstractHttpVcsClient.jsonRequest() → HttpURLConnection → GitLab/Bitbucket
  └── AgentService
        └── LlmClient (AnthropicLlmClient / OpenAiLlmClient)
              └── AbstractStreamingLlmClient → OkHttp SSE 流式 → LLM 网关
```

三类外部调用：
- **IssueTracker** / **VcsClient**: 简单 request-response (`HttpURLConnection`)，可直接桥接
- **LlmClient**: 流式 SSE (`OkHttp`)，桥接需 chunked relay（v2 实现）

### 1.2 问题

部署在 K8s/容器中的 SnapAgent 实例往往**无法直接访问内网 Issue 系统**（禅道、内网 GitLab 等），因为：

- 容器网络与内网隔离（K8s NetworkPolicy、VPC 隔离）
- 内网服务仅对办公网/VPN 开放
- 容器无法解析内网域名（如 `*.sfcloud.local`）

但用户浏览器天然能访问这些内网资源——浏览器运行在用户的办公电脑上，有完整的内网网络访问权限。

### 1.3 目标

利用用户浏览器的网络能力，作为 SnapAgent 服务端到内网 Issue 系统的**可选桥接通道**，在不影响直连场景的前提下，扩展 SnapAgent 的网络可达性。

---

## 2. 设计原则

### P1: 零侵入 — 直连场景完全不受影响

> "宿主 B 集群直接能访问 issue tracker 的情况下不要给他带来额外集成步骤或改任何代码"

- 当 `snap-agent.bridge.enabled=false`（默认）时，所有现有代码路径**逐字节不变**
- 不引入新的依赖、新的配置项、新的 Bean
- `IssueTracker` SPI 接口不变
- `ZentaoIssueTracker`、`GitHubIssueTracker`、`JiraIssueTracker` 实现不变
- `IssueClosureService` 不变
- 宿主应用的 `application.yml` 不需要加任何配置

### P2: 选择性激活 — 桥接是附加能力

- 桥接功能由 `snap-agent.bridge.enabled=true` 显式开启
- 开启后，根据**扩展状态**决定路由：扩展已安装且主开关开且服务代理开 → 直接走桥接；否则 → 直连
- Chrome Extension 是桥接的一种实现手段，不是唯一手段

### P3: 状态优先路由 — 扩展激活时直接桥接

```
扩展已安装 + 主开关 ON + 服务代理 ON
  → 直接走桥接 (BridgeHttpExecutor → SSE → Frontend → Extension → 内网)
  → 不尝试直连, 零等待

扩展未安装 / 主开关 OFF / 服务代理 OFF
  → 直连 (DirectHttpExecutor)
  → 与 bridge.enabled=false 行为一致

桥接请求超时 / Extension 不可用
  → 明确报错（不静默失败, 不自动降级到直连）
```

---

## 3. 架构概览

### 3.1 整体拓扑

```
┌─────────────────────────────────────────────────────────────────────┐
│  SnapAgent Server (容器/K8s)                                         │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │ IssueClosureService  (不变)                                │     │
│  │   └── IssueTracker  (不变, 通过 BeanPostProcessor 透明包装) │     │
│  │         └── AbstractHttpIssueTracker                      │     │
│  │               └── HttpExecutor  ← 新抽象                   │     │
│  │                     ├── DirectHttpExecutor (默认, 原逻辑)    │     │
│  │                     └── BridgeHttpExecutor (桥接模式)       │     │
│  └───────────────────────────┬─────────────────────────────┘     │
│                              │ SSE push                           │
│  ┌───────────────────────────▼─────────────────────────────┐     │
│  │ BridgeController (新增)                                    │     │
│  │   GET  /bridge/stream   → SSE 通道                        │     │
│  │   POST /bridge/result   → 接收前端回传                    │     │
│  │   GET  /bridge/status   → 桥接状态查询                    │     │
│  └───────────────────────────┬─────────────────────────────┘     │
│                              │                                     │
└──────────────────────────────┼─────────────────────────────────────┘
                               │
┌──────────────────────────────┼─────────────────────────────────────┐
│  Browser                      │                                     │
│  ┌────────────────────────────▼──────────────────────────────┐    │
│  │ SnapAgent Frontend (index.html)                           │    │
│  │   ┌─ bridge-client.js (条件加载)                          │    │
│  │   │    EventSource('/bridge/stream')                       │    │
│  │   │    → dispatch to Extension / direct fetch              │    │
│  │   │    → POST /bridge/result                               │    │
│  │   └───────────────────────────────────────────────────────┘    │
│  ┌───────────────────────────────────────────────────────────┐    │
│  │ Chrome Extension (可选, 独立安装)                         │    │
│  │   background.js: fetch(url) 无 CORS 限制                   │    │
│  │   content.js: 与 SnapAgent 页面通信                        │    │
│  │   chrome.storage: 存储目标域名白名单                       │    │
│  └───────────────────────────┬───────────────────────────────┘    │
│                              │                                     │
│  ┌───────────────────────────▼───────────────────────────────┐    │
│  │ 外部 Issue 系统 (内网)                                     │    │
│  │   禅道 / GitLab / Jira                                     │    │
│  └───────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 模块边界

| 模块 | 位置 | 职责 | 影响范围 |
|------|------|------|----------|
| `HttpExecutor` 接口 | `snap-agent-core` | HTTP 执行抽象 (通用, 非仅 IssueTracker) | 新增文件 |
| `DirectHttpExecutor` | `snap-agent-spring-boot-2x-starter` | 封装现有 `jsonRequest` 逻辑 | 提取自 `AbstractHttpIssueTracker` / `AbstractHttpVcsClient` |
| `BridgeHttpExecutor` | `snap-agent-spring-boot-2x-starter` | SSE 桥接执行器 (带 `serviceType` 标签) | 新增文件 |
| `IssueBridgeService` | `snap-agent-spring-boot-2x-starter` | 桥接请求管理 + SSE 推送 + URL 白名单 | 新增文件 |
| `BridgeController` | `snap-agent-spring-boot-2x-starter` | REST 端点 | 新增文件 |
| `BridgeAutoConfiguration` | `snap-agent-spring-boot-2x-starter` | 条件装配 (多服务) | 新增文件 |
| `BridgeProperties` | `snap-agent-spring-boot-2x-starter` | 配置属性 | 新增文件 |
| `bridge-client.js` | `snap-agent-demo` (静态资源) | 前端桥接客户端 + 指示灯 | 新增文件 |
| Chrome Extension | `snap-agent-bridge-extension/` | CORS-free 代理 + 服务开关 + 主开关 | 独立项目 |

### 3.3 多服务代理模型

每个 `BridgeHttpExecutor` 实例绑定一个 `serviceType` 字符串, 标识它代理的是哪类服务:

| serviceType | 绑定到 | 代理对象 | v1 支持 |
|-------------|--------|----------|---------|
| `issue-tracker` | `AbstractHttpIssueTracker` 子类 | 禅道/Jira/GitHub Issues | ✅ |
| `vcs` | `AbstractHttpVcsClient` 子类 | GitLab/Bitbucket | ✅ |
| `llm` | `AbstractStreamingLlmClient` 子类 | LLM 网关 (流式) | v2 (流式 chunked relay) |
| `plugin-{name}` | 插件自定义 `HttpExecutor` | 用户上传的 plugin | ✅ (插件自选接入) |

**SSE `proxy-request` 事件增加 `serviceType` 字段**:

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

Chrome Extension 根据 `serviceType` + 自身配置决定是否代理该请求。

---

## 4. 核心设计

### 4.1 HttpExecutor 抽象

**问题**: `AbstractHttpIssueTracker.jsonRequest()` 将 URL、method、headers、body 内聚在方法内部，外部无法拦截 HTTP 请求。

**方案**: 将 HTTP 执行逻辑提取为可注入的 `HttpExecutor` 接口。

```java
// snap-agent-core: 新增接口
public interface HttpExecutor {
    /**
     * 执行 HTTP 请求并返回解析后的 JSON 响应。
     * @throws TrackerException 连接失败或非 2xx 响应
     */
    HttpResponse execute(String url, String method,
                         Map<String, String> headers, Object body);
}
```

```java
// snap-agent-spring-boot-2x-starter: 默认实现
public class DirectHttpExecutor implements HttpExecutor {
    @Override
    public HttpResponse execute(String url, String method,
                                Map<String, String> headers, Object body) {
        // 原 AbstractHttpIssueTracker.jsonRequest() 的全部逻辑搬过来
        // HttpURLConnection + Jackson ObjectMapper
    }
}
```

**对 `AbstractHttpIssueTracker` 的改动**（唯一一处修改现有代码）:

```java
abstract class AbstractHttpIssueTracker {

    // 新增: HTTP 执行器, 默认使用直连
    protected HttpExecutor httpExecutor = new DirectHttpExecutor();

    protected JsonNode jsonRequest(String urlStr, String method,
                                   Map<String, String> headers, Object body) {
        // 原有的 URL/headers/body 逻辑不变
        // 将实际的 HTTP 调用委托给 httpExecutor
        HttpResponse resp = httpExecutor.execute(urlStr, method, headers, body);
        // 原有的错误处理 + JSON 解析逻辑不变
        if (resp.getStatusCode() >= 400) {
            throw new TrackerException(type(), "HTTP " + resp.getStatusCode() + ...);
        }
        return resp.getJsonBody();
    }
}
```

**非侵入性保证**:

| 状态 | `httpExecutor` 值 | 行为 |
|------|-------------------|------|
| `bridge.enabled=false` (默认) | `new DirectHttpExecutor()` (字段初始化) | 与现有代码**逐字节等价** |
| `bridge.enabled=true` + 扩展未激活 | `BridgeHttpExecutor` (BeanPostProcessor 注入), 但内部走直连 | `BridgeHttpExecutor` 检测到扩展未激活, 委托 `DirectHttpExecutor` |
| `bridge.enabled=true` + 扩展已激活 | `BridgeHttpExecutor` (BeanPostProcessor 注入) | `BridgeHttpExecutor` 直接走 SSE 桥接, 不尝试直连 |

**BeanPostProcessor 注入** (仅 `bridge.enabled=true` 时存在):

```java
@ConditionalOnProperty(prefix = "snap-agent.bridge", name = "enabled", havingValue = "true")
public class BridgeHttpExecutorPostProcessor implements BeanPostProcessor {

    private final BridgeHttpExecutor issueTrackerBridge;
    private final BridgeHttpExecutor vcsBridge;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractHttpIssueTracker) {
            ((AbstractHttpIssueTracker) bean).httpExecutor = issueTrackerBridge;
        } else if (bean instanceof AbstractHttpVcsClient) {
            ((AbstractHttpVcsClient) bean).httpExecutor = vcsBridge;
        }
        // NoopIssueTracker 不包装 (它不做 HTTP 调用)
        return bean;
    }
}
```

**BridgeHttpExecutor 实例化** (每个服务一个):

```java
@Bean
public BridgeHttpExecutor issueTrackerBridgeExecutor(IssueBridgeService bridgeService) {
    return new BridgeHttpExecutor(new DirectHttpExecutor(), bridgeService, "issue-tracker");
}

@Bean
public BridgeHttpExecutor vcsBridgeExecutor(IssueBridgeService bridgeService) {
    return new BridgeHttpExecutor(new DirectHttpExecutor(), bridgeService, "vcs");
}
```

### 4.2 BridgeHttpExecutor — 状态优先桥接执行器

```java
public class BridgeHttpExecutor implements HttpExecutor {

    private final HttpExecutor directExecutor;  // DirectHttpExecutor, 扩展未激活时使用
    private final IssueBridgeService bridgeService;
    private final String serviceType;  // "issue-tracker" / "vcs" / "llm" / "plugin-xxx"

    @Override
    public HttpResponse execute(String url, String method,
                                Map<String, String> headers, Object body) {
        // 1. 检查桥接状态: 扩展是否已安装且该服务代理已开启?
        if (bridgeService.isBridgeActive(serviceType)) {
            // 扩展已激活 → 直接走桥接, 不尝试直连
            log.debug("Bridge active for [{}], proxying {} [{}]",
                      serviceType, method, url);
            return bridgeService.proxyRequest(serviceType, url, method, headers, body);
        }

        // 2. 扩展未激活 → 直连 (与 bridge.enabled=false 行为一致)
        return directExecutor.execute(url, method, headers, body);
    }
}
```

**关键设计**:
- `IssueBridgeService.isBridgeActive(serviceType)` 是原子读取, 返回扩展是否已连接且该服务代理已开启
- 扩展激活时不尝试直连, 避免对不可达地址的 3 秒等待
- 扩展未激活时走 `DirectHttpExecutor`, 行为与 `bridge.enabled=false` 一致
- 桥接请求超时或 Extension 返回错误 → 直接抛 `TrackerException`, **不自动降级到直连**
  (因为用户已经明确选择了桥接模式, 降级会造成困惑)

### 4.3 IssueBridgeService — 桥接请求管理 + 状态追踪

```java
public class IssueBridgeService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<HttpResponse>> pending = new ConcurrentHashMap<>();
    private final long requestTimeoutMs;

    // 扩展状态: 由前端通过 POST /bridge/status-update 上报
    private volatile BridgeClientStatus clientStatus = BridgeClientStatus.inactive();

    /** 前端上报扩展状态 (扩展安装/主开关/服务开关变化时调用) */
    public void updateClientStatus(BridgeClientStatus status) {
        this.clientStatus = status;
        log.info("Bridge client status updated: installed={}, masterEnabled={}, services={}",
                 status.isInstalled(), status.isMasterEnabled(), status.getServices());
    }

    /** 检查指定服务的桥接是否已激活 */
    public boolean isBridgeActive(String serviceType) {
        BridgeClientStatus status = this.clientStatus;
        return status.isInstalled()
            && status.isMasterEnabled()
            && status.getServices().getOrDefault(serviceType, false);
    }

    /** SSE 注册 — 前端连接后注册 emitter */
    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(0L);  // 无超时
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    /** 发送代理请求到所有连接的前端, 等待第一个返回结果 */
    public HttpResponse proxyRequest(String serviceType, String url, String method,
                                     Map<String, String> headers, Object body) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        pending.put(requestId, future);

        // 构造 SSE 事件 (含 serviceType, 扩展按此决定是否代理)
        BridgeRequest req = new BridgeRequest(requestId, serviceType, url, method, headers, body);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .id(requestId)
                    .name("proxy-request")
                    .data(objectMapper.writeValueAsString(req)));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }

        if (emitters.isEmpty()) {
            throw new TrackerException("bridge",
                "No bridge client connected; cannot proxy request to " + url);
        }

        try {
            return future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TrackerException("bridge",
                "Bridge request timed out after " + requestTimeoutMs + "ms for " + url);
        } catch (InterruptedException | ExecutionException e) {
            throw new TrackerException("bridge",
                "Bridge request failed for " + url + ": " + e.getMessage(), e);
        } finally {
            pending.remove(requestId);
        }
    }

    /** 接收前端回传的结果 */
    public void handleResult(BridgeResponse response) {
        CompletableFuture<HttpResponse> future = pending.get(response.getId());
        if (future == null) {
            log.warn("Received bridge result for unknown request: {}", response.getId());
            return;
        }
        if (response.getError() != null) {
            future.completeExceptionally(
                new TrackerException("bridge", response.getError()));
        } else {
            future.complete(new HttpResponse(
                response.getStatus(),
                response.getBody(),
                response.getHeaders()
            ));
        }
    }

    /** 桥接状态查询 */
    public BridgeStatus getStatus() {
        BridgeClientStatus cs = this.clientStatus;
        return new BridgeStatus(
            !emitters.isEmpty(),
            emitters.size(),
            pending.size(),
            cs.isInstalled(),
            cs.isMasterEnabled(),
            cs.getServices()
        );
    }
}
```

**BridgeClientStatus DTO**:

```java
public class BridgeClientStatus {
    private boolean installed;       // 扩展是否已安装
    private boolean masterEnabled;   // 主开关是否开启
    private Map<String, Boolean> services;  // 服务开关: {"issue-tracker": true, "vcs": false, ...}

    public static BridgeClientStatus inactive() {
        return new BridgeClientStatus(false, false, Collections.emptyMap());
    }
}
```

### 4.4 SSE 通信协议

#### 前端 → 服务端: 扩展状态上报 (POST `/bridge/status-update`)

当扩展安装状态、主开关或服务开关发生变化时, 前端立即上报:

```json
{
  "installed": true,
  "masterEnabled": true,
  "services": {
    "issue-tracker": true,
    "vcs": true,
    "llm": false
  }
}
```

服务端 `IssueBridgeService.updateClientStatus()` 更新内存状态, 后续 `BridgeHttpExecutor.isBridgeActive()` 即刻生效。

前端还通过 SSE 连接的存活状态隐式上报 `installed=false`:

```javascript
// bridge-client.js
eventSource.onopen = () => {
  // SSE 连接成功 → 上报扩展状态
  postStatus({ installed: this.extensionReady, masterEnabled: ..., services: ... });
};
eventSource.onerror = () => {
  // SSE 断开 → 服务端 emitter 自动移除, clientStatus 保持上次值
  // 前端重连后重新上报
};
```

#### Server → Client (SSE event: `proxy-request`)

```json
{
  "id": "req-a1b2c3d4",
  "serviceType": "issue-tracker",
  "url": "http://zentao.sfcloud.local/api.php/v1/products/1/bugs",
  "method": "POST",
  "headers": {
    "Authorization": "Token test-pat-token",
    "Content-Type": "application/json"
  },
  "body": {
    "title": "Bug title...",
    "desc": "Description...",
    "severity": 3,
    "pri": 3,
    "type": "codeerror"
  }
}
```

#### Client → Server (POST `/bridge/result`)

**成功响应**:
```json
{
  "id": "req-a1b2c3d4",
  "status": 201,
  "headers": {
    "Content-Type": ["application/json"]
  },
  "body": "{\"id\": 42}"
}
```

**错误响应**:
```json
{
  "id": "req-a1b2c3d4",
  "status": 0,
  "error": "NetworkError: Failed to fetch http://zentao.sfcloud.local/..."
}
```

### 4.5 前端桥接客户端 (bridge-client.js)

```javascript
class SnapAgentBridge {
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.extensionId = 'snapagent-bridge-extension';
    this.connected = false;
    this.extensionReady = false;
    this.extensionConfig = null;  // 从 extension 获取的主开关 + 服务开关
  }

  connect() {
    // 1. 监听 extension ready 信号
    window.addEventListener('message', (event) => {
      if (event.data.type === 'SNAP_AGENT_BRIDGE_READY') {
        this.extensionReady = true;
        this.queryExtensionConfig();
      }
      if (event.data.type === 'SNAP_AGENT_BRIDGE_CONFIG_CHANGED') {
        this.extensionConfig = event.data.payload;
        this.reportStatus();
      }
    });

    // 2. 连接 SSE
    this.eventSource = new EventSource(this.baseUrl + '/bridge/stream');
    this.eventSource.addEventListener('proxy-request', (event) => {
      this.handleProxyRequest(JSON.parse(event.data));
    });
    this.eventSource.onopen = () => {
      this.connected = true;
      this.reportStatus();
    };
    this.eventSource.onerror = () => {
      this.connected = false;
    };
  }

  // 向服务端上报扩展状态
  async reportStatus() {
    const status = {
      installed: this.extensionReady,
      masterEnabled: this.extensionConfig?.masterEnabled ?? false,
      services: this.extensionConfig?.services ?? {}
    };
    await fetch(this.baseUrl + '/bridge/status-update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(status)
    });
  }

  // 向 extension 查询当前配置
  queryExtensionConfig() {
    window.postMessage({ type: 'SNAP_AGENT_BRIDGE_QUERY_CONFIG' }, '*');
  }

  async handleProxyRequest(req) {
    try {
      const response = await this.dispatch(req);
      await this.postResult(req.id, response);
    } catch (err) {
      await this.postResult(req.id, {
        status: 0,
        error: err.message
      });
    }
  }

  async dispatch(req) {
    // 1. 尝试 Chrome Extension (CORS-free, 按 serviceType 路由)
    if (this.hasExtension()) {
      return await this.viaExtension(req);
    }
    // 2. 尝试直接 fetch (需要目标服务 CORS)
    try {
      return await this.viaDirectFetch(req);
    } catch (e) {
      throw new Error(`Bridge dispatch failed: ${e.message}. ` +
        'Install SnapAgent Bridge Extension for CORS-free access.');
    }
  }

  hasExtension() {
    return this.extensionReady && typeof chrome !== 'undefined' && chrome.runtime;
  }

  viaExtension(req) {
    return new Promise((resolve, reject) => {
      chrome.runtime.sendMessage(
        this.extensionId,
        { type: 'PROXY_REQUEST', ...req },
        (response) => {
          if (chrome.runtime.lastError) {
            reject(new Error(chrome.runtime.lastError.message));
          } else if (response && response.error) {
            reject(new Error(response.error));
          } else {
            resolve(response);
          }
        }
      );
    });
  }

  async viaDirectFetch(req) {
    const resp = await fetch(req.url, {
      method: req.method,
      headers: req.headers,
      body: req.body ? JSON.stringify(req.body) : undefined
    });
    const text = await resp.text();
    return { status: resp.status, body: text, headers: {} };
  }

  async postResult(id, result) {
    await fetch(this.baseUrl + '/bridge/result', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, ...result })
    });
  }
}

// 自动初始化 (仅在 bridge.enabled 时由后端注入标志)
if (window.snapAgentBridgeEnabled) {
  const bridge = new SnapAgentBridge(window.snapAgentBasePath);
  bridge.connect();
}
```

**条件加载机制**:

`SnapAgentController.getInfo()` 在 `/snap-agent/info` 响应中增加 `bridgeEnabled` 字段。`index.html` 检查此字段, 仅在 `true` 时动态加载 `bridge-client.js`:

```javascript
// index.html (在现有 init 流程中)
if (info.bridgeEnabled) {
  const script = document.createElement('script');
  script.src = basePath + '/static/bridge-client.js';
  script.onload = () => { window.snapAgentBridge = new SnapAgentBridge(basePath); window.snapAgentBridge.connect(); };
  document.head.appendChild(script);
}
```

### 4.6 Chrome Extension

```
snap-agent-bridge-extension/
├── manifest.json
├── background.js           # Service Worker, 处理 PROXY_REQUEST (含 serviceType 路由)
├── content.js              # 注入 SnapAgent 页面, 与 background 通信, 更新指示灯
├── popup.html              # 设置页 (主开关 + 服务开关 + 状态)
├── popup.js
└── icons/                  # 16/48/128 px
```

**manifest.json**:
```json
{
  "manifest_version": 3,
  "name": "SnapAgent Bridge",
  "version": "1.0.0",
  "description": "Network bridge for SnapAgent — proxy internal services through your browser",
  "permissions": ["storage", "activeTab", "scripting"],
  "host_permissions": ["<all_urls>"],
  "background": { "service_worker": "background.js" },
  "content_scripts": [{
    "matches": ["*://*/*/snap-agent/*", "*://*/*/snap-agent"],
    "js": ["content.js"],
    "css": ["bridge-indicator.css"]
  }],
  "action": { "default_popup": "popup.html" }
}
```

**popup.html — 扩展控制面板**:

```
┌─────────────────────────────────────┐
│  SnapAgent Bridge              ●ON  │  ← 主开关
├─────────────────────────────────────┤
│  代理状态: 已连接 (2 pending)        │
├─────────────────────────────────────┤
│  服务代理开关:                       │
│  ☑ Issue Tracker (禅道/Jira/GitHub) │
│  ☑ VCS (GitLab/Bitbucket)           │
│  ☐ LLM (实验性)                      │
│  ☑ plugin-daily-tips                │  ← 动态, 来自已注册插件
│  ☐ plugin-announcement              │
├─────────────────────────────────────┤
│  白名单域名:                         │
│  *.sfcloud.local       ✅            │
│  zentao.sf-express.com ✅            │
│  [+ 添加域名]                        │
└─────────────────────────────────────┘
```

**popup.js — 扩展配置存储**:

```javascript
// 默认配置
const DEFAULT_CONFIG = {
  masterEnabled: true,        // 主开关
  services: {                 // 按服务开关
    'issue-tracker': true,
    'vcs': true,
    'llm': false,             // v2: 流式代理
    // plugin-{name}: true    // 动态注册
  },
  allowedHosts: [],           // 域名白名单 (用户可编辑)
};

// 保存到 chrome.storage.local
chrome.storage.local.set({ bridgeConfig: DEFAULT_CONFIG });
```

**background.js — 带服务路由的代理**:

```javascript
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type !== 'PROXY_REQUEST') return;

  const { id, serviceType, url, method, headers, body } = msg;

  chrome.storage.local.get('bridgeConfig', (data) => {
    const config = data.bridgeConfig || {};

    // 1. 主开关检查
    if (!config.masterEnabled) {
      sendResponse({ id, status: 0, error: 'Bridge is disabled (master switch off)' });
      return;
    }

    // 2. 服务开关检查
    if (config.services && config.services[serviceType] === false) {
      sendResponse({ id, status: 0, error: `Service '${serviceType}' proxy is disabled` });
      return;
    }

    // 3. 域名白名单检查 (在 extension 侧也做一次)
    const host = new URL(url).hostname;
    const allowed = (config.allowedHosts || []).some(pattern => matchHost(host, pattern));
    if (!allowed) {
      sendResponse({ id, status: 0, error: `Host '${host}' not in extension allowlist` });
      return;
    }

    // 4. 执行代理
    fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'follow' })
      .then(async (resp) => {
        const text = await resp.text();
        const respHeaders = {};
        resp.headers.forEach((value, key) => {
          respHeaders[key] = respHeaders[key] || [];
          respHeaders[key].push(value);
        });
        sendResponse({ id, status: resp.status, body: text, headers: respHeaders });
      })
      .catch((err) => sendResponse({ id, status: 0, error: err.message }));
  });

  return true; // async response
});
```

**content.js — 页面指示灯 + 消息中继**:

```javascript
// 1. 注入指示灯 DOM
const indicator = document.createElement('div');
indicator.id = 'snapagent-bridge-indicator';
indicator.innerHTML = `
  <div class="bridge-led bridge-led-off" title="代理未连接"></div>
  <span class="bridge-label">代理</span>
`;
indicator.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:99999;display:flex;align-items:center;gap:6px;font:12px/1 system-ui;background:#fff;padding:6px 12px;border-radius:20px;box-shadow:0 2px 8px rgba(0,0,0,.15)';
document.body.appendChild(indicator);

// 2. 连接状态管理
function setBridgeStatus(status) {
  const led = indicator.querySelector('.bridge-led');
  const label = indicator.querySelector('.bridge-label');
  switch(status) {
    case 'connected':
      led.className = 'bridge-led bridge-led-on';
      led.style.cssText = 'width:10px;height:10px;border-radius:50%;background:#22c55e;box-shadow:0 0 6px #22c55e';
      label.textContent = '代理已连接';
      break;
    case 'disconnected':
      led.className = 'bridge-led bridge-led-off';
      led.style.cssText = 'width:10px;height:10px;border-radius:50%;background:#ccc';
      label.textContent = '代理未连接';
      break;
    case 'error':
      led.className = 'bridge-led bridge-led-error';
      led.style.cssText = 'width:10px;height:10px;border-radius:50%;background:#ef4444';
      label.textContent = '代理异常';
      break;
  }
}

// 3. 通知页面 extension 已安装 + 当前配置
function notifyConfigChanged() {
  chrome.storage.local.get('bridgeConfig', (data) => {
    const config = data.bridgeConfig || {};
    window.postMessage({
      type: 'SNAP_AGENT_BRIDGE_CONFIG_CHANGED',
      payload: config
    }, '*');
  });
}

window.postMessage({ type: 'SNAP_AGENT_BRIDGE_READY' }, '*');
setBridgeStatus('connected');
notifyConfigChanged();  // 首次通知配置

// 4. 配置变化时重新通知
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.bridgeConfig) {
    notifyConfigChanged();
  }
});

// 5. 监听来自页面的配置查询, 转发给 background
window.addEventListener('message', (event) => {
  if (event.source !== window) return;
  if (event.data.type === 'SNAP_AGENT_BRIDGE_QUERY_CONFIG') {
    notifyConfigChanged();
    return;
  }
  if (event.data.type !== 'SNAP_AGENT_BRIDGE_REQUEST') return;
  chrome.runtime.sendMessage(event.data.payload, (response) => {
    window.postMessage({
      type: 'SNAP_AGENT_BRIDGE_RESPONSE',
      payload: response
    }, '*');
  });
});
```

### 4.7 SnapAgent 页面集成

**index.html — 扩展下载入口**:

当 `bridgeEnabled=true` 且检测到 extension 未安装时, 在页面右上角显示下载提示:

```javascript
// 在 SnapAgent 页面 init 流程中
if (info.bridgeEnabled) {
  // 等待 extension ready 信号 (3s 超时)
  const extensionReady = await waitForExtensionReady(3000);
  if (!extensionReady) {
    showDownloadBanner();
  }
  // 动态加载 bridge-client.js
  loadScript(basePath + '/static/bridge-client.js');
}

function showDownloadBanner() {
  const banner = document.createElement('div');
  banner.innerHTML = `
    <div style="position:fixed;top:10px;right:10px;z-index:9999;background:#fef3c7;border:1px solid #f59e0b;border-radius:8px;padding:12px 16px;font:13px/1.5 system-ui;max-width:320px">
      <b>🔌 网络桥接</b><br>
      检测到部分服务无法直达, 安装 SnapAgent Bridge 扩展以启用浏览器代理。<br>
      <a href="${basePath}/static/snap-agent-bridge-extension.zip" download>下载扩展</a>
      <span style="margin-left:8px;color:#6b7280;font-size:11px">Chrome / Edge</span>
    </div>
  `;
  document.body.appendChild(banner);
}
```

**bridge-client.js — 指示灯联动**:

```javascript
class SnapAgentBridge {
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.connected = false;
    this.extensionReady = false;
  }

  connect() {
    // 监听 extension ready 信号
    window.addEventListener('message', (event) => {
      if (event.data.type === 'SNAP_AGENT_BRIDGE_READY') {
        this.extensionReady = true;
        this.updateIndicator('connected');
      }
    });

    // 连接 SSE
    this.eventSource = new EventSource(this.baseUrl + '/bridge/stream');
    this.eventSource.addEventListener('proxy-request', (event) => {
      this.handleProxyRequest(JSON.parse(event.data));
    });
    this.eventSource.onopen = () => {
      this.connected = true;
      this.updateIndicator('connected');
    };
    this.eventSource.onerror = () => {
      this.connected = false;
      this.updateIndicator('disconnected');
    };
  }

  updateIndicator(status) {
    // 通过 postMessage 通知 content.js 更新指示灯
    // content.js 自行管理 DOM, 这里只做状态广播
    window.postMessage({ type: 'SNAP_AGENT_BRIDGE_STATUS', status }, '*');
  }

  // ... handleProxyRequest / dispatch / postResult (同 v1.0 设计)
}
```

### 4.7 配置属性

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

**非侵入性验证**:

| 配置 | `bridge.enabled` | 效果 |
|------|-----------------|------|
| 不配置 `bridge` 段 | (缺失=false) | 全部使用 `DirectHttpExecutor`, 行为不变 |
| `bridge.enabled: false` | false | 全部使用 `DirectHttpExecutor`, 行为不变 |
| `bridge.enabled: true` + 扩展未激活 | true | `BridgeHttpExecutor` 内部走直连, 行为与 false 一致 |
| `bridge.enabled: true` + 扩展已激活 | true | `BridgeHttpExecutor` 直接走 SSE 桥接 |

---

## 5. 数据流详解

### 5.1 直连模式 (bridge.enabled=false)

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
  → IssueClosure 状态更新为 FIX_IN_PROGRESS
  → 返回给前端
```

**与现有流程完全一致, 无任何额外步骤。**

### 5.2 桥接模式 (bridge.enabled=true, 扩展已激活)

```
前提: 扩展已安装 + 主开关 ON + issue-tracker 代理 ON
      前端已通过 POST /bridge/status-update 上报状态

用户点击"创建外部 Issue"
  → Frontend: POST /snap-agent/runs/{taskId}/issue
  → SnapAgentController.createIssue()
  → IssueClosureService.createExternalIssue()
  → ZentaoIssueTracker.createIssue()
  → AbstractHttpIssueTracker.jsonRequest()
  → BridgeHttpExecutor.execute()
      → bridgeService.isBridgeActive("issue-tracker") → true
      → bridgeService.proxyRequest()     ← 直接走桥接, 不尝试直连
      →    SSE 推送给已连接的前端
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
  → AbstractHttpIssueTracker 解析 JSON
  → ZentaoIssueTracker 返回 "42"
  → IssueClosure 状态更新
  → 返回给前端
```

### 5.3 桥接模式 (bridge.enabled=true, 扩展未激活)

```
前提: bridge.enabled=true 但扩展未安装或主开关 OFF 或 issue-tracker 代理 OFF

ZentaoIssueTracker.createIssue()
  → BridgeHttpExecutor.execute()
      → bridgeService.isBridgeActive("issue-tracker") → false
      → directExecutor.execute()        ← 直连, 与 bridge.enabled=false 一致
      → HttpURLConnection → 禅道 API
      → 返回 {"id": 42} 或 ConnectException
```

**扩展未激活时, BridgeHttpExecutor 退化为 DirectHttpExecutor, 行为与无桥接一致。**

---

## 6. REST API 设计

### 新增端点

| 方法 | 路径 | 描述 | 认证 |
|------|------|------|------|
| GET | `/snap-agent/bridge/stream` | SSE 通道, 服务端推送代理请求 | SnapAgent 用户认证 |
| POST | `/snap-agent/bridge/result` | 前端回传代理结果 | SnapAgent 用户认证 |
| GET | `/snap-agent/bridge/status` | 桥接状态 (连接数、pending 数、扩展状态) | SnapAgent 用户认证 |
| POST | `/snap-agent/bridge/status-update` | 前端上报扩展状态 (安装、主开关、服务开关) | SnapAgent 用户认证 |

### 现有端点变更

**`GET /snap-agent/info`** 响应新增字段:

```json
{
  "issueClosureEnabled": true,
  "bridgeEnabled": false    // 新增
}
```

**其余端点不变。**

---

## 7. 安全设计

### 7.1 URL 白名单

`IssueBridgeService.proxyRequest()` 在发送 SSE 事件前校验目标 URL:

```java
private void validateUrl(String url) {
    String host = URI.create(url).getHost();
    for (String pattern : allowedHostPatterns) {
        if (matchHost(host, pattern)) {
            return; // 允许
        }
    }
    throw new TrackerException("bridge",
        "URL not allowed by bridge host patterns: " + url);
}
```

### 7.2 认证传递

**方案 A (推荐 v1)**: 服务端在 SSE 事件中包含认证头 (`Authorization: Token xxx`)。

- SSE 通道本身需要 SnapAgent 用户认证
- Token 仅在已认证的 SSE 通道中传输, 不暴露在页面 DOM 中
- Chrome Extension 的 `background.js` 在 Service Worker 上下文中执行 `fetch()`, 页面 JS 无法读取
- 适用于: 内网 Issue 系统的 Token 与 SnapAgent 用户权限相当的场景

**方案 B (v2 增强)**: Extension 独立持有 Token, 服务端不下发。

- Extension 的 `popup.js` 提供 Token 配置 UI, 存储在 `chrome.storage.local`
- SSE 事件不含 `Authorization` 头
- Extension 在 `fetch()` 时自动添加 Token
- 适用于: Token 权限高于 SnapAgent 用户的场景

### 7.3 SSE 连接认证

SSE 端点 (`GET /bridge/stream`) 复用 SnapAgent 现有安全框架 (`SecurityGateway`), 与其他 API 端点一致:
- Basic Auth / Bearer Token / Cookie 认证
- 权限校验 (`required-permission`)
- 审计日志

### 7.4 请求超时

- 桥接请求: 30 秒 (配置: `request-timeout-ms`)
- SSE 连接: 无超时 (保持长连接, 由客户端/网络断连自动清理)
- 扩展未激活时走直连, 使用 `DirectHttpExecutor` 默认超时 (与现有行为一致)

---

## 8. 路由策略

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

---

## 9. 部署场景

### 场景 A: 宿主集群直连可达 (不使用桥接)

```yaml
# application.yml — 无 bridge 配置
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.internal.com
      token: xxx
      product-id: 1
```

**行为**: `DirectHttpExecutor` 直接调用禅道 API, 与 v0.9 行为完全一致。
**零额外步骤, 零代码变更。**

### 场景 B: 容器网络隔离, 使用桥接

```yaml
# application.yml — 开启桥接
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
    direct-connect-timeout-ms: 3000
    allowed-host-patterns:
      - "*.sfcloud.local"
```

**行为**:
1. 用户打开 SnapAgent UI, `index.html` 检测 `bridgeEnabled=true`
2. 自动加载 `bridge-client.js`, 建立 SSE 连接
3. 前端检测到 Chrome Extension 已安装且主开关开且 issue-tracker 代理开
4. 前端 POST `/bridge/status-update` 上报扩展状态
5. 用户点击"创建外部 Issue"
6. 服务端 `BridgeHttpExecutor` 检测 `isBridgeActive("issue-tracker")=true` → 直接走 SSE 桥接
7. 前端通过 Chrome Extension 调用禅道 API
8. 结果回传, Issue 创建完成

**额外步骤**: 安装 Chrome Extension, 在扩展中开启主开关和 issue-tracker 代理。

### 场景 C: 容器直连可达, 但桥接已开启 (扩展未激活)

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.reachable.com  # 容器可直达
      token: xxx
  bridge:
    enabled: true  # 开了桥接, 但扩展未激活
```

**行为**: `BridgeHttpExecutor` 检测 `isBridgeActive("issue-tracker")=false` → 走 `DirectHttpExecutor` 直连。
**与 `bridge.enabled=false` 行为一致, 零额外开销。**

### 场景 D: 容器直连可达, 桥接已开启, 扩展已激活

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.reachable.com  # 容器可直达
      token: xxx
  bridge:
    enabled: true
```

**行为**: `BridgeHttpExecutor` 检测 `isBridgeActive("issue-tracker")=true` → 直接走 SSE 桥接, 通过用户浏览器代理。
**用户明确选择了代理模式, 即使服务端可达也走代理。**

---

## 10. 对现有代码的影响清单

### 需要修改的现有文件 (3 处)

| 文件 | 改动 | 非侵入性 |
|------|------|----------|
| `AbstractHttpIssueTracker.java` | `jsonRequest()` 委托给 `httpExecutor` 字段; `httpExecutor` 默认 `new DirectHttpExecutor()` | 当 `bridge.enabled=false` 时, `DirectHttpExecutor` 逻辑与原 `jsonRequest` 逐字节等价 |
| `AbstractHttpVcsClient.java` | 同上 — `jsonRequest()` 委托给 `httpExecutor` 字段 | 同上 |
| `SnapAgentController.java` | `getInfo()` 响应增加 `bridgeEnabled` 字段 | 仅新增一个 JSON 字段, 不影响现有字段 |

### 不需要修改的现有文件

| 文件 | 原因 |
|------|------|
| `IssueTracker.java` (SPI) | 接口不变 |
| `ZentaoIssueTracker.java` | 继承 `AbstractHttpIssueTracker`, `jsonRequest()` 调用不变 |
| `GitHubIssueTracker.java` | 同上 |
| `JiraIssueTracker.java` | 同上 |
| `GitLabVcsClient.java` | 继承 `AbstractHttpVcsClient`, `jsonRequest()` 调用不变 |
| `BitbucketVcsClient.java` | 同上 |
| `AbstractStreamingLlmClient.java` | 使用 OkHttp 流式, v2 实现 chunked relay 后再接入 |
| `IssueClosureService.java` | 注入 `IssueTracker` 不变, BeanPostProcessor 透明包装 |
| `IssueAutoConfiguration.java` | 现有 Bean 不变, 桥接 Bean 在新 `BridgeAutoConfiguration` |
| `SnapAgentProperties.java` | 新增 `Bridge` 内部类, 不影响现有属性 |
| 宿主 `application.yml` | 不使用桥接时无需添加任何配置 |

### 新增文件

| 文件 | 模块 |
|------|------|
| `HttpExecutor.java` | snap-agent-core |
| `HttpResponse.java` | snap-agent-core |
| `DirectHttpExecutor.java` | snap-agent-spring-boot-2x-starter |
| `BridgeHttpExecutor.java` | snap-agent-spring-boot-2x-starter |
| `IssueBridgeService.java` | snap-agent-spring-boot-2x-starter |
| `BridgeController.java` | snap-agent-spring-boot-2x-starter |
| `BridgeAutoConfiguration.java` | snap-agent-spring-boot-2x-starter |
| `BridgeProperties.java` | snap-agent-spring-boot-2x-starter |
| `BridgeHttpExecutorPostProcessor.java` | snap-agent-spring-boot-2x-starter |
| `BridgeRequest.java` | snap-agent-spring-boot-2x-starter (DTO, 含 `serviceType`) |
| `BridgeResponse.java` | snap-agent-spring-boot-2x-starter (DTO) |
| `BridgeStatus.java` | snap-agent-spring-boot-2x-starter (DTO, 含服务列表) |
| `BridgeClientStatus.java` | snap-agent-spring-boot-2x-starter (DTO, 扩展状态) |
| `bridge-client.js` | snap-agent-demo (静态资源, 含指示灯联动) |
| `manifest.json` | snap-agent-bridge-extension/ |
| `background.js` | snap-agent-bridge-extension/ (含 serviceType 路由) |
| `content.js` | snap-agent-bridge-extension/ (含指示灯 DOM) |
| `bridge-indicator.css` | snap-agent-bridge-extension/ |
| `popup.html` | snap-agent-bridge-extension/ (含主开关 + 服务开关) |
| `popup.js` | snap-agent-bridge-extension/ |

---

## 11. 测试策略

### 11.1 单元测试

- `DirectHttpExecutorTest`: 验证与原 `jsonRequest` 行为等价
- `BridgeHttpExecutorTest`: 扩展激活时走桥接、扩展未激活时走直连、桥接超时报错不降级
- `IssueBridgeServiceTest`: SSE 推送、结果回传、超时、无客户端报错、状态上报、isBridgeActive 判断
- `BridgeHttpExecutorPostProcessorTest`: 仅包装 `AbstractHttpIssueTracker`、不包装 `NoopIssueTracker`

### 11.2 集成测试

- `BridgeIntegrationTest`: 扩展激活 → SSE 桥接 → 前端回传 → Issue 创建成功
- `BridgeDirectFallbackTest`: 桥接开启但扩展未激活 → 走直连 → 行为与无桥接一致

### 11.3 端到端测试

- 使用 Zentao Mock (Docker) + Chrome Extension + SnapAgent Demo 验证完整链路
- 验证 `bridge.enabled=false` 时与 v0.9 行为完全一致

---

## 12. 里程碑

| 阶段 | 交付物 | 依赖 |
|------|--------|------|
| Phase 1: HttpExecutor 抽象 | `HttpExecutor` + `DirectHttpExecutor` + `AbstractHttpIssueTracker` + `AbstractHttpVcsClient` 重构 | 无 |
| Phase 2: 桥接核心 | `BridgeHttpExecutor` (含 serviceType) + `IssueBridgeService` + `BridgeHttpExecutorPostProcessor` (多服务) + `BridgeController` + `BridgeAutoConfiguration` | Phase 1 |
| Phase 3: 前端 + 扩展 | `bridge-client.js` (指示灯联动) + Chrome Extension (主开关 + 服务开关 + 指示灯 + 下载入口) | Phase 2 |
| Phase 4: 测试与文档 | 单元/集成/E2E 测试 + 用户文档 | Phase 1-3 |
