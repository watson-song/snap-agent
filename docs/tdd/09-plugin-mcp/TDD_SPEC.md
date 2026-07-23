# TDD 需求规格说明书 — Plugin & MCP 集成

> 版本: 1.0 | 日期: 2026-07-23 | 模块: snap-agent-spring-boot-2x-starter / snap-agent-core

---

## 1. 需求元信息

```yaml
需求ID: REQ-09-PLUGIN-MCP
需求名称: Plugin 上传/隔离/MCP SSE 集成
优先级: P0
迭代: v0.5
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: v1.0 `ToolPlugin` SPI 仅为元数据层，`ToolDispatcher.providers` 为 `final unmodifiableMap`，无 add/remove API。
- **用户价值**: 宿主可上传独立 JAR、隔离 ClassLoader、运行时启停；MCP Phase 2 接 SSE/HTTP 远端工具。
- **成功指标**: 上传→扫描→注册→`pluginOverrides` 路由全链路单测覆盖率 ≥ 80%。

### 1.2 范围边界
- **包含**: `PluginDescriptor`+`PluginRegistry` SPI、`ToolDispatcher` 重构、`@ToolPlugin` 注解 + `plugin-info.yml` 双来源、JAR 上传 + URLClassLoader 隔离、`snap-agent.tools.{pluginId}.*` 配置注入、REST API、Maven archetype、MCP SSE/HTTP 接入。
- **不包含**: 独立 jar 打包拆分、classpath 扫描、`@ConfigurationProperties` 强类型、plugin 间依赖、热升级。

## 1.3 风险与假设
- R1: ClassLoader GC 不及时（中/中，缓解：生产推荐 disable + 监控数量）
- R2: Plugin 启动非 daemon 线程（低/高，缓解：文档禁止 + ThreadMXBean 检测）
- R3: 恶意 plugin 代码执行（低/高，缓解：`snap-agent:plugin:manage` 权限）
- R4: MCP server 只读性远端保证（中/中，缓解：文档标注信任边界）

---

## 2. 用户故事 (User Stories)

### US-1: 上传自定义 plugin JAR
```gherkin
作为 宿主开发者
我希望 通过 REST 上传独立 JAR 并隔离 ClassLoader
以便 在不停机情况下扩展 LLM 可用工具集
```
**AC:**
```gherkin
AC1: 上传成功
  Given 含 @ToolPlugin 注解的合法 JAR (pluginId="remote-log")
  When POST /tools/plugins/upload
  Then 返回 200 + pluginId/toolType/version
  And JAR 落盘 <uploadDir>/remote-log/plugin.jar
  And registry.getPlugin("remote-log") 非空且 enabled=true

AC2: pluginId 路径穿越拒绝
  Given scanner 返回 pluginId="../../etc/evil"
  When uploader.upload(jarFile)
  Then 抛 IllegalArgumentException 含 "invalid pluginId"
  And registry.register 从未调用，临时 JAR 被删除
```

### US-2: 同 toolType 多 plugin + pluginOverrides 路由
```gherkin
作为 skill 作者
我希望 POST /runs 携带 pluginOverrides 指定具体 plugin
以便 LLM 不感知 plugin，dispatcher 按 override 路由
```
**AC:**
```gherkin
AC3: override 命中 non-default
  Given registry 注册 default "default-mysql" + non-default "remote-mysql"
  When POST /runs body pluginOverrides={"mysql_query":"remote-mysql"}
  Then 202 + taskId
  And dispatcher.activePlugins(overrides) 首元素 pluginId="remote-mysql"

AC4: override 校验失败
  Given registry 无 pluginId "nonexistent"
  When POST /runs body pluginOverrides={"mysql_query":"nonexistent"}
  Then 400 + error="INVALID_PLUGIN_OVERRIDE"
```

### US-3: plugin-info.yml 兜底扫描 — 作为 plugin 作者，我希望无 @ToolPlugin 时从 META-INF/snap-agent/plugin-info.yml 读元数据，以便用最少侵入方式打包 plugin。

### US-4: MCP SSE 远端工具适配 — 作为运维，我希望配置 snap-agent.mcp.servers 后自动拉取远端工具，以便不修改代码即可接入外部 MCP server。

### US-5: 反注册 + 资源清理
```gherkin
作为 宿主管理员
我希望 DELETE /tools/plugins/{id} 关闭 URLClassLoader 并删除 JAR
以便 不残留磁盘句柄和资源
```
**AC:**
```gherkin
AC5: system plugin 不可删
  Given registry 含 system=true 的 "mysql"
  When DELETE /tools/plugins/mysql
  Then 403 且 registry.getPlugin("mysql") 仍存在

AC6: 非 system plugin 清理
  Given 已注册 custom plugin 含 URLClassLoader 和 jarPath
  When uploader.cleanupPlugin(descriptor)
  Then JAR 和父目录被删除，URLClassLoader.close() 被调用
```

---

## 2.5 用户故事地图

开发(US-3)→部署(US-1)→调用(US-2 overrides)→集成(US-4 MCP)→运维(US-5 反注册)，每阶段依赖前一阶段。

---

## 3. 功能规格

### 3.1 用例清单

| ID | 用例 | 优先级 | AC | 类型 |
|----|------|--------|----|------|
| UC-01 | JAR 上传+扫描+注册+加载/实例化失败 | P0 | AC1 | 单元 |
| UC-02 | pluginId 路径穿越/特殊字符/重复拒绝 | P0 | AC1,AC2 | 单元 |
| UC-03 | pluginOverrides 路由+HTTP 校验 | P0 | AC3,AC4 | 单元+集成 |
| UC-04 | 注解优先+YAML 兜底扫描 | P0 | US-3 | 单元 |
| UC-05 | MCP SSE 握手+命名+执行 | P1 | US-4 | 单元 |
| UC-06 | cleanupPlugin+system 保护 | P0 | AC5,AC6 | 单元 |
| UC-07 | PluginConfigExtractor | P1 | US-1 | 单元 |

### 3.2 详细用例 (Gherkin)

#### UC-01: JAR 上传并注册
```gherkin
@priority:high @type:unit
功能: 上传 JAR 并注册 PluginDescriptor

  场景: 合法 JAR 上传成功
    Given scanner.scan 返回 metadata(pluginId="upload-test-plugin", toolType="log_read", providerClass="SimpleTestToolProvider")
    And registry.getPlugin 返回 null 且 configExtractor.extract 返回 {"base-url":"http://test:8080"}
    When uploader.upload(jarFile)
    Then descriptor.pluginId="upload-test-plugin" 且 enabled=true 且 system=false
    And descriptor.provider.name()="simple-test-tool" 且 jarPath 以 "plugin.jar" 结尾且存在
    And descriptor.pluginContext.getConfiguration() 含 base-url 且 registry.register 被调用一次

  场景大纲: provider 加载/实例化失败
    Given scanner 返回 metadata(providerClass=<className>)
    When uploader.upload(jarFile)
    Then 抛 RuntimeException 含 <msg>
    例子:
      | className | msg |
      | com.nonexistent.NoSuchProvider | failed to load provider class |
      | java.lang.Runnable | failed to instantiate provider |
```

#### UC-02: pluginId 校验与重复拒绝
```gherkin
@priority:high @type:unit
功能: 拒绝非法 pluginId 和重复注册

  场景大纲: 路径穿越和特殊字符
    Given scanner 返回 metadata(pluginId=<pluginId>)
    When uploader.upload(jarFile)
    Then 抛 IllegalArgumentException 含 "invalid pluginId"
    And registry.register 从未调用
    例子:
      | pluginId | 说明 |
      | ../../etc/evil | 路径穿越 |
      | evil/plugin | 斜杠注入 |
      | evil"; rm -rf | shell 元字符 |

  场景: 重复 pluginId
    Given registry.getPlugin("dup-plugin") 返回非 null
    When uploader.upload(jarFile)
    Then 抛 IllegalStateException "plugin already registered: dup-plugin"
    And registry.register 从未调用，临时 JAR 被删除
```

#### UC-03: pluginOverrides 路由与 HTTP 校验
```gherkin
@priority:high @type:unit+integration
功能: ToolDispatcher 按 pluginOverrides 路由 + HTTP 校验

  场景大纲: override 路由命中与校验
    Given registry 注册 plugin 配置见 <setup>
    When <action>
    Then <expect>

    例子:
      | setup | action | expect |
      | default+non-default "remote-mysql" | activePlugins({"mysql_query":"remote-mysql"}) | 首元素 pluginId="remote-mysql" |
      | 同上 | activePlugins() (无 override) | 首元素 pluginId="default-mysql" |
      | 仅 "mysql" | POST /runs pluginOverrides={"mysql_query":"nonexistent"} | 400 $.error="INVALID_PLUGIN_OVERRIDE" |
      | "custom-mysql" disabled | POST /runs pluginOverrides={"mysql_query":"custom-mysql"} | 400 $.error="INVALID_PLUGIN_OVERRIDE" |
      | "mysql" enabled | POST /runs 无 pluginOverrides | 202 向后兼容 |
```

#### UC-04: 注解优先 + YAML 兜底
```gherkin
@priority:high @type:unit
功能: PluginMetadataScanner 双来源扫描

  场景大纲: 双来源扫描结果
    Given JAR 内容 <content>
    When scanner.scan(classLoader)
    Then <expect>

    例子:
      | content | expect |
      | @ToolPlugin(id="test-annotated-plugin", version="3.0.0", isDefault=true) | pluginId="test-annotated-plugin" version="3.0.0" |
      | 仅 META-INF/snap-agent/plugin-info.yml (id="yaml-only-plugin", version="0.9.1") | pluginId="yaml-only-plugin" providerClass="com.example.TraceToolProvider" |
      | 空 JAR 无注解无 yml | 抛 IllegalStateException 含 "no plugin metadata found" |
```

#### UC-05: MCP SSE 握手与命名
```gherkin
@priority:medium @type:unit
功能: McpSseClient 握手 + McpToolProvider 命名/执行

  场景大纲: MCP 行为
    Given <setup>
    When <action>
    Then <expect>
    例子:
      | setup | action | expect |
      | baseUrl="https://bdp-mcp/sit/sse" authHeader="X-Bdp-Token" | client.connect() | 发送 initialize (protocolVersion="2024-11-05", clientInfo.name="snap-agent") + tools/list，返回 McpToolInfo 列表 size>0 |
      | - | McpSseClient.buildRequest(id=1, method="tools/list", params={}) | JSON 含 "jsonrpc":"2.0","id":1,"method":"tools/list" |
      | serverName="bdp-data-map" toolName="search_table" | new McpToolProvider(...).name() | 返回 "mcp__bdp-data-map__search_table" |
      | 同上 | provider.execute(args, ctx) | client.callTool 被调用(toolName, args, 30s)，从 response.result.content[] 提取 type="text" 拼接 |
```

#### UC-06: cleanupPlugin 与 system 保护
```gherkin
@priority:high @type:unit
功能: 资源清理与 system plugin 保护

  场景大纲: cleanup 行为
    Given <precondition>
    When uploader.cleanupPlugin(descriptor)
    Then <expect>
    例子:
      | precondition | expect |
      | descriptor.jarPath 存在且 classLoader 为 URLClassLoader | jarPath/父目录删除，URLClassLoader.close() 调用 |
      | null descriptor | 无异常 |
      | null classLoader + null jarPath | 无异常 |

  场景: system plugin DELETE 返回 403
    Given registry 含 system=true 的 "mysql"
    When controller.deletePlugin("mysql")
    Then 状态码 403 且 registry 仍含 "mysql"
```

#### UC-07: PluginConfigExtractor
```gherkin
@priority:medium @type:unit
功能: 从 Environment 绑定 snap-agent.tools.{pluginId}.*

  场景大纲: 配置抽取
    Given <env>
    When extractor.extract(env, <pluginId>)
    Then <expect>
    例子:
      | env | pluginId | expect |
      | snap-agent.tools.remote-log.base-url + max-lines | "remote-log" | map 含 base-url 和 max-lines |
      | 任意 env | null | 返回 emptyMap |
      | 无相关配置 | "nonexistent" | 返回 emptyMap |
```

---

## 4. 接口规格

### 4.1 REST 端点
- `GET /tools/plugins` 和 `GET /tools/plugins/{id}` — `snap-agent:plugin:read`
- `POST /tools/plugins/upload`、`DELETE /tools/plugins/{id}`、`POST /tools/plugins/{id}/enable|disable`、`PUT /tools/plugins/{id}/default` — `snap-agent:plugin:manage`
- `POST /runs` — 现有权限（body 可选 pluginOverrides）

### 4.2 内部接口
`upload(MultipartFile)`, `cleanupPlugin(PluginDescriptor)`, `scan(URLClassLoader)`, `extract(Environment, pluginId)`, `connect()` (McpSseClient), `callTool(toolName, args, timeoutSec)`。

### 4.3 MCP JSON-RPC
请求 `POST <postEndpoint>`：`{"jsonrpc":"2.0","id":<int>,"method":"tools/call","params":{"name":<toolName>,"arguments":<args>}}`，Headers `{auth-header, content-type:application/json}`；响应 200 `{"result":{"content":[{"type":"text","text":"..."}]}}`。

---

## 5. 数据规格

### 5.1 PluginDescriptor
字段：`pluginId`(PK, 正则 `^[a-zA-Z0-9_-]+$`), `toolType`, `displayName`, `version`, `description`, `isDefault`(volatile), `enabled`(volatile), `system`(final), `provider`(ToolProvider), `classLoader`(URLClassLoader|null), `jarPath`(Path|null), `pluginContext`(PluginContext|null)；并发靠 volatile + ConcurrentHashMap。

### 5.2 plugin-info.yml
`id/toolType/displayName/description/version/isDefault/providerClass` 六字段 YAML（示例：`id: remote-log, toolType: log_read, providerClass: com.example.RemoteLogToolProvider`）。

---

## 6. 错误处理

| 错误码 | 描述 | 用户提示 |
|--------|------|----------|
| E100 | invalid pluginId | "invalid pluginId: {id}" |
| E101 | plugin already registered | "plugin already registered: {id}" |
| E102 | failed to load provider class | "failed to load provider class: {cn}" |
| E103 | failed to instantiate provider | "failed to instantiate provider: {cn}" |
| E104 | no plugin metadata found | "no plugin metadata found in JAR" |
| E105 | MCP SSE connect failed | "MCP SSE connect failed: HTTP {code}" |
| E106 | MCP endpoint not sent | "MCP server did not send endpoint event" |

---

## 7. 非功能需求

### 7.1 性能
- JAR 上传+扫描+实例化 P95 < 2s（JAR ≤ 5MB）
- MCP initialize+tools/list P95 < 3s
- `dispatcher.activePlugins` P99 < 5ms

### 7.2 安全 — pluginId 正则防路径穿越；上传需 `snap-agent:plugin:manage` 权限；system plugin 不可 unregister；MCP auth header 走环境变量 `${BDP_TOKEN:}`；plugin 受宿主 SecurityGateway + audit 约束。

### 7.3 可测试性 — `@TempDir` + 真实 JAR 构造；Scanner 用 URLClassLoader 指向临时 JAR；McpSseClient 静态方法无网络可测；ToolDispatcher 路由用 Mockito。

---

## 8. 测试策略

### 8.1 已有测试覆盖

| 测试文件 | 类型 | 覆盖用例 |
|----------|------|----------|
| `PluginUploaderTest` | 单元 | UC-01/02 + 路径穿越/重复/加载/实例化失败/cleanup |
| `PluginMetadataScannerTest` | 单元 | UC-04 注解优先+YAML 兜底+空异常 |
| `PluginConfigExtractorTest` | 单元 | UC-07 抽取配置+null/空配置 |
| `PluginEndpointTest` | 单元 | UC-06 system 保护+list/get/enable/disable/setDefault |
| `PluginOverridesTest` | 集成 | UC-03 override 校验+400 |
| `PluginAutoWrappingTest` | 单元 | 内置 ToolProvider 自动包装+路由 |
| `PluginInfoYmlParserTest` | 单元 | YAML 解析 |

### 8.2 测试缺口
- P1: `McpSseClient.connect()` 全流程 mock（MockWebServer 验证 SSE endpoint + initialize + tools/list）
- P1: `McpSseClient.callTool` JSON-RPC 透传（MockWebServer 验证 content[].text 提取）
- P2: `McpToolProvider.schema()` JSON 合法性（解析断言 name/description/input_schema）
- P2: `McpBootstrap` 多 provider 累积、`ToolPluginRegistry` 空/null 列表
- P2: 上传 JAR 写盘 IOException、MCP SSE auth header 缺失分支
- P3: `cleanupPlugin(null classLoader+jarPath)` 组合边界

### 8.3 Mock 策略
```yaml
单元: PluginRegistry/Scanner=Mockito, Environment=MockEnvironment, MultipartFile=MockMultipartFile, 真实 JAR=JarOutputStream
集成: MockMvc + InMemoryPluginRegistry 真实实例
MCP: 静态方法无 Mock; connect/callTool 用 MockWebServer
```

---

## 9. 依赖与前置条件

- Spring Boot Binder（已就绪，try/catch 返回 emptyMap 兜底）
- OkHttp 3.x（已就绪，MCP 独立依赖）
- Jackson ObjectMapper（已就绪）
- InMemoryPluginRegistry（已就绪，可替换为 DB 实现）
- JDK URLClassLoader Java 8+（反射 newInstance()）

---

## 10. 可观测性

```yaml
日志: INFO "plugin uploaded: {pluginId} toolType={toolType} jarSize={bytes}"
      INFO "plugin unregistered: {pluginId}"
      INFO "MCP server POST endpoint: {url}" / "MCP returned {n} tools"
审计: PLUGIN_UPLOAD/UNREGISTER/ENABLE/DISABLE/SET_DEFAULT
指标: plugin_upload_total{result}, plugin_active_count, mcp_tool_call_duration_seconds
```

---

## 11. 原型与交互参考

| 操作 | 成功 | 错误 |
|------|------|------|
| 上传 JAR | 200 `{pluginId, toolType, version}` | 400/409 |
| 启停 | 200 `{enabled:bool}` | 404 |
| 设默认 | 200 `{toolType, pluginId}` | 404 |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 变更 |
|------|------|------|------|
| 1.0 | 2026-07-23 | TDD Bot | 初始版本 |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-21-plugin-architecture-refactor-design.md`
- `docs/embeed-skills-agent/04-tools-and-mcp.md`
- `docs/tdd/TEMPLATE.md`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| toolType | LLM 看到的工具名，dispatcher 路由键 |
| pluginId | plugin 唯一标识，正则 `^[a-zA-Z0-9_-]+$` |
| pluginOverrides | POST /runs body 字段，toolType→pluginId 路由覆盖 |
| system plugin | 内置 ToolProvider 自动包装，不可 unregister |
| MCP SSE | Model Context Protocol over Server-Sent Events transport |
