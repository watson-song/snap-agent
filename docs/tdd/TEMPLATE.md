# TDD需求规格说明书模板 (AI开发优化版 v2.0)

> 版本: 2.0  
> 适用: AI辅助开发 + TDD流程  
> 更新: 基于PM/Dev/QA角色评审反馈优化

---

## 1. 需求元信息

```yaml
需求ID: REQ-XXX
需求名称: [简短描述性名称]
优先级: [P0/P1/P2/P3]
迭代: [Sprint X]
负责人: [姓名]
状态: [草稿/评审中/开发中/测试中/已完成]
```

### 1.1 背景与目标
- **业务背景**: [为什么要做这个需求]
- **用户价值**: [解决什么问题，带来什么收益 - 需量化，如"减少50%重复错误"]
- **成功指标**: [可量化的验收指标，如"任务完成率>70%"]

### 1.2 范围边界
- **包含**: [明确在范围内]
- **不包含**: [明确排除在外]

### 1.3 风险与假设 [PM评审新增]

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 | 负责人 |
|--------|------|------|------|----------|--------|
| R1 | [技术/业务风险描述] | 高/中/低 | 高/中/低 | [具体措施] | [姓名] |
| R2 | [外部依赖风险] | 高/中/低 | 高/中/低 | [降级策略] | [姓名] |

**关键假设**:
- [假设1：用户行为的假设]
- [假设2：技术能力的假设]
- [假设3：外部服务的假设]

---

## 2. 用户故事 (User Stories)

### US-1: [故事标题]
```gherkin
作为 [角色]
我希望 [功能]
以便 [可量化的价值，如"在下次同类题目中减少50%以上的重复错误"]
```

**验收标准 (AC):**
```gherkin
AC1: [具体场景]
  Given [前置条件]
  When [动作]
  Then [期望结果]

AC2: [边界场景]
  Given [前置条件]
  When [动作]
  Then [期望结果]
```

---

## 2.5 用户故事地图 [PM评审新增]

展示用户旅程中各故事的依赖关系和业务价值传递。

| 用户阶段 | 用户故事 | 价值目标 | 衡量指标 | 依赖关系 |
|----------|----------|----------|----------|----------|
| 认知 | US-1 注册 | 降低使用门槛 | 注册转化率>60% | - |
| 使用 | US-2 核心功能 | 提供有效服务 | 任务完成率>70% | US-1 |
| 留存 | US-3 反馈闭环 | 建立用户粘性 | 7日留存率>40% | US-2 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 用例名称 | 优先级 | 对应AC | 测试类型 |
|--------|----------|--------|--------|----------|
| UC-01  | [名称]   | P0     | AC1    | 单元测试 |
| UC-02  | [名称]   | P1     | AC2    | 集成测试 |

### 3.2 详细用例 (Gherkin格式)

#### UC-01: [用例名称]

```gherkin
@priority:high @type:unit
功能: [一句话描述]

  # 主流程
  场景: [正向场景描述]
    Given [系统状态/数据准备]
      | 字段名 | 值 | 说明 |
      | name   | 张三 | 用户名 |
    And [附加条件]
    When [用户操作/系统触发]
    Then [期望结果断言]
      | 字段名 | 期望值 | 断言类型 |
      | status | 200    | equals   |
    And [附加断言]

  # 异常流程
  场景: [异常场景]
    Given [前置条件]
    When [触发异常的操作]
    Then [期望错误]
      | 错误码 | 错误消息 | 日志级别 |
      | E001   | 参数无效 | WARN     |

  # 边界值
  场景大纲: [参数化测试场景]
    Given [前置]
    When 输入 <input>
    Then 期望 <expected>
    
    例子:
      | input | expected | 说明 |
      | 0     | success  | 边界值 |
      | 100   | success  | 最大值 |
      | 101   | error    | 超出范围 |

  # 并发场景 [QA评审新增]
  场景: [并发场景]
    Given [并发前置条件]
    When [并发操作，如30秒内连续请求5次]
    Then [期望结果，如只有第一次成功]
    And [并发安全验证]

  # 安全场景 [QA评审新增]
  场景: [安全渗透场景]
    Given [恶意输入，如SQL注入payload]
    When [执行操作]
    Then [安全防护验证]
    And [安全日志记录]
```

---

## 4. 接口规格 (API Specs)

### 4.1 API版本控制策略 [Dev评审新增]

```yaml
版本策略:
  - URL路径: /api/v1/... /api/v2/...
  - 废弃API: 保留3个版本周期，返回Deprecation头
  - 兼容性: 请求头Accept-Version可选
  - 变更类型: 新增(compatible) / 修改(breaking) / 废弃(deprecated)
```

### 4.2 内部接口

#### [接口名称] - [函数/方法名]
```java
/**
 * [功能描述]
 * 
 * @param param1 [描述]
 * @param param2 [描述]
 * @return [返回值描述]
 * @throws [异常说明]
 * 
 * 测试要点:
 *   - TC1: 正常输入
 *   - TC2: null参数
 *   - TC3: 空字符串
 *   - TC4: 超长字符串
 *   - TC5: 并发调用 [Dev评审新增]
 *   - TC6: 幂等性验证 [Dev评审新增]
 * 
 * 事务传播: REQUIRED/REQUIRES_NEW/SUPPORTS [Dev评审新增]
 * 幂等键: [参数名或header名] [Dev评审新增]
 */
ReturnType methodName(ParamType param1, ParamType param2);
```

### 4.3 外部API

```yaml
接口: POST /api/v1/resource
名称: [操作名称]
版本: v1
废弃计划: [如有，说明时间和替代方案]

Request:
  Content-Type: application/json
  # 幂等性 [Dev评审新增]
  Idempotency-Key: [UUID，用于防止重复提交]
  Headers:
    Authorization: Bearer {token}
    Accept-Version: v1  # 可选
  Body:
    type: object
    required: [field1, field2]
    properties:
      field1:
        type: string
        example: "示例值"
        validation: "长度1-100"
      field2:
        type: integer
        example: 42
        validation: "0-1000"

Response:
  200:
    description: 成功
    body:
      type: object
      properties:
        code:
          type: string
          example: "SUCCESS"
        data:
          type: object
        pagination:  # 分页信息 [Dev评审新增]
          total: 1000
          page: 1
          size: 20
  
  400:
    description: 参数错误
    body:
      code: "PARAM_INVALID"
      message: "${field} 格式错误"
  
  409:  # 幂等冲突 [Dev评审新增]
    description: 重复请求
    body:
      code: "DUPLICATE_REQUEST"
      message: "请求已处理"
      originalResponse: {...}

TestCases:
  - TC-API-001: 正常请求 -> 200
  - TC-API-002: 缺少必填字段 -> 400
  - TC-API-003: 字段类型错误 -> 400
  - TC-API-004: 字段超出范围 -> 400
  - TC-API-005: 未授权访问 -> 401
  - TC-API-006: 重复提交(相同Idempotency-Key) -> 409 [Dev评审新增]
  - TC-API-007: 并发请求(相同Idempotency-Key) -> 只有一个成功 [Dev评审新增]
```

### 4.4 API幂等性设计 [Dev评审新增]

```yaml
幂等性策略:
  策略: 客户端生成Idempotency-Key(UUID)
  存储: Redis TTL=24h
  冲突响应: 409 Conflict + 原响应数据
  并发控制: 分布式锁(SETNX)，防止并发重复处理

实现示例:
  ```java
  @Idempotent(key = "#request.idempotencyKey", ttl = 86400)
  public Response process(Request request) { ... }
  ```
```

### 4.5 OpenAPI/Swagger注解规范 [Dev评审新增]

```java
@Operation(
    summary = "用户注册",
    description = "通过手机号验证码注册新用户"
)
@ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "注册成功",
        content = @Content(schema = @Schema(implementation = UserResponse.class))
    ),
    @ApiResponse(
        responseCode = "409",
        description = "手机号已存在",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))
    )
})
@PostMapping("/register")
public ApiResponse<UserResponse> register(
    @Valid @RequestBody UserRegisterRequest request
)
```

### 4.6 数据变更事件 (领域事件) [Dev评审新增]

```yaml
领域事件设计:
  UserRegisteredEvent:
    触发时机: 用户注册成功后
    消费者: 欢迎通知服务、用户分析服务
    属性: userId, phone, registerTime, source
  
  DiagnosisCompletedEvent:
    触发时机: 错因诊断完成后
    消费者: 学习分析服务、推荐服务
    属性: userId, questionId, misconceptionId, confidence
  
  GuidanceSessionEndedEvent:
    触发时机: 引导会话结束后
    消费者: 报告生成服务、成就系统
    属性: sessionId, userId, duration, finalResult

事件存储:
  - 事件持久化到数据库(event_store表)
  - 异步发送到消息队列(Kafka/RabbitMQ)
  - 支持事件重放和审计
```

---

## 5. 数据规格 (Data Specs)

### 5.1 数据模型

```yaml
实体: [EntityName]
表名: [table_name]

字段:
  - name: id
    type: BIGINT
    nullable: false
    primaryKey: true
    autoIncrement: true
    
  - name: name
    type: VARCHAR(100)
    nullable: false
    validation: "长度1-100，不允许特殊字符"
    testData: "测试用户_{random}"
  
  - name: status
    type: ENUM('ACTIVE', 'INACTIVE')
    default: 'ACTIVE'
    testData: "ACTIVE"

索引:
  - name: idx_status
    fields: [status, created_at]
    type: normal
    # 性能验证 [Dev评审新增]
    explain: "Index Scan, cost=12.34"

约束:
  - "UNIQUE(name)"
  - "CHECK(created_at <= updated_at)"

# 并发控制 [Dev评审新增]
乐观锁:
  version: version字段
  
分布式锁:
  keyPattern: "lock:{entity}:{id}"
  ttl: 30s
```

### 5.2 数据分层规范 [Dev评审新增]

```java
// Request DTO - 入参校验
public record UserRegisterRequest(
    @Pattern(regexp = "^1[3-9]\\d{9}$") 
    String phone,
    @Size(min = 6, max = 6) 
    String verificationCode,
    @Min(1) @Max(12)
    Integer grade
) {}

// Entity - 数据库映射
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue
    private Long id;
    // ...
}

// Response VO - 出参脱敏
public record UserResponse(
    Long id,
    String maskedPhone,  // 138****8000
    Integer grade
) {}

// 转换层
@Component
public class UserConverter {
    public User toEntity(UserRegisterRequest dto) { ... }
    public UserResponse toVO(User entity) { ... }
}
```

### 5.3 测试数据

```yaml
数据集: DS-001
描述: 标准测试数据集

records:
  - id: 1
    name: "测试用户1"
    status: "ACTIVE"
    created_at: "2024-01-01T00:00:00Z"

边界数据:
  - name: "最小长度"
    value: "A"
  - name: "最大长度"
    value: "A" * 100
```

### 5.4 测试数据生成器 [QA评审新增]

```yaml
数据集生成器: DG-001
描述: 动态生成测试数据
工具: JavaFaker / Python Faker / javafaker-js

生成规则:
  - phone: 
      pattern: "1[3-9]{9}"
      unique: true
  - grade: 
      type: random
      range: [1, 12]
  - subjects:
      type: random_sample
      source: ["MATH", "PHYSICS", "CHEMISTRY"]
      count: [1, 3]  # 随机选1-3个
  - createdAt:
      type: random_date
      range: ["2024-01-01", "2024-12-31"]
      
边界值生成:
  - 最小值: 固定边界
  - 最大值: 固定边界
  - 超长字符串: "A" * (maxLength + 1)
  - 特殊字符: "<script>alert('xss')</script>"
  - SQL注入: "'; DROP TABLE users; --"

并发数据生成:
  - 相同用户并发请求数据
  - 竞争条件测试数据集
```

---

## 6. 错误处理规格 (Error Handling)

### 6.1 错误码定义

| 错误码 | 级别 | 描述 | 用户提示 | 日志内容 | 告警策略 |
|--------|------|------|----------|----------|----------|
| E001 | WARN | 参数验证失败 | 输入参数有误 | 参数字段和实际值 | 不告警 |
| E002 | ERROR| 数据库连接失败 | 服务繁忙，请重试 | 异常堆栈 | 连续3次触发 |
| E003 | INFO | 记录不存在 | 数据未找到 | ID值 | 不告警 |

### 6.2 错误场景

```gherkin
场景: 数据库连接超时
  When 数据库连接超时
  Then 返回错误码 "DB_TIMEOUT"
  And 用户提示 "服务繁忙，请稍后重试"
  And 记录 ERROR 日志，包含连接池状态
  And 触发告警（连续3次）

场景: Redis连接失败
  When Redis连接失败
  Then 降级使用内存缓存（会话保持时间缩短至5分钟）
  And 记录 WARN 日志
  And 触发告警

场景: 外部服务熔断 [Dev评审新增]
  Given 外部服务连续5次超时
  When 第6次请求到达
  Then 触发熔断，直接返回降级结果
  And 30秒后尝试恢复
  And 触发告警

场景: 重复提交 [Dev评审新增]
  Given 同一请求在5秒内重复提交（通过Redis幂等键判断）
  When 接收到重复请求
  Then 返回上一次的响应结果
  And 记录 INFO 日志
```

### 6.3 降级策略 [Dev评审新增]

```yaml
降级策略:
  OCR服务故障:
    触发条件: 连续5次失败或超时
    降级方案: 提示用户"图片服务暂时不可用，请手动输入题目"
    恢复策略: 30秒后自动重试
  
  LLM服务故障:
    触发条件: 超时或配额耗尽
    降级方案: 使用模板话术，不调用LLM
    恢复策略: 熔断器半开状态试探
  
  BERT模型故障:
    触发条件: 超时或返回异常
    降级方案: 跳过BERT层，直接规则引擎+LLM兜底
    恢复策略: 立即重试1次，失败则降级
```

---

## 7. 非功能需求 (NFR)

### 7.1 性能要求

```yaml
指标:
  - name: 接口响应时间
    target: "P95 < 200ms"
    testMethod: "压力测试，并发100"
    
  - name: 吞吐量
    target: "> 1000 QPS"
    testMethod: "负载测试"
    
  - name: 数据库查询
    target: "单表查询 < 50ms"
    testMethod: "EXPLAIN ANALYZE验证"

性能拐点测试 [QA评审新增]:
  - 最大并发用户数: 200
  - 队列溢出策略: 等待10秒后返回"系统繁忙"
  - 熔断触发阈值: 错误率>50%或响应时间>5s
```

### 7.2 安全要求

- [x] 输入参数校验（防SQL注入、XSS）
- [x] 敏感数据加密存储(AES-256)
- [x] API鉴权验证（JWT Token）
- [x] 敏感操作审计日志
- [x] 传输层加密（TLS 1.3）
- [x] API限流（每分钟100次/用户）
- [x] 防暴力破解（验证码错误5次锁定15分钟）

### 7.3 安全测试场景 [QA评审新增]

```gherkin
场景: SQL注入防护验证
  Given 用户在输入中注入 "'; DROP TABLE users; --"
  When 执行数据库操作
  Then 系统应正常处理或返回参数错误
  And 不应执行任何DROP操作
  And 记录WARN安全日志

场景: XSS攻击防护验证
  Given 用户输入包含 "<script>alert('xss')</script>"
  When 存储并展示该内容
  Then 输出应被转义
  And 不应执行脚本

场景: JWT Token篡改检测
  Given 用户篡改了JWT Token的签名部分
  When 携带篡改后的Token请求受保护接口
  Then 应返回401 Unauthorized
  And 记录WARN安全日志，包含IP地址

场景: 越权访问测试
  Given 用户A的ID是10001
  When 用户B(10002)尝试访问用户A的数据
  Then 应返回403 Forbidden
  And 记录ERROR安全日志
```

### 7.4 可测试性要求

- [x] 核心逻辑单元测试覆盖率 > 80%
- [x] 所有public方法都有对应测试
- [x] 集成测试覆盖主流程
- [x] 边界值测试全覆盖
- [x] 并发测试覆盖关键路径 [QA评审新增]
- [x] 安全渗透测试覆盖 [QA评审新增]

---

## 8. 测试策略 (Test Strategy)

### 8.1 测试金字塔

```
        /\
       /  \  E2E测试 (2个主流程)
      /____\      - 完整业务流程
     /      \     - 关键用户旅程
    /________\
   /          \  集成测试 (API + DB + Redis)
  /            \    - 模块间交互
 /______________\   - 外部服务集成
/                \  - 契约测试 [QA评审新增]
   单元测试 (核心业务逻辑)
      - Service层
      - Repository层
      - 工具类
```

### 8.2 测试清单

| 测试ID | 类型 | 描述 | 自动化 | 优先级 |
|--------|------|------|--------|--------|
| UT-001 | 单元 | Service层正常流程 | 是 | P0 |
| UT-002 | 单元 | Repository层CRUD | 是 | P0 |
| UT-003 | 单元 | 异常处理分支 | 是 | P0 |
| UT-004 | 单元 | 并发场景 | 是 | P0 [QA评审新增] |
| IT-001 | 集成 | API端到端 | 是 | P0 |
| IT-002 | 集成 | 数据库事务 | 是 | P0 |
| IT-003 | 集成 | 契约测试 | 是 | P1 [QA评审新增] |
| CT-001 | 契约 | OCR服务契约 | 是 | P1 [QA评审新增] |
| CT-002 | 契约 | LLM服务契约 | 是 | P1 [QA评审新增] |
| E2E-01 | E2E | 完整业务流程 | 是 | P1 |
| MT-001 | 变异 | 核心业务逻辑变异测试 | 是 | P2 [QA评审新增] |
| CH-001 | 混沌 | 随机服务故障注入 | 否 | P2 [QA评审新增] |

### 8.3 Mock策略

```yaml
需要Mock的外部依赖:
  - 第三方API: 使用WireMock
  - 数据库: 使用H2内存数据库
  - 缓存: 使用Embedded Redis
  - 消息队列: 使用内存队列

分层Mock策略 [QA评审新增]:
  本地开发: 100% Mock，快速反馈
  CI构建: 90% Mock + 10%真实调用(关键路径)
  Nightly构建: 50% Mock + 50%真实调用
  预发布: 100%真实调用
```

### 8.4 变异测试 [QA评审新增]

```yaml
变异测试要求:
  目标: 变异得分 > 70%
  工具: 
    - Java: PIT (Pitest)
    - Python: MutPy
  执行时机:
    - 每次CI构建
    - 发布前必须达标
  变异算子:
    - 条件边界变异 (a > b → a >= b)
    - 数学运算符变异 (+ → -)
    - 返回值变异 (return true → return false)
    - 空值检查变异 (remove null check)
```

### 8.5 测试环境隔离 [QA评审新增]

```yaml
环境隔离要求:
  单元测试:
    资源: 完全隔离，只允许使用内存资源
    数据库: H2内存数据库
    缓存: Caffeine内存缓存
    并行: 支持并行执行(junit.jupiter.execution.parallel.enabled=true)
  
  集成测试:
    资源: 可连接测试环境DB
    事务: 必须在事务中执行并回滚
    并行: 串行执行，避免资源冲突
    清理: @AfterAll清理测试数据
  
  E2E测试:
    环境: 必须使用独立的E2E测试环境
    数据: 禁止连接生产数据
    隔离: 每个测试用例独立数据初始化

上下文隔离:
  - 使用@DirtiesContext确保Spring上下文隔离
  - 每个测试类独立的TestPropertySource
```

### 8.6 契约测试 [QA评审新增]

```yaml
消费者驱动的契约测试 (CDC):
  工具: Pact
  
  契约定义:
    - OCR服务契约: 定义请求图片格式/返回JSON结构
    - LLM服务契约: 定义prompt格式/返回话术结构
    - BERT服务契约: 定义输入/输出格式
  
  验证流程:
    - Provider端: 每次构建时验证provider是否遵守契约
    - Consumer端: 使用契约stub进行集成测试
    - 契约版本: 与API版本同步管理
  
  契约示例:
    ```json
    {
      "consumer": { "name": "diagnosis-service" },
      "provider": { "name": "ocr-service" },
      "interactions": [{
        "description": "OCR识别请求",
        "request": {
          "method": "POST",
          "path": "/recognize",
          "body": { "image": "base64encoded" }
        },
        "response": {
          "status": 200,
          "body": { "text": "string", "confidence": "number" }
        }
      }]
    }
    ```
```

### 8.7 混沌测试 [QA评审新增]

```yaml
混沌测试:
  工具: Chaos Monkey / Gremlin
  
  测试场景:
    - 随机杀死OCR服务Pod，验证降级策略
    - 随机延迟Redis响应(100ms-5s)，验证超时处理
    - 模拟数据库主从切换，验证读写分离
    - 网络分区测试，验证脑裂处理
  
  安全约束:
    - 仅在独立混沌测试环境执行
    - 禁止在生产环境执行
    - 测试前备份数据
  
  可观测性:
    - 记录混沌实验参数和系统响应
    - 生成恢复时间报告
```

---

## 9. 依赖与前置条件

### 9.1 外部依赖

| 依赖 | 状态 | 降级策略 | 联系人 |
|------|------|----------|--------|
| OCR服务 | 已完成 | [描述降级方案] | [姓名] |
| LLM API | 已完成 | [描述降级方案] | [姓名] |
| 短信服务 | 进行中 | [描述降级方案] | [姓名] |

### 9.2 内部依赖
- [ ] 用户模块（已完成）
- [ ] 权限模块（进行中）

### 9.3 数据依赖
- [ ] 初始化数据脚本
- [ ] 测试数据准备

---

## 10. 可观测性设计 (Observability) [Dev评审新增]

### 10.1 日志规范

```yaml
日志要求:
  请求追踪:
    - MDC.put("traceId", UUID)
    - 全链路传递traceId
  
  敏感信息脱敏:
    - phone=138****8000
    - idCard=110**********1234
  
  结构化日志:
    - 格式: JSON
    - 必填字段: timestamp, level, traceId, service, message
    - 业务字段: userId, action, result, duration
```

### 10.2 指标监控

```yaml
业务指标:
  - diagnosis_latency_seconds_histogram
  - guidance_session_active_gauge
  - ocr_success_rate
  - llm_call_count

系统指标:
  - jvm_memory_used
  - database_connection_pool_active
  - redis_operation_latency
```

### 10.3 分布式追踪

```yaml
追踪工具: OpenTelemetry / Jaeger

追踪点:
  - HTTP请求入口
  - 数据库操作
  - Redis操作
  - 外部服务调用(OCR/LLM)
  - 异步消息处理

标注:
  - LLM调用标注提示词长度和响应时间
  - 诊断流程标注各阶段耗时
```

---

## 11. 原型与交互参考 [PM评审新增]

### 11.1 设计资源
- **Figma原型**: [链接]
- **设计规范**: [链接]

### 11.2 关键流程截图
| 流程 | 截图 | 说明 |
|------|------|------|
| 注册流程 | [图] | 手机号→验证码→信息完善 |
| 辅导流程 | [图] | 上传→诊断→引导→完成 |

### 11.3 界面状态定义

| 状态 | 视觉表现 | 文案 | 交互 |
|------|----------|------|------|
| 加载中 | 旋转动画 | "正在处理..." | 不可点击 |
| 成功 | 绿色图标 | "操作成功" | 自动跳转 |
| 错误 | 红色图标 | 具体错误提示 | 可重试 |
| 空状态 | 插图 | "暂无数据" | 引导操作 |

---

## 12. 附录

### 12.1 变更历史

| 版本 | 日期 | 作者 | 变更内容 |
|------|------|------|----------|
| 2.0 | 2024-04-08 | 团队 | 基于角色评审优化 |
| 0.1 | 2024-01-01 | 张三 | 初始版本 |

### 12.2 参考文档
- [相关文档链接]
- [设计文档链接]

### 12.3 术语表

| 术语 | 定义 |
|------|------|
| [术语] | [定义] |

---

## AI开发提示词模板

```markdown
基于以下需求规格，请：

1. **生成单元测试代码**
   **⚠️ 数量要求：每个public方法至少3-5个测试方法**
   - 正例测试：正常输入，期望正确输出
   - 反例测试：无效输入，期望抛出异常/错误返回
   - 边界测试：最小值、最大值、空值、null、超长字符串等
   - 并发测试（如适用）：多线程安全验证
   - 安全测试（如适用）：SQL注入、XSS等防护验证
   - 覆盖所有AC场景（包括并发和安全场景）
   - 使用JUnit5 + Mockito + AssertJ
   - 遵循Given-When-Then结构
   - 使用@ParameterizedTest进行参数化测试
   - **测试命名规范：`should{期望结果}When{条件}`**
   - **示例：`shouldThrowExceptionWhenPhoneIsNull`**

2. **生成实现代码**
   - 先写骨架（接口/类定义）
   - 实现最小可用版本（MVP）
   - 确保所有测试通过
   - 遵循Clean Code原则
   - 添加必要的日志记录（SLF4J）
   - 标注事务传播行为

3. **生成集成测试**
   - 使用@SpringBootTest
   - 测试API端点（@AutoConfigureMockMvc）
   - 验证数据持久化和缓存一致性
   - 使用@Transactional保证测试隔离

4. **代码规范**
   - 包结构: com.example.{module}
   - 模块: controller/service/repository/entity/dto/exception
   - 统一使用ApiResponse包装响应
   - 异常使用BusinessException
   - 使用@Transactional保证事务
   - DTO/Entity/VO分层清晰

需求规格：
[Paste Section 3-4 here]
```

---

## 使用说明

### 对产品经理
1. 填写第1-2章（背景、用户故事、风险与假设）
2. 维护第2.5章用户故事地图
3. 与开发/测试评审第3章（AC场景）
4. 确认第4章接口设计
5. 提供第11章原型资源

### 对开发人员
1. 基于第3章AC生成测试代码
2. 基于第4章接口定义生成骨架（包含OpenAPI注解）
3. 实现使测试通过（注意幂等性和并发控制）
4. 自测覆盖第6章错误场景和降级策略
5. 配置第10章可观测性（日志/指标/追踪）

### 对测试人员
1. 基于第3章AC设计测试用例（包含并发/安全场景）
2. 使用第5.4章测试数据生成器准备数据
3. 执行第8章测试策略（包含变异/契约/混沌测试）
4. 验证第7章NFR指标和降级策略

### 对AI助手
1. 解析Gherkin格式生成测试代码
2. 根据接口规格生成API定义（含Swagger注解）
3. 根据数据模型生成Entity/Repository/Converter
4. 根据错误码生成异常处理代码
5. 生成分层DTO/Entity/VO代码
