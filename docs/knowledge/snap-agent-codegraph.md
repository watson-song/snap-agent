---
name: CodeGraph 代码图谱
description: SnapAgent CodeGraph 代码图谱 — 类信息扫描、依赖关系提取、调用链查询、影响范围分析
version: 1.0.0
tables: [code_graph_nodes, code_graph_edges]
services: [CodeGraphIndex, H2CodeGraphIndex, CodeGraphTools, CodeGraphNode, CodeGraphEdge]
entry_points: [CodeGraphTools.findClass(), CodeGraphTools.callChain(), CodeGraphTools.reverseCallChain(), CodeGraphTools.impactAnalysis()]
related_concepts: [代码分析，依赖关系，调用链，影响分析]
tags: [codegraph, code-analysis, call-chain, impact-analysis]
version: 1.0.0
module: snap-agent-core
author: SnapAgent
---

# CodeGraph 代码图谱

## 1. 模块概述

CodeGraph 代码图谱模块扫描项目源码，提取类信息和依赖关系，支持调用链查询、反向调用链查询、影响范围分析。

## 2. 核心类

### 2.1 CodeGraphIndex (代码图谱索引 SPI)

```java
public interface CodeGraphIndex {
    /** 查找类 */
    CodeGraphNode findClass(String className);
    
    /** 查询调用链 */
    List<CodeGraphNode> callChain(String className, int maxDepth);
    
    /** 查询反向调用链 */
    List<CodeGraphNode> reverseCallChain(String className, int maxDepth);
    
    /** 影响范围分析 */
    Set<String> impactAnalysis(String className, int maxDepth);
    
    /** 添加节点 */
    void addNode(CodeGraphNode node);
    
    /** 添加边 */
    void addEdge(CodeGraphEdge edge);
}
```

### 2.2 H2CodeGraphIndex (H2 实现)

| 方法 | 说明 |
|------|------|
| findClass(className) | 从 H2 数据库查询类信息 |
| callChain(className, maxDepth) | BFS 查询调用链 |
| reverseCallChain(className, maxDepth) | BFS 查询反向调用链 |
| impactAnalysis(className, maxDepth) | 查询所有依赖该类的类 |

### 2.3 CodeGraphNode (节点模型)

| 字段 | 类型 | 说明 |
|------|------|------|
| nodeId | String | 节点 ID（全限定类名） |
| name | String | 类名 |
| type | String | 类型（Controller/Service/Entity/Mapper/Class） |
| packageName | String | 包名 |
| sourceCode | String | 关键代码片段 |
| filePath | String | 文件路径 |

### 2.4 CodeGraphEdge (边模型)

| 字段 | 类型 | 说明 |
|------|------|------|
| sourceId | String | 源节点 ID |
| targetId | String | 目标节点 ID |
| type | String | 关系类型（IMPORTS/DEPENDS_ON） |

## 3. 数据库表结构

### 3.1 code_graph_nodes 表

```sql
CREATE TABLE code_graph_nodes (
    node_id VARCHAR(512) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    type VARCHAR(64) NOT NULL,
    package_name VARCHAR(512),
    source_code CLOB,
    file_path VARCHAR(1024)
);

CREATE INDEX idx_nodes_name ON code_graph_nodes(name);
CREATE INDEX idx_nodes_type ON code_graph_nodes(type);
CREATE INDEX idx_nodes_package ON code_graph_nodes(package_name);
```

### 3.2 code_graph_edges 表

```sql
CREATE TABLE code_graph_edges (
    source_id VARCHAR(512) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    type VARCHAR(64) NOT NULL,
    PRIMARY KEY (source_id, target_id, type)
);

CREATE INDEX idx_edges_source ON code_graph_edges(source_id);
CREATE INDEX idx_edges_target ON code_graph_edges(target_id);
```

## 4. 生成流程

### 4.1 源码扫描

```
1. 扫描所有 src/main/java 目录
2. 解析每个 Java 文件
3. 提取类信息（类名、类型、包名）
4. 提取依赖关系（import、@Autowired、@Resource）
5. 提取关键代码（类注释、字段、public 方法签名）
```

### 4.2 数据存储

```
1. 创建 H2 数据库连接
2. 创建 nodes 和 edges 表
3. 插入节点数据
4. 插入边数据
5. 创建索引
```

### 4.3 生成脚本

```python
def generate_codegraph(project_root, output_dir):
    # 1. 扫描 Java 文件
    nodes = scan_java_files(project_root)
    
    # 2. 提取依赖关系
    edges = extract_dependencies(nodes)
    
    # 3. 写入 H2 数据库
    write_to_h2(output_dir, nodes, edges)
    
    # 4. 输出 CSV 文件（可选）
    write_csv(output_dir, nodes, edges)
```

## 5. 查询示例

### 5.1 查找类

```java
CodeGraphNode node = codeGraphIndex.findClass("ReplenishmentPlanService");
// 返回类信息：名称、类型、包名、关键代码
```

### 5.2 查询调用链

```java
List<CodeGraphNode> chain = codeGraphIndex.callChain("ReplenishmentPlanService", 3);
// 返回：ReplenishmentPlanService → ISafetyStockService → SafetyStockMapper
```

### 5.3 查询反向调用链

```java
List<CodeGraphNode> chain = codeGraphIndex.reverseCallChain("SafetyStockMapper", 3);
// 返回：SafetyStockMapper ← ISafetyStockService ← ReplenishmentPlanService
```

### 5.4 影响范围分析

```java
Set<String> impacted = codeGraphIndex.impactAnalysis("SafetyStockMapper", 3);
// 返回所有依赖该 Mapper 的类名集合
```

## 6. 与领域知识的集成

CodeGraph 与领域知识系统协同工作：

```
1. 领域知识文档定义 services 字段
2. CodeGraph 存储这些服务的代码和依赖关系
3. Agent 查询领域知识时，同时获取相关类的代码信息
4. 支持更精确的代码分析和诊断
```

## 7. 配置

```yaml
snap-agent:
  codegraph:
    enabled: true
    h2-url: jdbc:h2:file:./data/codegraph
    source-dirs:
      - ./src/main/java
    class-types:
      - Controller
      - Service
      - Entity
      - Mapper
```

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
