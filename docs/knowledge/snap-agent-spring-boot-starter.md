---
name: Spring Boot Starter 模块
description: SnapAgent Spring Boot 2.x Starter — 自动配置、Web 端点、Bridge 桥接、CodeGraph 代码图谱
version: 1.0.0
tables: []
services: [SnapAgentAutoConfiguration, WebAutoConfiguration, BridgeAutoConfiguration, CodeGraphAutoConfiguration, LlmBridgeAutoConfiguration]
entry_points: [SnapAgentController, BridgeController, CodeGraphTools, LlmBridgeController]
related_concepts: [自动配置，REST API，SSE 流式，代码图谱，Bridge 桥接]
tags: [spring-boot, auto-configuration, rest-api, bridge, codegraph]
version: 1.0.0
module: snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# Spring Boot Starter 模块

## 1. 模块概述

Spring Boot Starter 模块提供 SnapAgent 的自动配置、Web 端点、Bridge 桥接、CodeGraph 代码图谱等功能。

## 2. 自动配置类

### 2.1 SnapAgentAutoConfiguration

核心自动配置，装配以下组件：
- `LlmClient`: LLM 客户端（根据配置选择实现）
- `ToolCallbackRegistry`: 工具回调注册表
- `SkillRegistry`: 技能注册表
- `AgentService`: Agent 服务
- `TaskStore`: 任务存储
- `DomainKnowledgeLoader`: 领域知识加载器
- `CodeGraphIndex`: 代码图谱索引

### 2.2 WebAutoConfiguration

Web 端点自动配置：
- `SnapAgentController`: 核心 REST API
- `ChatMemoryController`: 对话记忆 API
- `SkillController`: 技能管理 API

### 2.3 BridgeAutoConfiguration

Bridge 桥接自动配置：
- `IssueBridgeService`: Issue 桥接服务
- `BridgeHttpExecutor`: Bridge HTTP 执行器
- `BridgeController`: Bridge REST API

### 2.4 CodeGraphAutoConfiguration

CodeGraph 代码图谱自动配置：
- `H2CodeGraphIndex`: H2 代码图谱索引
- `CodeGraphTools`: 代码图谱工具

### 2.5 LlmBridgeAutoConfiguration

LLM Bridge 自动配置：
- `LlmBridgeService`: LLM Bridge 服务
- `LlmBridgeController`: LLM Bridge REST API

## 3. REST API

### 3.1 SnapAgentController

| 端点 | 方法 | 说明 |
|------|------|------|
| /snap-agent/runs | POST | 创建 Agent 任务 |
| /snap-agent/runs/{id} | GET | 查询任务状态 |
| /snap-agent/runs/{id}/cancel | POST | 取消任务 |
| /snap-agent/skills | GET | 列出技能 |
| /snap-agent/skills/{name} | GET | 查询技能详情 |
| /snap-agent/models | GET | 列出可用模型 |
| /snap-agent/domain-knowledge | GET | 查询领域知识 |

### 3.2 ChatMemoryController

| 端点 | 方法 | 说明 |
|------|------|------|
| /snap-agent/chat-memory | GET | 查询对话记忆 |
| /snap-agent/chat-memory | POST | 保存对话记忆 |
| /snap-agent/chat-memory/{conversationId} | DELETE | 删除对话记忆 |

### 3.3 SkillController

| 端点 | 方法 | 说明 |
|------|------|------|
| /snap-agent/skills | GET | 列出技能 |
| /snap-agent/skills/{name} | GET | 查询技能详情 |
| /snap-agent/skills | POST | 创建技能 |
| /snap-agent/skills/{name} | PUT | 更新技能 |
| /snap-agent/skills/{name} | DELETE | 删除技能 |

### 3.4 BridgeController

| 端点 | 方法 | 说明 |
|------|------|------|
| /snap-agent/bridge/issue/stream | GET | Issue Bridge SSE 流 |
| /snap-agent/bridge/issue/result | POST | Issue Bridge 结果回传 |
| /snap-agent/bridge/issue/status | GET | Issue Bridge 状态 |

### 3.5 LlmBridgeController

| 端点 | 方法 | 说明 |
|------|------|------|
| /snap-agent/bridge/llm/stream | GET | LLM Bridge SSE 流 |
| /snap-agent/bridge/llm/result | POST | LLM Bridge 结果回传 |
| /snap-agent/bridge/llm/status | GET | LLM Bridge 状态 |

## 4. Bridge 桥接

### 4.1 Issue Bridge

用于桥接 Issue Tracker（禅道/Jira/GitHub Issues）HTTP 请求。

**流程**：
```
Agent → IssueBridgeService → SSE → 浏览器扩展 → fetch(内网 URL) → 结果回传
```

### 4.2 LLM Bridge

用于桥接 LLM API 调用。

**流程**：
```
Agent → LlmBridgeService → SSE → 浏览器扩展 → fetch(LLM API) → 结果回传
```

## 5. CodeGraph 代码图谱

### 5.1 功能

- 扫描项目源码，提取类信息和依赖关系
- 支持调用链查询、反向调用链查询、影响范围分析
- 持久化存储到 H2 数据库

### 5.2 核心类

| 类名 | 说明 |
|------|------|
| H2CodeGraphIndex | H2 代码图谱索引实现 |
| CodeGraphTools | 代码图谱工具（提供 Agent 可调用的工具） |
| CodeGraphNode | 节点模型（类名、类型、包名、源码） |
| CodeGraphEdge | 边模型（源节点、目标节点、关系类型） |

### 5.3 工具接口

| 工具名 | 说明 |
|--------|------|
| codegraph_find_class | 查找类 |
| codegraph_call_chain | 查询调用链 |
| codegraph_reverse_call_chain | 查询反向调用链 |
| codegraph_impact_analysis | 影响范围分析 |

## 6. 配置属性

```yaml
snap-agent:
  llm:
    type: anthropic|openai|bridge  # LLM 类型
    api-key: ${LLM_API_KEY}        # API Key
    model: claude-sonnet-4-20250514  # 模型
    base-url: https://api.anthropic.com  # API 地址
  
  bridge:
    enabled: true                  # 启用 Bridge
    port: 8090                     # Bridge 端口
  
  codegraph:
    enabled: true                  # 启用 CodeGraph
    h2-url: jdbc:h2:file:./data/codegraph  # H2 数据库 URL
  
  skill:
    directory: ./skills            # 技能目录
  
  domain-knowledge:
    directory: ./knowledge         # 领域知识目录
```

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
