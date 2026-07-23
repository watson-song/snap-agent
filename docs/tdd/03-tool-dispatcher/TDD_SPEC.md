# TDD需求规格说明书 — ToolDispatcher 路由与插件体系

> 版本: 2.0 | 模块: snap-agent-core / tool | 状态: 开发中

---

## 1. 需求元信息

```yaml
需求ID: REQ-03-TOOL-DISPATCHER
需求名称: ToolDispatcher 路由 + pluginOverrides + ToolProvider SPI + ToolPlugin 注解
优先级: P0
迭代: Sprint v0.5
负责人: core-team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: v1.0 的 `ToolDispatcher` 持有 `final unmodifiableMap`，无 add/remove API；`ToolPlugin` SPI 仅为元数据层，无法兑现"plugin 可热插拔 tool"承诺。
- **用户价值**: 让宿主可运行时注册/启停/设默认/反注册 plugin，同一 toolType 可有多 plugin，按 default 或显式 `pluginOverrides` 路由，减少 50% 硬编码工具迁移成本。
- **成功指标**: 旧 `@Component ToolProvider` bean 零修改仍可用；新 plugin 引用 `pluginOverrides` 端到端 < 2s。

### 1.2 范围边界
- **包含**: `PluginDescriptor`/`PluginRegistry` SPI、`ToolDispatcher` 基于 registry 路由、`pluginOverrides`、`@ToolPluginAnnotation`、`ToolPlugin` SPI、`InMemoryPluginRegistry`。
- **不包含**: JAR 上传 + URLClassLoader 隔离（见 09-plugin-mcp）、REST API 端点、Maven archetype。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 |
|--------|------|------|------|----------|
| R1 | `pluginOverrides` 指向已禁用 plugin 静默失效 | 中 | 高 | dispatcher 返回 `ToolResult.error`，LLM 自纠 |
| R2 | 并发 register/disable 与 dispatch 竞争 | 中 | 中 | `ConcurrentHashMap` + `volatile` flag |
| R3 | system plugin 误被反注册 | 低 | 高 | `unregister` 抛 `UnsupportedOperationException` |

**关键假设**: 1 plugin = 1 tool；LLM 不感知 plugin，只看 toolType；现有 skill 不传 overrides → 走 default → 行为一致。

---

## 2. 用户故事 (User Stories)

### US-1: 默认 plugin 路由
```gherkin
作为 Agent 执行循环
我希望 dispatch(toolType, args, ctx) 时自动路由到该 toolType 的默认 plugin
以便 现有 skill 不传 overrides 时行为 100% 向后兼容
```
**AC:** AC1: Given "mysql"(toolType="mysql_query", isDefault=true, enabled=true) 已注册 / When dispatch 且 overrides 为空 / Then provider.execute 调用一次且 isSuccess()=true；AC2: Given registry 为空 / When dispatch("unknown_type") / Then 返回 error 含 "no plugin registered for: unknown_type"。

### US-2: pluginOverrides 显式覆盖
```gherkin
作为 skill 编写者
我希望 在 POST /runs 时传 pluginOverrides["log_read"]="remote-log" 来选远程 plugin
以便 同一 toolType 的多个 plugin 可按场景显式选择，减少 30% 重复 plugin 部署
```
**AC:** AC1: Given default "local-log" 和非 default "remote-log" / When override={"log_read":"remote-log"} dispatch / Then "remote-log".execute 调用，"local-log" 从未调用；AC2: Given "remote-log" enabled=false / When override 指向它 / Then error 含 "plugin disabled: remote-log"。

### US-3: plugin 启停与默认切换
```gherkin
作为 运维管理员
我希望 调用 registry.enable/disable/setDefault 来运行时切换 plugin 状态
以便 不重启宿主即可降级或切换，运维变更窗口从 30 分钟降至 < 1s
```
**AC:** AC1: Given "log1"(default) 和 "log2"(同 toolType) 已注册 / When registry.disable("log1") / Then "log1".enabled==false 且 getDefault("log_read")=="log2"(自动提升); AC2: Given disabled "log1" / When registry.enable("log1") / Then "log1".enabled==true; AC3: Given registry 为空 / When registry.setDefault("log_read", "nonexistent") / Then 不抛异常.

### US-4: system plugin 保护
```gherkin
作为 平台开发者
我希望 system=true 的 plugin 不可被 unregister
以免 内置工具被误删导致 skill 全量失败
```
**AC:** AC1: Given "mysql"(system=true) 已注册 / When registry.unregister("mysql") / Then 抛 UnsupportedOperationException 含 "cannot unregister system plugin"; AC2: Given "log1"(default, system=false) 和 "log2"(非 default, 同 toolType) 已注册 / When registry.unregister("log1") / Then getDefault("log_read")=="log2" 且 "log2".isDefault()==true.

### US-5: @ToolPluginAnnotation 元数据声明
```gherkin
作为 plugin 开发者
我希望 用 @ToolPluginAnnotation(id, toolType, version, isDefault) 标注类
以便 扫描时自动读取元数据，减少 80% YAML 清单维护成本
```
**AC:** AC1: Given 类标注 @ToolPluginAnnotation(id="remote-log", toolType="log_read", version="2.0.0", isDefault=true) / When 读取注解 / Then 各字段匹配且 displayName/description 为空串；AC2: Given 最小注解 @ToolPluginAnnotation(id="m", toolType="metrics_query") / Then version="1.0.0", isDefault=false。

### US-6: ToolResult 截断保护
```gherkin
作为 系统运维
我希望 dispatch 超长 tool result 自动截断
以防 LLM context 窗口被工具输出撑爆
```
**AC:** AC1: Given maxToolResultChars=50 且 provider.execute 返回 content 长度 80 / When dispatch / Then result.isTruncated()==true 且 content 长度 <= 50; AC2: Given provider.execute 返回 error result / When dispatch / Then error result 不被截断.

### US-7: 审计回调隔离
```gherkin
作为 安全审计员
我希望 dispatch 每次调用都触发 audit callback 且 callback 异常不破坏主流程
以便 审计日志完整且审计故障不影响诊断
```
**AC:** AC1: Given ctx.auditCallback != null / When dispatch 成功 / Then callback.onToolExecuted 被调用 once，toolName 为 toolType（非 pluginId）; AC2: Given callback.onToolExecuted 抛 RuntimeException / When dispatch / Then 不向上抛出，正常返回 ToolResult.

### US-8: activePlugins 去重计算
```gherkin
作为 Agent 执行循环
我希望 activePlugins() 每个 toolType 只暴露一个 plugin（default 或 override）
以便 LLM 看到的工具定义无重复
```
**AC:** AC1: Given "local-log"(default, enabled) 和 "remote-log"(enabled) 同 toolType / When activePlugins() / Then 返回 size==1 且 pluginId=="local-log"; AC2: Given overrides={"log_read":"remote-log"} / When activePlugins(overrides) / Then 返回 size==1 且 pluginId=="remote-log"; AC3: Given "mysql"(default, enabled=false) / When activePlugins() / Then 返回空集合.

### US-9: 旧构造器向后兼容
```gherkin
作为 宿主开发者
我希望 现有 @Component ToolProvider bean 零修改仍可工作
以便 升级 SnapAgent 版本时无需改宿主代码
```
**AC:** AC1: Given 旧 @Component ToolProvider bean 已注册 / When ToolDispatcher 构造 / Then bean 被自动包装为 PluginDescriptor(system=true, isDefault=true) 且 dispatch 正常路由; AC2: Given 旧 bean 和新 @ToolPlugin 注解 plugin 同 toolType / When dispatch 无 override / Then 旧 bean (default) 被调用.

---

## 3. 功能规格 (Functional Specs)

### 3.2 详细用例 (Gherkin)

#### UC-01: dispatch 默认路由与 ctx=null
```gherkin
@priority:high @type:unit
功能: 默认 plugin 路由
  场景: 命中 default plugin
    Given registry 注册 "mysql"(toolType="mysql_query", isDefault=true, enabled=true)
    And provider.execute 返回 ToolResult.success("1", 1, 10L)
    When dispatch("mysql_query", {sql:"SELECT 1"}, ctx) 且 ctx.pluginOverrides 为空
    Then result.isSuccess() == true 且 provider.execute 被调用 once
    And audit callback 收到 toolName="mysql_query"
  场景: ctx 为 null 时仍能路由
    Given registry 注册 default plugin
    When dispatch("mysql_query", args, null)
    Then 不抛 NPE 且仍调用 provider.execute(args, null)
```

#### UC-02: dispatch pluginOverrides 覆盖
```gherkin
@priority:high @type:unit
功能: pluginOverrides 显式覆盖
  场景: override 命中非 default plugin
    Given "local-log"(default) 和 "remote-log"(非 default) 同 toolType="log_read"
    And ctx.pluginOverrides = {"log_read":"remote-log"}
    When dispatch("log_read", {}, ctx)
    Then "remote-log".execute 调用 once，"local-log".execute 从未调用
  场景: override 指向不存在/已禁用 pluginId
    Given ctx.pluginOverrides = {"log_read":"nonexistent"}
    When dispatch("log_read", {}, ctx)
    Then 返回 error 含 "no plugin registered for: log_read"
    Given "remote-log" enabled=false 且 override 指向它
    When dispatch("log_read", {}, ctx)
    Then 返回 error 含 "plugin disabled: remote-log"
```

#### UC-03: registry register 边界
```gherkin
@priority:high @type:unit
功能: register 冲突与自动默认
  场景: 重复 pluginId 抛异常
    Given registry 已注册 "mysql"
    When 注册新 plugin "mysql"
    Then 抛 IllegalArgumentException 含 "plugin already registered: mysql"
  场景: 首个 plugin 自动成 default + 新 default 清旧
    Given registry 为空
    When 注册 plugin(toolType="log_read", isDefault=false)
    Then getDefault("log_read")==该 plugin 且 isDefault()==true
    Given "log1"(isDefault=true) 已注册
    When 注册 "log2"(同 toolType, isDefault=true)
    Then "log1".isDefault()==false 且 "log2".isDefault()==true 且 getDefault=="log2"
```

#### UC-04: registry unregister system 保护与自动提升
```gherkin
@priority:high @type:unit
功能: unregister 与 system 保护
  场景: system plugin 不可反注册
    Given "mysql"(system=true) 已注册
    When registry.unregister("mysql")
    Then 抛 UnsupportedOperationException 含 "cannot unregister system plugin"
  场景: 反注册 default 后自动提升 + unknown id no-op
    Given "log1"(default) 和 "log2"(非 default, 同 toolType) 已注册
    When registry.unregister("log1")
    Then getDefault("log_read")=="log2" 且 "log2".isDefault()==true
    Given registry 为空
    When registry.unregister("nonexistent")
    Then 不抛异常
```

#### UC-05: registry setDefault 清旧
```gherkin
@priority:high @type:unit
功能: setDefault 切换
  场景: 设新 default 清同 type 其他 + unknown no-op
    Given "log1"(default) 和 "log2"(非 default) 同 toolType="log_read"
    When registry.setDefault("log_read", "log2")
    Then "log1".isDefault()==false 且 "log2".isDefault()==true 且 getDefault=="log2"
    Given registry 为空
    When registry.setDefault("log_read", "nonexistent")
    Then 不抛异常
```

#### UC-06: activePlugins 去重与 override
```gherkin
@priority:high @type:unit
功能: activePlugins 计算
  场景: 同 toolType 多 plugin 只暴露 default + override 替换
    Given "local-log"(default, enabled) 和 "remote-log"(enabled) 已注册
    When activePlugins()
    Then 返回 size==1 且 pluginId=="local-log"
    Given overrides = {"log_read":"remote-log"}
    When activePlugins(overrides)
    Then 返回 size==1 且 pluginId=="remote-log"
  场景: 禁用 plugin 不出现 + override 指向禁用不替换
    Given "mysql"(default, enabled=false) 已注册
    When activePlugins()
    Then 返回集合为空
    Given "local"(default, enabled) 和 "remote"(enabled=false) 且 overrides={"log_read":"remote"}
    When activePlugins(overrides)
    Then 返回 size==1 且 pluginId=="local"
```

#### UC-07: truncate 超长结果
```gherkin
@priority:medium @type:unit
功能: ToolResult 截断
  场景大纲: 内容长度边界
    Given maxToolResultChars = <max>，provider.execute 返回 content 长度 <len>
    When dispatch 被调用
    Then result.isTruncated() == <truncated>
    例子:
      | max | len | truncated | 说明       |
      | 50  | 80  | true      | 超限截断   |
      | 50  | 50  | false     | 等于上限   |
      | 5   | 25(error) | false | error 不截 |
```

#### UC-08: audit callback 触发
```gherkin
@priority:medium @type:unit
功能: audit 回调
  场景: 命中/未知 toolType 均触发 + audit 异常不破坏主流程
    Given ctx.auditCallback != null
    When dispatch 成功执行
    Then callback.onToolExecuted("mysql_query", args, result) 调用 once，toolName 是 toolType 不是 pluginId
    Given ctx.auditCallback != null
    When dispatch("foo", ...) 返回 error
    Then callback.onToolExecuted("foo", args, errorResult) 调用 once
    Given callback.onToolExecuted 抛 RuntimeException
    When dispatch 被调用
    Then 不向上抛出，正常返回 ToolResult
```

---

## 4. 接口规格 (API Specs)

```java
/** ToolDispatcher.dispatch: 路由 tool_use 调用
 * 1. ctx.pluginOverrides[toolType] → 指定 plugin  2. registry.getDefault(toolType) → 默认
 * 3. 命中且 enabled → provider.execute(args, ctx)  4. 未命中或禁用 → ToolResult.error
 * 测试要点: 默认命中/override命中/未知type/禁用plugin/ctx=null/provider返回null/provider抛异常 */
ToolResult dispatch(String toolType, Map<String,Object> args, ToolContext ctx);
/** PluginRegistry: register(冲突→IllegalException, 首个toolType自动default, 新default清旧)
 *   unregister(system→UnsupportedOperationException, default被删时自动提升下一个)
 *   setDefault(清同type其他default, unknown pluginId no-op) */
void register(PluginDescriptor); void unregister(String); void setDefault(String, String);
```

---

## 5. 数据规格 (Data Specs)

```yaml
实体: PluginDescriptor
字段:
  pluginId: String 非 null 唯一 | toolType: String 非 null | displayName/description/version: String
  isDefault: volatile boolean(可变) | enabled: volatile boolean(可变) | system: boolean(不可变)
  provider: ToolProvider | classLoader/jarPath/pluginContext: 自定义非 null, system 为 null
约束: pluginId/toolType 非 null 否则构造抛 IllegalArgumentException
测试数据: {mysql,mysql_query,system=true} {local-log,log_read} {remote-log,log_read,system=false}
边界: pluginId=null→抛异常 | provider返回null→error | provider抛异常→error
```

---

## 6. 错误处理规格 (Error Handling)

| 错误码 | 级别 | 描述 | 告警策略 |
|--------|------|------|----------|
| E301 | WARN | 未注册 toolType | 不告警 |
| E302 | WARN | plugin 禁用 | 不告警 |
| E303 | ERROR | provider 返回 null | 不告警 |
| E304 | ERROR | provider 抛异常 | 连续 3 次告警 |
| E305 | WARN | register 冲突 | 不告警 |
| E306 | ERROR | system unregister | 告警 |

```gherkin
场景: provider.execute 抛 RuntimeException
  When provider.execute 抛 new RuntimeException("boom")
  Then dispatcher 返回 ToolResult.error 含 "plugin execution failed: boom" 且 audit 仍触发
场景: provider.execute 返回 null
  When provider.execute 返回 null
  Then dispatcher 返回 ToolResult.error 含 "tool returned null result" 且不抛 NPE
```

---

## 7. 非功能需求 (NFR)

- **性能**: dispatch 路由 P95<1ms；activePlugins<5ms(100 plugin)；registry 并发>10000 ops/s
- **安全**: system plugin 不可反注册；audit 异常不破坏主流程；pluginOverrides 不可变 Map
- **可测试性**: 核心逻辑覆盖率>90%；所有 public 方法有测试；并发+边界值测试

---

## 8. 测试策略 (Test Strategy)

| 测试ID | 类型 | 描述 | 优先级 |
|--------|------|------|--------|
| UT-301~305 | 单元 | dispatch 默认/override/未知/禁用/ctx=null | P0 |
| UT-306~307 | 单元 | dispatch provider 返回 null/抛异常 | P1 |
| UT-308~309 | 单元 | truncate 超长/audit callback 触发与隔离 | P1 |
| UT-310~311 | 单元 | activePlugins 去重+override/跳过禁用 | P0 |
| UT-312~314 | 单元 | register 冲突/自动default/unregister system保护 | P0 |
| UT-315~316 | 单元 | unregister 自动提升/setDefault 清旧 | P0/P1 |
| UT-317 | 单元 | enable/disable | P0 |
| UT-318 | 单元 | @ToolPluginAnnotation 读取 | P1 |
| UT-319 | 单元 | 旧构造器向后兼容 | P0 |
| UT-320 | 并发 | register/disable 与 dispatch 竞争 | P1 |

**Mock 策略**: Mock ToolProvider/AuditCallback/PluginContext；不Mock InMemoryPluginRegistry/PluginDescriptor/ToolDispatcher

---

## 9. 依赖与前置条件

外部依赖: Mockito 5.x / JUnit 5 / AssertJ (已完成)
内部依赖: ToolProvider SPI / ToolResult / ToolContext / PluginContext (已完成)

---

## 10. 可观测性设计

日志: dispatch 入口记录 toolType+pluginId+override+durationMs+truncated+error
指标: tool_dispatch_count{toolType,pluginId,result} / tool_dispatch_duration_seconds / plugin_registry_size

---

## 11. 原型与交互参考

无 UI 交互，纯后端 SPI。

---

## 12. 附录

### 12.1 已有测试覆盖

| 测试文件 | 测试数 | 覆盖点 |
|----------|--------|--------|
| `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolDispatcherTest.java` | 20 | 默认/override 路由、未注册/禁用 error、truncate、audit、activePlugins、旧构造器、pluginContext 注入 |
| `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/InMemoryPluginRegistryTest.java` | 14 | register/unregister/enable/disable/setDefault、冲突、自动 default、system 保护、自动提升 |
| `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/tool/ToolPluginAnnotationTest.java` | 4 | 注解 id/toolType 读取、默认值、自定义值 |

**结论**: 核心路由逻辑、registry 生命周期、注解读取已较完整覆盖（38 个测试）。

### 12.2 测试缺口

| 缺口ID | 描述 | 优先级 | 建议测试 |
|--------|------|--------|----------|
| G-301 | `dispatch` 时 provider.execute 返回 null 分支未显式覆盖 | P1 | UT-306 |
| G-302 | `dispatch` 时 provider.execute 抛 RuntimeException 分支未覆盖 | P1 | UT-307 |
| G-303 | `unregister(null)` no-op 未覆盖 | P2 | UT-315a |
| G-304 | `setDefault` 对 unknown pluginId no-op 未覆盖 | P2 | UT-316a |
| G-305 | `activePlugins` override 指向禁用 plugin fallback 未覆盖 | P1 | UT-311a |
| G-306 | 并发 register/disable 与 dispatch 竞争 | P1 | UT-320 |
| G-307 | `ToolPlugin` SPI 接口（非注解）无单测 | P2 | UT-318a |
| G-308 | `PluginDescriptor` 构造 null pluginId/toolType 抛异常未覆盖 | P2 | UT-312a |
| G-309 | `buildToolDefinitions` @Deprecated 兼容性未覆盖 | P3 | UT-319a |
| G-310 | `availableToolTypes` 与 `availableToolNames` 等价性未覆盖 | P3 | UT-319b |

### 12.3 参考文档
- `docs/embeed-skills-agent/04-tools-and-mcp.md` | `docs/superpowers/specs/2026-07-21-plugin-architecture-refactor-design.md` | `docs/tdd/TEMPLATE.md`

### 12.4 术语表

| 术语 | 定义 |
|------|------|
| toolType | LLM 在 tool_use 中调用的工具名，等价于 `ToolProvider.name()` |
| pluginId | plugin 实例唯一标识，可与 toolType 不同 |
| pluginOverrides | skill 执行时传的 `toolType→pluginId` 映射 |
| system plugin | 内置不可删除的 plugin，`PluginDescriptor.system=true` |
| default plugin | 某 toolType 的默认 plugin，无 override 时 dispatcher 路由到此 |
