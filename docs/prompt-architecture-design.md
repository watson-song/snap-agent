# SnapAgent Prompt 架构设计

> 基于 7 层 Context Stack 模式，描述 SnapAgent 2.x 的 prompt 构建、注入和序列化全流程。

## 1. 设计目标

| 目标 | 说明 |
|------|------|
| **分层隔离** | 每层 context 独立构建、独立注入，互不干扰 |
| **可扩展** | 新增 context 层只需添加新的 Advisor 或在 Node 中追加逻辑 |
| **Provider 无关** | 统一的 `LlmRequest` DTO，由 `LlmClient` 实现适配 Anthropic / OpenAI |
| **Skill 驱动** | Skill YAML frontmatter 声明 tools / output-format / inputs，运行时自动注入 |

## 2. 7 层 Context Stack 映射

| 层 | 名称 | SnapAgent 实现 | 构建位置 |
|----|------|---------------|----------|
| 1 | **Instructions** | 只读护栏 + `<skill_body>` + 项目结构摘要 | `EntryNode` + `ProjectContextAdvisor` |
| 2 | **User Input** | `<user_inputs>` 标签包裹的 JSON 键值对 | `EntryNode` |
| 3 | **Retrieved Facts** | RAG 检索结果 → `<retrieved_facts>` 块注入 system prompt | `RetrievalAugmentationAdvisor` → `AgentNode` |
| 4 | **Tools** | 按 skill 声明的 tools 过滤，`@Tool/@ToolParam` 反射生成 JSON Schema | `AgentNode` |
| 5 | **Short-term Notes** | 对话历史 `memory.messages` 注入到 messages 列表前部；超窗时摘要压缩 | `MessageChatMemoryAdvisor` → `AgentNode` |
| 6 | **Long-term Memory** | `UserProfileStore` + `ProjectFactsStore` SPI 存储稳定事实，`LongTermMemoryAdvisor` 按需注入 | `LongTermMemoryAdvisor` (order=150) |
| 7 | **Output Format** | skill frontmatter 声明 `output-format` → `<output_format>` 块 | `EntryNode` |

## 3. 核心组件

### 3.1 EntryNode — Prompt 构建入口

**职责**: 构建 system prompt（Layer 1 + 7）和 user message（Layer 2）。

**system prompt 组装顺序**:
```
READ_ONLY_PREFIX          ← Layer 1: 安全护栏（只读模式）
+ <skill_body>            ← Layer 1: skill 指令体（XML 标签防注入）
+ <output_format>         ← Layer 7: 输出格式约束（可选，来自 skill frontmatter）
```

**user message 格式** (P4 优化 — JSON 结构化):
```
<user_inputs>
{
  "service": "order-service",
  "metric": "cpu_usage",
  "threshold": 0.85,
  "tags": ["critical", "p0"]
}
</user_inputs>
```

支持嵌套对象、列表和数值类型，比 key=value 格式表达力更强。

### 3.2 Advisor 链 — 横切增强

Advisor 按 `getOrder()` 排序，在 Node 执行前后注入 context：

| Order | Advisor | 层 | 职责 |
|-------|---------|-----|------|
| 10 | `ProjectContextAdvisor` | 1 | 在 entry 后追加项目结构摘要（Maven 模块、Java 文件计数、关键目录，max 1500 字符） |
| 50 | `SafeGuardAdvisor` | — | 前置过滤敏感词，后置过滤 LLM 输出中的敏感信息 |
| 100 | `MessageChatMemoryAdvisor` | 5 | 前置加载对话历史到 `memory.messages`；后置持久化 user/assistant 消息 |
| 150 | `LongTermMemoryAdvisor` | 6 | 前置加载用户偏好 + 项目事实，注入 system prompt 的 `<user_profile>` 和 `<project_facts>` 块 |
| 200 | `RetrievalAugmentationAdvisor` | 3 | 前置执行 RAG 管线 → `rag.context` |

### 3.3 AgentNode — LLM 请求组装

**职责**: 将各层 context 组装为不可变 `LlmRequest`。

**组装顺序**:
```
Layer 1+3+6: systemPrompt = state["system.prompt"]
                                 + <user_profile>      ← Layer 6 (from LongTermMemoryAdvisor)
                                 + <project_facts>     ← Layer 6
                                 + <retrieved_facts>   ← Layer 3
Layer 2:      userMessage  = state["user.message"]
Layer 5:      messages     = memory.messages + [userMessage]
Layer 4:      toolDefs     = registry.getAll() filtered by skill.tools
              → LlmRequest(systemPrompt, messages, toolDefs, model, maxTokens, streaming)
```

**关键改进**: RAG context 从 user message 移到 system prompt 的 `<retrieved_facts>` 块，保持 Layer 2 和 Layer 3 独立。

### 3.4 RAG 管线

3 阶段模块化设计：

```
QueryTransformer → DocumentRetriever → QueryAugmenter
     (改写查询)        (向量检索)        (格式化)
```

- `QueryTransformer`: 支持查询改写（如 HyDE、扩展）
- `DocumentRetriever`: 调用 `VectorStore.similaritySearch()` topK
- `DefaultQueryAugmenter`: 格式化为 Markdown，`## 相关知识` + `### 知识片段 N`

### 3.5 ChatMemory — 对话历史管理

#### 3.5.1 滑窗策略 (`MessageWindowChatMemory`)
- 默认 20 条消息/会话
- System 消息永不淘汰
- 淘汰以完整 turn 为单位（user+assistant pair）

#### 3.5.2 摘要压缩策略 (`SummarizingChatMemory`) — P3 优化
- 当消息数超过 `maxMessages` 阈值时，对最旧的 N 条调用 LLM 生成摘要
- 摘要作为 system 消息替代原始消息，而非直接丢弃
- 保留最近的消息完整不变
- 构造: `SummarizingChatMemory(ChatMemoryRepository, LlmClient, maxMessages, summarizeThreshold)`

#### 3.5.3 持久化 SPI (`ChatMemoryRepository`)
- `InMemoryChatMemoryRepository` — 默认内存实现
- 可扩展为 Redis / 文件 / 数据库实现

### 3.6 Long-term Memory — 稳定事实存储 (P2 优化)

#### 3.6.1 UserProfileStore SPI
存储用户级稳定事实：
```java
interface UserProfileStore {
    UserProfile load(String userId);
    void save(String userId, UserProfile profile);
}
```
- `UserProfile`: 用户偏好（语言、输出风格）、常用服务、历史决策摘要
- `InMemoryUserProfileStore`: 默认内存实现

#### 3.6.2 ProjectFactsStore SPI
存储项目级稳定事实：
```java
interface ProjectFactsStore {
    List<ProjectFact> load(String projectId);
    void save(String projectId, List<ProjectFact> facts);
}
```
- `ProjectFact`: 技术栈、编码规范、架构约定、已知约束
- `InMemoryProjectFactsStore`: 默认内存实现

#### 3.6.3 LongTermMemoryAdvisor (order=150)
- `beforeNode("agent")`: 从两个 Store 加载稳定事实，注入 system prompt
- 注入格式: `<user_profile>` 块 + `<project_facts>` 块
- 运行在 Memory(100) 之后、RAG(200) 之前

### 3.7 LlmRequest — 统一请求 DTO

```java
LlmRequest {
    String     systemPrompt;   // Layer 1 + 3 + 6 + 7
    List<Message> messages;    // Layer 5 (history) + Layer 2 (user input)
    List<ToolDef>  tools;      // Layer 4
    String     model;
    int        maxTokens;
    boolean    streaming;
}
```

### 3.8 Provider 序列化

| Provider | system 位置 | tools 格式 |
|----------|-----------|-----------|
| Anthropic | 顶层 `"system"` 字段 | `[{name, description, input_schema}]` |
| OpenAI | 首条 `messages[0]` role=system | `[{type: "function", function: {name, description, parameters}}]` |

## 4. Skill Frontmatter 扩展

新增 `output-format` 字段（可选）：

```yaml
---
name: health-patrol
description: "健康巡检"
tools: [metrics_query]
output-format: |
  请按以下格式输出：
  ## 诊断结论
  ## 异常指标
  ## 建议操作
---
```

解析链路: `SkillLoader.parse()` → `map.get("output-format")` → `SkillMeta.outputFormat`

## 5. 完整 Prompt 流程

```
┌─────────────────────────────────────────────────────────────────┐
│  1. EntryNode                                                   │
│     system.prompt = READ_ONLY_PREFIX                            │
│                      + <skill_body>                             │
│                      + <output_format>  (optional)             │
│     user.message  = <user_inputs> (JSON)                       │
│                                                                  │
│  2. AdvisorNode (before "agent")                                │
│     [10]  ProjectContextAdvisor → append project structure      │
│     [50]  SafeGuardAdvisor → sanitize user.query               │
│     [100] MessageChatMemoryAdvisor → load memory.messages      │
│           (SummarizingChatMemory: 超窗时摘要替代丢弃)            │
│     [150] LongTermMemoryAdvisor → load UserProfile + ProjFacts │
│           → <user_profile> + <project_facts> 注入 system prompt │
│     [200] RetrievalAugmentationAdvisor → RAG → rag.context     │
│                                                                  │
│  3. AgentNode                                                   │
│     systemPrompt += <user_profile>    (from long-term memory)  │
│     systemPrompt += <project_facts>   (from long-term memory) │
│     systemPrompt += <retrieved_facts>  (from rag.context)      │
│     messages = memory.messages + [userMessage]                │
│     toolDefs = registry filtered by skill.tools                │
│     → LlmRequest(systemPrompt, messages, toolDefs, ...)        │
│                                                                  │
│  4. LlmClient (provider-specific serialization)                 │
│     Anthropic: system as top-level string                       │
│     OpenAI:    system as first message with role=system        │
│                                                                  │
│  5. AdvisorNode (after "agent")                                 │
│     [100] Memory → persist user msg + assistant thought        │
│     [50]  SafeGuard → sanitize LLM output                      │
└─────────────────────────────────────────────────────────────────┘
```

## 6. 关键文件索引

| 文件 | 模块 | 职责 |
|------|------|------|
| `EntryNode.java` | core/graph/react | Layer 1+7+2 构建 (JSON 结构化输入) |
| `AgentNode.java` | core/graph/react | Layer 1+3+4+5+6 组装 |
| `LlmRequest.java` | core/llm | 不可变请求 DTO |
| `SkillMeta.java` | core/skill | Skill 元数据（含 outputFormat） |
| `SkillLoader.java` | core/skill | YAML frontmatter 解析 |
| `DefaultQueryAugmenter.java` | core/rag | RAG 结果格式化 |
| `RetrievalAugmentationAdvisor.java` | core/rag | RAG 管线编排 (order=200) |
| `MessageChatMemoryAdvisor.java` | core/memory | 对话历史注入 (order=100) |
| `MessageWindowChatMemory.java` | core/memory | 滑窗策略 |
| `SummarizingChatMemory.java` | core/memory | 摘要压缩策略 (P3) |
| `ChatMemoryRepository.java` | core/memory | 持久化 SPI |
| `UserProfileStore.java` | core/memory | 用户偏好存储 SPI (P2) |
| `ProjectFactsStore.java` | core/memory | 项目事实存储 SPI (P2) |
| `LongTermMemoryAdvisor.java` | core/memory | 稳定事实注入 (order=150, P2) |
| `ProjectContextAdvisor.java` | boot2x/context | 项目结构注入 (order=10) |
| `AnthropicLlmClient.java` | boot2x/llm | Anthropic API 序列化 |
| `OpenAiLlmClient.java` | boot2x/llm | OpenAI API 序列化 |
