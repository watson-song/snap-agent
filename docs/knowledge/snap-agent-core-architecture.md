---
name: SnapAgent 核心架构
description: SnapAgent 智能体框架核心架构 — LLM 客户端、ReAct 图引擎、Skill 系统、Tool 工具系统
version: 1.0.0
tables: []
services: [LlmClient, ReActGraphFactory, AgentService, SkillRegistry, ToolCallbackRegistry, DomainKnowledgeLoader, CodeGraphIndex]
entry_points: [AgentService.execute(), ReActGraphFactory.build(), SkillRegistry.load()]
related_concepts: [LLM 调用，ReAct 循环，工具调用，技能加载，领域知识，代码图谱]
tags: [core-architecture, llm, react, skill-system, tool-system]
version: 1.0.0
module: snap-agent-core
author: SnapAgent
---

# SnapAgent 核心架构

## 1. 模块概述

SnapAgent 是一个基于 Java 的智能体框架，提供 LLM 调用、ReAct 图引擎、Skill 技能系统、Tool 工具系统等核心能力。

## 2. 核心子模块

### 2.1 LLM 客户端 (cn.watsontech.snapagent.core.llm)

| 类名 | 说明 |
|------|------|
| LlmClient | LLM 客户端 SPI 接口 |
| LlmRequest | LLM 请求封装（messages, tools, model） |
| LlmEventSink | LLM 事件回调接口（onThought, onToolUse, onToolResult） |
| Message | 消息封装（role, content） |
| ToolDef | 工具定义（name, description, parameters） |
| ToolUseBlock | 工具调用块（id, name, input） |

**核心接口**：
```java
public interface LlmClient {
    void stream(LlmRequest request, LlmEventSink sink);
    default void cancel(String taskId) {}
    default List<String> listModels() { return Collections.emptyList(); }
}
```

### 2.2 ReAct 图引擎 (cn.watsontech.snapagent.core.graph)

| 类名 | 说明 |
|------|------|
| ReActGraphFactory | ReAct 图工厂，构建 entry→agent→tools→end 图 |
| GraphExecutor | 图执行器，驱动节点执行循环 |
| CodeGraphIndex | 代码图谱索引，存储类信息和依赖关系 |
| CheckpointStore | 检查点存储，支持执行恢复 |

**核心流程**：
```
EntryNode → AgentNode → ToolsNode → End
                ↑            |
                └────────────┘ (循环直到无工具调用)
```

### 2.3 Agent 服务 (cn.watsontech.snapagent.core.agent)

| 类名 | 说明 |
|------|------|
| AgentService | Agent 服务，协调 LLM 调用和工具执行 |
| AgentTask | Agent 任务封装 |
| TaskStore | 任务存储（内存/持久化） |

### 2.4 Skill 系统 (cn.watsontech.snapagent.core.skill)

| 类名 | 说明 |
|------|------|
| SkillRegistry | 技能注册表，管理技能加载和查询 |
| SkillMeta | 技能元数据（name, description, tools, entry_points） |
| SkillLoader | 技能加载器，从 Markdown 文件加载技能 |

**技能格式**：
```markdown
---
name: 技能名称
description: 技能描述
tools: [tool1, tool2]
entry_points: [POST /api/xxx]
---

# 技能名称

## 技能描述
...
```

### 2.5 Tool 工具系统 (cn.watsontech.snapagent.core.tool)

| 类名 | 说明 |
|------|------|
| ToolCallbackRegistry | 工具回调注册表 |
| ToolCallback | 工具回调接口 |
| CodeReadTool | 代码读取工具 |
| JdbcQueryTool | JDBC 查询工具 |

### 2.6 Domain Knowledge 领域知识 (cn.watsontech.snapagent.core.domain)

| 类名 | 说明 |
|------|------|
| DomainKnowledge | 领域知识模型（name, tables, services, entry_points） |
| DomainKnowledgeLoader | 领域知识加载器，解析 Markdown frontmatter |
| DomainKnowledgeIndex | 领域知识索引 |

### 2.7 Vector Store 向量存储 (cn.watsontech.snapagent.core.vectorstore)

| 类名 | 说明 |
|------|------|
| VectorStore | 向量存储 SPI 接口 |
| Document | 文档封装（content, metadata） |
| InMemoryVectorStore | 内存向量存储实现 |

## 3. 核心流程

### 3.1 Agent 执行流程

```
1. AgentService.execute(task, skill)
2. ReActGraphFactory.build(skill) → 构建 ReAct 图
3. GraphExecutor.execute(graph) → 执行图
4. AgentNode: 调用 LlmClient.stream()
5. LlmEventSink.onToolUse() → 触发工具调用
6. ToolsNode: 执行 ToolCallback
7. 循环直到无工具调用
8. 返回最终结果
```

### 3.2 技能加载流程

```
1. SkillRegistry.load(directory)
2. SkillLoader.parseFile(file) → 解析 Markdown frontmatter
3. 创建 SkillMeta 对象
4. 注册到 SkillRegistry
5. 可通过 name/tags 查询技能
```

### 3.3 领域知识加载流程

```
1. DomainKnowledgeLoader.loadFromDirectory(dir)
2. 扫描 *.md 文件
3. 解析 YAML frontmatter（tables, services, entry_points）
4. 创建 DomainKnowledge 对象
5. 存入 DomainKnowledgeIndex
6. 同时存入 VectorStore 用于语义检索
```

## 4. 依赖关系

```
AgentService → ReActGraphFactory → GraphExecutor
                    ↓
              LlmClient (SPI)
                    ↓
              AnthropicLlmClient / OpenAiLlmClient / BridgeLlmClient
                    ↓
              ToolCallbackRegistry → ToolCallback
                    ↓
              CodeReadTool / JdbcQueryTool / ...
```

## 5. 扩展点

| 扩展点 | SPI 接口 | 说明 |
|--------|----------|------|
| LLM 客户端 | LlmClient | 接入不同 LLM 提供商 |
| 工具 | ToolCallback | 添加自定义工具 |
| 技能 | SkillMeta | 定义新技能 |
| 向量存储 | VectorStore | 接入不同向量数据库 |
| 任务存储 | TaskStore | 持久化任务数据 |
| 检查点 | CheckpointStore | 支持执行恢复 |

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
**项目版本**: SnapAgent 2.0.0-SNAPSHOT
