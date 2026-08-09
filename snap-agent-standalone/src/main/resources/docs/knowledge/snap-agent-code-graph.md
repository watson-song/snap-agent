---
name: snap-agent-code-graph
description: CodeGraph 代码图谱 — 扫描、持久化、热重载、查询
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# CodeGraph 代码图谱

## 1. 功能概述

CodeGraph 扫描项目源码，构建代码实体关系图谱，用于：
- 代码理解与导航
- 依赖分析
- 影响范围评估
- AI 辅助开发上下文

## 2. 架构

```
CodeGraphScanner
  ── scan(packages)
        └── 扫描指定包下的所有类
              ├── 类 → 节点
              ├── 继承 → 边
              ├── 实现 → 边
              ├── 依赖 → 边
              └── 注解 → 属性

CodeGraphStore (持久化)
  ── H2 文件数据库
  ── 节点表：class_name, type, package, methods, fields
  ── 边表：source, target, relation_type

CodeGraphQueryService
  ── findClass(name)
  ├── findDependencies(name)
  ├── findDependents(name)
  └── findInfluences(name)
```

## 3. 配置

```yaml
snap-agent:
  code-graph:
    enabled: true
    scan-packages:
      - cn.watsontech.snapagent
    persistence: h2
    h2-url: jdbc:h2:file:/app/data/codegraph
    hot-reload-enabled: true
    hot-reload-poll-ms: 2000
```

## 4. 扫描范围

### 默认扫描的包

```
cn.watsontech.snapagent.core
  ├── agent/          # Agent 引擎
  ├── llm/            # LLM SPI
  ├── security/       # 安全接口
  ── conversation/   # 对话管理

cn.watsontech.snapagent.boot2x
  ├── autoconfig/     # 自动配置
  ├── web/            # Web 控制器
  ├── llm/            # LLM 实现
  ├── tool/           # 工具实现
  └── bridge/         # 桥接系统
```

### 扫描的实体类型

| 类型 | 说明 | 示例 |
|------|------|------|
| `CLASS` | Java 类 | `AnthropicLlmClient` |
| `INTERFACE` | 接口 | `LlmClient` |
| `ENUM` | 枚举 | `TaskStatus` |
| `ANNOTATION` | 注解 | `@Tool` |

## 5. 关系类型

| 关系 | 说明 | 示例 |
|------|------|------|
| `EXTENDS` | 继承 | `OpenAiLlmClient extends AbstractStreamingLlmClient` |
| `IMPLEMENTS` | 实现 | `AnthropicLlmClient implements LlmClient` |
| `DEPENDS_ON` | 依赖 | `AgentService depends on LlmClient` |
| `USES` | 使用 | `Controller uses Service` |

## 6. 持久化

### H2 文件数据库

```
/app/data/codegraph.mv.db
```

**优点**：
- 启动时加载，无需重新扫描
- 支持增量更新
- 查询性能好

### 热重载

```yaml
hot-reload-enabled: true
hot-reload-poll-ms: 2000  # 2 秒轮询
```

**检测机制**：
- 监听 `.class` 文件变化
- 增量更新图谱
- 无需重启应用

## 7. 查询 API

### 通过 Skill 查询

```
使用 code-analysis skill:

输入：
- query: "找出所有 LlmClient 的实现类"

输出：
- AnthropicLlmClient
- OpenAiLlmClient  
- BridgeLlmClient
- CostTrackingLlmClient
```

### 通过代码图谱工具

```
工具：code_graph_query

输入：
- action: find_class / find_deps / find_dependents
- class_name: cn.watsontech.snapagent.boot2x.llm.AnthropicLlmClient

输出：
- 类信息 + 关系列表
```

## 8. 性能指标

| 指标 | 数值 | 说明 |
|------|------|------|
| 扫描速度 | ~500 类/秒 | 首次扫描 |
| 内存占用 | ~50MB | 335 个类 |
| 查询延迟 | <10ms | 单类查询 |
| 存储大小 | ~5MB | H2 数据库 |

## 9. 使用场景

### 场景 1：代码理解

```
用户：这个项目的核心架构是什么？

Agent：
1. 查询 code_graph，获取顶层模块
2. 分析模块间依赖关系
3. 生成架构图
```

### 场景 2：影响分析

```
用户：修改 LlmClient 接口会影响哪些类？

Agent：
1. 查询 find_dependents(LlmClient)
2. 返回所有实现类和依赖类
3. 评估影响范围
```

### 场景 3：依赖导航

```
用户：BridgeLlmClient 依赖哪些服务？

Agent：
1. 查询 find_deps(BridgeLlmClient)
2. 返回依赖列表
3. 可视化依赖图
```
