---
name: LLM 客户端模块
description: SnapAgent LLM 客户端模块 — 支持 Anthropic、OpenAI、Bridge 三种 LLM 客户端实现
version: 1.0.0
tables: []
services: [LlmClient, AnthropicLlmClient, OpenAiLlmClient, BridgeLlmClient, LlmRequest, LlmEventSink]
entry_points: [LlmClient.stream(), LlmClient.cancel(), LlmClient.listModels()]
related_concepts: [LLM 调用，流式输出，工具调用，Token 计数]
tags: [llm, anthropic, openai, bridge, streaming]
version: 1.0.0
module: snap-agent-core
author: SnapAgent
---

# LLM 客户端模块

## 1. 模块概述

LLM 客户端模块提供统一的 LLM 调用接口，支持多种 LLM 提供商（Anthropic、OpenAI）和本地 Bridge 模式。

## 2. 核心类

### 2.1 LlmClient (SPI 接口)

```java
public interface LlmClient {
    /**
     * 流式调用 LLM
     * @param request LLM 请求（messages, tools, model）
     * @param sink 事件回调（onThought, onToolUse, onToolResult）
     */
    void stream(LlmRequest request, LlmEventSink sink);
    
    /** 取消正在进行的调用 */
    default void cancel(String taskId) {}
    
    /** 列出可用模型 */
    default List<String> listModels() { return Collections.emptyList(); }
}
```

### 2.2 LlmRequest (请求封装)

| 字段 | 类型 | 说明 |
|------|------|------|
| messages | List\<Message\> | 消息列表 |
| tools | List\<ToolDef\> | 可用工具列表 |
| model | String | 模型名称 |
| temperature | Double | 温度参数 |
| maxTokens | Integer | 最大 token 数 |

### 2.3 LlmEventSink (事件回调)

| 方法 | 说明 |
|------|------|
| onThought(String text) | LLM 思考过程 |
| onToolUse(String id, String name, Map input) | 工具调用 |
| onToolResult(String toolUseId, String result) | 工具执行结果 |
| onUsage(long inputTokens, long outputTokens) | Token 使用量 |
| onComplete(String text) | 最终回复 |
| onError(String error) | 错误信息 |

### 2.4 Message (消息封装)

| 字段 | 类型 | 说明 |
|------|------|------|
| role | String | 角色（system/user/assistant/tool） |
| content | String | 消息内容 |
| toolCalls | List\<ToolUseBlock\> | 工具调用列表 |
| toolCallId | String | 工具调用 ID |

### 2.5 ToolDef (工具定义)

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 工具名称 |
| description | String | 工具描述 |
| parameters | Map | 参数定义（JSON Schema） |

### 2.6 ToolUseBlock (工具调用块)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 调用 ID |
| name | String | 工具名称 |
| input | Map | 输入参数 |

## 3. 实现类

### 3.1 AnthropicLlmClient

- **API**: Anthropic Messages API
- **流式**: 支持 SSE 流式输出
- **工具调用**: 支持 function calling
- **Token 计数**: 自动计算 input/output tokens

### 3.2 OpenAiLlmClient

- **API**: OpenAI Chat Completions API
- **流式**: 支持 SSE 流式输出
- **工具调用**: 支持 function calling

### 3.3 BridgeLlmClient

- **模式**: 通过浏览器 Bridge 调用 LLM
- **场景**: 本地开发、无 API Key 场景
- **依赖**: 需要浏览器扩展支持

## 4. 使用示例

```java
// 1. 创建 LLM 客户端
LlmClient llmClient = new AnthropicLlmClient(apiKey, model);

// 2. 构建请求
LlmRequest request = LlmRequest.builder()
    .addMessage("user", "你好")
    .addTool(jdbcTool)
    .model("claude-sonnet-4-20250514")
    .build();

// 3. 流式调用
llmClient.stream(request, new LlmEventSink() {
    @Override
    public void onThought(String text) {
        System.out.println("思考：" + text);
    }
    
    @Override
    public void onToolUse(String id, String name, Map input) {
        System.out.println("工具调用：" + name + " " + input);
    }
    
    @Override
    public void onComplete(String text) {
        System.out.println("回复：" + text);
    }
});
```

## 5. 扩展新 LLM 客户端

实现 `LlmClient` 接口：

```java
public class CustomLlmClient implements LlmClient {
    @Override
    public void stream(LlmRequest request, LlmEventSink sink) {
        // 1. 调用 LLM API
        // 2. 解析响应
        // 3. 通过 sink 回调事件
        sink.onThought("...");
        sink.onToolUse("tool_1", "jdbc_query", map);
        sink.onComplete("最终回复");
    }
}
```

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
