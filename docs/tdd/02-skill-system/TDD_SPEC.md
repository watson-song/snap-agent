# TDD需求规格说明书 — Skill 系统

> 版本: 2.0
> 适用: AI辅助开发 + TDD流程

---

## 1. 需求元信息

```yaml
需求ID: REQ-SKILL-SYSTEM
需求名称: Skill 加载、两层系统与热重载
优先级: P0
迭代: Sprint 1
负责人: snap-agent team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: 诊断 skill 以 Markdown frontmatter 格式定义，需要解析、校验工具契约、缓存并支持内置 + 上传两层模型。用户可在运行时上传/删除自定义 skill。
- **用户价值**: skill 加载从启动到可用 < 5 秒；两层模型让内置 skill 零配置可用，自定义 skill 持久化且可覆盖内置。
- **成功指标**: skill 解析成功率 > 95%；刷新不阻塞读操作；custom 覆盖 builtin 后删除自动恢复 builtin。

### 1.2 范围边界
- **包含**: SkillLoader frontmatter 解析、SkillRegistry 两层合并与目录扫描、SkillMeta 元数据、ClasspathSkillScanner classpath 扫描、SkillHotReloader 文件监听热重载、InputSpec 参数规格。
- **不包含**: skill 上传/删除 controller 层 (SnapAgentController)、工具分发器实现。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 | 负责人 |
|--------|------|------|------|----------|--------|
| R1 | snakeyaml 反序列化攻击 | 低 | 高 | 使用 SafeConstructor 拒绝任意对象构造 | team |
| R2 | 宿主 classpath skill 覆盖内置 skill | 中 | 中 | ClasspathSkillScanner 两遍扫描，JAR 优先 | team |
| R3 | 热重载 WatchService 在 macOS 轮询延迟 10s | 中 | 低 | 使用 HIGH sensitivity (2s) | team |
| R4 | 并发刷新时读者看到半构建缓存 | 低 | 高 | volatile holder 原子替换 | team |

**关键假设**:
- 假设1: classpath 在运行时不变，ClasspathSkillScanner 只在启动时扫描一次。
- 假设2: 上传目录可读写，重启后持久化。

---

## 2. 用户故事 (User Stories)

### US-1: Frontmatter 解析
```gherkin
As a skill author
I want the system to parse YAML frontmatter with SafeConstructor
So that skill files are safely loaded without deserialization attacks
```

**验收标准 (AC):**
```gherkin
AC1: 合法 frontmatter 解析成功
  Given 一个 .md 文件以 "---" 开头，含 name/description/tools 字段
  When SkillLoader.parse 被调用
  Then 返回 SkillMeta with availability=AVAILABLE
  And name/description/tools/inputs/body 均正确

AC2: 缺少必填字段标记 INVALID
  Given frontmatter 缺少 name 字段
  When parse 被调用
  Then availability=INVALID
  And unavailableReason 包含 "name"
```

### US-2: 两层 Skill 系统
```gherkin
As a platform operator
I want built-in skills (classpath) and uploadable skills (filesystem) to coexist
So that default skills work out-of-box and custom skills can override them
```

**验收标准 (AC):**
```gherkin
AC1: builtin + custom 合并
  Given builtin list 含 "health-check" 且 upload dir 含 "my-skill"
  When SkillRegistry 构造完成
  Then all() 返回 2 个 skill
  And "health-check".source == "builtin"
  And "my-skill".source == "custom"

AC2: custom 覆盖 builtin
  Given builtin 和 upload dir 均有同名 "health-check"
  Then all() 仅返回 1 个 (custom 版本)
  And merged.source == "custom"
  And merged.overridesBuiltin == true

AC3: 删除 custom 恢复 builtin
  Given custom 覆盖了 builtin "health-check"
  When 删除 custom 文件并 refresh()
  Then merged.source == "builtin"
  And merged.overridesBuiltin == false
```

### US-3: 工具契约校验
```gherkin
As a skill author
I want the system to check if my declared tools are registered
So that unavailable skills are clearly marked
```

**验收标准 (AC):**
```gherkin
AC1: 所有工具已注册 -> AVAILABLE
  Given skill.tools=[mysql_query] 且 dispatcher 已注册 mysql_query
  When validateContract 后
  Then availability=AVAILABLE

AC2: 缺少工具 -> UNAVAILABLE
  Given skill.tools=[mysql_query, redis_get] 且 dispatcher 仅注册 mysql_query
  When validateContract 后
  Then availability=UNAVAILABLE
  And unavailableReason 包含 "redis_get"
```

### US-4: 目录 Skill 扫描
```gherkin
As a skill author
I want to bundle a skill with auxiliary files in a directory
So that reference docs and configs travel with the skill
```

**验收标准 (AC):**
```gherkin
AC1: 目录含 SKILL.md 时仅解析 SKILL.md
  Given 目录 my-skill/ 含 SKILL.md + REFERENCE.md + config.json
  When scan 遍历到该目录
  Then 仅解析 SKILL.md
  And REFERENCE.md 不作为独立 skill 解析
  And SKIP_SUBTREE 跳过子树

AC2: 组织性目录递归
  Given 目录 category/ 无 SKILL.md，含 nested.md
  When scan 遍历到 category/
  Then 递归进入，解析 nested.md 为独立 skill
```

### US-5: 热重载
```gherkin
As a developer
I want skill files to be hot-reloaded when changed
So that I can iterate on skills without restarting the application
```

**验收标准 (AC):**
```gherkin
AC1: .md 文件变更触发刷新
  Given SkillHotReloader 正在监听 upload dir
  When 一个 .md 文件被创建/修改/删除
  Then SkillRegistry.refresh() 被调用
  And 日志记录刷新结果

AC2: 非 .md 文件不触发
  Given 一个 .txt 文件被修改
  Then refresh 不被调用
```

### US-6: Classpath 两遍扫描保护
```gherkin
As a platform developer
I want SnapAgent JAR skills to take precedence over host project skills
So that host classpath resources cannot accidentally shadow built-in skills
```

**验收标准 (AC):**
```gherkin
AC1: JAR skill 优先于 host skill
  Given JAR 内有 health-check.md 且宿主 classpath 也有 health-check.md
  When ClasspathSkillScanner.scan 被调用
  Then 结果仅含 JAR 版本 (source="builtin")
  And host 版本被跳过并记录 WARN
  And host skill source="host" 仅当 name 不冲突时
```

### US-7: InputSpec 参数校验
```gherkin
As a skill author
I want the system to validate InputSpec types and enforce enum options
So that invalid skill parameters are caught at load time, not at runtime
```

**验收标准 (AC):**
```gherkin
AC1: enum 类型必须有非空 options
  Given frontmatter inputs 含 type="enum" 但无 options
  When SkillLoader.parse 被调用
  Then availability == INVALID
  And unavailableReason 包含 "enum type without options"

AC2: 合法 enum 带 options
  Given frontmatter inputs 含 type="enum" options=["A","B","C"]
  When parse 被调用
  Then availability == AVAILABLE
  And inputs[0].options 包含 ["A","B","C"]
```

### US-8: Refresh 异常回滚
```gherkin
As a platform operator
I want skill refresh to preserve the old cache when scanning fails
So that a transient filesystem error doesn't wipe all loaded skills
```

**验收标准 (AC):**
```gherkin
AC1: refresh 扫描异常时保留旧缓存
  Given registry 已有 3 个 skill 且 scan 抛 RuntimeException
  When refresh() 被调用
  Then 返回 RefreshResult 含旧缓存 counts
  And cache 不被替换 (get("skill-a") 仍非 null)

AC2: 空工具列表 skill 保持 AVAILABLE
  Given skill.tools 为空列表 (纯 LLM skill)
  When validateContract 被调用
  Then availability == AVAILABLE (不因空 tools 标 UNAVAILABLE)
```

---

## 2.5 用户故事地图

| 用户阶段 | 用户故事 | 价值目标 | 衡量指标 | 依赖关系 |
|----------|----------|----------|----------|----------|
| 解析 | US-1 Frontmatter | 安全加载 | 解析成功率 > 95% | - |
| 加载 | US-2 两层系统 | 零配置+可扩展 | builtin 可用率 100% | US-1 |
| 校验 | US-3 工具契约 | 可用性明确 | unavailable 标记准确 | US-2 |
| 组织 | US-4 目录 skill | 支持辅助文件 | 目录扫描正确 | US-1 |
| 迭代 | US-5 热重载 | 无需重启 | 文件变更后 < 5s 生效 | US-2 |
| 保护 | US-6 JAR 优先 | 防覆盖 | 0 次误覆盖 | US-2 |
| 校验 | US-7 InputSpec | 参数安全 | enum 校验 100% | US-1 |
| 容错 | US-8 Refresh 回滚 | 缓存安全 | 旧缓存保留 100% | US-2 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 用例名称 | 优先级 | 对应AC | 测试类型 |
|--------|----------|--------|--------|----------|
| UC-01 | 合法 frontmatter 全字段解析 | P0 | US-1 AC1 | 单元 |
| UC-02 | 缺 name 标记 INVALID | P0 | US-1 AC2 | 单元 |
| UC-03 | 缺 description 标记 INVALID | P0 | US-1 AC2 | 单元 |
| UC-04 | 缺 closing delimiter 标记 INVALID | P0 | US-1 | 单元 |
| UC-05 | inputs 含 enum+options 解析 | P0 | US-1 | 单元 |
| UC-06 | shortcuts 解析 | P1 | US-1 | 单元 |
| UC-07 | required-permission 解析 | P1 | US-1 | 单元 |
| UC-08 | builtin+custom 合并 | P0 | US-2 AC1 | 单元 |
| UC-09 | custom 覆盖 builtin | P0 | US-2 AC2 | 单元 |
| UC-10 | 删除 custom 恢复 builtin | P0 | US-2 AC3 | 单元 |
| UC-11 | 工具缺失标记 UNAVAILABLE | P0 | US-3 AC2 | 单元 |
| UC-12 | 目录 skill 仅解析 SKILL.md | P0 | US-4 AC1 | 单元 |
| UC-13 | 组织性目录递归 | P0 | US-4 AC2 | 单元 |
| UC-14 | 嵌套目录 skill | P1 | US-4 | 单元 |
| UC-15 | refresh 拾取新文件 | P0 | US-2 | 单元 |
| UC-16 | refresh 返回计数 | P0 | US-2 | 单元 |
| UC-17 | 并发安全读写 | P0 | US-2 | 单元 |
| UC-18 | 重复 custom name last-wins | P1 | US-2 | 单元 |

### 3.2 详细用例 (Gherkin格式)

#### UC-01: 合法 frontmatter 全字段解析
```gherkin
@priority:high @type:unit
Feature: Valid frontmatter parsing

  Scenario: All required fields present
    Given content starts with "---"
    And YAML block contains name="test-skill", description="测试", tools=[mysql_query]
    And inputs section has key=skuCode, label=件号, required=true, type=string
    And body contains "WHERE sku_code='{skuCode}'"
    When SkillLoader.parse(content)
    Then meta.name == "test-skill"
    And meta.description == "测试"
    And meta.tools contains exactly ["mysql_query"]
    And meta.inputs has size 1
    And meta.inputs[0].key == "skuCode"
    And meta.availability == AVAILABLE
    And meta.body contains "{skuCode}"
```

#### UC-02: 缺 name 标记 INVALID
```gherkin
@priority:high @type:unit
Feature: Missing name field

  Scenario: frontmatter without name
    Given content YAML block has description and tools but no name
    When parse(content)
    Then availability == INVALID
    And unavailableReason contains "name"
```

#### UC-04: 缺 closing delimiter
```gherkin
@priority:high @type:unit
Feature: Missing closing delimiter

  Scenario: No closing "---" found
    Given content starts with "---" but no second "---"
    When parse(content)
    Then availability == INVALID
    And unavailableReason contains "frontmatter"
```

#### UC-08: builtin+custom 合并
```gherkin
@priority:high @type:unit
Feature: Builtin and custom skill merge

  Scenario: Two distinct skills from two tiers
    Given builtin list contains skill "builtin-skill" (source=builtin)
    And upload dir contains "custom.md" with name "custom-skill"
    When SkillRegistry is constructed
    Then all() has size 2
    And get("builtin-skill").source == "builtin"
    And get("custom-skill").source == "custom"
```

#### UC-09: custom 覆盖 builtin
```gherkin
@priority:high @type:unit
Feature: Custom overrides builtin

  Scenario: Same name in both tiers
    Given builtin list has "shared-skill" (body="builtin body")
    And upload dir has "override.md" with name "shared-skill" (body="custom body")
    When SkillRegistry is constructed
    Then all() has size 1 (only custom)
    And get("shared-skill").source == "custom"
    And get("shared-skill").overridesBuiltin == true
    And get("shared-skill").body contains "custom body"
```

#### UC-10: 删除 custom 恢复 builtin
```gherkin
@priority:high @type:unit
Feature: Delete custom restores builtin

  Scenario: Delete override file then refresh
    Given custom "shared-skill" overrides builtin
    When the override file is deleted and refresh() is called
    Then get("shared-skill").source == "builtin"
    And get("shared-skill").overridesBuiltin == false
```

#### UC-11: 工具缺失标记 UNAVAILABLE
```gherkin
@priority:high @type:unit
Feature: Tool contract validation

  Scenario: Skill declares tool not registered
    Given skill.tools=[mysql_query, redis_get]
    And dispatcher only has mysql_query registered
    When SkillRegistry validates the contract
    Then availability == UNAVAILABLE
    And unavailableReason contains "redis_get"
```

#### UC-12: 目录 skill 仅解析 SKILL.md
```gherkin
@priority:high @type:unit
Feature: Directory skill scanning

  Scenario: Directory with SKILL.md and auxiliary files
    Given directory "my-dir-skill/" contains SKILL.md, REFERENCE.md, config.json
    When scan visits the directory
    Then only SKILL.md is parsed
    And REFERENCE.md is NOT parsed as standalone skill
    And get("REFERENCE") returns null
    And SKIP_SUBTREE prevents recursing into subdirectories
```

#### UC-13: 组织性目录递归
```gherkin
@priority:high @type:unit
Feature: Organizational directory recursion

  Scenario: Directory without SKILL.md contains standalone .md
    Given directory "category/" has no SKILL.md but has "nested.md"
    When scan visits "category/"
    Then it recurses (CONTINUE)
    And "nested.md" is parsed as standalone skill
```

#### UC-15: refresh 拾取新文件
```gherkin
@priority:high @type:unit
Feature: Refresh picks up new files

  Scenario: Add file then refresh
    Given registry has 1 skill after initial scan
    When a new "b.md" is written and refresh() is called
    Then all() has size 2
    And get("skill-b") is not null
```

#### UC-17: 并发安全
```gherkin
@priority:high @type:unit
Feature: Concurrent read/write safety

  Scenario: 4 readers + 1 refresher concurrent
    Given registry with 1 skill
    When 4 threads read 100x and 1 thread refreshes 50x concurrently
    Then no exception thrown
    And get("skill-a") is not null after completion
```

---

## 4. 接口规格 (API Specs)

### 4.2 内部接口

#### SkillLoader.parse
```java
/**
 * Parse skill .md content into SkillMeta.
 * Uses snakeyaml SafeConstructor (no arbitrary object construction).
 *
 * @param content raw .md file text
 * @return SkillMeta (AVAILABLE or INVALID), never null
 *
 * 测试要点:
 *   - TC1: valid frontmatter all fields
 *   - TC2: missing name/description -> INVALID
 *   - TC3: missing closing delimiter -> INVALID
 *   - TC4: null/empty content -> INVALID
 *   - TC5: enum input with options
 *   - TC6: shortcuts parsing
 *   - TC7: required-permission parsing
 */
SkillMeta parse(String content);
```

#### SkillRegistry
```java
/**
 * Two-tier skill registry: builtin (classpath) + custom (filesystem).
 * Custom overrides builtin by name; delete custom restores builtin.
 * Cache stored in volatile holder; refresh atomically replaces.
 */
List<SkillMeta> all();                    // merged list
SkillMeta get(String name);               // custom if exists, else builtin
boolean isBuiltin(String name);           // true if builtin exists
Path getCustomSkillPath(String name);     // file/dir path for delete
RefreshResult refresh();                  // re-scan + atomically replace cache
```

#### ClasspathSkillScanner.scan
```java
/**
 * Scan classpath for builtin skills. Two-pass: JAR resources first,
 * host project resources second (cannot override JAR names).
 * Normalizes classpath: to classpath*: for multi-root scanning.
 */
List<SkillMeta> scan(String classpathDir);
```

#### SkillHotReloader
```java
/**
 * Watch upload dir for .md file changes, trigger SkillRegistry.refresh().
 * Daemon thread, HIGH sensitivity on macOS.
 * No-op if dir doesn't exist at start().
 */
void start();
void stop();
```

---

## 5. 数据规格 (Data Specs)

### 5.1 数据模型

```yaml
实体: SkillMeta (immutable)
字段:
  - name: String (必填, 唯一标识)
  - description: String (必填)
  - tools: List<String> (可选, 空列表=纯 LLM skill)
  - inputs: List<InputSpec> (可选)
  - shortcuts: List<Shortcut> (可选)
  - body: String (正文, 含 {key} 占位符)
  - availability: enum [AVAILABLE, UNAVAILABLE, INVALID]
  - unavailableReason: String
  - source: String ["builtin" | "custom" | "host"]
  - overridesBuiltin: boolean
  - requiredPermission: String

实体: InputSpec (immutable)
  - key: String (必填)
  - label: String
  - required: boolean
  - type: String [string | enum | date | number | boolean]
  - options: List<String> (enum 必填)
  - defaultValue: String

实体: SkillRegistry.Cache
  - all: List<SkillMeta> (unmodifiable)
  - byName: Map<String, SkillMeta> (unmodifiable)
  - customSkillPaths: Map<String, Path> (unmodifiable)
```

### 5.3 测试数据
```yaml
标准 valid skill:
  content: |
    ---
    name: test-skill
    description: 测试 skill
    tools: [mysql_query]
    inputs:
      - key: skuCode
        label: 件号
        required: true
        type: string
    ---
    # Body
    WHERE sku_code='{skuCode}'

目录 skill 结构:
  my-dir-skill/
    SKILL.md       # 入口, 被解析
    REFERENCE.md   # 辅助, 被跳过
    config.json    # 辅助, 被跳过
```

---

## 6. 错误处理规格 (Error Handling)

### 6.1 错误码定义

| 错误码 | 级别 | 描述 | 行为 |
|--------|------|------|------|
| INVALID_FRONTMATTER | WARN | frontmatter 不合规 | skill 标 INVALID, 跳过 |
| YAML_PARSE_ERR | WARN | YAML 解析异常 | skill 标 INVALID, reason 含错误信息 |
| TOOL_MISSING | WARN | 声明工具未注册 | skill 标 UNAVAILABLE, reason 列出缺失工具 |
| DIR_NOT_FOUND | WARN | upload 目录不存在 | 仅加载 builtin, 日志 WARN |
| DUPLICATE_NAME | WARN | 重复 custom skill name | last-scanned wins, 前一个被覆盖 |
| HOST_SHADOWED | WARN | host skill 与 JAR 同名 | host 版本跳过, 使用 JAR 版本 |

### 6.2 错误场景
```gherkin
Scenario: Upload dir null
  When uploadDir is null
  Then only builtin skills are loaded
  And log warning "upload-skills-dir is null"

Scenario: Upload dir not a directory
  When uploadDir path is a file (not directory)
  Then log warning and load only builtin skills

Scenario: Scan throws IOException
  When Files.walkFileTree throws IOException
  Then log warning and return empty custom list

Scenario: Refresh fails
  When refresh() scan throws RuntimeException
  Then return RefreshResult with existing cache counts
  And cache is NOT replaced
```

---

## 7. 非功能需求 (NFR)

### 7.1 性能要求
```yaml
指标:
  - skill 解析: 单文件 < 10ms
  - 启动扫描: 100 个 skill < 2s
  - refresh: 原子替换, 读不加锁
  - 热重载检测延迟: macOS < 2s (HIGH sensitivity)
```

### 7.4 可测试性要求
- [x] SkillLoader 单元覆盖率 > 80%
- [x] SkillRegistry 两层合并全覆盖
- [x] 目录扫描规则全覆盖
- [x] 并发安全有测试

---

## 8. 测试策略 (Test Strategy)

### 8.1 测试金字塔
```
   /\
  /  \  E2E (完整 skill 加载 — 未来)
 /____\
/        \  集成 (SkillRegistry + TempDir + mock ToolDispatcher)
/          \  单元 (SkillLoader, SkillMeta, InputSpec, ClasspathSkillScanner, SkillHotReloader)
```

### 8.2 测试清单

| 测试ID | 类型 | 描述 | 自动化 | 优先级 |
|--------|------|------|--------|--------|
| UT-001 | 单元 | 合法 frontmatter 全字段 | 是 | P0 |
| UT-002 | 单元 | 缺 name INVALID | 是 | P0 |
| UT-003 | 单元 | 缺 description INVALID | 是 | P0 |
| UT-004 | 单元 | 缺 tools 默认空列表 | 是 | P0 |
| UT-005 | 单元 | 缺 closing delimiter | 是 | P0 |
| UT-006 | 单元 | null/empty content | 是 | P0 |
| UT-007 | 单元 | enum input + options | 是 | P0 |
| UT-008 | 单元 | input default value | 是 | P1 |
| UT-009 | 单元 | shortcuts 解析 | 是 | P1 |
| UT-010 | 单元 | required-permission | 是 | P1 |
| UT-011 | 单元 | body 提取 | 是 | P0 |
| UT-012 | 单元 | builtin+custom 合并 | 是 | P0 |
| UT-013 | 单元 | custom 覆盖 builtin | 是 | P0 |
| UT-014 | 单元 | 删除 custom 恢复 builtin | 是 | P0 |
| UT-015 | 单元 | 工具缺失 UNAVAILABLE | 是 | P0 |
| UT-016 | 单元 | null dispatcher UNAVAILABLE | 是 | P0 |
| UT-017 | 单元 | 目录 skill SKILL.md | 是 | P0 |
| UT-018 | 单元 | 辅助 .md 跳过 | 是 | P0 |
| UT-019 | 单元 | 组织性目录递归 | 是 | P0 |
| UT-020 | 单元 | 嵌套目录 skill | 是 | P1 |
| UT-021 | 单元 | refresh 新增/删除/修改 | 是 | P0 |
| UT-022 | 单元 | refresh 返回计数 | 是 | P0 |
| UT-023 | 单元 | 并发安全 | 是 | P0 |
| UT-024 | 单元 | 重复 name last-wins | 是 | P1 |
| UT-025 | 单元 | custom skill path 获取 | 是 | P1 |
| UT-026 | 单元 | required-permission 保留 | 是 | P1 |
| UT-027 | 单元 | SkillMeta with* 方法 | 是 | P1 |

### 8.3 Mock策略
```yaml
需要Mock的外部依赖:
  - ToolDispatcher: Mockito mock, when().availableToolNames() 返回 Set
  - ToolProvider: Mockito mock, when().name()/schema()
  - ResourcePatternResolver: Mockito mock (for ClasspathSkillScanner)
  - 文件系统: @TempDir (JUnit5)
```

### 8.4 已有测试覆盖

| 测试类 | 文件路径 | 覆盖内容 |
|--------|----------|----------|
| SkillLoaderTest | `snap-agent-core/src/test/java/.../skill/SkillLoaderTest.java` | 全字段解析、缺 name/description INVALID、缺 closing delimiter、null/empty content、tools 非列表 INVALID、enum+options、input default、shortcuts 解析+缺 label INVALID、required-permission 解析+默认空、body 提取、多工具、inputs block list 格式 |
| SkillRegistryTest | `snap-agent-core/src/test/java/.../skill/SkillRegistryTest.java` | 三文件加载、AVAILABLE/UNAVAILABLE、INVALID 跳过、不存在目录、无 .md 文件、refresh 新增/删除/修改 body、null skill 查找、refresh 计数、null 目录、null dispatcher、并发安全 (4 reader + 1 refresher)、builtin-only、builtin+custom 合并、custom 覆盖 builtin、删除恢复 builtin、isBuiltin、custom path、目录 skill (SKILL.md)、辅助文件跳过、组织性目录递归、嵌套目录、重复 name last-wins、source=custom、required-permission 保留+降级+解析 |
| SkillMetaTest | `snap-agent-core/src/test/java/.../skill/SkillMetaTest.java` | 全字段持有、null tools/inputs -> 空列表、toString、所有 availability 值、requiredPermission 默认空+持有+with* 保留+null 处理 |

### 8.5 测试缺口 (Bug 候选)

| 缺口ID | 描述 | 风险 | 优先级 |
|--------|------|------|--------|
| GAP-1 | ClasspathSkillScanner 无独立测试类 (starter 模块)，两遍扫描逻辑、classpath: -> classpath*: 规范化、isFromSnapAgentJar 判断、parseGroupedResources 目录分组 均未测试 | host skill 可能误覆盖 JAR 内置 skill | P0 |
| GAP-2 | SkillHotReloader 无独立测试类，start/stop/watchLoop、.md 过滤、目录不存在 no-op、daemon 线程 均未测试 | 热重载可能不触发或泄漏线程 | P0 |
| GAP-3 | SkillLoader.ensureYamlValueQuoted (auto-quoting) 未直接测试，含冒号的值可能解析失败 | 含 "URI: http://..." 的 description 可能解析错误 | P1 |
| GAP-4 | InputSpec 无独立测试类，type=options 校验 (enum 必须有 options) 未测试 | enum 类型无 options 时 UI 渲染可能出错 | P1 |
| GAP-5 | SkillRegistry.scan 失败异常处理 (RuntimeException catch) 仅在构造函数有 log.warn，refresh 失败返回旧 cache counts 但 cache 不替换，该路径无测试 | refresh 异常时可能返回错误计数 | P1 |
| GAP-6 | SkillRegistry validateContract 对空 tools 列表 (skill 无 tools 声明) 的行为未测试 | 纯 LLM skill 可能被误标 UNAVAILABLE | P1 |
| GAP-7 | SkillHotReloader 在 macOS 上的 SensitivityWatchEventModifier.HIGH 行为无集成测试验证 | 热重载延迟可能 > 2s | P2 |
| GAP-8 | ClasspathSkillScanner.parseResource 读取大文件 (readAll) 无内存限制测试 | 大 skill 文件可能 OOM | P2 |
| GAP-9 | SkillLoader.parse 对 frontmatter 中多余未知字段的处理 (忽略 vs 报错) 未测试 | 未知字段可能导致解析异常 | P2 |
| GAP-10 | SkillRegistry 对 uploadDir 可读但不可写的情况未测试 | refresh 时写入失败但缓存未更新 | P2 |

---

## 9. 依赖与前置条件

### 9.1 外部依赖
| 依赖 | 状态 | 降级策略 |
|------|------|----------|
| snakeyaml (SafeConstructor) | 已完成 | 由 Spring Boot BOM 管控 |
| Spring ResourcePatternResolver | 已完成 | ClasspathSkillScanner 依赖 |
| Java NIO WatchService | 已完成 | macOS 轮询, Linux inotify |

### 9.2 内部依赖
- [x] SkillLoader (已完成)
- [x] SkillMeta (已完成)
- [x] ToolDispatcher (已完成)

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 变更内容 |
|------|------|------|----------|
| 2.0 | 2026-07-23 | snap-agent team | 初始 TDD 规格 |

### 12.2 参考文档
- `docs/embeed-skills-agent/02-skill-loading.md` — Skill 加载设计
- `docs/superpowers/specs/2026-07-03-two-tier-skill-system-design.md` — 两层 skill 系统设计

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| builtin | 内置 skill, 打包在 JAR 中, 只读, 不可删除 |
| custom | 上传 skill, 存储在文件系统, 读写, 持久化 |
| overridesBuiltin | custom skill 同名覆盖 builtin 的标记 |
| directory skill | 目录含 SKILL.md, 整个目录为一个 skill |
| organizational dir | 无 SKILL.md 的目录, 递归扫描子内容 |
| frontmatter | .md 文件首部 YAML 元数据块, 以 `---` 分隔 |
