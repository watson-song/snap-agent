---
name: snap-agent-code-graph
description: 代码图谱 — 扫描、H2 持久化、热重载、查询工具、模块架构图
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 代码图谱

## 1. 架构

```
SimpleCodeGraphBuilder / AstCodeGraphBuilder → CodeGraph
    → H2CodeGraphIndex / InMemoryCodeGraphIndex
    → CodeGraphTools (LLM 查询工具)
    → CodeGraphHotReloader (文件变更自动更新)
```

## 2. 核心 SPI (core/codegraph/)

| 类 | 说明 |
|----|------|
| `CodeGraphNode` | 节点（id/type/name/packageName/className/filePath/lineNumber）|
| `CodeGraphEdge` | 边（fromId/toId/EdgeType[CALLS/IMPLEMENTS/EXTENDS/DEPENDS_ON/OVERRIDES/REFERENCES]）|
| `CodeGraph` | 不可变图（nodes + edges）|
| `CodeGraphBuilder` | 构建 SPI |
| `CodeGraphIndex` | 查询 SPI: findByName/getOutgoingEdges/findCallChain/findImpactScope |

## 3. 实现类 (boot2x/codegraph/)

| 类 | 说明 |
|----|------|
| `SimpleCodeGraphBuilder` | 正则解析 Java 源码 |
| `AstCodeGraphBuilder` | AST 解析（更精确）|
| `InMemoryCodeGraphIndex` | 内存索引 |
| `H2CodeGraphIndex` | H2 文件数据库持久化 |
| `CodeGraphHotReloader` | 监听 .java 文件变更，增量更新 |
| `AsyncCodeGraphIndex` | 异步索引包装 |
| `CodeGraphTools` | @Tool 注解，code_graph_tools 4 子工具 |
| `ModuleArchitectureTools` | @Tool，generate_module_arch Mermaid 生成 |
| `CodeGraphCli` | CLI 工具 |

## 4. 配置

```yaml
snap-agent:
  code-graph:
    enabled: false
    scan-packages:
      - cn.watsontech.snapagent
    persistence: h2          # in-memory | h2
    h2-url: jdbc:h2:file:/app/data/codegraph
    hot-reload-enabled: true
    hot-reload-poll-ms: 2000
```
