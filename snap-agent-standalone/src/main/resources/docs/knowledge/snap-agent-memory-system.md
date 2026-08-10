---
name: snap-agent-memory-system
description: Memory 记忆系统 — ChatMemory SPI、MessageChatMemoryAdvisor、长期记忆、记忆蒸馏
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Memory 记忆系统

## 1. 四层记忆模型

| 层级 | 组件 | 持久化 | 说明 |
|------|------|--------|------|
| 短期记忆 | `ChatMemory` + `MessageChatMemoryAdvisor` | 可选 | 滑动窗口对话历史 |
| 摘要压缩 | `SummarizingChatMemory` + `Summarizer` | 可选 | 超长对话自动摘要 |
| 长期记忆 | `LongTermMemoryAdvisor` + `UserProfileStore` / `ProjectFactsStore` | 内存/文件 | 跨对话经验沉淀 |
| 记忆蒸馏 | `MemoryLearningExtractor` | 文件 | 从对话中提取持久化经验 |

## 2. ChatMemory SPI

```java
// core/memory/ChatMemory.java
public interface ChatMemory {
    void add(String conversationId, Message message);
    List<Message> get(String conversationId, int lastN);
    void clear(String conversationId);
}
```

| 实现 | 说明 |
|------|------|
| `MessageWindowChatMemory` | 滑动窗口，保留最近 N 条 |
| `SummarizingChatMemory` | 超阈值时调 Summarizer 压缩历史 |

## 3. ChatMemoryRepository

```java
// core/memory/ChatMemoryRepository.java
public interface ChatMemoryRepository {
    void save(String conversationId, List<Message> messages);
    List<Message> load(String conversationId);
    default List<String> listConversations(String userId);  // 按用户过滤
    void delete(String conversationId);
}
```

| 实现 | 模块 | 说明 |
|------|------|------|
| `InMemoryChatMemoryRepository` | core | 内存，默认（listConversations 返回空）|
| `FileChatMemoryRepository` | boot2x | JSON 文件持久化 |

## 4. MessagePartitioner

```java
// core/memory/MessagePartitioner.java
public interface MessagePartitioner {
    List<Message> partition(List<Message> history, String userMessage);
}
```

| 实现 | 说明 |
|------|------|
| `LastNMessagePartitioner` | 保留历史 + 追加 userMessage |

## 5. MessageChatMemoryAdvisor (Order=100)

```
beforeNode("agent"):
  → chatMemory.get(conversationId, lastN) → state["memory.messages"]

afterNode("agent"):
  → 持久化 assistant 消息到 chatMemory

afterNode("tools"):
  → 持久化 tool_result 消息到 chatMemory
```

## 6. 长期记忆

| 组件 | 说明 |
|------|------|
| `LongTermMemoryAdvisor` (Order=150) | 检索用户画像和项目事实注入上下文 |
| `UserProfileStore` / `InMemoryUserProfileStore` | 用户偏好存储 |
| `ProjectFactsStore` / `InMemoryProjectFactsStore` | 项目事实存储 |
| `Summarizer` / `LlmSummarizer` / `TruncatingSummarizer` | 摘要生成 |
| `MemoryLearningExtractor` | 从对话中提取经验沉淀 |

## 7. 配置

```yaml
snap-agent:
  memory:
    repository-type: in-memory  # in-memory | file
    max-messages: 50
    summarize-threshold: 100
    learning:
      enabled: true
```
