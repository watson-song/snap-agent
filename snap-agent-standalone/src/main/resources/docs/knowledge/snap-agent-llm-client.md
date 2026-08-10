---
name: snap-agent-llm-client
description: LLM 客户端 — LlmClient SPI、Anthropic/OpenAI/Bridge 实现、LlmEventSink
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent LLM 客户端

## 1. LlmClient SPI

```java
// core/llm/LlmClient.java
public interface LlmClient {
    void stream(LlmRequest req, LlmEventSink events, String taskId);
    default void cancel(String taskId) {}
    default List<String> listModels() { return Collections.emptyList(); }
}
```

## 2. 实现类

| 类 | 模块 | API | 流式 |
|----|------|-----|------|
| `AnthropicLlmClient` | boot2x/llm | Anthropic Messages API | SSE |
| `OpenAiLlmClient` | boot2x/llm | OpenAI Chat Completions | SSE |
| `BridgeLlmClient` | boot2x/llm | 浏览器 Bridge 代理 | 异步回调 |
| `CostTrackingLlmClient` | boot2x/cost | 装饰器 | 委托 + token 统计 |
| `AbstractStreamingLlmClient` | boot2x/llm | 抽象基类 | SSE 公共逻辑 |

## 3. LlmRequest / LlmEventSink

```java
// core/llm/LlmRequest.java
public class LlmRequest {
    String systemPrompt;
    List<Message> messages;
    List<ToolDef> tools;
    String model;
    int maxTokens;
    boolean streaming;
}

// core/llm/LlmEventSink.java
public interface LlmEventSink {
    void onThought(String text);
    void onToolUse(String id, String name, Map<String, Object> input);
    void onToolResult(String toolUseId, String result);
    void onStop(String stopReason);
    void onError(String message);
    default void onUsage(long input, long output, long cacheRead) {}
}
```

## 4. 配置

```yaml
snap-agent:
  llm:
    api-type: anthropic        # anthropic | openai | bridge
    base-url: https://api.anthropic.com
    auth-token: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-20250514
    max-tokens: 8192
    timeout-seconds: 120
```
