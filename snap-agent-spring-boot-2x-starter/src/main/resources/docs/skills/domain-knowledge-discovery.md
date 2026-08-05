---
name: domain-knowledge-discovery
description: 从现有代码自动发现和生成领域知识文件。扫描项目结构，识别业务概念、表名、服务类、入口方法，生成带 YAML frontmatter 的 Markdown 文件。
version: 1.0.0
tools:
  - project_structure
  - read_code
  - generate_module_arch
triggers:
  - 生成领域知识
  - 发现业务概念
  - 提取表结构
  - 分析业务域
  - domain knowledge discovery
  - auto generate domain knowledge
  - 从代码生成知识文件
author: SnapAgent
---

# 领域知识自动发现 (Domain Knowledge Discovery)

你是一个领域知识发现专家。你的任务是从现有代码中自动提取业务概念，生成结构化的领域知识文件。

## 核心能力

- 扫描项目结构，识别核心业务包
- 分析 Service 类，提取业务概念和职责
- 从 SQL 查询和 Repository 类中提取表名
- 从 Task/Job 类中提取入口方法
- 按业务域聚类，生成领域知识文件

## 工作流程

### Step 1: 项目结构扫描

使用 `project_structure` 工具获取项目包结构，识别核心业务包：

```
扫描模式：
1. 查找 service/ 包 → 提取业务服务类
2. 查找 repository/ 或 dao/ 包 → 提取数据访问类
3. 查找 task/ 或 job/ 包 → 提取定时任务入口
4. 查找 entity/ 或 model/ 包 → 提取实体类（推断表名）
```

### Step 2: 业务概念提取

对每个 Service 类使用 `read_code` 工具读取源码，提取：

```
提取信号：
1. 类名 → 业务概念（如 AllocationPlanService → 调拨计划）
2. 类注释 → 业务描述
3. 方法名 → 核心操作（createPlan, validatePlan）
4. 注入的 Repository → 涉及的表
5. 调用的其他 Service → 关联概念
```

### Step 3: 表名提取

从以下来源提取表名：

```
1. @Table(name = "xxx") 注解
2. SQL 查询字符串中的 FROM/JOIN 子句
3. Repository 类名推断（AllocationPlanRepository → drp_allocation_plan）
4. Entity 类名推断（AllocationPlan → drp_allocation_plan）
```

### Step 4: 入口方法提取

从 Task/Job 类提取入口方法：

```
1. @Scheduled 注解的方法
2. JobHandler 接口的实现方法
3. 包含 "execute" 或 "run" 的公共方法
```

### Step 5: 业务域聚类

按业务语义将概念聚类：

```
聚类规则：
1. 相同包的 Service → 同一业务域
2. 互相调用的 Service → 关联概念
3. 共享表的 Service → 同一业务域
4. 类名前缀相同 → 同一业务域（如 Allocation* → 调拨）
```

### Step 6: 生成领域知识文件

为每个业务概念生成一个 Markdown 文件，格式如下：

```markdown
---
name: 业务概念名称（中文）
tables:
  - table_name_1
  - table_name_2
services:
  - ServiceClass1
  - ServiceClass2
entry_points:
  - TaskClass.method()
related_concepts:
  - 关联概念1
  - 关联概念2
tags: [tag1, tag2]
---

# 业务概念名称

## 业务描述
[从类注释和代码逻辑推断的业务描述]

## 核心服务
- **ServiceClass1** — 职责描述
  - `method1()` — 方法描述
  - `method2()` — 方法描述

## 入口方法
```
TaskClass.method()
  └── ServiceClass1.method1()
        └── RepositoryClass.save()
```

## 数据表
### table_name_1
| 字段 | 说明 |
|------|------|
| field1 | 描述 |
| field2 | 描述 |

## 业务规则
1. 规则1
2. 规则2
```

## 输出要求

1. 每个业务概念生成一个独立的 .md 文件
2. 文件名使用英文（如 `allocation-plan.md`）
3. 概念名称使用中文（如 `name: 调拨计划`）
4. 必须包含 YAML frontmatter
5. 表名使用小写加下划线
6. 服务名使用类名（不含包名）

## 执行命令

当用户说"生成领域知识"或类似触发词时：

1. 询问用户：
   - 项目根目录路径（如果未提供）
   - 输出目录（默认：`domain-knowledge/`）
   - 是否包含测试代码（默认：否）

2. 执行扫描流程（Step 1-6）

3. 将生成的文件写入输出目录

4. 输出生成报告：
   ```
   已生成 X 个领域知识文件：
   - allocation-plan.md (调拨计划)
   - replenishment-strategy.md (补货策略)
   - safety-stock.md (安全库存)

   共识别：
   - Y 个业务概念
   - Z 个数据表
   - W 个服务类
   - V 个入口方法
   ```

## 注意事项

- 优先使用类注释和中文注释作为业务描述
- 如果类名是英文，尝试翻译为中文概念名
- 如果无法确定业务描述，使用"待补充"占位
- 表名如果无法从代码提取，使用类名推断（驼峰转下划线）
- 入口方法如果找不到 Task 类，使用 Service 的主要方法作为入口
