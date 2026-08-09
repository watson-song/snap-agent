# SnapAgent LLM Bridge E2E 测试手册

> 本文档记录如何通过浏览器注入 JavaScript 模拟 Chrome 扩展，
> 测试 LLM Bridge 的端到端流程（无需真实扩展）。

---

## 1. 测试架构

```
┌─────────────────────────────────────────────────────────────┐
│  Browser (Chrome with --disable-extensions)                 │
│  ┌─────────────────────────────────────────────────────    │
│  │ SnapAgent UI (index.html)                          │    │
│  │  ─ llm-bridge-client.js                           │    │
│  │       ├─ EventSource → GET /bridge/llm/stream (SSE) │    │
│  │       ├─ POST /bridge/llm/result                    │    │
│  │       └─ POST /bridge/llm/error                     │    │
│  │                                                     │    │
│  │  Injected Simulator (evaluate_script)               │    │
│  │       └─ fetch(LLM API) — 直接调用 LLM              │    │
│  └─────────────────────────────────────────────────────┘    │
─────────────────────────────────────────────────────────────┘
                             │ SSE (llm-request →)
                             │ HTTP POST (← llm-response)
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  SnapAgent Server (localhost:8090)                          │
│  ┌─────────────────────────────────────────────────────    │
│  │ LlmBridgeController                                  │    │
│  │  ├─ GET  /bridge/llm/stream   → SseEmitter          │    │
│  │  ├─ POST /bridge/llm/result   → handleResult()      │    │
│  │  └─ POST /bridge/llm/error    → handleError()       │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────    │
│  │ LlmBridgeService                                    │    │
│  │  ├─ isBridgeActive()                                │    │
│  │  ├─ proxyLlmRequest() → SSE push + CompletableFuture│   │
│  │  ─ handleResult() → complete future                │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ BridgeLlmClient (LlmClient 实现)                    │    │
│  │  └─ stream() → bridgeService.proxyLlmRequest()      │    │
│  └─────────────────────────────────────────────────────┘    │
─────────────────────────────────────────────────────────────┘
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
           ┌──────────────┐  ┌──────────────┐
           │ LLM API      │  │ LLM API      │
           │ (Anthropic)  │  │ (OpenAI)     │
           └──────────────┘  └──────────────┘
```

---

## 2. 前置条件

| 项目 | 要求 |
|------|------|
| Java | Corretto 1.8.0_492 |
| Maven | 3.9.15 |
| Chrome | 已安装 |
| SnapAgent Standalone | 已构建 (`snap-agent-standalone-2.0.0-SNAPSHOT.jar`) |
| LLM API Key | Anthropic 或 OpenAI |

---

## 3. 启动 SnapAgent Standalone

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent

JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" \
java -jar snap-agent-standalone/target/snap-agent-standalone.jar \
  --snap-agent.llm.api-type=bridge \
  --snap-agent.bridge.enabled=true \
  --server.port=8090
```

**验证启动：**

```bash
curl -s http://localhost:8090/actuator/health | python3 -m json.tool
# 预期: {"status": "UP"}
```

---

## 4. 打开 SnapAgent UI

```
navigate_page → http://localhost:8090/snap-agent/index.html
```

---

## 5. 注入 LLM Bridge 模拟器

使用 `evaluate_script` 注入以下代码：

```javascript
// 1. 移除旧模拟器（如果存在）
if (window.__llmBridgeSimulatorHandler) {
    window.removeEventListener('message', window.__llmBridgeSimulatorHandler);
}

// 2. LLM 配置（根据实际使用的 LLM 修改）
var LLM_CONFIG = {
    apiType: 'anthropic',  // 或 'openai'
    baseUrl: 'https://api.anthropic.com',
    apiKey: 'sk-ant-xxxxx',  // 替换为实际 API Key
    model: 'claude-sonnet-4-20250514',
    maxTokens: 8192
};

// 3. 注入模拟器
window.__llmBridgeSimulatorHandler = function(event) {
    if (event.data && event.data.type === 'snapagent-llm-request') {
        var req = event.data.request;
        var taskId = req.id;
        
        console.log('[LLM Simulator] Received request:', taskId);
        
        // 构造 LLM API 请求
        var url = LLM_CONFIG.baseUrl + '/v1/messages';
        var headers = {
            'Content-Type': 'application/json',
            'x-api-key': LLM_CONFIG.apiKey,
            'anthropic-version': '2023-06-01'
        };
        
        var body = {
            model: LLM_CONFIG.model,
            max_tokens: LLM_CONFIG.maxTokens,
            messages: req.messages || [],
            system: req.system || '',
            tools: req.tools || [],
            stream: false
        };
        
        // 调用 LLM API
        fetch(url, {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(body)
        }).then(function(resp) {
            if (!resp.ok) {
                return resp.text().then(function(t) {
                    throw new Error('LLM API error ' + resp.status + ': ' + t);
                });
            }
            return resp.json();
        }).then(function(data) {
            // 解析 Anthropic 响应
            var text = '';
            var toolCalls = [];
            
            if (data.content) {
                for (var i = 0; i < data.content.length; i++) {
                    var block = data.content[i];
                    if (block.type === 'text') {
                        text += block.text;
                    } else if (block.type === 'tool_use') {
                        toolCalls.push({
                            id: block.id,
                            name: block.name,
                            input: block.input
                        });
                    }
                }
            }
            
            var response = {
                id: taskId,
                text: text,
                toolCalls: toolCalls,
                usage: data.usage || {
                    input_tokens: 0,
                    output_tokens: 0,
                    cache_read_input_tokens: 0
                }
            };
            
            console.log('[LLM Simulator] Response:', text.substring(0, 100));
            
            // 发送结果回 Server
            fetch('/snap-agent/bridge/llm/result', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(response)
            }).then(function(r) {
                console.log('[LLM Simulator] Result sent:', r.status);
            });
        }).catch(function(err) {
            console.error('[LLM Simulator] Error:', err.message);
            
            fetch('/snap-agent/bridge/llm/error', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    id: taskId,
                    error: err.message
                })
            });
        });
    }
};

window.addEventListener('message', window.__llmBridgeSimulatorHandler);

// 4. 通知 Server 模拟器已就绪
fetch('/snap-agent/bridge/llm/status', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        installed: true,
        masterEnabled: true,
        services: { 'llm': true }
    })
}).then(function(r) {
    console.log('[LLM Simulator] Status reported:', r.status);
});

console.log('[LLM Simulator] Ready — listening for LLM requests');
```

---

## 6. 验证模拟器

```bash
curl -s http://localhost:8090/snap-agent/bridge/llm/status | python3 -m json.tool
# 预期: {"active": true, "pending": 0}
```

---

## 7. E2E 测试场景

### 7.1 场景一：LLM Bridge 连接与状态验证

**步骤：**
1. 打开 SnapAgent UI
2. 注入模拟器脚本
3. 检查 `/bridge/llm/status` 返回 `active: true`

**预期结果：**
- Simulator 控制台输出 `[LLM Simulator] Ready`
- Server 返回 `{"active": true, "pending": 0}`

---

### 7.2 场景二：通过 LLM Bridge 发起对话

**步骤：**
1. 在 SnapAgent UI 中发送消息："你好，请介绍一下你自己"
2. 观察 Simulator 控制台日志
3. 检查 Server 返回的对话结果

**预期结果：**
- Simulator 收到 `snapagent-llm-request` 事件
- Simulator 调用 LLM API 并成功返回
- Server 显示 Agent 回复："你好！我是 SnapAgent，一个 AI 助手..."

**验证命令：**

```bash
# 获取最近的 run
RUN_ID=$(curl -s http://localhost:8090/snap-agent/runs | python3 -c "import sys,json; runs=json.load(sys.stdin); print(runs[-1]['taskId'])")

# 查看 transcript
curl -s "http://localhost:8090/snap-agent/runs/${RUN_ID}/transcript" | python3 -c "
import sys,json
data = json.load(sys.stdin)
for e in data.get('transcript',[]):
    if e['type'] == 'output':
        print(e['text'])
"
```

---

### 7.3 场景三：LLM Bridge Tool Call 处理

**步骤：**
1. 在 SnapAgent UI 中选择 `database-query` skill
2. 发送消息："查询 drp_transfer_suggestion 表最近 10 条记录"
3. 观察 Simulator 是否收到 tool_calls
4. 检查 Server 是否正确处理 tool_result

**预期结果：**
- LLM 返回包含 `tool_calls` 的响应
- Simulator 正确解析并转发
- Server 执行工具并返回结果
- Agent 继续对话并给出最终答案

---

### 7.4 场景四：LLM API 错误处理

**步骤：**
1. 修改模拟器中的 `apiKey` 为无效值
2. 发送消息
3. 观察错误处理流程

**预期结果：**
- Simulator 捕获 LLM API 错误
- Simulator 发送错误到 `/bridge/llm/error`
- Server 记录错误并在 UI 中显示

---

## 8. 故障排查

### 问题 1：Simulator 未收到请求

**检查：**
```bash
# 1. 检查 SSE 连接
curl -s http://localhost:8090/snap-agent/bridge/llm/stream

# 2. 检查 Server 日志
tail -f /tmp/snap-agent-standalone.log | grep -i "llm\|bridge"
```

**可能原因：**
- `llm-bridge-client.js` 未加载（检查 `index.html` 是否包含 `<script src="llm-bridge-client.js">`）
- Server 未启用 bridge 模式（检查 `api-type=bridge`）

---

### 问题 2：LLM API 调用失败

**检查：**
```bash
# 测试 LLM API 连通性
curl -X POST https://api.anthropic.com/v1/messages \
  -H "x-api-key: YOUR_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4-20250514","max_tokens":100,"messages":[{"role":"user","content":"Hi"}]}'
```

**可能原因：**
- API Key 无效或过期
- 网络问题（防火墙/代理）
- LLM API 限流

---

### 问题 3：Tool Call 未执行

**检查：**
```bash
# 查看工具注册状态
curl -s http://localhost:8090/snap-agent/tools | python3 -m json.tool
```

**可能原因：**
- 工具未正确注册
- Tool schema 不匹配
- 工具执行报错

---

## 9. 清理与回收

```bash
# 停止 SnapAgent Standalone
pkill -f "snap-agent-standalone"

# 清理日志
rm -f /tmp/snap-agent-standalone.log

# 清除浏览器缓存（可选）
# Chrome → Settings → Privacy → Clear browsing data
```

---

## 附录 A：模拟器通信协议

### Server → Simulator (SSE)

```json
{
  "type": "llm-request",
  "data": {
    "id": "task-123",
    "request": {
      "messages": [{"role": "user", "content": "Hello"}],
      "systemPrompt": "You are a helpful assistant.",
      "tools": [],
      "model": "claude-sonnet-4-20250514"
    }
  }
}
```

### Simulator → Server (HTTP POST)

**成功响应：**
```json
POST /snap-agent/bridge/llm/result
{
  "id": "task-123",
  "text": "Hello! How can I help you?",
  "toolCalls": [],
  "usage": {
    "inputTokens": 10,
    "outputTokens": 5,
    "cacheReadTokens": 0
  }
}
```

**错误响应：**
```json
POST /snap-agent/bridge/llm/error
{
  "id": "task-123",
  "error": "LLM API error 401: Invalid API Key"
}
```

---

## 附录 B：多宿主配置测试

测试不同域名使用不同 LLM 配置：

```javascript
// 为 localhost:8090 配置 Anthropic
var LLM_CONFIG = {
    apiType: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    apiKey: 'sk-ant-xxxxx',
    model: 'claude-sonnet-4-20250514'
};

// 为其他域名配置 OpenAI（如果需要）
// var LLM_CONFIG = {
//     apiType: 'openai',
//     baseUrl: 'https://api.openai.com',
//     apiKey: 'sk-xxxxx',
//     model: 'gpt-4'
// };
```

---

## 附录 C：与真实扩展对比

| 特性 | 模拟器 | 真实扩展 |
|------|--------|----------|
| **安装** | 无需安装，注入 JS | 需要加载扩展 |
| **API Key 存储** | JS 变量（明文） | `chrome.storage.local`（加密） |
| **多宿主隔离** | 手动切换配置 | 自动按域名隔离 |
| **CORS** | 受浏览器限制 | 无 CORS 限制 |
| **适用场景** | 开发/测试 | 生产环境 |

---

**文档版本：** 1.0  
**最后更新：** 2026-08-08  
**维护者：** SnapAgent Team
