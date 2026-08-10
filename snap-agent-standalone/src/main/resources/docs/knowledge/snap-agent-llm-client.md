---
name: snap-agent-llm-client
description: LLM 客户端 — LlmClient SPI、LlmEventSink、Anthropic/OpenAI/Bridge/CostTracking 实现
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent LLM 客户端

## 1. 核心 SPI

### 1.1 LlmClient 接口（3 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/llm/LlmClient.java -->
```java
public interface LlmClient {
    void stream(LlmRequest req, LlmEventSink events, String taskId);
    default void cancel(String taskId) {}
    default List<String> listModels() {
        return Collections.emptyList();
    }
}
```

### 1.2 LlmEventSink 接口（6 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/llm/LlmEventSink.java -->
```java
public interface LlmEventSink {
    void onThought(String text);
    void onToolUse(String id, String name, Map<String, Object> input);
    void onToolResult(String toolUseId, String result);
    void onStop(String stopReason);
    void onError(String message);
    default void onUsage(long inputTokens, long outputTokens, long cacheReadTokens) {}
}
```

### 1.3 LlmRequest

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/llm/LlmRequest.java -->
```java
public class LlmRequest {
    String systemPrompt;
    List<Message> messages;
    List<ToolDef> tools;
    String model;
    int maxTokens;
    boolean streaming;
}
```

## 2. 实现类

| 类 | 模块 | API | 说明 |
|----|------|-----|------|
| `AbstractStreamingLlmClient` | boot2x/llm | — | OkHttp SSE 抽象基类，proxy/cancel/listModels |
| `AnthropicLlmClient` | boot2x/llm | Anthropic Messages | SSE 流式，x-api-key 认证 |
| `OpenAiLlmClient` | boot2x/llm | OpenAI Chat Completions | SSE 流式，Bearer 认证 |
| `BridgeLlmClient` | boot2x/llm | 浏览器 Bridge | 通过 LlmBridgeService 代理 |
| `CostTrackingLlmClient` | boot2x/cost | 装饰器 | 拦截 onUsage() 记录 CostRecord |

<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/AbstractStreamingLlmClient.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/AnthropicLlmClient.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/OpenAiLlmClient.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/BridgeLlmClient.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/cost/CostTrackingLlmClient.java -->

## 3. AbstractStreamingLlmClient 架构

```
AbstractStreamingLlmClient
  ├── stream() → buildHttpRequest() → executeCall() → parseSseResponse()
  ├── cancel() → activeCalls.get(taskId).cancel()
  └── listModels() → GET /models → parse "data" array

  Provider-specific hooks:
  ├── buildHttpRequest(LlmRequest)  — URL, headers, body
  ├── parseSseResponse(Response, LlmEventSink, LlmRequest)
  ├── addAuthHeader(Request.Builder)
  └── getModelsUrl()
```

## 4. CostTrackingLlmClient 装饰器

```java
// boot2x/cost/CostTrackingLlmClient.java
public class CostTrackingLlmClient implements LlmClient {
    // 包装原始 LlmClient, 拦截 onUsage() 事件
    // onStop() 时创建 CostRecord → CostTracker.record()
    // 无 usage 信息时跳过记录
}
```

## 5. 配置

```yaml
snap-agent:
  llm:
    api-type: anthropic        # anthropic | openai | bridge
    base-url: https://api.anthropic.com
    api-key: ${ANTHROPIC_API_KEY}
    auth-token: ${BEARER_TOKEN}   # 可选 Bearer 认证
    proxy-url: ${HTTP_PROXY}      # 可选 HTTP 代理
    model: claude-sonnet-4-20250514
    timeout-seconds: 120
```
