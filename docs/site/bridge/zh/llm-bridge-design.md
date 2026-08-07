# LLM Bridge — 精简版架构设计

> 版本：v1.0 | 日期：2026-08-07 | 关联：[bridge-architecture.md](bridge-architecture.md)

## 1. 背景与动机

当前 SnapAgent 的 LLM 调用链路是 **服务端直连**：
```
Server → OkHttp → Anthropic/OpenAI API → SSE 流式响应 → Server 解析 → 返回前端
```

这要求 **Server 必须配置 LLM API Key**，且 Server 需能访问外部 LLM 网关。

**问题**：
1. 用户部署 standalone JAR 后，需要手动配置 `LLM_AUTH_TOKEN` / `LLM_API_KEY`
2. API Key 暴露在 Server 配置中，安全风险
3. 容器网络隔离时，Server 无法访问外部 LLM API

**浏览器插件已有 LLM 能力**：用户的 bridge 插件可以调用本地 LLM（如 Claude Code 插件），且浏览器天然能访问外部 API。

## 2. 设计方案：LLM 在浏览器，Server 只做工具服务

### 核心思路

将 **LLM 调用从 Server 移到浏览器**，Server 退化为纯工具提供者：

```
┌─────────────────────┐                      ┌──────────────┐
│  浏览器               │                      │  Server       │
│                      │                      │              │
│  ┌────────────────┐  │   prompt (SSE)        │  Agent Loop  │
│  │  LLM (本地)     │  │ ─────────────────→  │  Skill 加载   │
│  │                  │  │                      │  状态管理     │
│  │  本地显示打字效果  │  │  streaming chunks     │              │
│  │  (不给 Server)    │  │ ──────────────────  │              │
│  │                  │  │                      │              │
│  │  tool_call 时     │  │                      │              │
│  │  转发给 Server     │  │  tool_call (HTTP)    │  Tool 执行    │
│  └────────────────┘  │ ──────────────────→   │  mysql_query  │
│                      │                      │  redis_query  │
│                      │  tool_result (HTTP)   │  log_search   │
│                      │ ◄──────────────────   │              │
│                      │                      │              │
│                      │  done (HTTP)          │  保存对话     │
│                      │ ──────────────────→   │              │
└─────────────────────┘                      └──────────────┘
```

### 对比：Chunked Relay vs 精简版

| 维度 | Chunked Relay (v2 原设计) | 精简版 (本文) |
|------|--------------------------|---------------|
| **LLM 位置** | Server | **浏览器** |
| **chunks 处理** | 每个 chunk 回传 Server (~300 POST/响应) | **不回传，仅本地显示** |
| **HTTP 次数** | ~300 POST + 300 SSE | **~3-5 HTTP/响应** |
| **额外延迟** | 500ms+ (chunk 累积) | **<50ms** |
| **Server 线程阻塞** | 10-30s (等 LLM 完成) | **0s (LLM 在浏览器跑)** |
| **并发压力** | 高 (线程池耗尽风险) | **几乎无** |
| **实现复杂度** | 高 (SSE 双向 relay) | **低 (仅消息转发)** |

### 通信协议

只需转发 **4 类消息**：

| 消息类型 | 方向 | 频率 | Payload |
|---------|------|------|---------|
| **prompt** | Server → 浏览器 | 每 turn 1 条 | `{role, content, tools}` |
| **LLM 响应** | 浏览器 → Server | 每 turn 1 条 | `{text, tool_calls}` |
| **tool_call** | Server → 浏览器 → Server | 按需 | `{name, arguments}` |
| **tool_result** | Server → 浏览器 → Server | 按需 | `{name, result}` |
| **done** | 浏览器 → Server | 每对话 1 条 | `{conversation_id, summary}` |

### Server 端改造

**新增**：`BridgeLlmClient` (实现 `StreamingLlmClient` 接口)

```java
public class BridgeLlmClient implements StreamingLlmClient {
    private final IssueBridgeService bridgeService;
    
    @Override
    public void stream(LlmRequest request, LlmEventSink sink) {
        // 1. 通过 bridge 发送 prompt 到浏览器
        CompletableFuture<LlmResponse> future = bridgeService.proxyLlmRequest(request);
        
        // 2. 浏览器 LLM 完成后回调
        LlmResponse response = future.get();
        
        // 3. 解析 tool_calls，逐个执行工具
        for (ToolCall call : response.getToolCalls()) {
            sink.onToolCall(call);
            ToolResult result = toolRegistry.execute(call);
            sink.onToolResult(result);
        }
        
        // 4. 返回最终文本
        sink.onTextComplete(response.getText());
    }
}
```

**配置**：
```yaml
snap-agent:
  llm:
    api-type: bridge    # 新增: bridge 模式
    # 不需要 api-key, base-url, auth-token
  bridge:
    enabled: true
    allowed-host-patterns:
      - "api.anthropic.com"  # LLM 域名白名单
```

### 浏览器插件改造

**新增**：LLM 代理逻辑

```javascript
// background.js — 新增 LLM 代理
if (message.type === 'llm-request') {
  const response = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': message.apiKey,  // 从扩展配置读取
      'anthropic-version': '2023-06-01'
    },
    body: JSON.stringify(message.payload)
  });
  
  // 流式响应：本地显示，同时收集完整文本
  const reader = response.body.getReader();
  let fullText = '';
  const toolCalls = [];
  
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    
    const chunk = decodeChunk(value);
    // 1. 本地显示打字效果
    postMessage({type: 'llm-chunk', text: chunk.text});
    
    // 2. 收集完整文本和 tool_calls
    fullText += chunk.text;
    if (chunk.tool_use) toolCalls.push(chunk.tool_use);
  }
  
  // 3. 完成后回传 Server
  postMessage({
    type: 'llm-complete',
    text: fullText,
    tool_calls: toolCalls
  });
}
```

### 前端改造

**新增**：`llm-bridge-client.js`

```javascript
class LlmBridgeClient {
  constructor() {
    this.bridge = new SnapAgentBridge();
    this.bridge.connect();
  }
  
  // 监听 Server 的 prompt
  onPrompt(prompt) {
    // 转发给浏览器插件
    this.bridge.sendToExtension({
      type: 'llm-request',
      prompt: prompt
    });
  }
  
  // 监听插件的 LLM 响应
  onLlmResponse(response) {
    // 回传 Server
    fetch('/snap-agent/bridge/llm-result', {
      method: 'POST',
      body: JSON.stringify(response)
    });
  }
  
  // 本地显示打字效果
  onLlmChunk(chunk) {
    this.displayTypingEffect(chunk.text);
  }
}
```

## 3. 安全设计

### API Key 存储

| 方案 | 位置 | 安全性 |
|------|------|--------|
| **扩展配置** (推荐) | `chrome.storage.local` | ✅ 页面 JS 无法读取 |
| Server 配置 | `application.yml` | ❌ 暴露风险 |

**推荐**：API Key 存在浏览器插件配置中，Server 完全不知道 Key。

### 请求白名单

```yaml
snap-agent:
  bridge:
    allowed-host-patterns:
      - "api.anthropic.com"
      - "*.openai.com"
      - "api-inference.huggingface.co"
```

只有匹配白名单的 LLM 域名才会被代理。

### 认证传递

- Server → 浏览器：通过已认证的 SSE 通道（Basic Auth / Bearer Token）
- 浏览器 → LLM API：通过扩展的 `x-api-key` header（页面 JS 不可见）

## 4. 降级策略

| 场景 | 行为 |
|------|------|
| 插件未安装 | Server 回退到直连 LLM（需配置 `api-key`） |
| 插件已安装但 LLM toggle OFF | Server 回退到直连 LLM |
| LLM API 超时 | 返回错误，不降级（避免混合模式混乱） |

## 5. 配置示例

### 纯 Bridge 模式（推荐）

```yaml
snap-agent:
  llm:
    api-type: bridge    # 使用浏览器 LLM
  bridge:
    enabled: true
    allowed-host-patterns:
      - "api.anthropic.com"
```

**无需配置**：`api-key`, `base-url`, `auth-token`

### 混合模式（Bridge 优先，直连备用）

```yaml
snap-agent:
  llm:
    api-type: bridge
    fallback-api-key: sk-xxx  # 插件不可用时降级
    fallback-base-url: https://api.anthropic.com
  bridge:
    enabled: true
```

## 6. 性能评估

### 单次对话（2000 tokens）

| 指标 | 直连模式 | Bridge 模式 |
|------|---------|-------------|
| HTTP 请求数 | 1 (OkHttp SSE) | ~3-5 (prompt + tool_calls + done) |
| Server 线程占用时间 | 10-30s | **0s** |
| 额外延迟 | 0ms | **<50ms** |
| 带宽消耗 | ~50KB (SSE) | **~10KB** (JSON) |

### 并发 10 用户

| 指标 | 直连模式 | Bridge 模式 |
|------|---------|-------------|
| 阻塞线程数 | 10 | **0** |
| POST /秒 | 0 | **~30-50** |
| 内存占用 | 高 (SSE buffers) | **低** |

## 7. 实现路线图

### Phase 1: Server 端

- [ ] 新增 `BridgeLlmClient` 实现
- [ ] 新增 `BridgeLlmProperties` 配置
- [ ] 修改 `LlmClientFactory` 支持 `api-type: bridge`
- [ ] 新增 `/bridge/llm-result` 端点

### Phase 2: 浏览器插件

- [ ] 新增 LLM 代理逻辑 (`background.js`)
- [ ] 新增 LLM toggle 开关 (`popup.html`)
- [ ] 新增 API Key 配置界面
- [ ] 新增 LLM 域名白名单

### Phase 3: 前端

- [ ] 新增 `llm-bridge-client.js`
- [ ] 修改 `index.html` 条件加载
- [ ] 新增本地打字效果显示

### Phase 4: 测试

- [ ] Server 端单元测试 (`BridgeLlmClientTest`)
- [ ] 浏览器插件 E2E 测试
- [ ] 降级策略测试

## 8. 验收标准

1. **零配置启动**：用户只需安装插件 + 开启 LLM toggle，无需配置 API Key
2. **性能无损**：Bridge 模式延迟 < 50ms，无额外 Server 压力
3. **降级透明**：插件不可用时自动回退到直连模式
4. **安全隔离**：API Key 不经过 Server，不暴露在页面 DOM
5. **向后兼容**：`bridge.enabled=false` 时，所有代码路径不变

---

## 附录 A: 消息格式

### prompt (Server → 浏览器)

```json
{
  "id": "prompt-123",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant..."},
    {"role": "user", "content": "查询最近 10 条订单"}
  ],
  "tools": [
    {
      "name": "mysql_query",
      "description": "Execute read-only SQL query",
      "parameters": {
        "sql": "SELECT * FROM orders LIMIT 10"
      }
    }
  ],
  "model": "claude-sonnet-4-20250514",
  "max_tokens": 8192
}
```

### llm-result (浏览器 → Server)

```json
{
  "id": "prompt-123",
  "text": "我来帮您查询订单...\n\n[tool_call: mysql_query]",
  "tool_calls": [
    {
      "id": "call_1",
      "name": "mysql_query",
      "arguments": {"sql": "SELECT * FROM orders ORDER BY created_at DESC LIMIT 10"}
    }
  ],
  "usage": {
    "input_tokens": 150,
    "output_tokens": 80
  }
}
```

### tool-result (Server → 浏览器 → Server)

```json
{
  "id": "call_1",
  "name": "mysql_query",
  "result": "[{\"id\": 1, \"amount\": 100}, ...]",
  "error": null
}
```

---

## 附录 B: 与现有 Bridge 的关系

| 组件 | 现有 Bridge (v1) | LLM Bridge (本文) |
|------|-----------------|-------------------|
| **代理对象** | Issue Tracker / VCS | **LLM API** |
| **通信方向** | Server → 浏览器 → 外部系统 | **浏览器 → LLM API → 浏览器 → Server** |
| **数据流** | 请求-响应 (同步) | **流式 (SSE) + 最终回传** |
| **Server 角色** | HTTP 代理 | **工具提供者** |
| **配置项** | `bridge.enabled` | **`llm.api-type: bridge`** |

**共存**：两种桥接可以同时启用，互不干扰。

---

## 附录 C: 多宿主隔离设计

### 问题

用户可能同时运行多个 SnapAgent 实例（dev / staging / prod），每个实例可能需要不同的 LLM 配置：

| 宿主 | 域名 | LLM 配置 |
|------|------|----------|
| Dev | localhost:8090 | Claude Sonnet (测试用) |
| Staging | staging.sfcloud.local | Claude Opus (生产模型) |
| Prod | prod.sfcloud.local | Claude Opus + 更高 max_tokens |

**问题**：如果浏览器插件只存一份 LLM 配置，所有宿主会互相干扰。

### 解决方案：按域名隔离配置

浏览器插件使用 `chrome.storage.local` 按域名存储配置：

```javascript
// storage structure
{
  "llm-config:localhost:8090": {
    apiKey: "sk-dev-key",
    baseUrl: "https://api.anthropic.com",
    model: "claude-sonnet-4-20250514",
    maxTokens: 4096
  },
  "llm-config:staging.sfcloud.local": {
    apiKey: "sk-staging-key",
    baseUrl: "https://api.anthropic.com",
    model: "claude-opus-4-20250514",
    maxTokens: 8192
  },
  "llm-config:prod.sfcloud.local": {
    apiKey: "sk-prod-key",
    baseUrl: "https://api.anthropic.com",
    model: "claude-opus-4-20250514",
    maxTokens: 16384
  }
}
```

### 域名识别

浏览器插件通过 `document.location.origin` 识别当前宿主：

```javascript
function getHostNamespace() {
  const origin = window.location.origin;  // "http://localhost:8090"
  return `llm-config:${origin}`;
}

async function getLlmConfig() {
  const key = getHostNamespace();
  const config = await chrome.storage.local.get(key);
  return config[key] || getDefaultConfig();
}
```

### Popup 界面改造

Popup 显示当前宿主的配置：

```html
<!-- popup.html -->
<div id="current-host">localhost:8090</div>

<div class="config-section">
  <label>API Key</label>
  <input type="password" id="api-key" />
  
  <label>Base URL</label>
  <input type="text" id="base-url" value="https://api.anthropic.com" />
  
  <label>Model</label>
  <select id="model">
    <option value="claude-sonnet-4-20250514">Claude Sonnet</option>
    <option value="claude-opus-4-20250514">Claude Opus</option>
  </select>
  
  <label>Max Tokens</label>
  <input type="number" id="max-tokens" value="8192" />
  
  <button id="save">Save for this host</button>
</div>

<div class="host-list">
  <h3>Configured Hosts</h3>
  <ul id="host-list">
    <li>localhost:8090 ✅</li>
    <li>staging.sfcloud.local ✅</li>
    <li>prod.sfcloud.local ✅</li>
  </ul>
</div>
```

### 配置同步

当用户切换宿主时，插件自动加载对应配置：

```javascript
// content.js
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === 'GET_LLM_CONFIG') {
    getLlmConfig().then(config => {
      sendResponse({config, host: window.location.origin});
    });
    return true;  // async response
  }
});
```

### 安全隔离

| 隔离维度 | 实现方式 |
|----------|----------|
| **配置隔离** | `chrome.storage.local` 按域名 key 存储 |
| **API Key 隔离** | 每个域名独立的 API Key |
| **请求隔离** | `background.js` 根据请求来源域名选择配置 |
| **权限隔离** | 域名白名单按宿主独立配置 |

### 默认配置

如果宿主没有独立配置，使用全局默认配置：

```javascript
const DEFAULT_LLM_CONFIG = {
  apiKey: '',
  baseUrl: 'https://api.anthropic.com',
  model: 'claude-sonnet-4-20250514',
  maxTokens: 8192
};
```

用户首次访问新宿主时，Popup 会提示配置 LLM。

---

## 附录 D: 更新后的 TDD 验收标准

新增 AC（多宿主隔离）：

```gherkin
AC-11: 多宿主配置隔离
  Given 用户访问 localhost:8090 并配置 API Key "sk-dev"
  And 用户访问 staging.sfcloud.local 并配置 API Key "sk-staging"
  When 用户在 localhost:8090 发送消息
  Then LLM 调用使用 "sk-dev"
  And staging.sfcloud.local 的配置不受影响

AC-12: 新宿主默认配置
  Given 用户首次访问 prod.sfcloud.local
  When 打开 Popup
  Then 显示默认配置（空 API Key）
  And 提示用户配置 LLM

AC-13: 配置持久化
  Given 用户在 localhost:8090 配置了 LLM
  When 刷新页面
  Then 配置仍然生效
  And 无需重新配置
```
