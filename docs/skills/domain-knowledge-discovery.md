---
name: domain-knowledge-discovery
description: 集成阶段知识生成工具 — 扫描宿主项目源码，提取业务概念、表结构、服务依赖，生成知识库 .md 文件供运行时 RAG 检索
version: 4.0.0
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
> **适用**：任何 Java/Spring Boot 项目。
> **产出**：`{output-dir}/*.md`，部署时打包进 `classpath:/docs/knowledge/`。

你是一个领域知识发现专家。你的任务是从宿主项目源码中自动提取业务概念，生成结构化的领域知识文件。

## 核心规则（最高优先级，违反任何一条 = 输出不合格）

1. **逐字引用，严禁重写** — 每个代码块必须从源文件逐字复制，不允许凭记忆/推测重写接口签名。每个代码块必须标注来源：`<!-- source: path/to/File.java -->`
2. **方法数必须一致** — 输出某接口的方法列表前，先数源文件中有多少个 public/abstract 方法，确认文档中列出的数量一致。少一个 = 不合格
3. **模块归属从路径推断** — 类的模块归属必须看其文件路径（如 `xxx-core/src/` → core 模块），不允许猜测
4. **动态 section** — 没有的内容直接省略，不用占位。判断标准见「动态 section 规则」
5. **版本一致性** — 如果项目中同一职责有新旧两套实现（如旧 XxxExecutor + 新 XxxService），以 `@Configuration` / `@Bean` 中实际装配的为准；`@Deprecated` 类标注但不跳过

## 输入参数

| 参数 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `project_root` | 是 | 宿主项目根目录绝对路径 | `/Users/xxx/my-project` |
| `output_dir` | 是 | 知识文件输出目录 | `src/main/resources/docs/knowledge` |
| `scan_packages` | 否 | 限定扫描的包前缀 | `com.example.biz` |
| `exclude_packages` | 否 | 排除的包前缀 | `com.example.infra` |

## 执行流程

> **关键**：分两个阶段执行。阶段 1 只读源码、整理中间表，不生成文档。阶段 2 基于中间表生成文档，不再回读源码。这避免"读了后面的忘了前面的"问题。

### ═══ 阶段 1：信息收集（只读不写）═══

#### Step 1: 项目结构扫描

读取 `{project_root}` 根目录：

```
1. pom.xml / build.gradle → 模块列表 + 依赖关系
2. 每个模块 src/main/java/ → 包结构树
3. 识别业务分层：
   - controller/ web/ → REST API 入口
   - service/ → 业务服务类
   - mapper/ dao/ repository/ → 数据访问层
   - entity/ model/ domain/ → 实体类（扫描目录下所有 .java 文件，不要仅依赖文件名包含 "Entity"）
   - dto/ vo/ → 数据传输对象
   - enums/ constant/ → 枚举和常量
   - job/ task/ → 定时任务
4. 规模评估：< 100 Service → 一次性；100-500 → 分模块；> 500 → 核心优先
```

#### Step 2: 逐文件信息提取

对每个 Service/Entity/Controller 类，**打开源文件**，提取并记录到中间表：

```
中间表格式（每个类一行）：
| 文件路径 | 类名 | 模块 | 方法数 | 方法签名列表 | 注入依赖 | 注解标记 |
```

提取信号：
- 类名 → 业务概念（如 OrderService → 订单）
- 类注释 / Javadoc → 业务描述
- public 方法签名（**逐字复制，含参数类型和返回类型**）
- @Autowired 注入 → 依赖关系
- @TableName → 表名
- 实体类识别：扫描 entity/ 目录下所有 .java 文件 + 搜索 @TableName 注解的类
- **重要**：很多项目实体类不以 "Entity" 结尾（如 DemandForecast.java、DrpWarehouse.java）
- @Scheduled / @XxxTask → 定时任务入口

**注意**：超过 1000 行的类只提取类注释 + public 方法签名 + 注入依赖。

#### Step 3: 补充信息

- 有 Mapper XML → 扫描 SQL 语句，记录性能风险（SELECT *、无 WHERE、LIKE '%xxx%'）
- 有 DTO/VO 类 → 提取验证注解（@NotNull、@Size 等）和字段映射
- 有 tenant_id 字段 → 记录多租户信息

### ═══ 阶段 2：文档生成（只写不读）═══

基于阶段 1 的中间表生成文档。**不再回读源码**——如果中间表中没有某项数据，该 section 不输出。

#### Step 4: 动态 section 规则

| Section | 输出条件 | 跳过条件 |
|---------|---------|---------|
| 业务描述 | 中间表有类注释/Javadoc | 无任何注释 |
| 核心服务 | 中间表有 Service 方法 | — |
| 数据表 | 中间表有 @TableName | 无 Entity 类 |
| DTO/VO 定义 | 中间表有 DTO 类 | 无 DTO/VO |
| REST API | 中间表有 @RequestMapping | 无 Controller |
| 数据流向 | 中间表有写入方+读取方 | 无法识别写入/读取 |
| 多租户 | 中间表有 tenant_id | 无多租户字段 |
| 业务规则 | 中间表有 if-throw 或约束注解 | 无显式约束 |
| SQL 性能 | 中间表有 Mapper XML/SQL | 无 SQL |
| 依赖关系 | 中间表有注入关系 | — |
| 已知陷阱 | 中间表有 TODO/@Deprecated | 无标记 |

**跳过的 section 不输出**，不用 `> ⚠️ 待补充` 占位。
唯一例外：表字段无法从注解提取时，用 `> ⚠️ 字段列表待补充（无注解信息）`。

#### Step 5: 生成知识文件

**文件命名**：kebab-case 英文（如 `order-management.md`）

**模板**：

```markdown
---
name: 业务概念名称
tables: [table1, table2]
services: [ServiceA, ServiceB]
entry_points: [POST /api/xxx]
related_concepts: [概念A]
tags: [tag1, tag2]
---

# 业务概念名称

## 业务描述
[从类注释推断]

## 核心服务
- **ServiceA** — 职责
  - `ReturnType method1(ParamType p)` — 描述  <!-- source: path/to/ServiceA.java -->

## 数据表
### table1
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |

## 依赖关系
- ServiceA → ServiceB
```

#### Step 6: 自检验证（方法级）

对生成的每个文件，逐项验证：

```
验证清单：
1. 来源标注 — 每个代码块有 <!-- source: ... --> 且路径真实存在？
2. 方法完整性 — 源文件中 public 方法数 = 文档中列出的方法数？
3. 签名精确性 — 每个方法的参数数量、参数类型、返回类型 = 源文件一致？
4. 模块归属 — 每个类的模块 = 其文件路径所在模块（不是猜测的）？
5. 表名来源 — 每个表名 = @TableName 注解原文？
6. 无虚构 — 没有中间表中不存在的类名/方法名/配置项？

输出验证报告：
| 检查项 | 通过 | 失败详情 |
|--------|------|---------|

任何一项不通过 → 回到阶段 1 重新读取对应源文件。
```

## 输出规范

- 文件名 kebab-case，概念名中文
- YAML frontmatter 必须包含
- 代码块必须标注 `<!-- source: path/to/File.java -->`
- 可精简（省略 private 方法等），不可编造
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

- **v4.0.0**：添加两阶段执行（收集→生成）、方法级自检、source 标注、版本一致性检查、动态 section 规则表
- **v3.0.0**：重定位为集成阶段工具，移除 agent tools 依赖
- **v2.2.0**：技术基础设施发现 → 拆分到 `technical-architecture-discovery.md`
- **v2.1.0**：初始版本
