---
name: snap-agent-memory-system
description: Memory 记忆系统详解 — ChatMemory 组装、MessageChatMemoryAdvisor、长期记忆
version: 1.0.1
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
tools:
  - code_read
  - code_search
---

# SnapAgent Memory 记忆系统

## 1. 架构概述

```
┌─────────────────────────────────────────────────────────┐
│                    AgentService                          │
│  execute(taskId, skill, task, advisors)                 │
│  ┌─────────────────────────────────────────────────┐   │
│  │          ReActGraphFactory.build()               │   │
│  │   EntryNode → ReActNode[] → ExitNode            │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────┬───────────────────────────────┘
                          │
                          │ advisors 注入（按 Order 排序）
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    Advisors 层                            │
│  Order 50:   SafeGuardAdvisor (安全检查)                 │
│  Order 100:  MessageChatMemoryAdvisor (对话记忆)  ◄核心   │
│  Order 200:  RAGAdvisor (检索增强)                       │
│  Order 300:  LongTermMemoryAdvisor (长期记忆)            │
│  Order 400:  AnchorOrchestrator (锚点注入)               │
└─────────────────────────┬───────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                  Memory 存储层                           │
│  ┌────────────────────┐  ┌────────────────────────┐    │
│  │  ChatMemory        │  │  LongTermMemoryStore   │    │
│  │  - MessageWindow   │  │  - 向量检索            │    │
│  │  - Summarizing     │  │  - 跨对话知识          │    │
│  └─────────┬──────────┘  └────────────────────────┘    │
│            ▼                                            │
│  ┌────────────────────┐                                 │
│  │ChatMemoryRepository│                                 │
│  │  - InMemory        │                                 │
│  │  - File            │                                 │
│  │  - Redis (可扩展)  │                                 │
│  └────────────────────┘                                 │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心代码

### 2.1 ChatMemory SPI

```java
package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;
import java.util.List;

public interface ChatMemory {
    void add(String conversationId, Message message);
    List<Message> get(String conversationId, int lastN);
    void clear(String conversationId);
}
```

### 2.2 MessageChatMemoryAdvisor（核心）

```java
package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;

public class MessageChatMemoryAdvisor implements Advisor {
    private final ChatMemory chatMemory;
    private final int retrieveLastN = 50;
    private final String conversationIdKey = "conversation.id";

    @Override
    public int getOrder() { return 100; }

    @Override
    public String getName() { return "chat-memory"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        String conversationId = (String) state.get(conversationIdKey);
        if (conversationId == null) {
            return state.with("memory.messages", new ArrayList<>());
        }
        List<Message> history = chatMemory.get(conversationId, retrieveLastN);
        return state.with("memory.messages", history);
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) {
        String conversationId = (String) state.get(conversationIdKey);
        if (conversationId == null) return state;

        switch (nodeName) {
            case "agent":
                persistAssistantTurn(state, conversationId);
                break;
            case "tools":
                persistToolResults(state, conversationId);
                break;
        }
        return state;
    }
}
```

### 2.3 自动配置

```java
@Bean
public ChatMemoryRepository chatMemoryRepository(SnapAgentProperties props) {
    String repoType = props.getMemory().getRepositoryType();
    if ("file".equalsIgnoreCase(repoType)) {
        String dir = props.getUploadSkillsDir() + "/memory/conversations";
        return new FileChatMemoryRepository(dir);
    }
    return new InMemoryChatMemoryRepository();
}

@Bean
public ChatMemory chatMemory(ChatMemoryRepository repo,
                             ObjectProvider<Summarizer> summarizerProvider,
                             SnapAgentProperties props) {
    Summarizer summarizer = summarizerProvider.getIfAvailable();
    if (summarizer != null) {
        return new SummarizingChatMemory(repo, summarizer, 
            props.getMemory().getMaxMessages(), 
            props.getMemory().getSummarizeThreshold());
    }
    return new MessageWindowChatMemory(repo, props.getMemory().getMaxMessages());
}

@Bean
public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
    return new MessageChatMemoryAdvisor(chatMemory);
}
```

## 3. 执行流程

```
T=0: entryNode.beforeNode()
     └─ 初始化 GraphState

T=1: MessageChatMemoryAdvisor.beforeNode("entry")
     ─ 加载对话历史 → state["memory.messages"]

T=2: entryNode.execute()
     ─ 注入 skill body 到 system prompt

T=3: agentNode.beforeNode()
     └─ Advisors 注入上下文

T=4: agentNode.execute()
     ├─ 组装 LLM 请求（system + history + tools）
     ├─ 调用 LlmClient.stream()
     └─ 解析 tool_calls

T=5: MessageChatMemoryAdvisor.afterNode("agent")
     └─ 持久化 assistant 消息

T=6: ShouldContinue.evaluate()
     ├─ 有 tool_calls? → "tools"
     └─ 无 tool_calls? → "end"

T=7: toolsNode.execute()
     └─ 执行工具调用

T=8: MessageChatMemoryAdvisor.afterNode("tools")
     └─ 持久化 tool_result 消息

T=9: 返回 T=3 继续循环...
```

## 4. 配置

```yaml
snap-agent:
  memory:
    repository-type: in-memory  # in-memory | file
    max-messages: 50            # 滑动窗口大小
    summarize-threshold: 100    # 触发摘要的 token 阈值
```
