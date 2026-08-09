---
name: snap-agent-memory-system
description: Memory 记忆系统详解 — ChatMemory 组装、MessageChatMemoryAdvisor、长期记忆、分层架构
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Memory 记忆系统

## 1. 架构概述

SnapAgent 采用 **分层记忆架构**，支持短期对话记忆和长期知识记忆：

```
┌─────────────────────────────────────────────────────────┐
│                    AgentService                         │
│                                                         │
│   execute(taskId, skill, task, advisors)                │
│                                                         │
│   ┌─────────────────────────────────────────────────┐  │
│   │            ReActGraphFactory.build()              │  │
│   │                                                  │  │
│   │   EntryNode → ReActNode[] → ExitNode            │  │
│   └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                        │
                        │ advisors 注入（按 Order 排序）
                        ▼
─────────────────────────────────────────────────────────┐
│                    Advisors 层                          │
│                                                         │
│  Order 50:  SafeGuardAdvisor (安全检查)                 │
│  Order 100: MessageChatMemoryAdvisor (对话记忆)  ← 核心  │
│  Order 200: RAGAdvisor (检索增强)                       │
│  Order 300: LongTermMemoryAdvisor (长期记忆)            │
│  Order 400: AnchorOrchestrator (锚点注入)               │
└─────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│                  Memory 存储层                          │
│                                                         │
│  ┌────────────────────┐  ┌─────────────────────────┐  │
│  │ ChatMemory         │  │ LongTermMemoryStore     │  │
│  │ (短期对话记忆)      │  │ (长期知识记忆)           │  │
│  └────────────────────┘  └─────────────────────────┘  │
│           │                                              │
│           ▼                                              │
│  ┌────────────────────┐                                  │
│  │ ChatMemoryRepository│                                  │
│  │ (持久化存储)        │                                  │
│  │ - InMemory         │                                  │
│  │ - File             │                                  │
│  │ - Redis (可扩展)   │                                  │
│  └────────────────────┘                                  │
└─────────────────────────────────────────────────────────┘
```

## 2. ChatMemory 组装流程

### 2.1 自动配置（SnapAgentAutoConfiguration）

```java
// 1. 创建 ChatMemoryRepository（持久化层）
@Bean
public ChatMemoryRepository chatMemoryRepository(SnapAgentProperties props) {
    String repoType = props.getMemory().getRepositoryType();
    if ("file".equalsIgnoreCase(repoType)) {
        return new FileChatMemoryRepository(dir);  // 文件存储
    }
    return new InMemoryChatMemoryRepository();     // 内存存储（默认）
}

// 2. 创建 ChatMemory（记忆核心）
@Bean
public ChatMemory chatMemory(ChatMemoryRepository repo, 
                             ObjectProvider<Summarizer> summarizerProvider,
                             SnapAgentProperties props) {
    Summarizer summarizer = summarizerProvider.getIfAvailable();
    if (summarizer != null) {
        // 带摘要功能的记忆
        return new SummarizingChatMemory(repo, summarizer, maxMsgs, threshold);
    }
    // 滑动窗口记忆（默认）
    return new MessageWindowChatMemory(repo, props.getMemory().getMaxMessages());
}

// 3. 创建 MessageChatMemoryAdvisor（注入到 Agent）
@Bean
public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
    return new MessageChatMemoryAdvisor(chatMemory);  // order=100
}
```

### 2.2 配置属性

```yaml
snap-agent:
  memory:
    repository-type: in-memory  # in-memory | file
    max-messages: 50            # 滑动窗口大小
    summarize-threshold: 100    # 触发摘要的 token 阈值
```

## 3. MessageChatMemoryAdvisor 核心逻辑

### 3.1 beforeNode（加载历史）

```java
@Override
public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
    String conversationId = resolveConversationId(state);
    if (conversationId == null) {
        return state.with(StateKeys.MEMORY_MESSAGES, new ArrayList<>());
    }
    
    // 从 ChatMemory 加载最近 N 条消息
    List<Message> history = chatMemory.get(conversationId, retrieveLastN);
    return state.with(StateKeys.MEMORY_MESSAGES, history);
}
```

**执行时机**：每个 ReAct Node 执行前
**作用**：将对话历史加载到 `state["memory.messages"]`
**读取数量**：默认 50 条（可配置）

### 3.2 afterNode（持久化消息）

```java
@Override
public GraphState afterNode(String nodeName, GraphState state, Object ctx) {
    String conversationId = resolveConversationId(state);
    
    switch (nodeName) {
        case "agent":
            // 持久化助手回复（包含 tool_use blocks）
            persistAssistantTurn(state, conversationId);
            break;
        case "tools":
            // 持久化工具执行结果
            persistToolResults(state, conversationId);
            break;
    }
    return state;
}
```

**持久化规则**：

| Node 名称 | 持久化内容 | 消息类型 |
|-----------|-----------|----------|
| `agent` | 助手回复 + tool_use blocks | `Message.assistant()` |
| `tools` | 工具执行结果 | `Message.toolResult()` |
| `entry` | 用户消息（由 MessagePartitioner 注入） | 不持久化（避免重复） |

## 4. ChatMemory 实现类

### 4.1 MessageWindowChatMemory（默认）

```java
public class MessageWindowChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final int maxMessages;  // 滑动窗口大小
    
    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> all = repository.findAll(conversationId);
        // System 消息始终保留
        List<Message> system = all.stream()
            .filter(m -> m.getRole() == Role.SYSTEM)
            .collect(Collectors.toList());
        
        // 非 System 消息取最近 N 条
        List<Message> nonSystem = all.stream()
            .filter(m -> m.getRole() != Role.SYSTEM)
            .collect(Collectors.toList());
        
        int start = Math.max(0, nonSystem.size() - lastN);
        List<Message> recent = nonSystem.subList(start, nonSystem.size());
        
        // 合并：system + recent
        List<Message> result = new ArrayList<>(system);
        result.addAll(recent);
        return result;
    }
}
```

**特点**：
- 滑动窗口机制，保留最近 N 条消息
- System 消息始终保留（不被窗口限制）
- 简单高效，适合大多数场景

### 4.2 SummarizingChatMemory（带摘要）

```java
public class SummarizingChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final Summarizer summarizer;
    private final int maxMessages;
    private final int summarizeThreshold;  // token 阈值
    
    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> all = repository.findAll(conversationId);
        
        // 如果总 token 数超过阈值，触发摘要
        if (countTokens(all) > summarizeThreshold) {
            // 保留最近 N 条 + 历史摘要
            List<Message> recent = getRecent(all, lastN);
            Message summary = summarizer.summarize(getOlder(all, lastN));
            recent.add(0, summary);  // 摘要作为第一条
            return recent;
        }
        
        return getRecent(all, lastN);
    }
}
```

**特点**：
- 支持长对话压缩
- 自动触发摘要（token 阈值控制）
- 适合需要长期上下文的场景

## 5. ChatMemoryRepository 实现

### 5.1 InMemoryChatMemoryRepository（默认）

```java
public class InMemoryChatMemoryRepository implements ChatMemoryRepository {
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();
    
    @Override
    public List<Message> findAll(String conversationId) {
        return store.getOrDefault(conversationId, Collections.emptyList());
    }
    
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        store.put(conversationId, new ArrayList<>(messages));
    }
}
```

**特点**：
- 内存存储，速度快
- 应用重启后丢失
- 适合开发/测试环境

### 5.2 FileChatMemoryRepository（文件存储）

```java
public class FileChatMemoryRepository implements ChatMemoryRepository {
    private final Path baseDir;
    
    @Override
    public List<Message> findAll(String conversationId) {
        Path file = baseDir.resolve(conversationId + ".json");
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        // 从 JSON 文件加载消息
        return objectMapper.readValue(file.toFile(), new TypeReference<List<Message>>() {});
    }
    
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Path file = baseDir.resolve(conversationId + ".json");
        objectMapper.writeValue(file.toFile(), messages);
    }
}
```

**特点**：
- 文件持久化，重启不丢失
- 适合单机部署
- 存储路径：`{uploadSkillsDir}/memory/conversations/`

## 6. 长期记忆（LongTermMemoryAdvisor）

```java
public class LongTermMemoryAdvisor implements Advisor {
    private final LongTermMemoryStore store;
    
    @Override
    public int getOrder() {
        return 300;  // 在 RAG 之后
    }
    
    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        // 从长期记忆存储中检索相关笔记
        String query = extractQuery(state);
        List<String> notes = store.retrieve(query, topK);
        return state.with(StateKeys.LONG_TERM_NOTES, notes);
    }
}
```

**特点**：
- 跨对话的知识记忆
- 基于向量检索的相关性匹配
- 适合需要记住用户偏好的场景

## 7. 配置示例

### 7.1 开发环境（内存存储）

```yaml
snap-agent:
  memory:
    repository-type: in-memory
    max-messages: 50
```

### 7.2 生产环境（文件存储）

```yaml
snap-agent:
  memory:
    repository-type: file
    max-messages: 100
    summarize-threshold: 200
```

### 7.3 自定义存储（Redis/DB）

```java
@Bean
public ChatMemoryRepository chatMemoryRepository() {
    return new RedisChatMemoryRepository(redisTemplate);
}
```

## 8. 常见问题

### Q1: 对话历史丢失？
- 检查 `repository-type` 配置
- `in-memory` 模式重启后丢失
- 使用 `file` 模式持久化

### Q2: 上下文窗口太小？
- 调整 `max-messages` 配置
- 启用 Summarizer 支持长对话

### Q3: 如何扩展自定义存储？
- 实现 `ChatMemoryRepository` 接口
- 注册为 Spring Bean（`@ConditionalOnMissingBean` 会自动替换）

## 9. 性能指标

| 指标 | InMemory | File | Redis |
|------|----------|------|-------|
| 读取延迟 | <1ms | 5-10ms | 2-5ms |
| 写入延迟 | <1ms | 10-20ms | 2-5ms |
| 存储容量 | 受内存限制 | 受磁盘限制 | 受 Redis 限制 |
| 重启恢复 | ❌ | ✅ | ✅ |
| 分布式支持 |  | ❌ | ✅ |
