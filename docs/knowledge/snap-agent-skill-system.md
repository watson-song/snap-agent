---
name: 技能系统
description: SnapAgent 技能系统 — 技能加载、注册、查询、Markdown 格式技能定义
version: 1.0.0
tables: []
services: [SkillRegistry, SkillLoader, SkillMeta]
entry_points: [SkillRegistry.load(), SkillRegistry.query(), SkillRegistry.getByName()]
related_concepts: [领域知识，工具调用，Agent 执行]
tags: [skill, markdown, loading, registry]
version: 1.0.0
module: snap-agent-core
author: SnapAgent
---

# 技能系统

## 1. 模块概述

技能系统是 SnapAgent 的核心扩展机制，通过 Markdown 文件定义技能，支持自动加载和语义检索。

## 2. 核心类

### 2.1 SkillMeta (技能元数据)

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 技能名称 |
| description | String | 技能描述 |
| content | String | 技能内容（Markdown body） |
| tables | List\<String\> | 涉及的数据库表 |
| services | List\<String\> | 涉及的服务类 |
| entryPoints | List\<String\> | 入口方法或 API 端点 |
| relatedConcepts | List\<String\> | 关联概念 |
| tags | List\<String\> | 标签列表 |
| frontmatter | Map\<String, Object\> | 原始 frontmatter 数据 |

### 2.2 SkillRegistry (技能注册表)

| 方法 | 说明 |
|------|------|
| load(Path directory) | 从目录加载技能 |
| query(String keyword) | 语义检索技能 |
| getByName(String name) | 按名称查询技能 |
| getByTag(String tag) | 按标签查询技能 |
| getAll() | 获取所有技能 |

### 2.3 SkillLoader (技能加载器)

| 方法 | 说明 |
|------|------|
| parseFile(Path file) | 解析 Markdown 文件 |
| parseContent(String content) | 解析 Markdown 内容 |
| parseFrontmatter(String frontmatter) | 解析 YAML frontmatter |

## 3. 技能格式

### 3.1 Markdown 技能文件

```markdown
---
name: 技能名称
description: 技能描述（50 字以内）
tables: [table1, table2]
services: [ServiceA, ServiceB]
entry_points: [POST /api/xxx, XxxTask.run()]
related_concepts: [概念 A, 概念 B]
tags: [tag1, tag2]
version: 1.0.0
module: 所属模块
author: 作者
---

# 技能名称

## 技能描述
技能详细描述...

## 使用场景
- 场景 1
- 场景 2

## 使用示例
示例代码...
```

### 3.2 Frontmatter 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| name | 是 | 技能名称（中文） |
| description | 是 | 一句话描述 |
| tables | 是 | 涉及的表名列表 |
| services | 是 | 涉及的服务类列表 |
| entry_points | 是 | 入口方法或 API 端点 |
| related_concepts | 是 | 关联概念 |
| tags | 是 | 标签（英文 kebab-case） |
| version | 是 | 版本号 |
| module | 是 | 所属模块 |
| author | 是 | 作者 |

## 4. 加载流程

```
1. SkillRegistry.load(directory)
2. 扫描 directory 下所有 *.md 文件
3. SkillLoader.parseFile(file) 解析每个文件
4. 提取 frontmatter 和 body
5. 创建 SkillMeta 对象
6. 注册到 SkillRegistry
7. 建立索引（按名称、标签、语义）
```

## 5. 查询方式

### 5.1 按名称查询
```java
SkillMeta skill = registry.getByName("补货计划");
```

### 5.2 按标签查询
```java
List<SkillMeta> skills = registry.getByTag("replenishment");
```

### 5.3 语义检索
```java
List<SkillMeta> skills = registry.query("如何生成补货计划？");
```

## 6. 与领域知识的关系

技能系统和领域知识系统都使用类似的 Markdown + frontmatter 格式：

| 特性 | 技能系统 | 领域知识系统 |
|------|----------|------------|
| 文件格式 | *.md | *.md |
| Frontmatter | name, description, tables, services, entry_points, tags | name, description, tables, services, entry_points, tags |
| 加载器 | SkillLoader | DomainKnowledgeLoader |
| 注册表 | SkillRegistry | DomainKnowledgeIndex |
| 用途 | Agent 执行时的技能选择 | RAG 检索时的知识召回 |

## 7. 扩展示例

创建新技能文件 `skills/my-skill.md`：

```markdown
---
name: 数据导出
description: 将查询结果导出为 Excel 文件
tables: [drp_export_task, drp_export_record]
services: [IExportService, IExcelGeneratorService]
entry_points: [POST /api/export/excel, ExportTask.run()]
related_concepts: [数据查询，文件生成]
tags: [export, excel, file]
version: 1.0.0
module: data-export
author: Developer
---

# 数据导出

## 技能描述
将查询结果导出为 Excel 文件，支持自定义列和格式。

## 使用场景
- 导出报表数据
- 导出分析结果
- 批量数据导出

## 使用示例
调用 `IExportService.export()` 方法...
```

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
