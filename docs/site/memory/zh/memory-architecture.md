# SnapAgent 记忆架构设计

> 版本: v1.1 | 日期: 2026-08-04 | 状态: 设计中 (已通过代码审查修正)

## 1. 问题陈述

SnapAgent 当前拥有多个记忆相关组件（ChatMemory、ConversationStore、Anchor 摘要、知识沉淀、领域知识、长期记忆），但它们之间存在以下问题：

1. **职责重叠**：`ConversationStore` 存完整对话，`ChatMemory` 也存对话消息，两者互不关联
2. **能力断裂**：`SummarizingChatMemory` 已实现但未接入；沉淀经验文档缺少诊断路径和文件持久化
3. **层级不清**：短期记忆、长期记忆、领域知识、诊断经验混在同一层面，没有清晰的分层模型
4. **无法自动学习**：长期记忆（UserProfile / ProjectFacts）是静态配置，不会从对话中自动提取
5. **重启丢失**：`InMemoryChatMemoryRepository` 重启后对话历史消失，`FileConversationStore` 有持久化但不回填 ChatMemory
6. **蒸馏缺失**：对话历史超过 20 条直接硬截断，不做摘要压缩

**核心问题**：SnapAgent 有 5 套独立的"记忆"机制，但没有一个统一的记忆架构来协调它们。

## 2. 现状盘点

### 2.1 现有组件清单

| 组件 | 包路径 | 职责 | 存储 | 状态 |
|------|--------|------|------|------|
| `MessageWindowChatMemory` | core/memory | 滑动窗口 20 条，硬截断 | InMemory | ✅ 已接入 |
| `SummarizingChatMemory` | core/memory | LLM 摘要压缩旧消息 | InMemory | ⚠️ 已实现未接入 |
| `MessageChatMemoryAdvisor` | core/memory | 注入对话历史到 Agent | — | ✅ 已接入 |
| `LongTermMemoryAdvisor` | core/memory | 注入 UserProfile + ProjectFacts | InMemory | ✅ 已接入 |
| `UserProfileStore` | core/memory | 用户偏好 (language/outputStyle/frequentServices) | InMemory | ✅ 静态配置 |
| `ProjectFactsStore` | core/memory | 项目级 key-value 事实 | InMemory | ✅ 静态配置 |
| `ConversationStore` | boot2x/conversation | 完整对话持久化 (JSON) | File | ✅ 已接入 |
| `FileConversationStore` | boot2x/conversation | ConversationStore 文件实现 | File | ✅ 已接入 |
| `AnchorContextSummarizer` | boot2x/anchor | 锚点内容摘要 (LLM) | — | ✅ 已接入 |
| `AnchorSummaryCache` | boot2x/anchor | 摘要缓存 (Caffeine LRU+TTL) | Cache | ✅ 已接入 |
| `KnowledgeSedimentationService` | boot2x/knowledge | IssueClosure 知识沉淀 (close→extract→embed→VectorStore) | VectorStore | ⚠️ 链路完整但元数据格式需优化 |
| `DomainKnowledgeLoader` | boot2x/domain | 领域知识加载 (YAML+MD) | VectorStore + Index | ✅ 已接入 |
| `DomainKnowledgeIndex` | core/domain | 概念↔表/类 反向索引 | InMemory | ✅ 已接入 |
| `CodeGraphIndex` | core/codegraph | 代码调用关系图谱 | InMemory/H2 | ✅ 已接入 |

> **未列入但相关的组件**：`MessagePartitioner` / `LastNMessagePartitioner`（控制用户消息+历史如何组装给 LLM，属 Layer 0 组装逻辑）；`SedimentationReviewer`（沉淀质量门控，属 Layer 2）；`RetrievalAugmentationAdvisor`（RAG 检索，属 Layer 0 注入）；`DomainKnowledgeTools` / `CodeGraphTools` / `ModuleArchitectureTools`（Layer 3 查询工具）。

### 2.2 当前数据流

```
                        ┌──────────────────────────────────────┐
                        │          LLM Context Window           │
                        │  (System Prompt + Messages + RAG)     │
                        └──────────────┬───────────────────────┘
                                       │
                 ┌─────────────────────┼─────────────────────┐
                 │                     │                     │
    ┌────────────▼──────┐  ┌──────────▼──────────┐  ┌──────▼──────────┐
    │ MessageChatMemory │  │ LongTermMemory      │  │ RAG Advisor     │
    │ Advisor (100)     │  │ Advisor (150)       │  │ (200)           │
    │                   │  │                     │  │                  │
    │ MessageWindow     │  │ UserProfile         │  │ VectorStore     │
    │ ChatMemory        │  │ + ProjectFacts      │  │ similaritySearch│
    │ (硬截断 20 条)    │  │ (静态, 不学习)      │  │ (知识+沉淀+架构)│
    └────────┬──────────┘  └─────────────────────┘  └─────────────────┘
             │
    ┌────────▼──────────┐     ┌──────────────────┐
    │ InMemory          │     │ FileConversation │
    │ ChatMemoryRepo    │     │ Store            │
    │ (重启丢失)        │     │ (JSON 文件)      │
    └───────────────────┘     │ (不回填Memory)   │
                              └──────────────────┘

    问题:
    ✗ 两个独立的对话存储 (Memory vs ConversationStore)
    ✗ 重启后 Memory 丢失, ConversationStore 不回填
    ✗ 对话超过 20 条硬截断, SummarizingChatMemory 未接入
    ✗ UserProfile/ProjectFacts 静态, 不自动学习
    ✗ 诊断结论沉淀为经验但缺少诊断路径和文件持久化
```

### 2.3 设计文档中的原始规划

| 设计文档 | 规划内容 | 实施状态 |
|---------|---------|---------|
| v0.7 知识库设计 | `ConversationKnowledgeSource`（历史对话提取 Q&A 对）— v0.7.1 | ❌ v0.7.1 从未排期 |
| v0.7 知识库设计 | 知识沉淀闭环（对话标注 + 自动摘要入库）— v0.7.1 | ❌ 从未实施 |
| v0.9 问题闭环设计 | `KnowledgeSedimentationExtractor.extract(issue)` → Document | ⚠️ 部分实现 |
| v0.9 问题闭环设计 | `close()` → 沉淀 → VectorStore | ⚠️ 链路完整但元数据需优化 (source→diagnosis-experience, 增加诊断路径) |
| v2.x 架构重构 | `MessageWindowChatMemory` 滑动窗口 | ✅ 已实现 |
| v2.x 架构重构 | `SummarizingChatMemory` 摘要压缩 | ❌ 不在设计中，代码已写但未接入 |

## 3. 目标架构：四层记忆模型

### 3.1 分层设计

```
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 0: Working Memory (工作记忆)                                  │
│  ─────────────────────────────────                                   │
│  LLM 上下文窗口: System Prompt + 短期历史 + RAG 注入 + 领域知识       │
│  生命周期: 单次请求                                                   │
│  容量: ~128K tokens                                                  │
│  组装者: Advisor 链 (SafeGuard → ChatMemory → LongTerm → RAG)        │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 1: Session Memory (会话记忆)                                   │
│  ─────────────────────────────────                                   │
│  当前对话的短期历史 (含工具调用)                                       │
│  生命周期: 会话级 (跨 turn, 跨重启)                                   │
│  容量: 可配置 (默认 50 条, 超出自动摘要压缩)                          │
│  存储: FileChatMemoryRepository (JSON 文件, 按 conversationId)        │
│  实现: SummarizingChatMemory (LLM 摘要, 替代硬截断)                   │
│  注入: MessageChatMemoryAdvisor (order=100)                          │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 2: Experience Memory (经验记忆)                                │
│  ─────────────────────────────────                                   │
│  诊断经验: 问题 → 根因 → 方案 → 验证结果                              │
│  生命周期: 永久 (随诊断积累)                                          │
│  容量: 无上限 (VectorStore 存储)                                      │
│  存储: VectorStore (source="diagnosis-experience")                    │
│  触发: IssueClosure.close() 自动提取                                  │
│  检索: RAG Advisor 按相似度匹配                                       │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 3: Domain Memory (领域记忆)                                    │
│  ─────────────────────────────────                                   │
│  业务概念 + 代码关系 + 模块架构                                        │
│  生命周期: 随代码/文档更新                                             │
│  存储:                                                                │
│    • DomainKnowledgeIndex (概念 ↔ 表/类 映射)                         │
│    • CodeGraphIndex (代码调用图谱)                                    │
│    • VectorStore (source="domain-knowledge")                         │
│  加载: 启动时自动扫描 domain-knowledge/*.md + 构建 CodeGraph          │
│  检索: DomainKnowledgeTools (按概念/表/类查询) + RAG                  │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 4: Identity Memory (身份记忆)                                  │
│  ─────────────────────────────────                                   │
│  用户偏好 + 项目事实 (可自动学习)                                      │
│  生命周期: 永久                                                       │
│  存储:                                                                │
│    • UserProfileStore (用户偏好: language/outputStyle/frequentServices) │
│    • ProjectFactsStore (项目事实: 环境/配置/约束)                      │
│  注入: LongTermMemoryAdvisor (order=150) → system prompt             │
│  学习: 从对话中自动提取 (Phase 2)                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 层间数据流

```
                自动学习 (Phase 2)
        ┌──────────────────────────────┐
        │                              ▼
   ┌────┴────┐    沉淀 close()    ┌─────────┐   RAG 检索    ┌─────────────┐
   │ Layer 1 │ ──────────────────▶ │ Layer 2 │ ────────────▶ │ Layer 0     │
   │ Session │                     │Experience│               │ Working     │
   │         │ ◀─── 对话历史加载 ── │         │               │             │
   └────┬────┘                     └─────────┘               │             │
        │                                                    │             │
        │                     启动加载                        │             │
   ┌────▼────┐                  ┌─────────┐               │             │
   │ 持久化   │                  │ Layer 3 │ ────────────▶ │             │
   │ File    │ ◀── 自动构建 ─── │ Domain  │               │             │
   │ JSON    │                  └─────────┘               │             │
   └─────────┘                                            │             │
                                                          │             │
                        静态注入                            │             │
                        ┌─────────┐                       │             │
                        │ Layer 4 │ ────────────────────▶ │             │
                        │Identity │                       └─────────────┘
                        └─────────┘
```

**关键设计原则**：

1. **向上注入**：所有层最终都注入 Layer 0（LLM 上下文）
2. **向下沉淀**：Layer 1 的对话可以沉淀为 Layer 2 的经验
3. **层间解耦**：每层有独立的存储、生命周期、检索机制
4. **渐进增强**：每层可独立启用/禁用，不依赖其他层

### 3.3 与现有组件的映射

| 层 | 现有组件 | 保留/重构/替换 | 变更说明 |
|----|---------|:---:|---------|
| L0 | Advisor 链 | 保留 | 不变 |
| L1 | `MessageWindowChatMemory` | **替换** | 换为 `SummarizingChatMemory` |
| L1 | `InMemoryChatMemoryRepository` | **替换** | 换为 `FileChatMemoryRepository` |
| L1 | `MessageChatMemoryAdvisor` | 保留 | 不变 |
| L1 | `ConversationStore` / `FileConversationStore` | **合并** | 合并到 Layer 1 持久化 |
| L2 | `KnowledgeSedimentationService` | **优化** | 链路已完整(close→extract→embed→VectorStore)，需优化元数据格式+增加文件持久化+补充诊断路径 |
| L2 | VectorStore (source="sedimentation") | 保留 | 改 source 为 "diagnosis-experience" |
| L3 | `DomainKnowledgeLoader` / `DomainKnowledgeIndex` | 保留 | 不变 |
| L3 | `CodeGraphIndex` / `CodeGraphTools` | 保留 | 不变 |
| L3 | `ModuleArchitectureTools` | 保留 | 不变 |
| L4 | `UserProfileStore` / `ProjectFactsStore` | 保留 | Phase 2 加自动学习 |
| L4 | `LongTermMemoryAdvisor` | 保留 | 不变 |
| — | `AnchorContextSummarizer` | **参考** | 摘要逻辑可参考，但需适配：Anchor输入String，Summarizer输入List\<Message\>，需写适配器 |

## 4. 详细设计

### 4.1 Layer 1: SummarizingChatMemory 接入

**变更**：将 `SnapAgentAutoConfiguration` 中的 `chatMemory()` bean 从 `MessageWindowChatMemory` 替换为 `SummarizingChatMemory`。

```java
@Bean
public ChatMemory chatMemory(ChatMemoryRepository repo, ObjectProvider<Summarizer> summarizer) {
    Summarizer s = summarizer.getIfAvailable(() -> new TruncatingSummarizer());
    return new SummarizingChatMemory(repo, s,
            props.getMemory().getMaxMessages(),      // 默认 50
            props.getMemory().getSummarizeThreshold()); // 默认 10
}
```

**配置**：
```yaml
snap-agent:
  memory:
    max-messages: 50           # 最大消息数 (超出触发摘要)
    summarize-threshold: 10    # 每次摘要压缩的最旧消息数
    repository-type: file      # file | memory (默认 file)
```

**Summarizer 实现**：
- `LlmSummarizer` — 调用 LLM 生成摘要。参考 `AnchorContextSummarizer` 的 LLM 调用逻辑，但需适配输入类型（`List<Message>` → prompt → LLM → 摘要文本）
- `TruncatingSummarizer` — 简单截断 (fallback, 无 LLM 时使用)

### 4.2 Layer 1: FileChatMemoryRepository

**变更**：新建 `FileChatMemoryRepository`，将对话历史持久化到 JSON 文件，替代 `InMemoryChatMemoryRepository`。

> **注意**：`ChatMemoryRepository` 当前接口只有 `save/load/delete`。新增的 `listConversations(String userId)` 方法需要扩展 SPI 接口，或者作为 `FileChatMemoryRepository` 独有的方法（通过 instanceof 检查调用）。建议采用前者以保持 SPI 一致性。

```java
// ChatMemoryRepository.java (SPI 扩展)
public interface ChatMemoryRepository {
    void save(String conversationId, List<Message> messages);
    List<Message> load(String conversationId);
    void delete(String conversationId);
    // 新增: 列出用户的对话 ID 列表
    default List<String> listConversations(String userId) { return Collections.emptyList(); }
}
```

**与 ConversationStore 的关系**：
- `ConversationStore` 面向 UI：存完整对话元数据 (title, skillId, userId, timestamps)
- `FileChatMemoryRepository` 面向 Agent：存消息列表 (role, content, tool_use/tool_result)
- 两者存储路径不同，互不干扰
- Phase 2 可实现 `ConversationStore` 从 `FileChatMemoryRepository` 回填

### 4.3 Layer 2: 诊断经验沉淀优化

**现状**：`IssueClosureService.close()` 已调用 `sedimentationService.sediment(issue)`，链路完整（extract → reviewer → embed → VectorStore）。但存在以下不足：
- 元数据 `source` 为 `"sedimentation:{issueId}"`，不利于按类型过滤
- 提取的经验文档缺少"诊断路径"和"涉及组件"章节
- 无文件持久化（仅 VectorStore，重启后若无 VectorStore 持久化则丢失）

**变更**：优化 `KnowledgeSedimentationService.extract()` 的输出格式 + 增加文件写入。

```java
// 现有链路 (已完整, 不需要改动):
// close() → sedimentationService.sediment(issue) → extract → reviewer → embed → vectorStore.add

// 需要优化的是 KnowledgeSedimentationService.extract() 的输出:
public Document extract(IssueClosure issue) {
    // ... 现有逻辑保留 ...

    // 优化 1: 元数据 source 改为 "diagnosis-experience"
    metadata.put("source", "diagnosis-experience");
    metadata.put("issueId", issue.getIssueId());

    // 优化 2: 增加诊断路径章节 (从 IssueClosure 的 toolTrace 提取)
    if (issue.getToolTrace() != null && !issue.getToolTrace().isEmpty()) {
        content.append("\n## 诊断路径\n");
        for (String step : issue.getToolTrace()) {
            content.append("- ").append(step).append("\n");
        }
    }

    // 优化 3: 增加涉及组件章节
    // (从 DomainKnowledge 反查涉及的表/类)
}
```

**经验文档格式**：
```markdown
## 问题
SKU A123 今天没有生成补货策略

## 根因
安全库存表 replm_safety_stock 有15分钟延迟。Dolphin 任务执行后，
安全库存尚未更新，导致补货策略计算时安全库存为0，跳过生成。

## 方案
等待15分钟后重试，或手动触发安全库存刷新。

## 诊断路径
1. lookup_concept("补货策略") → 入口: ReplenishmentStrategyTask.execute()
2. call_chain → 依赖 SafetyStockService.calculate()
3. find_by_table("replm_safety_stock") → 命中已知陷阱

## 涉及组件
- 表: replm_safety_stock, replm_inv_param_sku_wh_input
- 类: SafetyStockService, ReplenishmentStrategyService
- 概念: 补货策略
```

### 4.4 Layer 4: 身份记忆自动学习 (Phase 2)

Phase 1 先保持静态配置。Phase 2 增加：

```java
/**
 * 从对话中自动提取用户偏好和项目事实。
 * 在 IssueClosure.close() 时触发。
 */
public class MemoryLearningExtractor {

    /** 从对话历史中提取用户偏好 → 更新 UserProfile */
    public UserProfile extractUserProfile(List<Message> conversation);

    /** 从诊断过程中提取项目事实 → 更新 ProjectFacts */
    public List<ProjectFact> extractProjectFacts(List<Message> conversation);
}
```

## 5. Advisor 链组装顺序

```
Order  50: SafeGuardAdvisor          — 安全守卫 (SQL注入/路径穿越防护)
Order 100: MessageChatMemoryAdvisor  — Layer 1: 注入会话历史
Order 150: LongTermMemoryAdvisor     — Layer 4: 注入用户偏好 + 项目事实
Order 200: RetrievalAugmentationAdvisor — Layer 2+3: RAG 检索 (经验+领域知识+架构)
```

每层注入的内容互不干扰，最终在 AgentNode 中组装为完整的 LLM 请求。

## 6. 配置规范

```yaml
snap-agent:
  memory:
    # Layer 1: 会话记忆
    max-messages: 50              # 最大消息数
    summarize-threshold: 10       # 每次摘要压缩条数
    repository-type: file         # file | memory
    storage-dir: ${upload-skills-dir}/memory/conversations/

    # Layer 2: 经验记忆 (通过 issue-closure 配置)
    # issue-closure.sedimentation-enabled: true

    # Layer 3: 领域记忆 (通过 domain-knowledge / code-graph 配置)
    # domain-knowledge.enabled: true
    # code-graph.enabled: true

    # Layer 4: 身份记忆
    # 长期记忆目前为静态配置，Phase 2 增加自动学习
```

## 7. 迁移计划

| 阶段 | 变更 | 风险 | 测试 | 状态 |
|------|------|------|------|------|
| P1.1 | 接入 `SummarizingChatMemory` 替代 `MessageWindowChatMemory` | 低 — 已有实现+测试 | SummarizingChatMemoryTest (已有) | ✅ 完成 |
| P1.2 | 新建 `FileChatMemoryRepository` 替代 `InMemoryChatMemoryRepository` | 中 — 需扩展 `ChatMemoryRepository` SPI + 并发安全 | 新建 FileChatMemoryRepositoryTest | ✅ 完成 |
| P1.3 | 优化 `KnowledgeSedimentationService.extract()` 输出格式 + 增加文件持久化 | 低 — 在现有完整链路上增强 | 新建 SedimentationFormatTest | ✅ 完成 |
| P1.4 | 统一 memory 配置项 (`snap-agent.memory.*`) | 低 — 新增配置 | 配置测试 | ✅ 完成 |
| P2.1 | `MemoryLearningExtractor` 自动学习 | 中 — 新能力 | MemoryLearningExtractorTest | ✅ 完成 |
| P2.2 | `ConversationStore` 从 `FileChatMemoryRepository` 回填 | 中 — 合并两套存储 | TDD | 🔲 待实施 |

## 8. 文件布局

```
snap-agent-core/src/main/java/.../memory/
├── ChatMemory.java                     ← 保留
├── ChatMemoryRepository.java           ← 保留 (P1.2 扩展 listConversations)
├── Summarizer.java                     ← 保留
├── SummarizingChatMemory.java          ← 保留 (P1.1 接入)
├── MessageWindowChatMemory.java        ← 保留 (作为 fallback)
├── MessageChatMemoryAdvisor.java       ← 保留
├── LongTermMemoryAdvisor.java          ← 保留
├── MessagePartitioner.java             ← 保留
├── LastNMessagePartitioner.java        ← 保留
├── UserProfile.java                    ← 保留
├── UserProfileStore.java               ← 保留
├── InMemoryUserProfileStore.java       ← 保留
├── ProjectFact.java                    ← 保留
├── ProjectFactsStore.java              ← 保留
├── InMemoryProjectFactsStore.java      ← 保留
└── FileChatMemoryRepository.java       ← 新增 (P1.2)

snap-agent-spring-boot-2x-starter/src/main/java/.../memory/
├── LlmSummarizer.java                  ← 新增 (P1.1)
├── TruncatingSummarizer.java           ← 新增 (P1.1 fallback)
└── MemoryLearningExtractor.java        ← 新增 (P2.1)

snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/
├── SnapAgentAutoConfiguration.java     ← 修改 chatMemory() bean (P1.1, P1.2, P1.4)
└── IssueAutoConfiguration.java         ← 修改 issueClosureService() bean (P2.1)
```
