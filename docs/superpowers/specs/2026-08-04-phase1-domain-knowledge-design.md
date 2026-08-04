# Phase 1: 领域知识加载器设计

> 日期: 2026-08-04 | 状态: 待实现 | 依赖: 2026-08-04-domain-cognition-engine.md

## 1. 概述

实现领域知识加载器（`DomainKnowledgeLoader`）和查询工具（`DomainKnowledgeTools`），
使 Agent 能够理解业务概念与代码之间的映射关系。

## 2. 架构

```
domain-knowledge/                    ← 开发者维护的知识文件
├── allocation-plan.md               ← YAML frontmatter + Markdown body
├── replenishment-strategy.md
└── safety-stock.md
         │
         ▼
┌─────────────────────┐
│  DomainKnowledge    │
│  Loader             │
│  ┌───────────────┐  │
│  │ Frontmatter   │  │ ← 解析 YAML header
│  │ Parser        │  │
│  └───────┬───────┘  │
│          │          │
│  ┌───────▼───────┐  │
│  │ Document      │  │ ← 生成 Document (metadata 含结构化数据)
│  │ Builder       │  │
│  └───────┬───────┘  │
│          │          │
│  ┌───────▼───────┐  │
│  │ VectorStore   │  │ ← 存入向量库 (source: "domain-knowledge")
│  │ + Domain      │  │
│  │   Knowledge   │  │ ← 同时维护内存索引 (概念名→tables/services 映射)
│  │   Index       │  │
│  └───────────────┘  │
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│  DomainKnowledge    │
│  Tools              │
│  ┌───────────────┐  │
│  │ lookup_concept│  │ ← 按概念名查询详情
│  │ find_by_table │  │ ← 按表名反查概念
│  │ find_by_class │  │ ← 按类名反查概念
│  │ list_concepts │  │ ← 列出所有概念
│  └───────────────┘  │
└─────────────────────┘
```

## 3. 核心接口

### 3.1 DomainKnowledge (core SPI)

```java
/**
 * Represents a domain concept with structured metadata.
 * Parsed from Markdown files with YAML frontmatter.
 */
public class DomainKnowledge {
    private final String name;                    // "调拨计划"
    private final String content;                 // Markdown body
    private final List<String> tables;            // ["drp_allocation_plan"]
    private final List<String> services;          // ["AllocationPlanService"]
    private final List<String> entryPoints;       // ["ReplenishmentPlanTask.generate()"]
    private final List<String> relatedConcepts;   // ["补货策略", "安全库存"]
    private final List<String> tags;              // ["replenishment", "allocation"]
    private final Map<String, Object> rawMetadata; // all frontmatter fields
}
```

### 3.2 DomainKnowledgeIndex (core SPI)

```java
/**
 * In-memory index for domain knowledge lookups.
 * Maintains reverse mappings: table→concept, service→concept.
 */
public interface DomainKnowledgeIndex {
    DomainKnowledge findByName(String conceptName);
    List<DomainKnowledge> findByTable(String tableName);
    List<DomainKnowledge> findByService(String serviceName);
    List<DomainKnowledge> findAll();
    int size();
}
```

### 3.3 DomainKnowledgeLoader (starter)

```java
/**
 * Loads domain knowledge from Markdown files with YAML frontmatter.
 * Stores documents in VectorStore for RAG and maintains DomainKnowledgeIndex.
 */
public class DomainKnowledgeLoader {
    List<DomainKnowledge> loadFromDirectory(Path dir);
    DomainKnowledge parseFile(Path file);  // parse single file
}
```

### 3.4 DomainKnowledgeTools (starter @Tool)

```java
@Tool(name = "lookup_concept")
String lookupConcept(String conceptName);

@Tool(name = "find_by_table")
String findByTable(String tableName);

@Tool(name = "find_by_class")
String findByClass(String className);

@Tool(name = "list_concepts")
String listConcepts();
```

## 4. 文件格式规范

```markdown
---
name: 调拨计划                    # 必填：概念名称
tables:                           # 可选：涉及的数据库表
  - drp_allocation_plan
  - drp_allocation_detail
services:                         # 可选：涉及的 Java 类
  - AllocationPlanService
  - BalanceAlgorithmService
entry_points:                     # 可选：入口方法
  - ReplenishmentPlanTask.generate()
related_concepts:                 # 可选：关联概念
  - 补货策略
  - 安全库存
tags: [replenishment, allocation] # 可选：标签
---

# 调拨计划

## 业务描述
航材消耗件在多基地间的库存平衡调拨...

## 业务规则
1. 只有航材消耗件才走多基地平衡算法
2. 调拨数量 = max(0, 安全库存 - 可用库存)

## 已知陷阱
- 并发场景下 getAvailableStock() 可能返回过期数据

## SQL 示例
```sql
SELECT * FROM drp_allocation_plan
WHERE sku_code = 'A123'
```
```

## 5. 与现有系统的集成

| 现有组件 | 集成方式 |
|---------|---------|
| `MarkdownDocumentReader` | 复用，直接读取 .md 文件 |
| `VectorStore` | 存入 Document，metadata.source = "domain-knowledge" |
| `KnowledgeETLPipeline` | 启动时调用 DomainKnowledgeLoader |
| `CodeGraphIndex` | 独立索引，不修改 CodeGraph |
| `KnowledgeRestController` | 新增 `/knowledge/domain/status` 端点 |
| 前端知识库弹框 | 新增"领域知识"Tab |

## 6. TDD Spec

### US-1: DomainKnowledgeLoader

| AC | Given | When | Then |
|----|-------|------|------|
| AC1 | .md 文件含 YAML frontmatter | `parseFile()` | 返回 DomainKnowledge，tables/services 正确解析 |
| AC2 | .md 文件无 frontmatter | `parseFile()` | 返回 null 或空 DomainKnowledge |
| AC3 | 目录含多个 .md 文件 | `loadFromDirectory()` | 返回所有有效概念 |
| AC4 | 目录不存在 | `loadFromDirectory()` | 返回空列表 |

### US-2: DomainKnowledgeIndex

| AC | Given | When | Then |
|----|-------|------|------|
| AC5 | Index 含 "调拨计划" 概念 | `findByName("调拨计划")` | 返回正确概念 |
| AC6 | "调拨计划" 涉及 drp_allocation_plan | `findByTable("drp_allocation_plan")` | 返回 "调拨计划" |
| AC7 | "调拨计划" 涉及 AllocationPlanService | `findByService("AllocationPlanService")` | 返回 "调拨计划" |
| AC8 | Index 含 3 个概念 | `findAll()` | 返回 3 个 |

### US-3: DomainKnowledgeTools

| AC | Given | When | Then |
|----|-------|------|------|
| AC9 | Index 含 "调拨计划" | `lookup_concept("调拨计划")` | 返回概念详情（表、类、规则） |
| AC10 | "调拨计划" 涉及 drp_allocation_plan | `find_by_table("drp_allocation_plan")` | 返回 "调拨计划" |
| AC11 | Index 含 3 个概念 | `list_concepts()` | 返回 3 个概念名 |
| AC12 | 概念不存在 | `lookup_concept("不存在")` | 返回 "未找到" |

## 7. 文件布局

```
snap-agent-core/src/main/java/.../domain/
├── DomainKnowledge.java           ← 值对象
└── DomainKnowledgeIndex.java      ← SPI 接口

snap-agent-spring-boot-2x-starter/src/main/java/.../domain/
├── DomainKnowledgeLoader.java     ← 文件解析 + 加载
├── InMemoryDomainKnowledgeIndex.java ← 默认实现
└── DomainKnowledgeTools.java      ← @Tool 方法

snap-agent-spring-boot-2x-starter/src/main/java/.../autoconfig/
└── DomainKnowledgeAutoConfiguration.java ← Bean 装配

snap-agent-core/src/test/java/.../domain/
└── DomainKnowledgeTest.java

snap-agent-spring-boot-2x-starter/src/test/java/.../domain/
├── DomainKnowledgeLoaderTest.java
├── InMemoryDomainKnowledgeIndexTest.java
└── DomainKnowledgeToolsTest.java
```

## 8. 配置

```yaml
snap-agent:
  domain-knowledge:
    enabled: true
    dir: ${upload-skills-dir}/domain-knowledge  # 知识文件目录
    auto-load-on-startup: true                   # 启动时自动加载
```
