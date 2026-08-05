---
name: domain-knowledge-discovery
description: 从现有代码自动发现和生成领域知识文件。扫描项目结构，识别业务概念、表名、服务类、入口方法，生成带 YAML frontmatter 的 Markdown 文件。
version: 2.1.0
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

**跨模块扫描**：
多模块项目需要扫描所有模块，特别关注：
```
扫描策略：
1. 基础设施模块（如 dfpro-core）：安全框架、工具类、配置类
2. 系统管理模块（如 dfpro-sys）：用户、角色、权限、认证
3. 业务模块（如 dfpro-biz）：核心业务逻辑
4. 启动模块（如 dfpro-starter）：配置文件、启动类

注意：同一业务领域的代码可能分布在多个模块中
例如：SSO 认证涉及 dfpro-biz（SsoController）和 dfpro-sys（IUserService.loginSsoByUserName）
```

**注释代码检测**：
```
检测规则：
1. 检查 Controller 文件是否被注释掉（首行以 // 或 /* 开头）
2. 被注释的 Controller 不计入活跃入口
3. 在文档中标注："部分 Controller 已被注释（如 DeptController、DictDataController）"
4. 被注释的代码可能表示功能已废弃或正在迁移
```

### Step 3: 业务概念提取

对每个 Service 类使用 `read_code` 工具读取源码，提取业务概念：

```
提取信号：
1. 类名 → 业务概念（如 AllocationPlanService → 调拨计划）
2. 类注释 → 业务描述
3. 方法名 → 核心操作（createPlan, validatePlan）
4. 注入的 Repository/Mapper → 涉及的表名（仅表名，字段详情在 Step 4）
5. 调用的其他 Service → 关联概念（填入 related_concepts）
6. @Autowired/@Resource 注入 → 依赖关系
7. 包名/模块名 → 标签（填入 tags，如 replenishment, allocation）
```

**降级策略**：如果类文件超过 1000 行，只提取：
- 类注释
- public 方法签名
- @Autowired 注入的依赖

### Step 4: 表结构与多租户识别

从 Entity 类提取表名、字段和多租户配置：

**表结构提取**：
```
提取规则：
1. @TableName("xxx") → 表名
2. @TableId → 主键字段
3. @ApiModelProperty("xxx") → 字段描述
4. 字段类型 → 数据类型（String/Integer/Date/BigDecimal）
5. @TableField(exist = false) → 排除非数据库字段
```

**多租户识别**：
```
提取规则：
1. tenant_id / tenantId 字段 → 多租户标识
2. @TenantId 注解 → 多租户字段
3. TenantLineHandler 配置 → 租户隔离策略
4. 如果检测到多租户，在文档中添加：
   ```markdown
   ## 多租户
   - 租户字段：tenant_id
   - 隔离策略：行级隔离（通过 TenantLineHandler）
   ```
```

**降级策略**：如果无法从注解提取字段列表，记录为：
```markdown
## 数据表
### table_name
> ⚠️ 字段列表待补充（无法从代码自动提取）
```

### Step 5: DTO/VO 深度分析

从 DTO/VO 类提取数据传输对象的详细定义：

```
提取规则：
1. 验证注解 → 字段验证规则
   - @NotNull / @NotBlank → 必填字段
   - @Size(min, max) → 长度限制
   - @Min / @Max → 数值范围
   - @Pattern(regexp) → 格式验证
   - @Email → 邮箱格式
   - @Phone → 手机号格式

2. 映射注解 → 字段映射关系
   - @JsonProperty("xxx") → JSON 字段映射
   - @JsonFormat(pattern) → 日期格式化
   - @JsonSerialize / @JsonDeserialize → 自定义序列化

3. 字段类型 → 数据类型推断
   - String → 文本
   - Integer/Long → 整数
   - BigDecimal → 金额/精度
   - Date/LocalDateTime → 时间
   - Boolean → 布尔值

4. 嵌套对象 → 复杂类型
   - List<XxxDTO> → 列表类型
   - XxxVO → 嵌套对象
```

**提取示例**：
```java
public class CreateOrderDTO {
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @Size(min = 1, max = 100, message = "商品数量必须在1-100之间")
    private Integer quantity;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime orderTime;
}
```

**输出格式**：
```markdown
## DTO/VO 定义
### CreateOrderDTO
| 字段 | 类型 | 验证规则 | 说明 |
|------|------|---------|------|
| orderNo | String | @NotBlank | 订单号（必填） |
| quantity | Integer | @Size(1-100) | 商品数量 |
| orderTime | LocalDateTime | @JsonFormat | 下单时间 |
```

### Step 6: REST API 与定时任务入口提取

从 Controller 和 Task 类提取入口方法：

**REST API 提取**：
```
提取规则：
1. @RestController → REST 控制器
2. @RequestMapping/@GetMapping/@PostMapping → API 端点
3. @ApiOperation("xxx") → API 描述
4. 方法参数 → 请求参数
5. 返回类型 → 响应类型
```

**定时任务提取**：
```
提取规则：
1. @Scheduled(cron = "xxx") → 定时任务
2. 类名包含 Task/Job/Batch → 批处理任务
3. 实现 CommandLineRunner/ApplicationRunner → 启动任务
4. @DolphinTask → Dolphin 调度任务
5. 填入 entry_points，格式：TaskClass.method()
```

### Step 7: 业务规则与已知陷阱提取

从 Service 实现类提取业务规则和已知陷阱：

**业务规则提取**：
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

**已知陷阱提取**：
```
提取信号：
1. TODO/FIXME/HACK 注释 → 已知问题
2. @Deprecated → 已废弃方法
3. 复杂的 try-catch 块 → 异常处理陷阱
4. 魔法数字/硬编码 → 维护陷阱
5. 复杂的条件判断 → 逻辑陷阱
6. 性能警告注释 → 性能陷阱
```

**降级策略**：如果代码中没有 try-catch，记录为：
```markdown
## 异常处理
> ⚠️ 代码中未检测到显式异常处理逻辑
```

### Step 8: SQL 性能分析

从 Mapper XML 和 @Select 注解分析 SQL 性能问题：

```
分析规则：
1. SELECT * → 警告：应避免使用 SELECT *，建议指定具体字段
   输出：> ⚠️ 性能警告：使用了 SELECT *，建议指定具体字段

2. 无 WHERE 条件 → 警告：全表扫描风险
   输出：> ⚠️ 性能警告：查询缺少 WHERE 条件，可能导致全表扫描

3. 嵌套子查询 → 提示：可能存在性能优化空间
   输出：> 💡 优化建议：嵌套子查询可考虑改为 JOIN

4. LIKE '%xxx%' → 提示：前缀模糊查询无法使用索引
   输出：> 💡 优化建议：前缀模糊查询（LIKE '%xxx%'）无法使用索引

5. 多表 JOIN → 提示：检查 JOIN 顺序和索引
   输出：> 💡 优化建议：多表 JOIN 请确保关联字段有索引
```

**输出格式**：
```markdown
## SQL 性能分析
### XxxMapper.selectByCondition
- ⚠️ 使用了 SELECT *，建议指定具体字段
- 💡 多表 JOIN 请确保关联字段有索引
```

### Step 9: 依赖关系分析与架构图生成

分析 Service 之间的依赖关系，并使用 `generate_module_arch` 工具生成模块架构图：

```
分析维度：
1. 直接依赖 → A 调用 B
2. 间接依赖 → A 调用 B，B 调用 C
3. 循环依赖 → A 调用 B，B 调用 A（标记为警告）

架构图生成：
使用 `generate_module_arch` 工具生成 Mermaid 格式的模块依赖图：
- 节点：每个业务概念（Service）
- 边：依赖关系（直接/间接）
- 样式：循环依赖用红色标注
```

**降级策略**：如果 `generate_module_arch` 工具不可用，手动绘制 Mermaid 图：
```mermaid
graph TD
    A[ServiceA] --> B[ServiceB]
    B --> C[ServiceC]
```

### Step 10: 数据流向分析

分析数据的生成和消费关系：

**数据生成分析**：
```
分析哪些服务/任务写入数据到表：
1. 查找 *Mapper.insert/save/update 调用
2. 查找 *Service.create/save/update 方法
3. 查找定时任务（@Scheduled, Task, Job）
4. 查找消息消费者（@RabbitListener, @KafkaListener）
5. 记录为：数据生成方
```

**数据消费分析**：
```
分析哪些服务/任务读取数据从表：
1. 查找 *Mapper.select/query/find/list 调用
2. 查找 *Service.get/query/find/list 方法
3. 查找定时任务的数据读取
4. 查找消息生产者的数据来源
5. 记录为：数据消费方
```

**输出格式**：
```markdown
## 数据流向
### 数据生成（写入方）
- **TaskReplenishmentPlanJob.execute()** — 定时任务生成补货计划
  - 调用：ReplenishmentPlanService.createPlan()
  - 写入表：drp_allocation_plan
  - 触发频率：每天凌晨 2:00

- **AllocationPlanService.createPlan()** — 手动创建调拨计划
  - 触发方式：REST API POST /api/allocation-plan
  - 写入表：drp_allocation_plan

### 数据消费（读取方）
- **TransferSuggestionService.generateSuggestion()** — 生成调拨建议
  - 读取表：drp_allocation_plan
  - 用途：基于调拨计划生成调拨建议

- **ReportAllocationPlanService.exportReport()** — 导出调拨计划报表
  - 读取表：drp_allocation_plan
  - 用途：导出报表供业务方查看
```

### Step 10.5: 基础设施域识别

区分业务域和基础设施域，采用不同的文档策略：

```
域类型判断：
1. 业务域（如补货计算、调拨建议）：
   - 核心内容：业务规则、数据流向、SQL 逻辑
   - 重点：计算逻辑、业务流程、数据处理

2. 基础设施域（如系统管理、认证授权）：
   - 核心内容：权限模型、认证流程、数据权限
   - 重点：RBAC 模型、Token 管理、SSO 集成
   - 简化：不需要详细的 SQL 性能分析

3. 常量/枚举服务（如 ConstantController）：
   - 核心内容：数据来源映射表
   - 重点：哪些接口查哪些表
   - 简化：不需要业务规则和数据流向

基础设施域文档模板调整：
- 可以省略"SQL 性能分析"
- 可以简化"数据流向"
- 增加"权限模型"或"认证流程"章节
- 增加"已知陷阱"中记录跨模块依赖
```

### Step 11: 生成领域知识文件

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

## DTO/VO 定义
### XxxDTO
| 字段 | 类型 | 验证规则 | 说明 |
|------|------|---------|------|
| field1 | String | @NotBlank | 字段描述 |

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

## 数据流向
### 数据生成（写入方）
- **TaskXxxJob.execute()** — 定时任务生成数据
  - 调用：XxxService.create()
  - 写入表：table_name_1
  - 触发频率：每天凌晨 2:00

- **XxxService.createXxx()** — 手动创建数据
  - 触发方式：REST API POST /api/xxx
  - 写入表：table_name_1

### 数据消费（读取方）
- **YyyService.generateYyy()** — 生成下游数据
  - 读取表：table_name_1
  - 用途：基于此数据生成下游数据

- **ZzzService.exportReport()** — 导出报表
  - 读取表：table_name_1
  - 用途：导出报表供业务方查看

## 多租户
- 租户字段：tenant_id
- 隔离策略：行级隔离（通过 TenantLineHandler）

## 业务规则
1. 规则1
2. 规则2

## SQL 性能分析
### XxxMapper.selectByCondition
- ⚠️ 使用了 SELECT *，建议指定具体字段
- 💡 多表 JOIN 请确保关联字段有索引

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

### 工具层面错误

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

### 业务层面错误

5. **循环依赖导致分析死锁**
   - 错误：检测到 A → B → A 的循环依赖
   - 处理：在依赖关系图中标记为警告（红色），继续分析其他依赖，不中断流程
   - 输出：在生成的文档中添加 `> ⚠️ 检测到循环依赖：ServiceA ↔ ServiceB`

6. **部分文件读取失败**
   - 错误：某些文件因权限问题无法读取
   - 处理：跳过无法读取的文件，在汇总报告中列出失败文件
   - 输出：`> ⚠️ 以下文件无法读取：path/to/file.java`

7. **项目结构混乱**
   - 错误：项目没有标准的分层结构（没有 controller/service/dao 等标准包）
   - 处理：尝试识别实际的业务包命名模式，如果无法识别，提示用户手动指定业务包路径
   - 输出：`> ⚠️ 未检测到标准分层结构，请确认业务包路径`

8. **类名翻译歧义**
   - 错误：英文类名可能有多种中文翻译（如 AllocationPlan 可能是"调拨计划"或"分配计划"）
   - 处理：优先使用项目中的中文注释，如果没有注释，使用最常见的翻译，并在文档中标注"待确认"
   - 输出：`name: 调拨计划（待确认，可能为"分配计划"）`

## 注意事项

- 优先使用类注释和中文注释作为业务描述
- 如果类名是英文，尝试翻译为中文概念名（参考以下示例）：
  - `AllocationPlanService` → `allocation-plan.md` / 调拨计划
  - `ReplenishPlanService` → `replenish-plan.md` / 补货计划
  - `InventoryCheckService` → `inventory-check.md` / 库存校验
  - `OrderCreateService` → `order-create.md` / 订单创建
- 如果无法确定业务描述，使用"待补充"占位
- 表名如果无法从代码提取，使用类名推断（驼峰转下划线）
- **严禁编造内容**：如果无法从代码提取，明确标记为"待补充"
