---
name: domain-knowledge-discovery
description: 从现有代码自动发现和生成领域知识文件。扫描项目结构，识别业务概念、表名、服务类、入口方法，生成带 YAML frontmatter 的 Markdown 文件。
version: 1.2.0
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

## v1.2.0 新增特性

### 1. DTO/VO 类识别增强
- 扫描 `dto/` 和 `vo/` 包，提取数据传输对象
- 识别 DTO/VO 与 Entity 的映射关系
- 提取字段验证规则和转换逻辑

### 2. 业务规则智能提取
- 识别验证注解（`@NotNull`, `@Size`, `@Min`, `@Max`, `@Pattern`）
- 分析条件判断逻辑（if/else/switch）
- 提取业务约束和不变量

### 3. 服务依赖深度分析
- 分析方法调用链（A → B → C）
- 识别循环依赖和深层依赖
- 生成服务依赖拓扑图

### 4. 多租户模式识别
- 自动识别 `tenant_id` 字段
- 提取租户隔离相关的业务规则
- 识别跨租户操作的敏感点

### 5. SQL 提取增强
- 支持注解方式 SQL（`@Select`, `@Insert`, `@Update`, `@Delete`）
- 提取复杂查询的执行计划建议
- 识别潜在的性能问题（N+1 查询、全表扫描）

## 核心能力

- 扫描项目结构，识别核心业务包
- 分析 Service 类，提取业务概念和职责
- 从 @TableName 注解和 Entity 类提取表名和字段
- 从 Controller 类提取 REST API 入口
- 从 Task/Job 类提取定时任务入口
- 从 Mapper XML 提取复杂 SQL 查询
- 从 Enum 类提取业务状态/类型定义
- 按业务域聚类，生成领域知识文件
- 自动生成业务概念关系图（Mermaid）

## 工作流程

### Step 1: 项目结构扫描

使用 `project_structure` 工具获取项目包结构，识别核心业务包：

```
扫描模式：
1. 查找 controller/ 包 → 提取 REST API 入口
2. 查找 service/ 包 → 提取业务服务类
3. 查找 mapper/ 或 dao/ 或 repository/ 包 → 提取数据访问类
4. 查找 task/ 或 job/ 包 → 提取定时任务入口
5. 查找 entity/ 或 model/ 包 → 提取实体类（推断表名）
6. 查找 enums/ 包 → 提取业务枚举定义
```

### Step 2: 业务概念提取

对每个 Service 类使用 `read_code` 工具读取源码，提取：

```
提取信号：
1. 类名 → 业务概念（如 AllocationPlanService → 调拨计划）
2. 类注释 → 业务描述
3. 方法名 → 核心操作（createPlan, validatePlan）
4. 注入的 Repository/Mapper → 涉及的表
5. 调用的其他 Service → 关联概念
6. @Autowired/@Resource 注入 → 依赖关系
```

### Step 3: 表名和字段提取

从 Entity 类提取表名和字段：

```
1. @TableName("xxx") 注解 → 表名
2. @TableId 注解 → 主键字段
3. @ApiModelProperty 注解 → 字段描述
4. 字段类型 → 数据类型（String/Integer/Date/BigDecimal）
5. @TableField(exist = false) → 非数据库字段，排除
```

示例提取：
```java
@TableName("config_pl_transfer_calendar")
public class ConfigPlTransferCalendar {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("产品线")
    private String productLine;
}
```
→ 表名：config_pl_transfer_calendar
→ 字段：id (主键), product_line (产品线)

### Step 4: REST API 入口提取

从 Controller 类提取 REST API：

```
1. @RestController 注解的类
2. @RequestMapping/@GetMapping/@PostMapping 注解的方法
3. @ApiOperation 注解 → API 描述
4. 方法参数 → 请求参数
5. 返回类型 → 响应类型
```

示例：
```java
@RestController
@RequestMapping("/api/replenish")
public class ReplenishController {
    @PostMapping("/calculate")
    @ApiOperation("触发补货计算")
    public Result calculate(@RequestBody ReplenishReq req) {
        // ...
    }
}
```
→ API: POST /api/replenish/calculate (触发补货计算)

### Step 5: 定时任务入口提取

从 Task/Job 类提取定时任务入口：

```
1. @Scheduled 注解的方法
2. JobHandler 接口的实现方法
3. 包含 "execute" 或 "run" 的公共方法
4. 类名包含 Job/Task/Scheduler
```

### Step 6: 业务枚举提取

从 Enum 类提取业务状态/类型定义：

```
1. 枚举名 → 业务类型（如 TaskStatusEnum → 任务状态）
2. 枚举值 → 状态/类型值（如 PENDING, RUNNING, SUCCESS）
3. @EnumValue 注解 → 数据库存储值
4. 枚举注释 → 值描述
```

示例：
```java
public enum TaskStatusEnum {
    PENDING(0, "待处理"),
    RUNNING(1, "处理中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败");
}
```
→ 任务状态：0=待处理, 1=处理中, 2=成功, 3=失败

### Step 7: DTO/VO 类分析（v1.2.0 新增）

从 DTO/VO 类提取数据传输对象：

```
扫描路径：
1. dto/ 包 → 数据传输对象
2. vo/ 包 → 视图对象
3. request/ 包 → 请求对象
4. response/ 包 → 响应对象

提取信息：
1. 类名 → DTO/VO 名称
2. 字段 → 数据字段
3. 验证注解 → 验证规则（@NotNull, @Size, @Min, @Max）
4. 转换注解 → 映射关系（@JsonProperty, @JsonFormat）
5. 关联 Entity → 数据来源
```

示例：
```java
public class ReplenishPlanDTO {
    @NotNull(message = "SKU不能为空")
    private String skuCode;

    @Size(min = 1, max = 100, message = "数量必须在1-100之间")
    private Integer quantity;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate planDate;
}
```
→ DTO: ReplenishPlanDTO
→ 字段: skuCode (必填), quantity (1-100), planDate (日期格式)
→ 验证规则: SKU必填, 数量1-100, 日期格式yyyy-MM-dd

### Step 8: 业务规则智能提取（v1.2.0 新增）

从 Service 实现类提取业务规则：

```
提取模式：
1. 验证注解 → 字段级规则
   - @NotNull → 必填
   - @Size(min, max) → 长度范围
   - @Min, @Max → 数值范围
   - @Pattern → 格式要求

2. 条件判断 → 业务逻辑规则
   - if (condition) → 前置条件
   - if (error) throw → 业务约束
   - assert → 不变量

3. 状态转换 → 状态机规则
   - status = X → 状态定义
   - transition from A to B → 状态转换
```

示例提取：
```java
public void createPlan(PlanDTO dto) {
    // 验证规则
    if (dto.getQuantity() <= 0) {
        throw new BusinessException("数量必须大于0");
    }

    // 业务规则
    if (dto.getPlanDate().isBefore(LocalDate.now())) {
        throw new BusinessException("计划日期不能早于今天");
    }

    // 状态转换
    plan.setStatus(PlanStatus.DRAFT);
}
```
→ 业务规则:
  - 数量必须大于0
  - 计划日期不能早于今天
  - 新建计划状态为DRAFT

### Step 9: 服务依赖深度分析（v1.2.0 新增）

分析方法调用链：

```
分析维度：
1. 直接依赖 → A 调用 B
2. 间接依赖 → A 调用 B，B 调用 C
3. 循环依赖 → A 调用 B，B 调用 A（警告）
4. 依赖深度 → 调用链的最大深度

工具：
- 读取 Service 实现类
- 分析 @Autowired 注入
- 追踪方法调用
- 构建依赖图
```

示例：
```java
@Service
public class ReplenishPlanService {
    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private SkuService skuService;

    public void createPlan() {
        Sku sku = skuService.getSku(skuCode);
        Integer stock = inventoryService.getStock(skuCode);
        // ...
    }
}
```
→ 依赖关系:
  - ReplenishPlanService → InventoryService (直接)
  - ReplenishPlanService → SkuService (直接)
  - 依赖深度: 1

### Step 10: 多租户模式识别（v1.2.0 新增）

识别多租户相关代码：

```
识别模式：
1. tenant_id 字段 → 租户标识
2. TenantContext → 租户上下文
3. @TenantFilter → 租户过滤注解
4. TenantInterceptor → 租户拦截器

提取信息：
- 哪些表包含 tenant_id
- 哪些查询自动过滤租户
- 哪些操作需要租户隔离
- 跨租户操作的敏感点
```

示例：
```java
@Entity
@TableName("replenish_plan")
public class ReplenishPlan {
    private Long tenantId;  // 租户ID
    private String planNo;
    // ...
}
```
→ 多租户: replenish_plan 表支持租户隔离

### Step 11: SQL 提取增强（v1.2.0 新增）

从注解方式提取 SQL：

```
支持注解：
1. @Select("SELECT ...") → 查询语句
2. @Insert("INSERT ...") → 插入语句
3. @Update("UPDATE ...") → 更新语句
4. @Delete("DELETE ...") → 删除语句

性能分析：
- 识别 SELECT * → 建议指定字段
- 识别无 WHERE 条件 → 警告全表扫描
- 识别 N+1 查询 → 建议批量查询
- 识别缺少索引 → 建议添加索引
```

示例：
```java
@Mapper
public interface PlanMapper {
    @Select("SELECT * FROM replenish_plan WHERE sku_code = #{skuCode}")
    List<ReplenishPlan> selectBySkuCode(@Param("skuCode") String skuCode);
}
```
→ SQL: SELECT * FROM replenish_plan WHERE sku_code = #{skuCode}
→ 性能建议: 避免 SELECT *，指定需要的字段

### Step 12: Mapper XML 分析

从 Mapper XML 提取复杂 SQL：

```
1. <select> 标签 → 查询语句
2. <insert>/<update>/<delete> → 写操作
3. <if>/<where>/<choose> → 动态 SQL 条件
4. SQL 注释 → 查询用途
```

关注点：
- 复杂 JOIN 查询 → 表关联关系
- 子查询 → 数据依赖
- 动态条件 → 业务规则

### Step 13: 业务域聚类

按业务语义将概念聚类：

```
聚类规则：
1. 相同包的 Service → 同一业务域
2. 互相调用的 Service → 关联概念
3. 共享表的 Service → 同一业务域
4. 类名前缀相同 → 同一业务域（如 Allocation* → 调拨）
5. Controller 和 Service 的 RequestMapping 路径前缀相同
```

### Step 14: 生成业务概念关系图

使用 Mermaid 生成业务概念关系图：

```mermaid
graph LR
    A[调拨日历配置] --> B[任务调度]
    B --> C[补货计算]
    C --> D[输出结果表]
    A --> C
```

关系类型：
- 实线箭头 (→)：直接调用/依赖
- 虚线箭头 (⇢)：间接关联
- 双向箭头 (↔)：数据同步

### Step 15: 生成领域知识文件

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
api_endpoints:
  - POST /api/xxx
  - GET /api/xxx
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

## 入口方法
```
TaskClass.method()
  └── ServiceClass1.method1()
        └── MapperClass.insert()
```

## 数据表
### table_name_1（表描述）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| field1 | String | 字段描述 |
| field2 | Integer | 字段描述 |

## 业务枚举
### StatusEnum（状态枚举）
| 值 | 名称 | 说明 |
|----|------|------|
| 0 | PENDING | 待处理 |
| 1 | RUNNING | 处理中 |

## 业务规则
1. 规则1
2. 规则2

## 已知陷阱
- 陷阱1
- 陷阱2

## 业务概念关系图
```mermaid
graph LR
    A[当前概念] --> B[关联概念1]
    A --> C[关联概念2]
    B --> C
```

## SQL 示例
```sql
-- 查询示例
SELECT * FROM table_name_1 WHERE field1 = 'value';
```
```

## 输出要求

1. 每个业务概念生成一个独立的 .md 文件
2. 文件名使用英文，kebab-case（如 `allocation-plan.md`）
3. 概念名称使用中文（如 `name: 调拨计划`）
4. 必须包含 YAML frontmatter
5. 表名使用小写加下划线（snake_case）
6. 服务名使用类名（不含包名）
7. 字段名使用 snake_case（数据库字段）或 camelCase（Java 字段）
8. 必须包含业务概念关系图（Mermaid）
9. 每个数据表必须包含字段列表和类型
10. 如果存在枚举，必须列出枚举值和含义

## 批量生成模式

当项目较大时，可以分批生成：

```
批次 1：核心业务域（Service + Entity + Controller）
批次 2：任务调度（Job + Task + Scheduler）
批次 3：配置管理（Config* 相关）
批次 4：数据导入（Import* 相关）
批次 5：报表输出（Report* 相关）
```

每批生成后，生成一个汇总报告：
```markdown
# 领域知识发现报告 - 批次 X

## 生成文件
- file1.md (概念1)
- file2.md (概念2)

## 统计
- 识别 X 个业务概念
- 提取 Y 个数据表
- 发现 Z 个 REST API
- 识别 W 个定时任务

## 业务概念关系图
```mermaid
graph LR
    A[概念1] --> B[概念2]
    B --> C[概念3]
```

## 待完善
- [ ] 概念1：需要补充业务背景
- [ ] 概念2：需要确认已知陷阱
```

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
