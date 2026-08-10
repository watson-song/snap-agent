---
name: snap-agent-memory-system
description: Memory 记忆系统 — ChatMemory SPI、MessagePartitioner、Summarizer、MessageChatMemoryAdvisor、长期记忆、记忆蒸馏
version: 3.0.0
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

## 2. 核心 SPI

### 2.1 ChatMemory 接口（3 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/ChatMemory.java -->
```java
public interface ChatMemory {
    void add(String conversationId, Message message);
    List<Message> get(String conversationId, int lastN);
    void clear(String conversationId);
}
```

### 2.2 ChatMemoryRepository 接口（4 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/ChatMemoryRepository.java -->
```java
public interface ChatMemoryRepository {
    void save(String conversationId, List<Message> messages);
    List<Message> load(String conversationId);
    void delete(String conversationId);
    default List<String> listConversations(String userId);
}
```

### 2.3 MessagePartitioner 接口（1 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/MessagePartitioner.java -->
```java
public interface MessagePartitioner {
    List<Message> partition(List<Message> history, String userMessage);
}
```

### 2.4 Summarizer 接口（1 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/Summarizer.java -->
```java
@FunctionalInterface
public interface Summarizer {
    String summarize(List<Message> messages);
}
```

## 3. 实现类

| 类 | 模块 | 实现接口 | 说明 |
|----|------|----------|------|
| `MessageWindowChatMemory` | core | ChatMemory | 滑动窗口，DEFAULT_MAX_MESSAGES=20 |
| `SummarizingChatMemory` | core | ChatMemory | 超阈值调 Summarizer 压缩，失败降级硬截断 |
| `InMemoryChatMemoryRepository` | core | ChatMemoryRepository | ConcurrentHashMap 内存 |
| `FileChatMemoryRepository` | boot2x | ChatMemoryRepository | JSON 文件持久化 |
| `LastNMessagePartitioner` | core | MessagePartitioner | 保留全部/最近N条 + 追加 userMessage |
| `LlmSummarizer` | boot2x | Summarizer | 调用 LLM 生成摘要 |
| `TruncatingSummarizer` | boot2x | Summarizer | 截断拼接，不调 LLM |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/MessageWindowChatMemory.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/SummarizingChatMemory.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/InMemoryChatMemoryRepository.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/memory/FileChatMemoryRepository.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/LastNMessagePartitioner.java -->

## 4. MessageChatMemoryAdvisor（Order=100）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/MessageChatMemoryAdvisor.java -->
```java
public class MessageChatMemoryAdvisor implements Advisor {
    public int getOrder()  // → 100
    public String getName() // → "chat-memory"
}
```

执行流程：
- **beforeNode**: `chatMemory.get(conversationId, lastN)` → `state["memory.messages"]`
- **afterNode("agent")**: 持久化 assistant 消息（含 tool_use blocks）
- **afterNode("tools")**: 持久化 tool_result 消息，按 index 匹配 tool_use id

## 5. 长期记忆

### 5.1 UserProfileStore（2 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/UserProfileStore.java -->
```java
public interface UserProfileStore {
    UserProfile load(String userId);
    void save(String userId, UserProfile profile);
}
```

### 5.2 ProjectFactsStore（2 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/ProjectFactsStore.java -->
```java
public interface ProjectFactsStore {
    List<ProjectFact> load(String projectId);
    void save(String projectId, List<ProjectFact> facts);
}
```

### 5.3 LongTermMemoryAdvisor（Order=150）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/LongTermMemoryAdvisor.java -->
```java
public class LongTermMemoryAdvisor implements Advisor {
    public int getOrder()  // → 150
    public String getName() // → "long-term-memory"
}
```

beforeNode: 加载 UserProfile → `<user_profile>` 块 + 加载 ProjectFacts → `<project_facts>` 块 → 追加到 system prompt。

### 5.4 MemoryLearningExtractor

<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/memory/MemoryLearningExtractor.java -->
从对话中提取经验，沉淀到长期记忆存储。
