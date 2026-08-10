---
name: domain-knowledge-discovery
description: 集成阶段知识生成工具 — 扫描宿主项目源码，提取业务概念、表结构、服务依赖，生成知识库 .md 文件供运行时 RAG 检索
version: 3.0.0
type: integration-tool
triggers:
  - 从代码生成领域知识
  - 扫描项目生成文档
  - 提取业务概念
  - domain knowledge discovery
  - auto generate domain knowledge
author: SnapAgent
---

# 领域知识自动发现 (Domain Knowledge Discovery)

> **定位**：集成阶段工具，在宿主项目本地执行，直接读取源码文件系统，生成知识库 .md 文件。
> **不是**运行时 Agent Skill，不参与 Agent 执行循环。
> **产出**：`{output-dir}/*.md`，部署时打包进 `classpath:/docs/knowledge/`。

你是一个领域知识发现专家。你的任务是从宿主项目源码中自动提取业务概念，生成结构化的领域知识文件。

## 核心规则（最高优先级）

1. **所有代码内容必须来自实际源文件** — 直接读取项目源码，严禁编造任何类名、方法名、配置项
2. **读不到就标记"待补充"** — 绝不为了填满模板而编造内容
3. **动态输出 section** — 没有的内容直接省略对应 section，不用占位
4. **扫描目标 = 宿主项目** — 排除 SnapAgent 自身的包（`cn.watsontech.snapagent.*`）

## 输入参数

| 参数 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `project_root` | 是 | 宿主项目根目录绝对路径 | `/Users/xxx/my-project` |
| `output_dir` | 是 | 知识文件输出目录 | `src/main/resources/docs/knowledge` |
| `scan_packages` | 否 | 限定扫描的包前缀 | `com.example.biz` |
| `exclude_packages` | 否 | 排除的包前缀 | `com.example.infra` |

## 工作流程

### Step 1: 项目结构扫描

直接读取项目根目录，识别模块结构：

```
扫描步骤：
1. 读取 {project_root}/pom.xml → 识别 <modules> 列表
2. 对每个模块，扫描 src/main/java/ 下的包结构
3. 识别业务分层：
   - controller/ 或 web/ → REST API 入口
   - service/ → 业务服务类
   - mapper/ 或 dao/ 或 repository/ → 数据访问层
   - entity/ 或 model/ 或 domain/ → 实体类
   - dto/ 或 vo/ → 数据传输对象
   - enums/ 或 constant/ → 枚举和常量
   - config/ → 配置类
   - job/ 或 task/ → 定时任务
```

**规模评估**：
- 小型（< 100 Service）→ 一次性分析
- 中型（100-500）→ 分模块批次
- 大型（> 500）→ 按核心业务模块优先

### Step 2: 业务概念提取

读取每个 Service 类源码，提取：

```
提取信号（必须从源码中读取）：
1. 类名 → 业务概念（如 AllocationPlanService → 调拨计划）
2. 类注释 / Javadoc → 业务描述
3. public 方法名 → 核心操作（createPlan, validatePlan）
4. @Autowired 注入的 Mapper/Repository → 涉及的表名
5. 调用的其他 Service → 关联概念
6. 包名/模块名 → tags
```

**注意**：超过 1000 行的类只提取类注释 + public 方法签名 + 注入依赖。

### Step 3: 表结构与多租户识别

从 Entity 类源码提取：

```
提取规则：
1. @TableName("xxx") → 表名
2. @TableId → 主键字段
3. @ApiModelProperty("xxx") / @Schema(description="xxx") → 字段描述
4. 字段类型 → 数据类型
5. @TableField(exist = false) → 排除非数据库字段
6. tenant_id / tenantId / @TenantId → 多租户标识
```

### Step 4: DTO/VO 分析（如果有）

从 DTO/VO 类源码提取验证注解（@NotNull、@Size 等）和字段映射（@JsonProperty、@JsonFormat 等）。

**如果项目没有 DTO/VO 类，跳过此 section。**

### Step 5: REST API 与定时任务入口

从 Controller 类提取 @RequestMapping/@GetMapping/@PostMapping 端点。
从 Task/Job 类提取 @Scheduled、@DolphinTask 等定时任务入口。

### Step 6: 业务规则与已知陷阱

从 Service 实现类提取：
- `if (condition) throw` → 业务约束
- TODO/FIXME/HACK 注释 → 已知问题
- @Deprecated → 已废弃方法
- 魔法数字/硬编码 → 维护陷阱

**如果未检测到，不输出此 section。**

### Step 7: SQL 性能分析（如果有 Mapper XML）

从 Mapper XML 和 @Select 注解分析：
- SELECT * → ⚠️ 性能警告
- 无 WHERE → ⚠️ 全表扫描风险
- LIKE '%xxx%' → 💡 索引优化建议

**如果项目没有 Mapper XML 或 @Select 注解，跳过此 section。**

### Step 8: 依赖关系与数据流向

分析 Service 间的调用关系（直接/间接/循环依赖），分析数据的生成方（insert/save/update）和消费方（select/query/find）。

### Step 9: 生成知识文件

为每个业务概念生成一个 Markdown 文件。

**文件命名**：kebab-case 英文（如 `allocation-plan.md`）

**动态模板**（只输出实际存在的 section）：

```markdown
---
name: 业务概念名称
tables: [table1, table2]
services: [ServiceA, ServiceB]
entry_points: [POST /api/xxx, TaskClass.method()]
related_concepts: [概念A, 概念B]
tags: [tag1, tag2]
---

# 业务概念名称

## 业务描述
[从类注释和代码逻辑推断]

## 核心服务
- **ServiceA** — 职责
  - `method1()` — 描述

## 数据表
### table1
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |

## 数据流向
### 写入方
- ...
### 读取方
- ...

## 业务规则
1. ...

## 依赖关系
- ServiceA → ServiceB
```

**跳过的 section 不输出**，不用 `> ⚠️ 待补充` 占位。
唯一例外：如果表字段无法从注解提取，用 `> ⚠️ 字段列表待补充（无注解信息）` 标注。

### Step 10: 自检验证

生成完成后，逐一验证：

```
验证清单：
1. 每个代码块中引用的类名 → 确认在源码文件中存在
2. 每个方法签名 → 确认与源码一致
3. 每个表名 → 确认来自 @TableName 注解
4. 每个配置项 → 确认来自 @ConfigurationProperties
5. 没有残留的 "read_code" / "project_structure" 等工具调用痕迹

不通过则修正后重新输出。
```

## 输出规范

- 文件名 kebab-case，概念名中文
- YAML frontmatter 必须包含
- 代码块内容必须来自实际源码（可精简，不可编造）
- 每个文件 100-200 行，不超过 300 行

## 错误处理

| 场景 | 处理 |
|------|------|
| 文件读取失败 | 跳过，在汇总报告列出 |
| 非 Java 项目 | 提示不支持 |
| 项目无标准分层 | 尝试识别实际包结构，无法识别则提示 |
| 类名翻译歧义 | 优先用中文注释，无注释则标注"待确认" |
| 循环依赖 | 标记 ⚠️ 继续分析其他依赖 |

## 版本历史

- **v3.0.0**：重定位为集成阶段工具，移除 agent tools 依赖，添加强制源码提取约束和自检验证
- **v2.2.0**：新增技术基础设施发现（Step X/Y/Z）→ 已拆分到 `technical-architecture-discovery.md`
- **v2.1.0**：初始版本
