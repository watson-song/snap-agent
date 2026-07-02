# 05 — LLM 客户端

## 1. LlmClient SPI

```java
public interface LlmClient {
    /** 流式调用；events 回调逐个推（thought/tool_use/tool_result_chunk/stop） */
    void stream(LlmRequest req, LlmEventSink events);
}

public record LlmRequest(
    List<Message> messages,     // 含 system + 历史 + 新 tool_result
    List<ToolDef> tools,        // ToolDispatcher 提供的 schema
    String model,               // per-run 覆盖后的最终 model
    int maxTokens,
    boolean streaming
) {}
```

实现：`AnthropicLlmClient`（2x-starter，OkHttp 流式）。Phase 3 加 `OpenAiLlmClient`。

## 2. Anthropic Messages 流式客户端（OkHttp）

### HTTP
- `POST {base-url}/v1/messages`
- Headers：`x-api-key: {api-key}`、`anthropic-version: 2023-06-01`、`content-type: application/json`、`accept: text/event-stream`
- Body：`{ model, max_tokens, system, messages, tools, stream:true }`
- 流式：OkHttp `Call` + `ResponseBody.source()` 逐行读 SSE `event:`/`data:`，按 Anthropic streaming 协议累积 `content_block_delta`。

### 事件映射（→ LlmEventSink → AgentExecutor → SSE transcript）
| Anthropic 事件 | 处理 |
|----------------|------|
| `message_start` | 初始化 message 累积器 |
| `content_block_start` (type=text) | 新开一段 text 累积 |
| `content_block_delta` (text_delta) | 累积 + 推 thought 事件 |
| `content_block_start` (type=tool_use) | 新开 tool_use 累积（id/name/input） |
| `content_block_delta` (input_json_delta) | 累积 partial JSON |
| `content_block_stop` | 完成当前 block（text→thought 事件；tool_use→交 ToolDispatcher） |
| `message_delta` (stop_reason) | 记录 stop_reason |
| `message_stop` | 流结束 |
| `error` | 推 error 事件，task 标 FAILED |

### OkHttp 版本治理（决策 #17）
- 不在 pom 写 `<version>`，交 Spring Boot BOM 管控。
- 冲突兜底：可选 shade/relocate 到 `com.watsontech.snapagent.okhttp.`，仅在宿主已有不兼容 OkHttp 时启用（配置 `snap-agent.llm.shade-okhttp=true`，Phase 3 再做）。

## 3. 配置

```yaml
snap-agent:
  llm:
    base-url: https://api.anthropic.com
    api-key: ${LLM_API_KEY:}                 # 环境变量注入，空则 LlmClient 不装配
    model: claude-sonnet-4-6                 # 默认 model
    allowed-models: [claude-sonnet-4-6, claude-opus-4-6]   # 服务端强制白名单
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true
```

- `api-key` 空 → `LlmClient` bean `@ConditionalOnProperty(api-key)` 不满足 → 不装配 → `AgentExecutor` 缺 LlmClient → 所有 skill 标 UNAVAILABLE。starter 不崩，日志 ERROR 提示。
- `base-url` 可指向企业内代理网关（如 `https://llm-gateway.corp/anthropic`）。

## 4. per-run model 覆盖 + 服务端强制白名单（决策 #7）

### 流程
```
POST /runs { skillId, inputs, model?: "claude-opus-4-6" }
  │
  ▼ controller 校验
1. model 为空 → 用 yml 默认 model
2. model 非空 → 必须在 yml allowed-models 内，否则 400
  │
  ▼ 注入 AgentTask
task.model = 最终 model（per-run，无 session 持久）
  │
  ▼ AgentExecutor 每轮调 LlmClient
LlmRequest.model = task.model
```

### 关键点
- **服务端强制**：`allowed-models` 是服务端校验，不可被前端绕过。前端传不在白名单的 model → 400。
- **per-run 覆盖，无 session 持久**：一次 run 选 opus，下次 run 不带 model → 回到默认 sonnet。不存「用户偏好」到服务端。
- **localStorage 纯 UX**（见 [06-api-and-ui.md](06-api-and-ui.md)）：前端把上次选的 model 存 localStorage，下次打开页面预选；页面 load 时调 `GET /models` 拉服务端 allowed-models，若 localStorage 里的 model 已不在列表 → 清除选择回退默认。localStorage 无安全权重。

## 5. `GET /models` 接口

```json
{ "default": "claude-sonnet-4-6",
  "allowed": ["claude-sonnet-4-6", "claude-opus-4-6"] }
```
前端用此渲染下拉，并清理过期 localStorage 缓存（决策风险 #localStorage 模型缓存失效）。

## 6. OpenAI 适配器（Phase 3）

- `OpenAiLlmClient` 实现 `LlmClient`，调 `{base-url}/v1/chat/completions`（stream=true）。
- 工具协议映射：Anthropic `tool_use` ↔ OpenAI `tool_calls`；`tool_result` ↔ `role:tool` 消息。
- 配置：`snap-agent.llm.provider: anthropic|openai`（Phase 3 加）。
- Phase 1 不做，仅留 SPI 扩展点。

## 7. 风险

- **LLM 幻觉工具名/参数**：见 [03](03-agent-engine.md) §9，`ToolDispatcher` 名单外工具返回 not-found。
- **流式中断**：网络抖动断流 → OkHttp 抛 IOException → 该轮失败 → task FAILED + SSE error。不自动重试（避免重复计费/重复副作用；只读查询本可重试，但 Phase 1 不做，由用户重新发起 run）。
- **max-tokens 不足**：复杂 skill 报告超 max-tokens 被截断 → stop_reason=`max_tokens`。可 yml 调大，注意成本。
- **网关配额**：多用户并发跑 → LLM 网关限流。靠线程池 max=4 + 每用户并发 1 兜底。
