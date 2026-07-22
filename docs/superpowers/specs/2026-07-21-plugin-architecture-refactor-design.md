# SnapAgent Plugin 架构重构设计

> 版本：v0.5 | 设计日期：2026-07-21 | 分支：`feat/v0.5-plugin-improvement`

## 1. 背景与目标

### 1.1 当前状态

v1.0 交付的 `ToolPlugin` SPI 仅为元数据层，无法兑现 ROADMAP 中"plugin 可热插拔 tool"的承诺：

- `ToolDispatcher.providers` 为 `final unmodifiableMap`，无 add/remove API
- `ToolPlugin` 接口只暴露 name/version/description/toolNames，仅供 `GET /tools/plugins` 元数据查询
- 内置工具全部硬编码在 starter 模块，无 runtime 增减能力
- 无 plugin 上传/Classloader 隔离机制

### 1.2 目标

为 v0.5 引入真正的 plugin 抽象，让宿主可：

1. 开发自定义 plugin（`@ToolPlugin` 注解 + `ToolProvider` 实现）
2. 打包为独立 JAR 上传到 SnapAgent 实例
3. 运行时注册/启停/设默认/反注册
4. 在 skill 执行时通过 `pluginOverrides` 选择具体 plugin
5. 同一 toolType 可有多个 plugin（如本地 log + 远程 log），用默认或显式覆盖

### 1.3 范围（In Scope）

- ✅ `PluginDescriptor` + `PluginRegistry` SPI
- ✅ `ToolDispatcher` 重构为基于 `PluginRegistry` 路由（兼容现有 `@Component ToolProvider` 自动包装为 system plugin）
- ✅ `toolType` 抽象 + 默认 plugin + `pluginOverrides` 路由
- ✅ `@ToolPlugin` 注解 + `plugin-info.yml` 清单（双来源，注解优先）
- ✅ JAR 上传 + URLClassLoader 隔离
- ✅ 独立配置命名空间 `snap-agent.tools.{pluginId}.*`
- ✅ REST API（upload / unregister / enable / disable / set-default）
- ✅ Maven archetype 脚手架 + 开发者文档

### 1.4 不在范围（Out of Scope）

- ❌ 独立 jar 打包拆分（starter 模块保持不变）
- ❌ `META-INF/snap-agent/tools/` classpath 扫描（上传 API 替代）
- ❌ Spring `@ConfigurationProperties` 强类型绑定 plugin 配置（v0.5 用 `Map<String, Object>`）
- ❌ Plugin 间依赖声明（A plugin 依赖 B plugin）
- ❌ Plugin 热升级（必须 unregister + register 替换）

### 1.5 设计原则

- **向后兼容**：现有 `@Component ToolProvider` bean 无需修改，启动时自动包装为 system plugin
- **1 plugin = 1 tool**：一个 plugin 对应一个 LLM 可调用的工具；tool 内部可有 N 个 operation（通过 schema 参数分支）
- **LLM 不感知 plugin**：LLM 只看到 toolType 名字，dispatcher 按 `pluginOverrides`/default 路由到具体 plugin
- **ClassLoader 隔离**：每个自定义 plugin 一个独立 URLClassLoader，parent = 主 ClassLoader
- **只读安全不变**：plugin 仍受宿主 SecurityGateway 鉴权，audit 回调不变

---

## 2. 核心架构

### 2.1 组件关系

```
┌──────────────────────────────────────────────────────────────┐
│                     AgentExecutor                            │
│   (执行循环：LLM → tool_use → dispatch → tool_result)        │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│                     ToolDispatcher                           │
│   dispatch(toolType, args, ctx):                             │
│     1. ctx.pluginOverrides[toolType] → pluginId              │
│     2. registry.getDefault(toolType) → pluginId              │
│     3. plugin.provider.execute(args, ctx)                    │
│   activePlugins(overrides): 注入 LLM 的 plugin schema 列表   │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│                     PluginRegistry                           │
│   plugins: Map<pluginId, PluginDescriptor>                   │
│   byType: Map<toolType, List<PluginDescriptor>>              │
│   register / unregister / enable / disable / setDefault      │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│                     PluginDescriptor                        │
│   pluginId, toolType, displayName, version, isDefault,       │
│   enabled, system, provider (ToolProvider),                  │
│   classLoader (URLClassLoader|null), jarPath|null             │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 PluginDescriptor 数据模型

```java
public final class PluginDescriptor {
    private final String pluginId;          // 唯一标识，e.g., "remote-log"
    private final String toolType;          // LLM 调用的工具名，e.g., "log_read"
    private final String displayName;
    private final String description;
    private final String version;
    private volatile boolean isDefault;     // 该 toolType 的默认 plugin
    private volatile boolean enabled;       // 是否启用
    private final boolean system;           // true=内置不可删除
    private final ToolProvider provider;    // 实现
    private final ClassLoader classLoader;  // 自定义 plugin 的 URLClassLoader，system=null
    private final Path jarPath;             // 自定义 plugin 的 JAR 路径，system=null
    private final PluginContext pluginContext;  // 自定义 plugin 的配置上下文，system=null

    // getters...
    // package-private setters for isDefault, enabled
}
```

### 2.3 PluginRegistry SPI

```java
public interface PluginRegistry {
    /** 注册 plugin，pluginId 冲突抛 IllegalArgumentException。 */
    void register(PluginDescriptor descriptor);

    /** 反注册，system plugin 抛 UnsupportedOperationException。 */
    void unregister(String pluginId);

    /** 启用 plugin。 */
    void enable(String pluginId);

    /** 禁用 plugin。 */
    void disable(String pluginId);

    /** 设为该 toolType 的默认 plugin，其他同 toolType 的 isDefault 置为 false。 */
    void setDefault(String toolType, String pluginId);

    /** 按 pluginId 查找，不存在返回 null。 */
    PluginDescriptor getPlugin(String pluginId);

    /** 取该 toolType 的默认 plugin，无则返回 null。 */
    PluginDescriptor getDefault(String toolType);

    /** 取该 toolType 的所有 plugin（含禁用的）。 */
    List<PluginDescriptor> getPluginsForType(String toolType);

    /** 列出所有已注册 plugin。 */
    List<PluginDescriptor> list();
}
```

默认实现 `InMemoryPluginRegistry`（基于 `ConcurrentHashMap`），存于 `snap-agent-core`。

### 2.4 ToolDispatcher 路由逻辑

```java
public class ToolDispatcher {
    private final PluginRegistry registry;
    private final int maxToolResultChars;

    public ToolDispatcher(PluginRegistry registry, int maxToolResultChars) { ... }

    /**
     * 路由 tool_use 调用：
     * 1. ctx.pluginOverrides[toolType] → 指定 plugin
     * 2. registry.getDefault(toolType) → 默认 plugin
     * 3. 命中且 enabled → provider.execute(args, ctx)
     * 4. 未命中或禁用 → ToolResult.error，LLM 可自行纠正
     */
    public ToolResult dispatch(String toolType, Map<String, Object> args, ToolContext ctx) {
        String override = ctx != null ? ctx.getPluginOverrides().get(toolType) : null;
        PluginDescriptor plugin = (override != null)
            ? registry.getPlugin(override)
            : registry.getDefault(toolType);

        if (plugin == null) {
            ToolResult err = ToolResult.error("no plugin registered for: " + toolType, 0);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }
        if (!plugin.isEnabled()) {
            ToolResult err = ToolResult.error("plugin disabled: " + plugin.getPluginId(), 0);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }

        // 注入 plugin 的 PluginContext 到 ToolContext（用于 plugin 读取其配置）
        if (plugin.getPluginContext() != null && ctx != null) {
            ctx = ctx.withPluginContext(plugin.getPluginContext());
        }

        long start = System.currentTimeMillis();
        ToolResult result;
        try {
            result = plugin.getProvider().execute(args, ctx);
            if (result == null) {
                result = ToolResult.error("tool returned null result",
                                          System.currentTimeMillis() - start);
            }
        } catch (RuntimeException e) {
            result = ToolResult.error("plugin execution failed: " + e.getMessage(),
                                       System.currentTimeMillis() - start);
        }
        ToolResult finalResult = truncateIfNeeded(result);
        invokeAudit(ctx, toolType, args, finalResult);
        return finalResult;
    }

    /**
     * 返回本次 skill 执行应注入 LLM 的 plugin 集合。
     * 每个 toolType 只暴露当前绑定（override 或 default）的 plugin schema。
     */
    public Collection<PluginDescriptor> activePlugins(Map<String, String> overrides) {
        Map<String, PluginDescriptor> active = new LinkedHashMap<>();
        for (PluginDescriptor p : registry.list()) {
            if (!p.isEnabled()) continue;
            String toolType = p.getToolType();
            if (active.containsKey(toolType)) continue;  // 已选 default，跳过
            active.put(toolType, p);
        }
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                PluginDescriptor p = registry.getPlugin(e.getValue());
                if (p != null && p.isEnabled()) {
                    active.put(e.getKey(), p);
                }
            }
        }
        return active.values();
    }

    /** 向后兼容：无 override 版本。 */
    public Collection<PluginDescriptor> activePlugins() {
        return activePlugins(null);
    }

    /** 已注册的 toolType 集合（替代旧 availableToolNames()）。 */
    public Set<String> availableToolTypes() { ... }

    /** 旧 API 保留兼容（标记 @Deprecated），内部调用 dispatch(name, args, ctx)。 */
    @Deprecated
    public ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx) {
        return dispatch(name, args, ctx);  // 名字 = toolType
    }
}
```

### 2.5 ToolContext 扩展

```java
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;
    private final Map<String, String> pluginOverrides;  // 新增：toolType → pluginId

    public ToolContext(String taskId, String userId, AuditCallback auditCallback) {
        this(taskId, userId, auditCallback, Collections.<String, String>emptyMap());
    }

    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides) {
        this.taskId = taskId;
        this.userId = userId;
        this.auditCallback = auditCallback;
        this.pluginOverrides = pluginOverrides != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverrides))
            : Collections.<String, String>emptyMap();
    }

    public Map<String, String> getPluginOverrides() {
        return pluginOverrides;
    }
}
```

### 2.6 Built-in 工具的透明包装

`SnapAgentAutoConfiguration` 启动时，遍历所有 `@Component ToolProvider` bean，为每个创建 `PluginDescriptor`：

| 字段 | 值 |
|------|---|
| `pluginId` | `ToolProvider.name()` |
| `toolType` | `ToolProvider.name()` |
| `displayName` | `ToolProvider.name()` |
| `description` | 从 schema JSON 提取（或空串） |
| `version` | `"built-in"` |
| `isDefault` | `true`（每 toolType 第一个注册者为默认） |
| `system` | `true`（不可 unregister） |
| `provider` | 原始 bean |
| `classLoader` | `null` |
| `jarPath` | `null` |

**向后兼容保证**：现有 skill 不传 `pluginOverrides` → 走 default → 命中 system plugin → 行为与今天完全一致。现有 `ToolProvider` 接口和内置工具代码零修改。

---

## 3. JAR 上传与 ClassLoader 隔离

### 3.1 上传流程

```
客户端 → POST /tools/plugins/upload (multipart: jar)
    │
    ▼
1. 保存 JAR
   ${upload-skills-dir}/plugins/{pluginId}/plugin.jar
   (pluginId 取自 plugin-info.yml 或 @ToolPlugin.id；冲突则拒绝)
    ▼
2. 创建 URLClassLoader
   URL[] = {jarUrl}
   parent = 主应用 ClassLoader
    ▼
3. 扫描元数据（注解优先，YAML 兜底）
   - 扫描所有类寻找 @ToolPlugin 注解
   - 若无注解，读 META-INF/snap-agent/plugin-info.yml
    ▼
4. 实例化 ToolProvider
   - URLClassLoader.loadClass(providerClassName)
   - 反射 newInstance()（要求有无参构造）
   - 若 plugin 类需要 Spring 依赖（DataSource 等）：
     通过宿主 BeanFactory 注入（@Autowired 字段或 setter）
    ▼
5. 构造 PluginDescriptor + PluginRegistry.register()
   - 默认 isDefault=false（用户需主动 PUT /default）
    ▼
6. 返回 plugin 元数据（pluginId, toolType, version 等）
```

### 3.2 @ToolPlugin 注解

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPlugin {
    /** Plugin 唯一标识。 */
    String id();

    /** LLM 调用时的工具名（toolType）。 */
    String toolType();

    /** 显示名（默认空）。 */
    String displayName() default "";

    /** 描述。 */
    String description() default "";

    /** 版本。 */
    String version() default "1.0.0";

    /** 是否为该 toolType 的默认 plugin（注册时若与已有 default 冲突则忽略）。 */
    boolean isDefault() default false;
}
```

**使用示例**：

```java
@ToolPlugin(
    id = "remote-log",
    toolType = "log_read",
    displayName = "远程日志查询",
    description = "查询 Loki 历史日志",
    version = "1.2.0"
)
public class RemoteLogToolProvider implements ToolProvider {
    @Override
    public String name() {
        return "remote-log";  // 与 @ToolPlugin.id 一致
    }

    @Override
    public String schema() {
        return REMOTE_LOG_SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        // 实现细节
    }
}
```

### 3.3 plugin-info.yml（备用清单）

放在 JAR 的 `META-INF/snap-agent/plugin-info.yml`：

```yaml
id: remote-log
toolType: log_read
displayName: 远程日志查询
description: 查询 Loki 历史日志
version: 1.2.0
isDefault: false
providerClass: com.example.RemoteLogToolProvider
```

扫描顺序：先找 `@ToolPlugin` 注解类，若多个则用 plugin-info.yml 指定的 `providerClass`，若无注解则用 YAML 全字段构造。

### 3.4 ClassLoader 隔离与卸载

| 场景 | 处理 |
|------|------|
| 注册 | 每个 JAR 一个 `URLClassLoader`，parent = 主 ClassLoader |
| 执行 | `ToolProvider.execute()` 在 plugin ClassLoader 上下文执行 |
| ToolResult 返回 | `content` 为 `String`，跨 ClassLoader 安全 |
| 反注册 | 从 registry 移除 → `URLClassLoader.close()` → 等待 GC |
| 卸载限制 | Java ClassLoader GC 不保证立即回收。若 plugin 持有静态状态/线程/未关闭资源，可能泄漏。生产环境建议 disable 而非 unregister |

### 3.5 Plugin 配置注入

Plugin 通过 `PluginContext` 读取配置（v0.5 简化方案，不做 `@ConfigurationProperties` 强类型绑定）：

```java
public interface PluginContext {
    /** 从 snap-agent.tools.{pluginId}.* 读取所有配置。 */
    Map<String, Object> getConfiguration();

    /** 复用宿主 DataSourceRegistry（若存在）。 */
    DataSource getDataSource(String envName);

    /** 复用宿主 RedisTemplate（若存在）。 */
    RedisTemplate<String, ?> getRedisTemplate();
}
```

**集成方式（明确）**：`ToolContext` 增加 `pluginContext` 字段（可为 null，兼容旧调用方）：

```java
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;
    private final Map<String, String> pluginOverrides;
    private final PluginContext pluginContext;  // 新增
}
```

`ToolDispatcher.dispatch()` 执行 plugin 时，从 `PluginDescriptor` 关联的 `PluginContext`（注册时由 `PluginUploader` 构造并存储于 descriptor）取出，注入到 `ToolContext`。Plugin 实现可通过 `ctx.getPluginContext().getConfiguration()` 读取配置。Built-in system plugin 的 `pluginContext` 为 null（它们直接通过 Spring `@Value` 注入配置）。

YAML 配置示例：

```yaml
snap-agent:
  tools:
    remote-log:
      base-url: http://loki:3100
      timeout-seconds: 30
    mysql-basic:
      datasource:
        url: jdbc:mysql://...
```

---

## 4. REST API

### 4.1 端点清单

| 方法 | 端点 | 说明 | 权限 |
|------|------|------|------|
| `GET` | `/tools/plugins` | 列出所有 plugin | `snap-agent:plugin:read` |
| `GET` | `/tools/plugins/{id}` | 单个 plugin 详情 | `snap-agent:plugin:read` |
| `POST` | `/tools/plugins/upload` | 上传 JAR（multipart） | `snap-agent:plugin:manage` |
| `DELETE` | `/tools/plugins/{id}` | 反注册（system 返回 403） | `snap-agent:plugin:manage` |
| `POST` | `/tools/plugins/{id}/enable` | 启用 | `snap-agent:plugin:manage` |
| `POST` | `/tools/plugins/{id}/disable` | 禁用 | `snap-agent:plugin:manage` |
| `PUT` | `/tools/plugins/{id}/default` | 设为该 toolType 默认 | `snap-agent:plugin:manage` |
| `POST` | `/runs` | skill 执行，body 可选 `pluginOverrides` | 现有权限 |

### 4.2 权限模型

新增两个权限码：

- `snap-agent:plugin:read`：查看 plugin 列表/详情
- `snap-agent:plugin:manage`：上传/反注册/启停/设默认

`SnapAgentProperties.security` 增加配置 `plugin-manage-permission` 和 `plugin-read-permission`（默认值如上）。

### 4.3 审计

审计日志记录以下操作：

| 操作类型 | 触发端点 | 审计字段 |
|---------|---------|---------|
| `PLUGIN_UPLOAD` | POST /tools/plugins/upload | userId, pluginId, toolType, jarSize |
| `PLUGIN_UNREGISTER` | DELETE /tools/plugins/{id} | userId, pluginId |
| `PLUGIN_ENABLE` | POST /tools/plugins/{id}/enable | userId, pluginId |
| `PLUGIN_DISABLE` | POST /tools/plugins/{id}/disable | userId, pluginId |
| `PLUGIN_SET_DEFAULT` | PUT /tools/plugins/{id}/default | userId, pluginId, toolType |

### 4.4 POST /runs 扩展

请求 body 新增可选字段 `pluginOverrides`：

```json
{
  "skillName": "log-analysis",
  "inputs": {"keyword": "timeout"},
  "pluginOverrides": {
    "log_read": "remote-log"
  }
}
```

`AgentExecutor` 处理流程：

1. 校验所有 `pluginOverrides` 中的 pluginId 存在且 `enabled`
   - 任一 pluginId 不存在或已禁用 → 返回 HTTP 400，错误体：`{"error": "INVALID_PLUGIN_OVERRIDE", "message": "plugin '{id}' for toolType '{type}' not found or disabled"}`
2. 构造 `ToolContext` 携带 `pluginOverrides`（不可变 Map）和 `PluginContext`
3. 调用 `ToolDispatcher.activePlugins(overrides)` 获取本次执行的 plugin 集合
4. 组装 LLM tools 数组（每个 toolType 只暴露当前绑定 plugin 的 schema）
5. 执行 LLM 循环，dispatch 时按 override 路由

### 4.5 GET /tools/plugins 响应格式

```json
[
  {
    "pluginId": "redis_get",
    "toolType": "redis_get",
    "displayName": "redis_get",
    "description": "Read a Redis key.",
    "version": "built-in",
    "isDefault": true,
    "enabled": true,
    "system": true,
    "jarPath": null
  },
  {
    "pluginId": "remote-log",
    "toolType": "log_read",
    "displayName": "远程日志查询",
    "description": "查询 Loki 历史日志",
    "version": "1.2.0",
    "isDefault": false,
    "enabled": true,
    "system": false,
    "jarPath": "/data/snap-agent/plugins/remote-log/plugin.jar"
  }
]
```

---

## 5. Plugin 开发脚手架

### 5.1 Maven Archetype

新建独立 module `snap-agent-plugin-archetype`，提供 archetype 生成 plugin 项目骨架：

```bash
mvn archetype:generate \
  -DarchetypeGroupId=cn.watsontech.snapagent \
  -DarchetypeArtifactId=snap-agent-plugin-archetype \
  -DarchetypeVersion=0.4.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=my-remote-log-plugin \
  -DpluginId=remote-log \
  -DtoolType=log_read \
  -DdisplayName=远程日志查询 \
  -Ddescription=查询 Loki 历史日志
```

生成项目结构：

```
my-remote-log-plugin/
├── pom.xml                              ← 依赖 snap-agent-core (provided scope)
├── src/main/java/com/example/
│   └── RemoteLogToolProvider.java       ← @ToolPlugin 注解 + ToolProvider 实现
├── src/main/resources/
│   └── META-INF/snap-agent/
│       └── plugin-info.yml             ← 备用清单（注解优先时自动失效）
└── src/test/java/com/example/
    └── RemoteLogToolProviderTest.java   ← TDD 测试骨架
```

### 5.2 生成代码模板

**RemoteLogToolProvider.java**：

```java
@ToolPlugin(
    id = "${pluginId}",
    toolType = "${toolType}",
    displayName = "${displayName}",
    description = "${description}",
    version = "1.0.0"
)
public class ${className} implements ToolProvider {

    @Override
    public String name() {
        return "${pluginId}";
    }

    @Override
    public String schema() {
        return "{\"name\":\"${toolType}\","
             + "\"description\":\"${description}\","
             + "\"input_schema\":{\"type\":\"object\","
             + "\"properties\":{\"query\":{\"type\":\"string\"}},"
             + "\"required\":[\"query\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        try {
            String query = args != null ? (String) args.get("query") : null;
            if (query == null || query.isEmpty()) {
                return ToolResult.error("missing required parameter: query",
                                         System.currentTimeMillis() - start);
            }
            String content = "# ${displayName}\nQuery: " + query + "\nResult: TODO";
            return ToolResult.success(content, 1, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ToolResult.error("${pluginId} failed: " + e.getMessage(),
                                     System.currentTimeMillis() - start);
        }
    }
}
```

**plugin-info.yml（备用）**：

```yaml
id: ${pluginId}
toolType: ${toolType}
displayName: ${displayName}
description: ${description}
version: 1.0.0
isDefault: false
providerClass: ${groupId}.${className}
```

**RemoteLogToolProviderTest.java**：

```java
public class ${className}Test {
    private ${className} provider;

    @Before
    public void setUp() {
        provider = new ${className}();
    }

    @Test
    public void testNameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("${pluginId}");
    }

    @Test
    public void testSchemaIsValidJson() {
        String schema = provider.schema();
        // 校验 schema 是合法 JSON 且 name 字段 = toolType
    }

    @Test
    public void testExecuteWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
    }

    @Test
    public void testExecuteWithValidArgsReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test query");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("test query");
    }
}
```

### 5.3 CLI 脚手架（可选，轻量级）

辅助 shell 脚本 `docs/skills/scripts/snap-agent-plugin-init.sh`：

```bash
#!/bin/bash
# 用法: snap-agent-plugin-init.sh <pluginId> <toolType> <className> [groupId] [artifactId]
# 生成最小骨架（无 Maven archetype 依赖），适合快速验证
```

文档同时提供 Maven archetype 和 CLI 脚本两种方式，archetype 为推荐路径。

---

## 6. 开发者文档

### 6.1 新增文档

- `docs/site/plugins/zh/plugin-development-guide.md`：自定义 plugin 开发指南（中文）
- `docs/site/plugins/en/plugin-development-guide.md`：英文版

### 6.2 文档目录

```markdown
# SnapAgent 自定义 Plugin 开发指南

## 1. 核心概念
- Plugin = 1 个 ToolProvider + 元数据声明
- toolType = LLM 调用时看到的工具名
- pluginId = plugin 的唯一标识
- 1 plugin = 1 tool，tool 内可有多个 operation（通过 schema 参数分支）

## 2. 快速开始
### 2.1 用 Maven Archetype 生成项目
### 2.2 实现 ToolProvider
### 2.3 Operation 多功能分支（示例：mysql_query 的 query/explain/slow-log）

## 3. 打包
- mvn clean package → single-fat-jar
- 不打包 snap-agent-core (provided scope)
- 不打包宿主已有的 Spring/Jackson 等依赖

## 4. 上传
- POST /tools/plugins/upload (multipart/form-data)
- 完整 curl 示例

## 5. 配置
- snap-agent.tools.{pluginId}.{key} = value
- PluginContext.getConfiguration() 使用

## 6. 禁用 / 启用 / 设默认 / 反注册
- REST API 调用示例

## 7. 使用 plugin 在 skill 中
- POST /runs 的 pluginOverrides 字段
- 工作原理：LLM 只看 toolType，dispatcher 按 override 路由

## 8. 测试 Plugin
- 单元测试模板
- 本地验证：mvn package → curl upload → GET /tools/plugins 验证

## 9. 限制与最佳实践
- ClassLoader 卸载不保证立即回收
- Plugin 不能访问主应用 ThreadLocal，必须从 ToolContext 获取 userId
- ToolResult.content 必须是 String
- 不要在 plugin 内启动非 daemon 线程
```

### 6.3 旧文档更新

`docs/site/plugins/zh/tool-plugin-architecture.md` 增加章节说明新的 `PluginDescriptor` + `PluginRegistry` + 路由模型，标注旧 `ToolPlugin` SPI（v1.0 缩水版元数据）已被新架构取代，但接口保留兼容。

---

## 7. 向后兼容性

### 7.1 内置工具无改动

- 现有 `JdbcQueryToolProvider` / `RedisReadToolProvider` / `LogReadToolProvider` 等代码零修改
- 启动时自动包装为 system plugin（见 2.6）
- `ToolProvider` 接口不变

### 7.2 Skill 无改动

- 现有 skill 不传 `pluginOverrides` → 走 default → 命中 system plugin
- 行为与今天完全一致

### 7.3 REST API 兼容

- `GET /tools` 保留（返回 toolType 列表，等价于旧的 availableToolNames）
- `GET /tools/plugins` 响应格式扩展（新增 `pluginId` / `toolType` / `system` / `jarPath` 字段）
- `POST /runs` body 新增可选 `pluginOverrides`，无此字段时行为不变

### 7.4 API 弃用

- `ToolDispatcher(Collection<ToolProvider>, int)` 构造器标记 `@Deprecated`
- `ToolDispatcher.availableToolNames()` 标记 `@Deprecated`，等价于 `availableToolTypes()`
- `ToolDispatcher.providers()` 标记 `@Deprecated`，替代为 `activePlugins()`
- `ToolDispatcher.buildToolDefinitions()` 标记 `@Deprecated`，替代为基于 `activePlugins()` 的 schema 组装

---

## 8. 测试策略

### 8.1 单元测试

| 组件 | 测试覆盖 |
|------|---------|
| `PluginDescriptor` | 不可变性测试、equals/hashCode |
| `InMemoryPluginRegistry` | register/unregister/enable/disable/setDefault 边界条件、system plugin 保护、并发安全 |
| `ToolDispatcher` | override 路由、default 路由、plugin 禁用 fallback、未注册 toolType error |
| `ToolContext` | pluginOverrides 不可变性、空 Map 兼容 |
| `@ToolPlugin` 注解解析 | 注解优先、YAML 兜底、冲突处理 |
| `PluginUploader` | JAR 保存、URLClassLoader 创建、类扫描、实例化失败处理 |

### 8.2 集成测试

- `PluginLifecycleIntegrationTest`：上传 → 列出 → 启停 → 设默认 → 反注册全流程
- `PluginRoutingIntegrationTest`：skill 执行带 `pluginOverrides`，验证 LLM 实际调用的 plugin
- `BackwardCompatIntegrationTest`：现有 skill 不传 overrides，行为与旧版一致

### 8.3 E2E 测试

- `snap-agent-demo` 添加一个测试用 plugin JAR（`EchoPlugin`），通过 REST 上传 + skill 调用验证完整链路
- 验证 Spring Boot 应用重启后，已上传的 plugin 自动从 `${upload-skills-dir}/plugins/` 恢复

---

## 9. 实现顺序（TDD）

按 TDD red-green-refactor 循环实现，每个组件先写测试再写实现：

1. `PluginDescriptor` 数据类 + 测试
2. `PluginRegistry` SPI + `InMemoryPluginRegistry` + 测试
3. `ToolContext` 扩展（pluginOverrides 字段）+ 测试
4. `ToolDispatcher` 重构（基于 PluginRegistry）+ 测试
5. `@ToolPlugin` 注解 + `plugin-info.yml` 解析器 + 测试
6. `PluginUploader`（JAR 保存 + URLClassLoader + 扫描 + 实例化）+ 测试
7. REST API 端点（`SnapAgentController` 新增 6 个端点）+ 测试
8. `POST /runs` 扩展（pluginOverrides 校验 + 注入）+ 测试
9. Built-in 工具自动包装逻辑（`SnapAgentAutoConfiguration`）+ 测试
10. Maven archetype 脚手架 + 示例 plugin
11. 开发者文档

每步 TDD 循环：red（写失败测试）→ green（最小实现通过）→ refactor（重构去重复）。

---

## 10. 风险与限制

| 风险 | 缓解 |
|------|------|
| ClassLoader GC 不及时 | 文档明确说明，生产推荐 disable；监控 ClassLoader 数量告警 |
| Plugin 内启动非 daemon 线程 | 文档禁止，启动时检测 ThreadMXBean |
| Plugin 依赖宿主 Spring Bean 版本不兼容 | 文档声明 SnapAgent 核心 API 稳定版，宿主 Bean 注入需做兼容性校验 |
| Plugin 恶意代码 | Plugin 上传需 `snap-agent:plugin:manage` 权限，建议生产环境仅管理员持有；ClassLoader 可加 SecurityManager（v0.6 考虑） |
| `plugin-info.yml` 解析失败 | 双来源（注解 + YAML），任一可用即注册；都不行则拒绝上传 |
| 现有 `ToolDispatcher` 构造器签名变化 | 旧构造器 `@Deprecated` 保留，内部转调新构造器 |

---

## 11. 未来扩展（不在 v0.5 范围）

- Plugin 依赖声明（`plugin-info.yml` 的 `depends` 字段）
- Plugin 热升级（unregister + register 原子替换）
- 独立 jar 打包（starter 模块拆分为 `snap-agent-tool-mysql` 等）
- `META-INF/snap-agent/tools/` classpath 扫描（无上传也能发现内置插件）
- Spring `@ConfigurationProperties` 强类型绑定
- Plugin 间通信（A plugin 调用 B plugin 的工具）
- Plugin 市场（社区共享 plugin JAR）
