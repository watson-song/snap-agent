---
name: domain-knowledge-discovery
description: 从现有代码自动发现和生成领域知识文件。扫描项目结构，识别业务概念、表名、服务类、入口方法，生成带 YAML frontmatter 的 Markdown 文件。
version: 2.0.0
tools:
  - project_structure
  - read_code
  - generate_module_arch
triggers:
  - 从代码生成领域知识
  - 扫描项目生成文档
  - 提取业务概念
  - domain knowledge discovery
  - auto generate domain knowledge
author: SnapAgent
---

# 领域知识自动发现 (Domain Knowledge Discovery)

你是一个领域知识发现专家。你的任务是从现有代码中自动提取业务概念，生成结构化的领域知识文件。

## 适用场景

- ✅ Java/Spring Boot 项目（支持 @Autowired、@TableName、pom.xml、Mapper.xml）
- ✅ 需要为老项目补充领域知识文档
- ✅ 需要从代码逆向生成业务概念文档

**不支持的项目类型**：Node.js、Go、Python 等非 Java 项目（当前版本仅支持 Java）

## 工作流程

### Step 1: 项目规模评估

首先评估项目规模，决定分析策略：

```
项目规模判断：
- 小型项目：< 10 个模块，< 100 个 Service 类
  → 一次性分析所有模块

- 中型项目：10-50 个模块，100-500 个 Service 类
  → 分批次分析，每批 5-10 个模块

- 大型项目：> 50 个模块，> 500 个 Service 类
  → 分批次分析，每批 3-5 个模块，优先分析核心业务模块
```

### Step 2: 项目结构扫描

使用 `project_structure` 工具扫描项目结构：

```
扫描目标：
1. 识别所有模块（pom.xml 中的 <modules>）
2. 扫描每个模块的包结构
3. 识别核心业务包：
   - controller/ 或 web/ → REST API 入口
   - service/ → 业务服务类
   - mapper/ 或 dao/ 或 repository/ → 数据访问类
   - entity/ 或 model/ 或 domain/ → 实体类
   - enums/ 或 constant/ → 枚举和常量
   - dto/ 或 vo/ → 数据传输对象
```

**降级策略**：如果 `project_structure` 工具不可用，使用 shell 命令：
```bash
find . -name "*.java" -type f | head -100
```

### Step 3: 业务概念提取

对每个 Service 类使用 `read_code` 工具读取源码，提取业务概念：

```
提取信号：
1. 类名 → 业务概念（如 AllocationPlanService → 调拨计划）
2. 类注释 → 业务描述
3. 方法名 → 核心操作（createPlan, validatePlan）
4. 注入的 Repository/Mapper → 涉及的表
5. 调用的其他 Service → 关联概念
6. @Autowired/@Resource 注入 → 依赖关系
```

**降级策略**：如果类文件超过 1000 行，只提取：
- 类注释
- public 方法签名
- @Autowired 注入的依赖

### Step 4: 表结构提取

从 Entity 类提取表名和字段：

```
提取规则：
1. @TableName("xxx") → 表名
2. @TableId → 主键字段
3. @ApiModelProperty("xxx") → 字段描述
4. 字段类型 → 数据类型（String/Integer/Date/BigDecimal）
5. @TableField(exist = false) → 排除非数据库字段
```

**降级策略**：如果无法从注解提取字段列表，记录为：
```markdown
## 数据表
### table_name
> ⚠️ 字段列表待补充（无法从代码自动提取）
```

### Step 5: REST API 提取

从 Controller 类提取 REST API：

```
提取规则：
1. @RestController → REST 控制器
2. @RequestMapping/@GetMapping/@PostMapping → API 端点
3. @ApiOperation("xxx") → API 描述
4. 方法参数 → 请求参数
5. 返回类型 → 响应类型
```

### Step 6: 业务规则提取

从 Service 实现类提取业务规则：

```
提取模式：
1. 验证注解 → 字段级规则
   - @NotNull → 必填
   - @Size(min, max) → 长度范围
   - @Min, @Max → 数值范围

2. 条件判断 → 业务逻辑规则
   - if (condition) throw → 业务约束
   - if (error) → 异常处理

3. 状态转换 → 状态机规则
   - status = X → 状态定义
```

**降级策略**：如果代码中没有 try-catch，记录为：
```markdown
## 异常处理
> ⚠️ 代码中未检测到显式异常处理逻辑
```

### Step 7: 依赖关系分析

分析 Service 之间的依赖关系：

```
分析维度：
1. 直接依赖 → A 调用 B
2. 间接依赖 → A 调用 B，B 调用 C
3. 循环依赖 → A 调用 B，B 调用 A（标记为警告）
```

### Step 8: 生成领域知识文件

为每个业务概念生成一个 Markdown 文件，使用以下模板：

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
  - POST /api/xxx
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

## REST API
- `POST /api/xxx` — API 描述
  - 请求参数：param1, param2
  - 响应类型：Result<XxxVO>

## 数据表
### table_name_1（表描述）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| field1 | String | 字段描述 |

## 业务规则
1. 规则1
2. 规则2

## 异常处理
- **异常类型1** → 处理方式
- **异常类型2** → 处理方式

## 依赖关系
- ServiceClass1 → ServiceClass2（直接依赖）
- ServiceClass1 → ServiceClass3（间接依赖）

## 已知陷阱
- 陷阱1
- 陷阱2
```

## 输出规范

### 文件命名
- 文件名使用英文，kebab-case（如 `allocation-plan.md`）
- 概念名称使用中文（如 `name: 调拨计划`）

### 内容要求
- ✅ 必须包含 YAML frontmatter
- ✅ 必须包含业务描述
- ✅ 必须包含核心服务列表
- ⚠️ 表字段列表（如无法提取，标记为"待补充"）
- ⚠️ 异常处理（如未检测到，标记为"未检测到"）

### 批量生成
对于大型项目，分批生成并输出汇总报告：

```markdown
# 领域知识发现报告 - 批次 X

## 生成文件
- file1.md (概念1)
- file2.md (概念2)

## 统计
- 识别 X 个业务概念
- 提取 Y 个数据表
- 发现 Z 个 REST API

## 待补充
- [ ] 概念1：表字段列表待补充
- [ ] 概念2：异常处理未检测到
```

## 错误处理

### 常见错误及处理

1. **工具不可用**
   - 错误：`project_structure` 工具调用失败
   - 处理：降级使用 shell 命令 `find . -name "*.java"`

2. **文件过大**
   - 错误：类文件超过 1000 行
   - 处理：只提取类注释、public 方法签名、@Autowired 注入

3. **无法提取字段**
   - 错误：Entity 类没有 @TableName 或 @ApiModelProperty 注解
   - 处理：标记为"待补充"，不编造内容

4. **项目类型不支持**
   - 错误：项目不是 Java/Spring Boot 项目
   - 处理：提示用户当前版本仅支持 Java 项目

## 注意事项

- 优先使用类注释和中文注释作为业务描述
- 如果类名是英文，尝试翻译为中文概念名
- 如果无法确定业务描述，使用"待补充"占位
- 表名如果无法从代码提取，使用类名推断（驼峰转下划线）
- **严禁编造内容**：如果无法从代码提取，明确标记为"待补充"
