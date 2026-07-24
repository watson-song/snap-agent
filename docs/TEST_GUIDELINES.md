# SnapAgent 测试代码规范 (Test Guidelines)

> **TDD 驱动开发贯彻到底** — 每一行产品代码都有对应的测试代码。Bugfix 必须先写复现测试，Feature 必须先写失败测试。

---

## 1. 核心原则

### 1.1 TDD 红绿循环

所有产品代码变更必须遵循 TDD 循环：

```
Red:   写测试 → 运行 → 失败（功能未实现）
Green: 写最小实现 → 运行 → 通过
Refactor: 重构 → 运行 → 仍通过
```

**禁止先写实现再补测试**。测试是设计工具，不是覆盖率工具。

### 1.2 Bugfix 测试规则

修复 Bug 时必须：

1. **先写复现测试** — 在修复前，写一个测试能稳定复现该 Bug（红灯）
2. **再修复代码** — 最小改动让测试通过（绿灯）
3. **验证回归** — 确认修复不破坏其他测试
4. **提交信息引用测试** — commit message 中提及测试类名

```
fix(sqlguard): reject UNION in subquery
  SqlGuardTest.shouldRejectUnionInSubquery
```

### 1.3 Feature 测试规则

新增功能时必须：

1. **先写接口/空实现** — 定义类和方法签名，返回 null/空/抛异常
2. **写失败测试** — 测试调用接口，断言期望行为（红灯）
3. **实现功能** — 让测试通过（绿灯）
4. **边界测试** — 补充 null/空/超长/并发/异常路径
5. **重构** — 清理代码，保持测试绿灯

---

## 2. 测试分层

### 2.1 层级定义

| 层级 | 目录 | 特征 | 适用场景 |
|------|------|------|----------|
| Unit | `src/test/java/**/` | @ExtendWith(MockitoExtension.class)，standalone MockMvc，无 Spring 上下文 | 所有核心逻辑：Service/Executor/Loader/Registry/Guard |
| Integration | `src/test/java/**/` | standalone MockMvc + 真实组件链（非 @SpringBootTest），或 @SpringBootTest + 内存 DB | Controller 端点、多组件协作、条件装配 |
| E2E | `src/test/java/**/` | standalone MockMvc 模拟完整请求链 | 端到端关键路径（POST /runs → SSE → done → report） |
| Frontend Unit | `src/test/frontend/unit/` | Vitest + jsdom，eval 加载非模块化 JS | DOM 操作、工具函数、mock fetch |
| Frontend E2E | `src/test/frontend/e2e/` | Playwright + page.route() mock API | UI 交互、SSE 渲染、表单提交 |

### 2.2 选择规则

- **纯逻辑类**（Guard/Calculator/Parser）→ Unit
- **Controller** → Unit（standalone MockMvc）覆盖正常/错误路径，Integration 补充条件装配
- **多组件协作**（Orchestrator/Relay/Scheduler）→ Unit + Integration
- **REST 端点** → Unit（standalone MockMvc），E2E 覆盖关键路径
- **前端 JS** → Vitest Unit + Playwright E2E

### 2.3 禁止项

- **禁止 @SpringBootTest 用于纯逻辑测试** — 启动慢、不稳定，用 standalone MockMvc
- **禁止 @SpringBootTest 用于 Controller 测试** — 除非需要条件装配验证
- **禁止测试间共享状态** — 每个测试独立，@BeforeEach 重置
- **禁止测试依赖执行顺序** — 不使用 @TestMethodOrder
- **禁止 mock 被测对象本身** — mock 依赖，不 mock 被测类

---

## 3. 命名规范

### 3.1 测试类

```
被测类名 + Test
  SnapAgentController → SnapAgentControllerTest
  SqlGuard → SqlGuardTest
  AnchorE2ETest（特殊 E2E 类可命名 XxE2ETest）
```

### 3.2 测试方法

```
should + ExpectedBehavior + When + Condition
  shouldReturn429WhenRateLimited
  shouldRejectUnionInSubquery
  shouldReturn404WhenTaskNotFound
  shouldReturnSkillsListWhenGetSkills

或 should + Action + Result
  shouldCreatePatrolTask
  shouldDeleteCustomSkillAndReturn200
```

### 3.3 测试展示名

```java
@DisplayName("POST /runs 返回 202 + taskId + streamUrl")
@Test
void shouldReturn202WhenPostRunsValid() { ... }
```

---

## 4. 测试结构

### 4.1 标准模板 (Java Unit Test)

```java
@ExtendWith(MockitoExtension.class)
class XxxServiceTest {

    @Mock private DependencyA depA;
    @Mock private DependencyB depB;

    private XxxService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new XxxService(depA, depB);
        mockMvc = MockMvcBuilders.standaloneSetup(service).build();
    }

    @Test
    @DisplayName("正常路径：应返回 200 + 数据")
    void shouldReturn200WhenNormalRequest() throws Exception {
        // Given — 准备数据
        when(depA.find(anyString())).thenReturn(someData());

        // When + Then — 执行并断言
        mockMvc.perform(get("/api/resource")
                .header("X-User-Id", "test-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("123"));
    }

    @Test
    @DisplayName("错误路径：应返回 404")
    void shouldReturn404WhenNotFound() throws Exception {
        when(depA.find("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/resource/nonexistent")
                .header("X-User-Id", "test-user"))
            .andExpect(status().isNotFound());
    }
}
```

### 4.2 测试体结构 (3A 模式)

```java
@Test
void shouldDoSomething() {
    // Arrange — 准备 mock、数据、状态
    when(mock.method(any())).thenReturn(result);

    // Act — 执行被测逻辑
    Result actual = service.execute(input);

    // Assert — 断言结果
    assertThat(actual.getStatus()).isEqualTo(Status.SUCCESS);
    verify(mock).method(expectedInput);
}
```

### 4.3 参数化测试

当同一逻辑有多个输入边界时，使用参数化测试：

```java
@ParameterizedTest
@CsvSource({
    "SELECT * FROM users, true",
    "DROP TABLE users, false",
    "DELETE FROM users, false",
    "UPDATE users SET name=1, false",
})
void shouldValidateSelectOnly(String sql, boolean expected) {
    assertThat(sqlGuard.isAllowed(sql)).isEqualTo(expected);
}
```

---

## 5. 覆盖率要求

### 5.1 行覆盖率门槛 (JaCoCo)

| 模块 | 当前基线 | 目标 |
|------|----------|------|
| snap-agent-core | 0.72 | 0.85 |
| snap-agent-spring-boot-2x-starter | 0.73 | 0.85 |

覆盖率只升不降（ratchet 模式）。发版前 `mvn clean verify` 触发检查。

### 5.2 测试覆盖要求

- **每个 public 方法**至少 1 个正常路径测试 + 1 个边界/错误路径测试
- **Controller 端点**必须有：正常 200 + 错误（400/404/403/429 之一）+ 认证（401/403 之一）
- **条件分支**每个 if/switch 分支至少 1 个测试
- **异常路径**每个 catch 块至少 1 个测试
- **并发场景**有线程安全的类必须 1 个并发测试

### 5.3 不强制覆盖率的情况

- DTO/VO/Entity 的 getter/setter
- @Configuration 类（由 AutoConfigurationTest 间接覆盖）
- Application 启动类
- 无逻辑的常量类/枚举（仅有值的 enum）

---

## 6. Mock 策略

### 6.1 应该 Mock 的

- 外部依赖：LlmClient、TaskStore、SecurityGateway
- 框架设施：AsyncTaskExecutor、TaskScheduler、JavaMailSender
- 网络层：OkHttpClient（用 MockWebServer 替代）
- 文件系统：用 @TempDir 替代固定路径

### 6.2 不应该 Mock 的

- 被测类本身
- 纯值对象/数据类（直接构造）
- 简单的内部工具类（直接使用）
- 被测类的依赖的依赖（只 mock 直接依赖）

### 6.3 MockMvc 设置

```java
// ✅ 正确：standalone setup，无 Spring 上下文
mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

// ❌ 错误：启动 Spring 上下文，慢且不稳定
@AutoConfigureMockMvc
@SpringBootTest
class XxxTest { ... }
```

---

## 7. 前端测试规范

### 7.1 Vitest 单元测试

- 文件位置：`src/test/frontend/unit/*.test.js`
- 环境：jsdom
- 加载方式：eval 加载非模块化 JS（app.js、anchor.js）
- Mock：fetch、EventSource、localStorage
- 命名：`功能名.test.js`（modals.test.js、anchor.test.js）

### 7.2 Playwright E2E 测试

- 文件位置：`src/test/frontend/e2e/*.spec.js`
- Mock：`page.route()` 拦截 API 请求
- 服务：`npx serve` 静态文件服务
- 命名：`功能域.spec.js`（conversations-actions.spec.js、feature-modals.spec.js）

### 7.3 前端覆盖要求

- 所有 fetch/API 调用必须有测试
- 所有按钮点击/表单提交必须有测试
- SSE 事件渲染必须有测试（mock EventSource）
- 错误处理（401/403/429/network error）必须有测试

---

## 8. E2E 关键路径

每个 TDD 模块规格文件中包含 `E2E 关键路径` 小节，标记每条路径的覆盖状态：

- ✅已覆盖 — 有对应测试
- ⚠未实现 — 已记录 GAP，待补充
- ⚠部分覆盖 — 基本路径有测试，边界/异常路径缺失

新增 E2E 测试时，更新对应 TDD 规格文件中的状态列。

---

## 9. Git Hooks

### 9.1 安装

```bash
./scripts/install-hooks.sh
```

### 9.2 Pre-commit 检查项

1. **Bugfix 必须有测试** — commit message 含 `fix`/`bugfix` + staged 有源码但无测试 → 阻止
2. **Feature 必须有测试** — commit message 含 `feat`/`feature` + staged 有源码但无测试 → 阻止
3. **源码无测试时警告** — staged 有 .java 源码但无 .java 测试 → 警告（不阻止，适合重构）
4. **main 分支禁止 WIP** — main 上提交 WIP/tmp → 阻止

### 9.3 Commit-msg 检查项

1. **必须使用 Conventional Commits** — `type(scope): description` 格式
2. **Bugfix/Feature 警告无测试引用** — commit message 含 fix/feat 但无 test 关键词 → 警告
3. **Subject 行长度** — 不超过 100 字符

### 9.4 紧急旁路

**不推荐**，仅限生产事故紧急修复：

```bash
git commit --no-verify
```

旁路提交必须在后续 commit 中补齐测试，并在 PR review 中说明旁路原因。

---

## 10. CI/CD 检查项 (规划)

1. `mvn test` — 全量测试通过
2. `mvn verify` — JaCoCo 覆盖率门槛检查
3. 前端测试 — `cd src/test/frontend && npx vitest run && npx playwright test`
4. Conventional commit 检查
5. PR diff 中新增 .java 文件必须有对应 *Test.java

---

## 11. TDD 规格文件

测试规格位于 `docs/tdd/01-agent-engine/` 至 `docs/tdd/12-codegraph/`，每个模块包含：

- 已有测试覆盖（测试类、数量、覆盖点）
- E2E 关键路径（路径ID、端点、状态）
- 测试缺口（GAP ID、描述、优先级、建议测试）

新增功能时，先更新对应 TDD 规格文件的 E2E 关键路径和测试缺口，再写代码。
