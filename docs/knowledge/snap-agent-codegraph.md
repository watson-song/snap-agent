---
name: CodeGraph 代码图谱
description: SnapAgent CodeGraph 代码图谱 — JavaParser AST 构建、类/方法/字段节点、关键代码片段离线查看、调用链/反向调用链/影响分析、H2 持久化与热重载
version: 2.1.0
tables: [CODE_GRAPH_NODES, CODE_GRAPH_EDGES]
services: [AstCodeGraphBuilder, SimpleCodeGraphBuilder, InMemoryCodeGraphIndex, H2CodeGraphIndex, CodeGraphTools]
entry_points: [CodeGraphTools.callChain(), CodeGraphTools.reverseChain(), CodeGraphTools.impactAnalysis(), CodeGraphTools.find(), CodeGraphTools.codeView()]
related_concepts: [代码分析，依赖关系，调用链，影响分析，离线代码正文]
tags: [codegraph, code-analysis, call-chain, impact-analysis, code-view]
module: snap-agent-core
author: SnapAgent
---

# CodeGraph 代码图谱

## 1. 模块概述

CodeGraph 模块扫描宿主项目源码，构建代码图谱（类 / 方法 / 字段节点 + 关系边），支持正向调用链、反向调用链、影响范围分析与模糊查找，为 LLM 提供代码级上下文。

- **构建**：默认使用 `AstCodeGraphBuilder`（JavaParser AST 解析，精确无误报），`SimpleCodeGraphBuilder`（regex）保留为 `@Deprecated` 轻量备选。
- **索引**：`InMemoryCodeGraphIndex`（内存，默认）与 `H2CodeGraphIndex`（H2 文件持久化，供 K8s/CI 无源码部署）。
- **离线代码正文**：`AstCodeGraphBuilder` 构建期抽取**完整类源码（含完整方法体）**写入 `CodeGraphNode.sourceCode`，随 H2 持久化，运行时经 `code_view` 工具离线查看，便于定位 bug。"关键点优先"由 `scanMode=skills` 的 keyword 过滤在构建前完成，而非截断方法体。
- **工具**：通过 `@Tool` 注解方法暴露给 LLM，由 `ToolCallbacks.from()` 反射注册。

## 2. 核心 SPI

### 2.1 CodeGraphBuilder（构建器 SPI）

```java
public interface CodeGraphBuilder {
    CodeGraph build();   // 解析源码构建图谱（可能为空，永不为 null）
    String type();       // 实现标识："javaparser"（默认）/ "regex"（备选）
}
```

### 2.2 CodeGraphIndex（索引 SPI）

```java
public interface CodeGraphIndex {
    List<CodeGraphNode> findByName(String namePattern);          // 按名称模糊匹配（子串、不区分大小写）
    List<CodeGraphEdge> getOutgoingEdges(String nodeId);         // 出边
    List<CodeGraphEdge> getIncomingEdges(String nodeId);         // 入边
    List<CodeGraphNode> findCallChain(String methodId, int maxDepth);        // 正向调用链（沿 CALLS 出边 BFS）
    List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth); // 反向调用链（沿 CALLS 入边 BFS）
    List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth);        // 影响范围（沿所有入边 BFS）
    CodeGraphNode getNode(String id);                            // 按 ID 取节点（未命中返回 null）
    int nodeCount();                                             // 节点总数
    void rebuild(CodeGraphBuilder builder);                      // 热重载时全量重建
}
```

### 2.3 CodeGraphNode（节点模型）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 唯一标识。类=`com.example.Foo`；方法=`com.example.Foo#bar(String)`；字段=`com.example.Foo#fieldName` |
| type | enum | `CLASS` / `METHOD` / `FIELD` |
| name | String | 简短名称（类名 / 方法名 / 字段名） |
| packageName | String | 包名 |
| className | String | 所属类 FQCN |
| returnType | String | 返回类型（方法节点） |
| filePath | String | 源文件相对路径 |
| lineNumber | int | 行号 |
| sourceCode | String | 完整类源码（可 null）。构建期抽取：完整类声明 + 字段 + **完整方法体**，上限 64KB（仅防病态单类）。用于无源码运行时离线诊断，能看到具体逻辑以定位 bug |

### 2.4 CodeGraphEdge（边模型）

| 字段 | 类型 | 说明 |
|------|------|------|
| fromId | String | 源节点 ID |
| toId | String | 目标节点 ID |
| type | enum | `CALLS` / `EXTENDS` / `IMPLEMENTS` / `DEPENDS_ON` / `OVERRIDES` / `REFERENCES` |
| context | String | 位置信息（`file:line`） |

实际由 `AstCodeGraphBuilder` 产生的边类型：`EXTENDS`（继承）、`IMPLEMENTS`（实现接口）、`CALLS`（方法调用）、`DEPENDS_ON`（字段类型 / 方法参数类型依赖）。

## 3. 数据库表结构（H2 持久化）

`H2CodeGraphIndex` 建表如下（表名大写）：

```sql
CREATE TABLE CODE_GRAPH_NODES (
    ID VARCHAR PRIMARY KEY,
    TYPE VARCHAR,
    NAME VARCHAR,
    PACKAGE VARCHAR,
    CLASS_NAME VARCHAR,
    RETURN_TYPE VARCHAR,
    FILE_PATH VARCHAR,
    LINE_NUMBER INT,
    SOURCE_CODE CLOB
);

CREATE TABLE CODE_GRAPH_EDGES (
    FROM_ID VARCHAR,
    TO_ID VARCHAR,
    EDGE_TYPE VARCHAR,
    CONTEXT VARCHAR
);

CREATE INDEX IDX_EDGES_FROM ON CODE_GRAPH_EDGES(FROM_ID);
CREATE INDEX IDX_EDGES_TO   ON CODE_GRAPH_EDGES(TO_ID);
CREATE INDEX IDX_NODES_NAME ON CODE_GRAPH_NODES(NAME);
```

> **迁移**：旧版 H2 库（无 `SOURCE_CODE` 列）打开时，`H2CodeGraphIndex` 自动执行 `ALTER TABLE CODE_GRAPH_NODES ADD COLUMN IF NOT EXISTS SOURCE_CODE CLOB`，旧节点 `sourceCode` 为 null，不影响查询。

## 4. 生成与持久化

### 4.1 运行期构建

- 配置 `snap-agent.code-graph.enabled=true` 后，`KnowledgeAutoConfiguration` 装配 `AstCodeGraphBuilder` 与 `CodeGraphIndex`。
- `persistence=memory`（默认）：每次启动全量扫描源码到 `InMemoryCodeGraphIndex`。
- `persistence=h2`：启动时从 `h2-url` 指向的 H2 文件加载，不扫描源码。

### 4.2 CI 预构建

在有源码的 CI 阶段，用 `CodeGraphCli` 预构建 H2 图谱文件：

```bash
java -cp "snap-agent-spring-boot-2x-starter/target/*.jar:snap-agent-core/target/*.jar" \
  cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
  --project-root . \
  --scan-packages com.yourcompany \
  --output ./data/codegraph
```

产出 `data/codegraph.mv.db`，运行时 `H2CodeGraphIndex` 直接加载。

### 4.3 热重载

`CodeGraphHotReloader` 用 `WatchService` 监听源码 `.java` 变更，触发 `CodeGraphIndex#rebuild(CodeGraphBuilder)` 增量重建。仅在本地有源码时有效，默认关闭（`snap-agent.code-graph.hot-reload-enabled=false`）。

## 5. 工具（@Tool，暴露给 LLM）

`CodeGraphTools` 通过 `@Tool` 注解方法暴露，由 `ToolCallbacks.from()` 反射注册：

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `call_chain` | 正向调用链 | `query`（方法签名或方法名，必填）、`max_depth`（可选，默认5） |
| `reverse_chain` | 反向调用链 | `query`（必填）、`max_depth`（可选，默认5） |
| `impact_analysis` | 变更影响范围 | `query`（类名或方法签名，必填）、`max_depth`（可选，默认3） |
| `find` | 按名称模糊查找节点 | `query`（必填，模糊匹配、不区分大小写） |
| `code_view` | 查看关键业务代码（离线，含完整方法体） | `query`（类名或方法签名，必填） |
| `render_call_graph` | 渲染可交互 HTML 调用图（Mermaid.js） | `query`、`graph_type`（call_chain/reverse_chain/impact）、`max_depth` |

## 6. 查询示例

```java
// 模糊查找
List<CodeGraphNode> nodes = index.findByName("inventory");

// 正向调用链：Foo#bar() 调用了哪些方法
List<CodeGraphNode> chain = index.findCallChain("com.example.Foo#bar(String)", 5);

// 反向调用链：谁调用了 SafetyStockMapper#query()
List<CodeGraphNode> callers = index.findReverseCallChain("com.example.SafetyStockMapper#query()", 5);

// 影响范围：修改 InventoryService 影响哪些节点
List<CodeGraphNode> impacted = index.findImpactScope("com.example.InventoryService", 3);

// 离线查看关键业务代码（运行时无源码，含完整方法体）
List<CodeGraphNode> nodes = index.findByName("OrderService");
String excerpt = nodes.get(0).getSourceCode();  // 完整类源码，可定位 bug
```

## 7. 与领域知识的集成

```
1. 领域知识文档（.md frontmatter）定义 services 字段（如 AllocationPlanService）
2. CodeGraph 存储这些类的代码结构与依赖关系
3. Agent 查询领域知识时，可结合 CodeGraph 工具获取调用链与影响面
4. 支持更精确的代码分析与诊断
```

## 8. 配置

```yaml
snap-agent:
  code-graph:
    enabled: true                    # 是否启用（默认 false）
    scan-packages: [com.yourcompany] # 扫描包前缀（空=全部）
    scan-mode: all                   # all / skills / packages
    persistence: h2                  # memory（默认）/ h2
    h2-url: jdbc:h2:file:./data/codegraph
    hot-reload-enabled: false        # 生产环境关闭热重载
```

---

**文档版本**: 2.1.0
**最后更新**: 2026-09-11
