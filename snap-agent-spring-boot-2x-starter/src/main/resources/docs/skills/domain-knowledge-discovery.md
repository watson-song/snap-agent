---
name: domain-knowledge-discovery
description: 从现有代码自动发现和生成领域知识文件。扫描项目结构，识别业务概念、表名、服务类、入口方法，生成带 YAML frontmatter 的 Markdown 文件。支持老项目逆向分析模式。
version: 1.4.0
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
  - 老项目逆向分析
  - legacy project analysis
author: SnapAgent
---

# 领域知识自动发现 (Domain Knowledge Discovery)

你是一个领域知识发现专家。你的任务是从现有代码中自动提取业务概念，生成结构化的领域知识文件。

## v1.4.0 新增特性：老项目逆向分析模式

针对"老项目 + 写文档"的痛点，采用"先扫描目录结构，再逐层拆解，最后反向生成架构"的策略。

### 老项目逆向分析四步法

#### 第一步：生成项目"目录树"骨架（宏观认知）

先让 AI 对项目有宏观认知，避免上下文窗口限制导致分析失败。

```bash
# Linux/Mac
tree -L 4 -I 'target|node_modules|.git|logs' > project_tree.txt

# Windows PowerShell
Get-ChildItem -Recurse -Directory | Select-Object FullName
```

**分析要求**：
1. 顶层模块划分（按包名推测业务领域）
2. 分层架构识别（Controller/Service/DAO/Mapper/Util 是否清晰）
3. 指出可能存在的循环依赖风险点

#### 第二步：提取"依赖关系"DNA（核心）

从 pom.xml 和 @Autowired 注解提取模块间依赖关系。

**分析要求**：
1. 生成模块依赖矩阵图（Mermaid 语法）
2. 标明 A 模块引用了 B 模块的哪个 jar 包
3. 指出是否存在循环依赖

```java
// 关键线索识别
@Autowired
private XxxService xxxService;  // 本地依赖

@FeignClient("service-name")   // 远程依赖
@DubboReference                // RPC 依赖
@Resource                      // 本地依赖
```

#### 第三步：逆向绘制"架构分层与调用链"

对于老项目，业务逻辑可能分散在 Service、Manager、Handler 里。通过代码注解反推调用关系。

**分析要求**：
1. 分层职责表：Controller、Service、Manager、DAO 各层职责
2. 核心链路时序图（PlantUML/Mermaid）：选取最核心的 3 个业务用例，画出完整调用栈
3. 外部依赖拓扑：识别所有 @FeignClient 或 RestTemplate，列出下游服务
4. DB 层反向 ER 线索：根据 Mapper.xml 或 @Table 注解，生成核心表关联关系

#### 第四步：生成架构文档（最终交付）

整合所有分析，生成完整的架构文档，包含：
1. 整体架构图（Mermaid）
2. 模块清单与职责说明
3. 横向调用关系表（调用方 → 被调用方 → 调用方式）
4. 潜在技术债务警示（耦合点、反模式）

### 老项目"弯道超车"技巧

1. **先跑通再写文档**：如果项目能本地启动，先读取 application.yml 中的 spring.datasource 和 dubbo.registry 配置，自动识别微服务边界
2. **不要一次性喂大文件**：对于超过 1000 行的 Service 类，先总结前 10 个 public 方法的作用，确认后再分析实现细节
3. **借力工具**：如果模块关系不准，可以运行 jdepend 工具生成 package-cycle.txt，基于真实耦合数据画图
4. **异步事件依赖**：如果模块间通过消息队列（RocketMQ/Kafka）异步调用，识别 @RocketMQMessageListener、@KafkaListener 等注解

## v1.3.0 新增特性

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

## v1.3.0 新增特性

### 6. 关联表结构完整提取
- 不仅提取核心表，还提取所有关联表的完整字段结构
- 通过外键关系、Service 注入、SQL JOIN 识别关联表
- 为每个关联表生成完整的字段定义文档

### 7. 异常处理策略提取
- 识别 try-catch 块和异常处理模式
- 提取自定义异常类及其使用场景
- 记录异常处理策略（中止/收集所有错误/重试）
- 记录错误信息返回格式

### 8. 技术实现细节提取
- 识别动态表头实现（MenuDisplayEnum 等）
- 提取批量处理实现（线程池、批次大小）
- 提取缓存策略（Redis、本地缓存）
- 提取并发控制策略（分布式锁、乐观锁）

### 9. 模块集成关系提取
- 识别模块间的数据流转关系
- 提取跨模块调用的具体接口和参数
- 记录数据格式转换逻辑
- 生成模块集成关系图

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

### 模式选择

根据项目情况选择分析模式：
- **标准模式**：适用于结构清晰的 Spring Boot 项目
- **老项目逆向模式**：适用于历史遗留项目、文档缺失项目、多人协作混乱项目

### 老项目逆向模式工作流程（推荐优先使用）

#### Step 0: 项目目录树扫描（宏观认知）

先生成项目目录树，建立宏观认知：

```bash
# Linux/Mac
tree -L 4 -I 'target|node_modules|.git|logs' > project_tree.txt

# Windows PowerShell
Get-ChildItem -Recurse -Directory | Select-Object FullName
```

**分析输出**：
1. 顶层模块划分（按包名推测业务领域）
2. 分层架构识别（Controller/Service/DAO/Mapper/Util 是否清晰）
3. 循环依赖风险点识别

#### Step 0.1: 依赖关系 DNA 提取

从 pom.xml 和注解提取模块间依赖关系：

```
分析内容：
1. 父级 pom.xml → 模块依赖矩阵
2. 各子模块 pom.xml → 具体依赖的 jar 包
3. @Autowired/@Resource → 本地服务依赖
4. @FeignClient → 远程 HTTP 依赖
5. @DubboReference → RPC 依赖
6. @RocketMQMessageListener → 消息队列依赖
```

**输出**：
- 模块依赖矩阵图（Mermaid）
- 循环依赖检测报告
- 外部服务依赖清单

#### Step 0.2: 架构分层与调用链逆向

通过代码注解反推调用关系：

```
分析维度：
1. Controller 层 → REST API 入口识别
2. Service 层 → 业务逻辑职责划分
3. Manager/Handler 层 → 中间层职责识别
4. DAO/Mapper 层 → 数据访问模式识别
5. 跨层调用 → 识别不规范调用（如 Controller 直接调用 DAO）
```

**输出**：
- 分层职责表
- 核心链路时序图（选取最核心的 3 个业务用例）
- 外部依赖拓扑图

#### Step 0.3: 技术债务识别

识别项目中的技术债务和反模式：

```
识别模式：
1. 工具类包被所有模块引用 → 耦合风险
2. Service 层直接操作 HttpServletRequest → Web 耦合
3. 循环依赖 → 模块边界不清
4. 超大类（>1000行） → 职责不清
5. 硬编码配置 → 配置管理缺失
6. 重复代码 → 缺乏抽象
```

**输出**：
- 技术债务清单
- 耦合点分析
- 改进建议

### 标准模式工作流程

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

### Step 13: 关联表结构完整提取（v1.3.0 新增）

不仅提取核心表，还要提取所有关联表的完整字段结构：

```
关联表识别策略：
1. 外键关系 → Entity 中的 @ManyToOne, @OneToMany 注解
2. Service 注入 → Service 中注入的其他 Entity 的 Mapper
3. SQL JOIN → Mapper XML 中的 JOIN 语句
4. 查询条件 → 查询条件中引用的其他表
5. 业务关联 → 业务逻辑中一起使用的表

提取要求：
- 为每个关联表生成完整的字段定义
- 标注表之间的关联关系（外键、逻辑关联）
- 说明关联查询的使用场景
```

示例：
```java
// 核心表：output_replenish_mmc_sum
// 关联表识别：
// 1. SQL JOIN → output_replenish_sku_store (JOIN ON batch_dt + version)
// 2. Service 注入 → IReplenishSkuStoreService → output_replenish_sku_store
// 3. 业务关联 → output_replenish_sum (按天汇总)
```

### Step 14: 异常处理策略提取（v1.3.0 新增）

从代码中提取异常处理策略：

```
提取模式：
1. try-catch 块 → 异常处理逻辑
   - catch (Exception e) { throw ... } → 中止策略
   - catch (Exception e) { errors.add(e) } → 收集策略
   - catch (Exception e) { retry() } → 重试策略

2. 自定义异常类 → 业务异常定义
   - DfproServerException → 系统异常
   - BusinessException → 业务异常
   - ValidationException → 校验异常

3. 错误信息格式 → 错误返回格式
   - R.error(code, message) → 统一错误格式
   - 错误码定义 → 错误码枚举

4. 校验失败处理 → 校验失败策略
   - 第一个错误就中止 → 快速失败
   - 收集所有错误后返回 → 批量校验
```

示例提取：
```java
public void importExcel(MultipartFile file) {
    try {
        List<ExcelVO> list = EasyExcel.read(file).doReadSync();
        for (ExcelVO vo : list) {
            validate(vo);  // 可能抛出 ValidationException
        }
        saveBatch(list);
    } catch (ExcelAnalysisException e) {
        throw new DfproServerException(EXCEL_FORMAT_ERROR, e.getMessage());
    } catch (ValidationException e) {
        throw new DfproServerException(VALIDATION_ERROR, e.getMessage());
    }
}
```
→ 异常处理策略:
  - Excel格式错误 → 抛出 DfproServerException(EXCEL_FORMAT_ERROR)
  - 校验失败 → 抛出 DfproServerException(VALIDATION_ERROR)
  - 策略：遇到第一个错误就中止（快速失败）

### Step 15: 技术实现细节提取（v1.3.0 新增）

提取关键技术实现细节：

```
提取目标：
1. 动态表头实现
   - MenuDisplayEnum → 表头配置枚举
   - getDisplayHeaderByName() → 动态表头获取方法
   - 不同租户的表头差异

2. 批量处理实现
   - 线程池配置 → ThreadPoolExecutor 参数
   - 批次大小 → 每批处理的数据量
   - 并发控制 → 同步/异步策略

3. 缓存策略
   - Redis 缓存 → key 格式、过期时间
   - 本地缓存 → Caffeine/Guava 配置
   - 缓存更新 → 更新策略（主动/被动）

4. 并发控制
   - 分布式锁 → Redisson 锁
   - 乐观锁 → version 字段
   - @TaskCheck → 任务状态检查
```

示例提取：
```java
// 动态表头
List<List<String>> heads = configDisplayFiledService.getDisplayHeaderByName(
    MenuDisplayEnum.STRATEGY.getCode(),
    user.getLanguage(),
    MenuDisplayTypeEnum.EXPORT.getCode()
);

// 批量处理
ExecutorService updateThreadPool = new ThreadPoolExecutor(4, 4, ...);
for (List<Long> batch : batches) {  // 每批 300 条
    updateThreadPool.submit(() -> mapper.batchUpdate(batch));
}
```
→ 技术实现细节:
  - 动态表头: 通过 MenuDisplayEnum + language + displayType 动态获取
  - 批量处理: 4线程线程池，每批300条
  - 租户差异: Fendi 和 LV 使用不同的 Excel VO 类

### Step 16: 模块集成关系提取（v1.3.0 新增）

提取模块间的集成关系：

```
提取维度：
1. 数据流转 → 数据从一个模块流向另一个模块
   - 源模块 → 目标模块
   - 数据格式 → 数据格式转换
   - 触发条件 → 什么情况下触发

2. 接口调用 → 跨模块的接口调用
   - 调用方 → 被调用方
   - 接口签名 → 方法签名和参数
   - 返回值 → 返回值类型和含义

3. 共享数据 → 多个模块共享的数据
   - 共享表 → 多个模块访问的表
   - 共享缓存 → 多个模块使用的缓存
   - 共享配置 → 多个模块使用的配置

4. 依赖关系 → 模块间的依赖
   - 编译依赖 → Maven 依赖
   - 运行时依赖 → Spring 注入
   - 数据依赖 → 数据依赖关系
```

示例：
```
调拨日历配置模块 → 补货计算模块
  数据流转: config_pl_transfer_calendar → 补货计算输入
  触发条件: 用户点击"手动计算"或定时任务触发
  数据格式: 配置规则 → 计算参数

补货计算模块 → 调拨建议模块
  数据流转: output_replenish_mmc_sum → suggest_transfer_order
  触发条件: 用户选择补货结果并保存调拨建议
  数据格式: 补货结果 → 调拨建议单
```

### Step 17: 业务域聚类

按业务语义将概念聚类：

```
聚类规则：
1. 相同包的 Service → 同一业务域
2. 互相调用的 Service → 关联概念
3. 共享表的 Service → 同一业务域
4. 类名前缀相同 → 同一业务域（如 Allocation* → 调拨）
5. Controller 和 Service 的 RequestMapping 路径前缀相同
```

### Step 18: 生成业务概念关系图

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

### Step 19: 生成领域知识文件

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

### table_name_2（关联表描述）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| field1 | String | 字段描述 |

## 业务枚举
### StatusEnum（状态枚举）
| 值 | 名称 | 说明 |
|----|------|------|
| 0 | PENDING | 待处理 |
| 1 | RUNNING | 处理中 |

## 业务规则
1. 规则1
2. 规则2

## 异常处理
- **Excel格式错误** → 抛出 DfproServerException(EXCEL_FORMAT_ERROR)，中止导入
- **校验失败** → 收集所有错误后返回，或遇到第一个错误就中止
- **数据不存在** → 抛出 DfproServerException(DATA_NOT_FOUND)

## 技术实现细节
- **动态表头**: 通过 MenuDisplayEnum + language + displayType 动态获取
- **批量处理**: 4线程线程池，每批300条
- **租户差异**: Fendi 和 LV 使用不同的 Excel VO 类
- **并发控制**: @TaskCheck 注解检查任务状态

## 已知陷阱
- 陷阱1
- 陷阱2

## 模块集成关系
```
当前模块 → 下游模块1
  数据流转: 表A → 表B
  触发条件: xxx
当前模块 → 下游模块2
  接口调用: ServiceA.method() → ServiceB.method()
```

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
9. **每个数据表必须包含完整字段列表和类型**（包括关联表）
10. 如果存在枚举，必须列出枚举值和含义
11. **必须包含异常处理策略**（记录异常类型、处理策略、错误信息格式）
12. **必须包含技术实现细节**（动态表头、批量处理、缓存策略、并发控制）
13. **必须包含模块集成关系**（数据流转、接口调用、共享数据）

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
