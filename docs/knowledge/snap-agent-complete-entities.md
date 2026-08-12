---
name: SnapAgent 完整实体清单
description: SnapAgent 框架全部核心类清单，按模块分类
version: 1.0.0
tables: [code_graph_nodes, code_graph_edges]
services: [LlmClient, ReActGraphFactory, AgentService, SkillRegistry, ToolCallbackRegistry, DomainKnowledgeLoader, CodeGraphIndex, H2CodeGraphIndex, CodeReadTool, JdbcQueryTool]
entry_points: [AgentService.execute(), ReActGraphFactory.build(), SkillRegistry.load(), DomainKnowledgeLoader.loadFromDirectory()]
related_concepts: [LLM 调用，ReAct 循环，工具调用，技能加载，领域知识，代码图谱]
tags: [entities, complete-list, core-classes]
version: 1.0.0
module: snap-agent-core + snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 完整实体清单

## 1. 概览

| 模块 | 文件数 | 说明 |
|------|--------|------|
| snap-agent-core | 146 | 核心 SPI 和领域模型 |
| snap-agent-spring-boot-2x-starter | 170 | Spring Boot 自动配置和实现 |
| snap-agent-client | 6 | 客户端 SDK |
| snap-agent-demo | 5 | 示例项目 |
| snap-agent-standalone | 8 | 独立部署模块 |
| **总计** | **335** | - |

## 2. snap-agent-core 核心类

### 2.1 LLM 客户端 (cn.watsontech.snapagent.core.llm)

| 类名 | 类型 | 说明 |
|------|------|------|
| LlmClient | Interface | LLM 客户端 SPI |
| LlmRequest | Class | LLM 请求封装 |
| LlmEventSink | Interface | LLM 事件回调 |
| Message | Class | 消息封装 |
| ToolDef | Class | 工具定义 |
| ToolUseBlock | Class | 工具调用块 |

### 2.2 ReAct 图引擎 (cn.watsontech.snapagent.core.graph)

| 类名 | 类型 | 说明 |
|------|------|------|
| ReActGraphFactory | Class | ReAct 图工厂 |
| GraphExecutor | Class | 图执行器 |
| CodeGraphIndex | Interface | 代码图谱索引 SPI |
| CheckpointStore | Interface | 检查点存储 SPI |
| Advisor | Interface | Advisor SPI |

### 2.3 Agent 服务 (cn.watsontech.snapagent.core.agent)

| 类名 | 类型 | 说明 |
|------|------|------|
| AgentService | Class | Agent 服务 |
| AgentTask | Class | Agent 任务 |
| TaskStore | Interface | 任务存储 SPI |

### 2.4 技能系统 (cn.watsontech.snapagent.core.skill)

| 类名 | 类型 | 说明 |
|------|------|------|
| SkillRegistry | Class | 技能注册表 |
| SkillMeta | Class | 技能元数据 |
| SkillLoader | Class | 技能加载器 |

### 2.5 工具系统 (cn.watsontech.snapagent.core.tool)

| 类名 | 类型 | 说明 |
|------|------|------|
| ToolCallbackRegistry | Class | 工具回调注册表 |
| ToolCallback | Interface | 工具回调接口 |

### 2.6 领域知识 (cn.watsontech.snapagent.core.domain)

| 类名 | 类型 | 说明 |
|------|------|------|
| DomainKnowledge | Class | 领域知识模型 |
| DomainKnowledgeIndex | Interface | 领域知识索引 SPI |

### 2.7 向量存储 (cn.watsontech.snapagent.core.vectorstore)

| 类名 | 类型 | 说明 |
|------|------|------|
| VectorStore | Interface | 向量存储 SPI |
| Document | Class | 文档封装 |

### 2.8 其他核心类

| 模块 | 类名 | 说明 |
|------|------|------|
| memory | ChatMemory, ChatMemoryRepository | 对话记忆 |
| security | SecurityFramework, PrincipalResolver | 安全框架 |
| cost | CostCalculator, CostRecord | 成本计算 |
| embedding | EmbeddingModel | 嵌入模型 SPI |
| rag | DocumentReader, Chunker | RAG 组件 |
| vcs | VcsClient | VCS 客户端 SPI |
| issue | IssueTracker, IssueClosure | Issue 跟踪 |
| anchor | Anchor, AnchorRegistry | 锚点系统 |
| patrol | PatrolService, PatrolReport | 巡检服务 |

## 3. snap-agent-spring-boot-2x-starter 实现类

### 3.1 自动配置 (cn.watsontech.snapagent.boot2x.autoconfig)

| 类名 | 说明 |
|------|------|
| SnapAgentAutoConfiguration | 核心自动配置 |
| WebAutoConfiguration | Web 端点配置 |
| BridgeAutoConfiguration | Bridge 桥接配置 |
| CodeGraphAutoConfiguration | CodeGraph 配置 |
| LlmBridgeAutoConfiguration | LLM Bridge 配置 |

### 3.2 LLM 客户端实现 (cn.watsontech.snapagent.boot2x.llm)

| 类名 | 说明 |
|------|------|
| AnthropicLlmClient | Anthropic 实现 |
| OpenAiLlmClient | OpenAI 实现 |
| BridgeLlmClient | Bridge 模式实现 |

### 3.3 Web 控制器 (cn.watsontech.snapagent.boot2x.web)

| 类名 | 说明 |
|------|------|
| SnapAgentController | 核心 REST API |
| ChatMemoryController | 对话记忆 API |
| SkillController | 技能管理 API |
| BridgeController | Bridge API |
| LlmBridgeController | LLM Bridge API |

### 3.4 Bridge 桥接 (cn.watsontech.snapagent.boot2x.bridge)

| 类名 | 说明 |
|------|------|
| IssueBridgeService | Issue 桥接服务 |
| BridgeHttpExecutor | Bridge HTTP 执行器 |
| LlmBridgeService | LLM Bridge 服务 |

### 3.5 CodeGraph (cn.watsontech.snapagent.boot2x.codegraph)

| 类名 | 说明 |
|------|------|
| H2CodeGraphIndex | H2 代码图谱索引 |
| CodeGraphTools | 代码图谱工具 |

### 3.6 其他实现类

| 模块 | 类名 | 说明 |
|------|------|------|
| agent | AgentServiceImpl | Agent 服务实现 |
| skill | SkillLoaderImpl | 技能加载实现 |
| tool | CodeReadTool, JdbcQueryTool | 内置工具 |
| domain | DomainKnowledgeLoaderImpl | 领域知识加载实现 |
| memory | InMemoryChatMemoryRepository | 内存对话记忆 |
| vectorstore | InMemoryVectorStore | 内存向量存储 |
| security | SpringSecurityAdapter | Spring Security 适配 |
| cost | FileCostStore | 文件成本存储 |
| conversation | FileConversationStore | 文件对话存储 |

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
**项目版本**: SnapAgent 2.0.0-SNAPSHOT
