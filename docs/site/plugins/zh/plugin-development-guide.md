# SnapAgent 自定义 Plugin 开发指南

> 版本：v0.5 | 更新日期：2026-07-22

本指南介绍如何为 SnapAgent 开发自定义 Plugin —— 从项目生成到打包、上传、配置和测试的完整流程。

---

## 1. 核心概念

### Plugin = 1 个 ToolProvider + 元数据声明

SnapAgent 的 Plugin 是一个可热插拔的工具单元。每个 Plugin 包含:

- **1 个 `ToolProvider` 实现** —— 提供 `name()`、`schema()`、`execute()` 三个方法
- **元数据声明** —— 通过 `@ToolPluginAnnotation` 注解 (优先) 或 `plugin-info.yml` (备用) 声明

### toolType vs pluginId

| 概念 | 说明 | 示例 |
|------|------|------|
| `toolType` | LLM 调用时看到的工具名。一个 toolType 可有多个 Plugin | `log_read` |
| `pluginId` | Plugin 的唯一标识 | `remote-log`、`local-log` |

LLM 不感知 plugin 的存在 —— 它只看到 `toolType`。`ToolDispatcher` 按 `pluginOverrides` 或默认 Plugin 路由到具体实现。

### 1 plugin = 1 tool

一个 Plugin 对应一个 LLM 可调用的工具。Tool 内部可有多个 operation（通过 schema 参数分支），例如 `mysql_query` 可支持 `query`、`explain`、`slow-log` 三种操作。

### system plugin vs custom plugin

| 类型 | 来源 | 可删除 |
|------|------|--------|
| system plugin | 内置 `@Component ToolProvider` bean，启动时自动包装 | 否 |
| custom plugin | 通过 `POST /tools/plugins/upload` 上传的 JAR | 是 |

向后兼容: 现有 `@Component ToolProvider` bean 无需修改，启动时自动包装为 system plugin。

---

## 2. 快速开始

### 2.1 用 Maven Archetype 生成项目

```bash
mvn archetype:generate \
  -DarchetypeGroupId=cn.watsontech.snapagent \
  -DarchetypeArtifactId=snap-agent-plugin-archetype \
  -DarchetypeVersion=0.5.0 \
  -DgroupId=com.example \
  -DartifactId=my-remote-log-plugin \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.example \
  -DpluginId=remote-log \
  -DtoolType=log_read \
  -DdisplayName=远程日志查询 \
  -Ddescription="查询 Loki 历史日志" \
  -DclassName=RemoteLogToolProvider \
  -DinteractiveMode=false
```

生成项目结构:

```
my-remote-log-plugin/
├── pom.xml
├── src/main/java/com/example/
│   └── RemoteLogToolProvider.java
├── src/main/resources/META-INF/snap-agent/
│   └── plugin-info.yml
└── src/test/java/com/example/
│   └── RemoteLogToolProviderTest.java
```

### 2.2 实现 ToolProvider

生成的 `RemoteLogToolProvider.java` 已包含基本骨架。核心是三个方法:

```java
@ToolPluginAnnotation(
    id = "remote-log",
    toolType = "log_read",
    displayName = "远程日志查询",
    description = "查询 Loki 历史日志",
    version = "1.0.0"
)
public class RemoteLogToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "remote-log";
    }

    @Override
    public String schema() {
        return "{\"name\":\"log_read\", ... }";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String query = (String) args.get("query");
        return ToolResult.success(content, rowCount, durationMs);
    }
}
```

### 2.3 Operation 多功能分支

一个 tool 内可通过 schema 参数支持多种操作:

```java
@Override
public String schema() {
    return "{\"name\":\"mysql_query\","
         + "\"input_schema\":{\"type\":\"object\","
         + "\"properties\":{"
         +   "\"operation\":{\"type\":\"string\",\"enum\":[\"query\",\"explain\",\"slow-log\"]},"
         +   "\"sql\":{\"type\":\"string\"}"
         + "},\"required\":[\"operation\"]}}";
}

@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String operation = (String) args.get("operation");
    switch (operation) {
        case "query":     return executeQuery(args, ctx);
        case "explain":   return executeExplain(args, ctx);
        case "slow-log":  return executeSlowLog(args, ctx);
        default:          return ToolResult.error("unknown operation: " + operation, 0);
    }
}
```

---

## 3. 打包

```bash
cd my-remote-log-plugin
mvn clean package
```

打包要点:

- `snap-agent-core` 使用 `provided` scope —— **不打入** plugin JAR
- `spring-boot-starter` 也是 `provided + optional` —— 不打入
- 生成的 JAR 是 skinny jar，体积小
- 不要打成 fat jar —— 宿主已有 Spring/Jackson 等依赖，重复打入会导致 ClassLoader 冲突

输出: `target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar`

---

## 4. 上传

```bash
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -H "Authorization: Basic $(echo -n 'admin:password' | base64)" \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"
```

响应示例:

```json
{
    "pluginId": "remote-log",
    "toolType": "log_read",
    "displayName": "远程日志查询",
    "version": "1.0.0",
    "isDefault": false,
    "enabled": true,
    "system": false,
    "jarPath": "/data/snap-agent/plugins/remote-log/plugin.jar"
}
```

上传流程:
1. JAR 保存到 `${upload-skills-dir}/plugins/{pluginId}/plugin.jar`
2. 创建 `URLClassLoader` (parent = 主应用 ClassLoader)
3. 扫描元数据 (`@ToolPluginAnnotation` 优先，`plugin-info.yml` 兜底)
4. 实例化 `ToolProvider` (要求无参构造)
5. 构造 `PluginDescriptor` + 注册到 `PluginRegistry`
6. 默认 `isDefault=false` —— 需主动调用 `PUT /tools/plugins/{id}/default` 设为默认

---

## 5. 配置

### YAML 配置

```yaml
snap-agent:
  tools:
    remote-log:
      base-url: http://loki:3100
      timeout-seconds: 30
      max-lines: 500
```

### 在 Plugin 中读取配置

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    if (ctx.getPluginContext() != null) {
        Map<String, Object> config = ctx.getPluginContext().getConfiguration();
        String baseUrl = (String) config.get("base-url");
        int timeout = (Integer) config.getOrDefault("timeout-seconds", 30);
    }
}
```

配置来源: `snap-agent.tools.{pluginId}.*` 命名空间下的所有属性。

---

## 6. 禁用 / 启用 / 设默认 / 反注册

```bash
# 禁用 plugin
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/disable \
  -H "Authorization: Basic ..."

# 启用 plugin
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/enable \
  -H "Authorization: Basic ..."

# 设为该 toolType 的默认 plugin
curl -X PUT http://localhost:8080/skills-agent/tools/plugins/remote-log/default \
  -H "Authorization: Basic ..."

# 反注册 (system plugin 返回 403)
curl -X DELETE http://localhost:8080/skills-agent/tools/plugins/remote-log \
  -H "Authorization: Basic ..."
```

| 操作 | 端点 | 权限 |
|------|------|------|
| 禁用 | `POST /tools/plugins/{id}/disable` | `snap-agent:plugin:manage` |
| 启用 | `POST /tools/plugins/{id}/enable` | `snap-agent:plugin:manage` |
| 设默认 | `PUT /tools/plugins/{id}/default` | `snap-agent:plugin:manage` |
| 反注册 | `DELETE /tools/plugins/{id}` | `snap-agent:plugin:manage` |

> 生产环境推荐用 **disable** 而非 unregister —— ClassLoader GC 不保证立即回收。

---

## 7. 在 Skill 中使用 Plugin

### 通过 pluginOverrides 指定 plugin

```bash
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Authorization: Basic ..." \
  -H "Content-Type: application/json" \
  -d '{
    "skillName": "log-analysis",
    "inputs": {"keyword": "timeout"},
    "pluginOverrides": {
      "log_read": "remote-log"
    }
  }'
```

### 工作原理

```
LLM -> tool_use(log_read, args)
    -> ToolDispatcher.dispatch("log_read", args, ctx)
    -> ctx.pluginOverrides["log_read"] = "remote-log"
    -> registry.getPlugin("remote-log")
    -> plugin.provider.execute(args, ctx)
    -> ToolResult
```

- LLM 只看到 `toolType` (如 `log_read`)，不感知 plugin
- `ToolDispatcher` 先查 `ctx.pluginOverrides[toolType]`，再查 `registry.getDefault(toolType)`
- 不传 `pluginOverrides` 时走默认 plugin —— 向后兼容

---

## 8. 测试 Plugin

### 单元测试模板

生成的测试骨架已包含 4 个测试:

```java
class RemoteLogToolProviderTest {

    private RemoteLogToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RemoteLogToolProvider();
    }

    @Test
    void nameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("remote-log");
    }

    @Test
    void schemaContainsToolType() {
        assertThat(provider.schema()).contains("\"log_read\"");
    }

    @Test
    void executeWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
    }

    @Test
    void executeWithValidQueryReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
    }
}
```

### 本地验证流程

```bash
# 1. 编译 + 运行测试
cd my-remote-log-plugin
mvn clean test

# 2. 打包
mvn package

# 3. 上传到本地 SnapAgent 实例
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"

# 4. 验证已注册
curl http://localhost:8080/skills-agent/tools/plugins

# 5. 执行 skill 验证
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Content-Type: application/json" \
  -d '{"skillName":"log-analysis","inputs":{"keyword":"test"},"pluginOverrides":{"log_read":"remote-log"}}'
```

---

## 9. 限制与最佳实践

### ClassLoader 卸载

Java ClassLoader GC **不保证立即回收**。反注册 plugin 后，其 `URLClassLoader` 被关闭并等待 GC，但若有静态状态、线程或未关闭资源，可能泄漏。

- **推荐**: 生产环境用 `disable` 而非 `unregister`
- **监控**: 关注 ClassLoader 数量，异常增长时排查

### ThreadLocal

Plugin 在独立的 `URLClassLoader` 中执行，**不能访问主应用的 ThreadLocal**。所有请求级信息 (userId、taskId) 必须从 `ToolContext` 获取:

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String userId = ctx.getUserId();   // 正确
    // String userId = (String) ThreadLocalContext.get();  // 错误 - 不可用
}
```

### ToolResult.content 必须是 String

`ToolResult.getContent()` 返回 `String` 类型，确保跨 ClassLoader 安全。不要尝试返回自定义对象。

### 线程

不要在 Plugin 内启动**非 daemon 线程**。非 daemon 线程会阻止 JVM 退出。

### 依赖兼容

Plugin 可访问宿主的 Spring Bean (如 `DataSource`)。需确保宿主 Bean 版本与 Plugin 编译时依赖兼容。`snap-agent-core` API 稳定版保证向后兼容。
