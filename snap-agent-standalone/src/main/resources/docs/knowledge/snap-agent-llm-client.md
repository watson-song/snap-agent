---
name: snap-agent-llm-client
description: LLM 客户端详解 — LlmClient SPI、Anthropic/OpenAI/Bridge 实现、流式调用
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent LLM 客户端

## 1. LlmClient SPI

```java
public interface LlmClient {
    void stream(LlmRequest req, LlmEventSink events, String taskId);
    default void cancel(String taskId) {}
    default List<String> listModels() { return Collections.emptyList(); }
}
```

## 2. 实现类

| 实现 | API | 流式 |
|------|-----|------|
| AnthropicLlmClient | Anthropic Messages API | SSE |
| OpenAiLlmClient | OpenAI Chat Completions | SSE |
| BridgeLlmClient | 浏览器 Bridge 代理 | 异步回调 |
| CostTrackingLlmClient | 装饰器 | 委托 + 统计 |

## 3. LlmRequest / LlmEventSink

```java
public class LlmRequest {
    String systemPrompt;
    List<Message> messages;
    List<ToolDef> tools;
    String model;
    int maxTokens;
    boolean streaming;
}

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
    api-key: sk-...
    auth-token: 01414185
    model: claude-sonnet-4-20250514
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true
```
