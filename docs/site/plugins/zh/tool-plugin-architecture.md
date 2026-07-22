# SnapAgent 工具插件架构

> 版本：v1.0 | 更新日期：2026-07-17

## 1. 架构概览

SnapAgent 采用**两层工具架构**，将工具的执行能力与元数据声明分离：

```
┌──────────────────────────────────────────────────────────┐
│                      AgentExecutor                        │
│   (执行循环：LLM → tool_use → dispatch → tool_result)     │
└──────────────┬───────────────────────────┬──────────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   ToolDispatcher       │   │  ToolPluginRegistry  │
   │   (按名路由 + 截断)      │   │  (元数据收集)          │
   │   - dispatch(name,args) │   │  - getPlugins()      │
   │   - availableToolNames()│   │  - 无条件装配           │
   └───────────┬───────────┘   └──────────┬──────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   ToolProvider (SPI)   │   │  ToolPlugin (SPI)    │
   │   第一层：执行 + 定义     │   │  第二层：元数据        │
   │   - name()             │   │  - name()            │
   │   - schema()           │   │  - version()         │
   │   - execute() → Result │   │  - description()      │
   └───────────────────────┘   │  - toolNames()       │
                                └──────────────────────┘
```

### 两层职责分离

**第一层：`ToolProvider` SPI（v0.1）**

工具执行的核心 SPI。每个 `ToolProvider` 声明一个唯一的 `name()` 和 JSON Schema（Anthropic 工具格式），并提供 `execute()` 方法执行工具调用。通过 `@Component` 注解自动发现——classpath 上任何 `ToolProvider` bean 都会被 `ToolDispatcher` 收集。

**第二层：`ToolPlugin` SPI（v1.0）

工具插件元数据层。声明插件名称、版本、描述和贡献的工具名列表。由 `ToolPluginRegistry` 无条件收集，通过 `GET /tools/plugins` 端点暴露。**元数据层不影响工具发现**——即使不实现 `ToolPlugin`，`ToolProvider` 依然能被自动发现并执行。

### 调用链路

```
LLM → tool_use 事件 → AgentExecutor → ToolDispatcher.dispatch(name, args, ctx)
    → ToolProvider.execute(args, ctx) → ToolResult → 回传 LLM → 继续推理
```

---

## 2. 核心 SPI

### ToolProvider

工具提供者 SPI，定义工具名称、JSON Schema 和执行逻辑：

```java
public interface ToolProvider {
    /** 唯一工具名，skill frontmatter 的 tools 字段引用此名称。 */
    String name();

    /** JSON Schema 字符串，注入 LLM 工具定义（Anthropic 格式）。 */
    String schema();

    /**
     * 执行工具调用。
     *
     * @param args 从 LLM tool_use 块解析的参数
     * @param ctx  请求级上下文（userId, audit）
     * @return 不可变结果；永不返回 null
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
```

### ToolContext

请求级上下文，携带任务 ID、用户 ID 和审计回调：

```java
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;

    public ToolContext(String taskId, String userId, AuditCallback auditCallback) { ... }

    public String getTaskId() { ... }
    public String getUserId() { ... }
    public AuditCallback getAuditCallback() { ... }
}
```

### ToolResult

不可变工具执行结果，Java 8 兼容（final 类 + 显式 getter），提供三个工厂方法：

```java
public final class ToolResult {
    private final String content;
    private final int rowCount;
    private final boolean truncated;
    private final long durationMs;
    private final String error;

    /** 成功（非截断）结果。 */
    public static ToolResult success(String content, int rowCount, long durationMs) { ... }

    /** 成功但被截断的结果（内容超出限制）。 */
    public static ToolResult truncated(String content, int rowCount, long durationMs) { ... }

    /** 错误结果——content 为 null，error 消息已设置。 */
    public static ToolResult error(String message, long durationMs) { ... }

    public String getContent() { ... }
    public int getRowCount() { ... }
    public boolean isTruncated() { ... }
    public long getDurationMs() { ... }
    public String getError() { ... }
    public boolean isSuccess() { ... }   // error == null
    public boolean isError() { ... }     // error != null
}
```

### AuditCallback

工具执行后的审计回调，由 `ToolDispatcher` 在 `dispatch()` 内部调用：

```java
public interface AuditCallback {
    /**
     * 工具提供者返回结果后被调用。
     *
     * @param toolName 工具注册名
     * @param args     传给工具的参数
     * @param result   工具返回的结果
     */
    void onToolExecuted(String toolName, Map<String, Object> args, ToolResult result);
}
```

定义在 `tool` 包中，使 `ToolContext` 能携带它而不依赖 `agent` 包。Agent 层提供实现，构造 `AuditRecord` 对象。审计失败不会中断 agent 循环。

### ToolDispatcher

按名路由 `tool_use` 调用到匹配的 `ToolProvider`：

```java
public class ToolDispatcher {
    public ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars) { ... }

    /** 返回已注册的工具名集合。 */
    public Set<String> availableToolNames() { ... }

    /** 返回已注册的提供者（供 executor 提取 schema）。 */
    public Collection<ToolProvider> providers() { ... }

    /**
     * 派发工具调用到已注册的提供者。
     * 未知工具名返回 error Result，LLM 可自行纠正。
     * 成功结果内容超出 maxToolResultChars 时自动截断。
     */
    public ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx) { ... }

    /** 构建人类可读的工具定义列表，注入 system prompt。 */
    public String buildToolDefinitions() { ... }
}
```

关键行为：
- 构造时建立不可变 Map<name, ToolProvider>，按 `ToolProvider.name()` 索引
- `dispatch()` 中捕获 `RuntimeException`，返回 `ToolResult.error()` 而非抛异常
- 成功结果超过 `maxToolResultChars` 时，截断并附加 `[truncated, total N rows]` 后缀
- 每次执行后调用 `AuditCallback.onToolExecuted()`（如果 ctx 中存在），审计异常被静默吞掉

### ToolPlugin（v1.0 元数据 SPI）

```java
public interface ToolPlugin {
    /** 插件名（唯一标识）。 */
    String name();

    /** 版本。 */
    String version();

    /** 描述。默认返回空字符串。 */
    default String description() { return ""; }

    /** 提供的工具名列表。默认返回空列表。 */
    default List<String> toolNames() { return Collections.emptyList(); }
}
```

`ToolPlugin` 是纯元数据层——不影响工具发现。鼓励实现者同时实现 `ToolProvider`（或注册独立的 `ToolProvider` bean）使其工具可被发现。默认方法允许最小实现只声明 `name()` 和 `version()`。

---

## 3. 内置工具

SnapAgent 提供以下内置 `ToolProvider` 实现，按功能域分组：

### 数据诊断工具

| 提供者 | 工具名 | 说明 | 启用配置 |
|--------|--------|------|----------|
| `JdbcQueryToolProvider` | `mysql_query` | 多环境只读 SQL 查询，管道分隔表格式输出，经 `SqlGuard` 安全校验 | `snap-agent.jdbc.*`（配置 DataSource） |
| `RedisReadToolProvider` | `redis_get` | 只读 Redis 操作，支持 `get`/`exists` 命令，拒绝所有写/扫描命令 | `snap-agent.redis.*`（配置 RedisTemplate） |

### 代码理解工具

| 提供者 | 工具名 | 说明 | 启用配置 |
|--------|--------|------|----------|
| `CodeReaderToolProvider` | `code_read` | 读取源码文件，支持行范围选择和关键词过滤（上下文 ±2 行），经 `CodePathGuard` 校验 | `snap-agent.code.enabled=true` + `project-root` |
| `ProjectStructureToolProvider` | `project_structure` | 扫描项目目录树，排除 target/.git/node_modules 等，返回树形布局 | `snap-agent.code.enabled=true` + `project-root` |
| `GitLogToolProvider` | `git_log` | 查看 git 历史，支持 log/blame/show 三种模式，commit_hash 正则校验 | `snap-agent.code.enabled=true` + `project-root` |

### 日志分析工具

| 提供者 | 工具名 | 说明 | 启用配置 |
|--------|--------|------|----------|
| `LogReadToolProvider` | `log_read` | 读取应用日志文件，支持关键词搜索、日志级别过滤和 tail 模式，经 `LogPathGuard` 校验 | `snap-agent.logs.allowed-paths` |

### 运营诊断工具

| 提供者 | 工具名 | 说明 | 启用配置 |
|--------|--------|------|----------|
| `MetricsToolProvider` | `metrics_query` | Prometheus PromQL 查询，支持即时查询和范围查询（时间序列） | `snap-agent.metrics.enabled=true` + `base-url` |
| `LogSearchToolProvider` | `log_search` | Loki LogQL 日志搜索，纳秒时间戳，支持流标签和方向控制 | `snap-agent.log-search.enabled=true` + `base-url` |
| `TraceSearchToolProvider` | `trace_search` | Jaeger 分布式链路搜索，微秒时间戳，span 树渲染，慢 span 标记 | `snap-agent.trace.enabled=true` + `base-url` |
| `ConfigReadToolProvider` | `config_read` | 读取应用配置，支持本地 Spring Environment（敏感字段脱敏）和 Nacos HTTP API | `snap-agent.config-read.enabled=true` |

### 代码图谱工具

| 提供者 | 工具名 | 说明 | 启用配置 |
|--------|--------|------|----------|
| `CodeGraphToolProvider` | `code_graph_tools` | 代码关系图谱查询，单一工具含 4 个子工具（见下表） | `snap-agent.code-graph.enabled=true` |

`code_graph_tools` 的 4 个子工具：

| 子工具 | 参数 | 说明 |
|--------|------|------|
| `call_chain` | `query` + `max_depth`(默认5) | 正向调用链（此方法调用了哪些方法，逐层展开） |
| `reverse_chain` | `query` + `max_depth`(默认5) | 反向调用链（谁调用了此方法） |
| `impact_analysis` | `query` + `max_depth`(默认3) | 变更影响分析（修改此节点会影响哪些下游代码） |
| `find` | `pattern` | 按名称模糊查找代码节点（类/方法/字段） |

---

## 4. ToolPluginRegistry

### 工作机制

`ToolPluginRegistry` 是 `ToolPlugin` bean 的收集器，**无条件装配**（`@ConditionalOnMissingBean`，但只要有 Spring 容器就创建）：

```java
@Bean
@ConditionalOnMissingBean
public ToolPluginRegistry toolPluginRegistry(ObjectProvider<ToolPlugin> toolPluginProvider) {
    List<ToolPlugin> plugins = toolPluginProvider.orderedStream()
            .collect(Collectors.toList());
    log.info("ToolPluginRegistry assembled with {} plugin(s)", plugins.size());
    return new ToolPluginRegistry(plugins);
}
```

- 当没有 `ToolPlugin` bean 时，插件列表为空——Registry bean 仍然创建
- `ToolPlugin` bean 按 Spring `@Order` 排序收集
- `getPlugins()` 返回不可修改的列表视图

### ToolProvider 与 ToolPlugin 的关系

| 维度 | `ToolProvider` | `ToolPlugin` |
|------|----------------|---------------|
| 版本 | v0.1 起 | v1.0 起 |
| 职责 | 提供工具定义 + 执行工具调用 | 声明插件元数据（名称/版本/描述/工具名） |
| 发现机制 | `@Component` → `ToolDispatcher` 自动收集 | `@Component` → `ToolPluginRegistry` 自动收集 |
| 影响执行 | 是——没有 `ToolProvider` 的工具无法被调用 | 否——纯元数据层 |
| REST 暴露 | `GET /tools`（仅工具名） | `GET /tools/plugins`（完整元数据） |

一个插件可以包含多个工具（如 `CodeGraphToolProvider` 提供一个工具名 `code_graph_tools`，内部含 4 个子工具）。`ToolPlugin.toolNames()` 声明插件贡献的工具名列表，便于运维可视化。

### 已注册插件示例

| 插件名 | 版本 | 描述 | 工具名 |
|--------|------|------|--------|
| `weather` | 1.0.0 | 天气查询插件 | `["weather_query"]` |
| `inventory` | 2.1.0 | 库存诊断插件 | `["inventory_check", "stock_alert"]` |

> 上表为自定义插件示例。内置工具目前不强制声明 `ToolPlugin` 元数据；宿主可按需为自己的工具实现 `ToolPlugin` 以获得运维可视化。

---

## 5. 工具调用流程

### 执行时序

```
  LLM                 AgentExecutor          ToolDispatcher        ToolProvider
   │                       │                      │                     │
   │── tool_use ──────────▶│                      │                     │
   │   (name, args)        │                      │                     │
   │                       │── 记录 assistant      │                     │
   │                       │   message + tool_use  │                     │
   │                       │                      │                     │
   │                       │── 构建 ToolContext ──│                     │
   │                       │   (taskId,userId,    │                     │
   │                       │    auditCallback)     │                     │
   │                       │                      │                     │
   │                       │── 记录 tool_call ──▶ │                     │
   │                       │   transcript event   │                     │
   │                       │                      │                     │
   │                       │── dispatch(name, ───▶│                     │
   │                       │   args, ctx)          │                     │
   │                       │                      │── 按 name 查找 ──▶   │
   │                       │                      │   ToolProvider       │
   │                       │                      │                      │── execute(args, ctx)
   │                       │                      │                      │
   │                       │                      │                      │── ToolResult ──▶│
   │                       │                      │◀────────────────── │
   │                       │                      │                      │
   │                       │                      │── 截断(如需要)        │
   │                       │                      │── 调用 audit          │
   │                       │                      │   callback           │
   │                       │◀── ToolResult ───────│                     │
   │                       │                      │                     │
   │                       │── 记录 tool_result ─▶│                     │
   │                       │   transcript event   │                     │
   │                       │                      │                     │
   │                       │── 添加 tool_result    │                     │
   │                       │   message 到对话      │                     │
   │                       │                      │                     │
   │◀── tool_result ───────│                      │                     │
   │   (content/error)     │                      │                     │
   │                       │                      │                     │
   │── 继续推理 ──────────▶│                      │                     │
   │   (下一轮 LLM 调用)    │                      │                     │
```

### 详细步骤

1. **LLM 发出 tool_use 块**：LLM 返回的 assistant 消息包含 `tool_use` 块（工具名 + 参数 JSON），`TurnCollector` 通过 `LlmEventSink` 收集
2. **AgentExecutor 检查 stop_reason**：若为 `tool_use`，表示 LLM 需要工具结果才能继续
3. **记录 assistant 消息**：将带 `tool_use` 块的 assistant 消息加入对话列表（使下一轮请求能匹配 `tool_result` 与 `tool_use` ID）
4. **构建 ToolContext**：`buildToolContext(task)` 创建携带 taskId、userId 和 AuditCallback 的上下文
5. **逐个派发**：对每个 `ToolUseBlock`：
   - 记录 `tool_call` transcript 事件（toolId, toolName, input）
   - 调用 `toolDispatcher.dispatch(name, input, ctx)`
   - Dispatcher 按 name 查找 ToolProvider，执行 `execute(args, ctx)`
   - 截断超长结果，调用 audit callback
   - 记录 `tool_result` transcript 事件（content 预览 500 字符, rowCount, truncated, durationMs, error）
   - 添加 `tool_result` message 到对话列表
6. **继续下一轮**：携带 tool_result 消息再次调用 LLM，LLM 基于工具结果继续推理

---

## 6. 安全约束

SnapAgent 的工具体系从设计上保证只读安全。所有工具均**不提供写、删除或执行操作**。

### SqlGuard — SQL 只读策略

`JdbcQueryToolProvider` 的第一道语法防线，防御 prompt-injection 驱动的写操作：

| 步骤 | 规则 |
|------|------|
| 1. 去注释 | 移除行注释 `--`、块注释 `/* */`、hash 注释 `#` |
| 2. 去尾分号 | 去除尾部 `;` 和 MySQL `\g`/`\G` 终止符 |
| 3. 拒绝多语句 | 去尾后仍含 `;` 或 `\g`/`\G` 则拒绝 |
| 4. 首关键字白名单 | `WITH` / `SELECT` / `SHOW` / `DESCRIBE` / `EXPLAIN` / `DESC` |
| 5. 危险关键字黑名单 | `INSERT` / `UPDATE` / `DELETE` / `DROP` / `CREATE` / `ALTER` / `TRUNCATE` / `GRANT` / `REVOKE` / `LOAD` / `SLEEP` 等 |
| 6. LIMIT 注入/改写 | 无 LIMIT 时追加 `LIMIT {max}`；超出 max 时改写为 max |

> SqlGuard 是语法分析器，非语义分析器。第二道防线是 agent DataSource 使用的只读数据库用户。

### LogPathGuard — 日志路径安全

`LogReadToolProvider` 的路径安全防线：

- **允许目录列表**：只在配置的 `snap-agent.logs.allowed-paths` 目录下读取
- **拒绝目录穿越**：路径含 `..` 直接拒绝（在任何文件系统访问之前）
- **文件校验**：必须存在、必须是普通文件、不超过 `maxFileBytes` 上限
- 防御 LLM 读取 `/etc/passwd`、`.env` 等敏感文件

### CodePathGuard — 代码路径安全

`CodeReaderToolProvider`、`ProjectStructureToolProvider`、`GitLogToolProvider` 的路径安全防线：

- **单一项目根**：所有路径必须在 `snap-agent.code.project-root` 下
- **拒绝目录穿越**：路径含 `..` 直接拒绝
- **扩展名白名单**：只允许 `.java`、`.xml`、`.yml` 等配置的扩展名
- **大小限制**：文件不超过 `maxFileBytes` 上限
- `resolveWithinProject()` 供目录扫描使用，不检查存在性和扩展名

### 其他安全措施

| 措施 | 说明 |
|------|------|
| Redis 只读 | `RedisReadToolProvider` 只允许 `get`/`exists` 命令，拒绝 `set`/`del`/`incr`/`keys` 等 |
| Git 安全 | `GitLogToolProvider` 用 `ProcessBuilder` 参数列表（非 shell），`commit_hash` 正则 `^[0-9a-f]{7,40}$` 校验，10s 超时 |
| 超时强制 | 运营工具（Metrics/LogSearch/TraceSearch）使用 `config.timeoutSeconds * 1000` 毫秒超时（默认 15s），Nacos 固定 15s |
| 敏感脱敏 | `ConfigReadToolProvider` 对含 `password`/`secret`/`token`/`credential`/`key` 的配置值返回 `****` |
| 结果截断 | `ToolDispatcher` 对超过 `maxToolResultChars` 的结果截断，防止 LLM context 溢出 |
| 只读 HTTP | 运营工具仅使用 HTTP GET 请求，不提供 POST/PUT/DELETE |

---

## 7. 自定义工具插件

### 完整示例：WeatherToolProvider

实现一个调用天气 API 的自定义工具插件，展示 `ToolProvider`（执行层）和 `ToolPlugin`（元数据层）的组合使用：

```java
package com.example.snapagent.tools;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolPlugin;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

/**
 * 自定义天气查询工具——调用外部天气 API。
 */
@Component
public class WeatherToolProvider implements ToolProvider {

    private static final String SCHEMA = "{\"name\":\"weather_query\","
            + "\"description\":\"查询指定城市的当前天气信息。\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"city\":{\"type\":\"string\",\"description\":\"城市名，如 'Beijing' 或 'Shanghai'\"}"
            + "},"
            + "\"required\":[\"city\"]}}";

    private final String apiBaseUrl;
    private final String apiKey;

    public WeatherToolProvider(@Value("${weather.api-base-url}") String apiBaseUrl,
                                @Value("${weather.api-key}") String apiKey) {
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "weather_query";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        // 1. 提取参数
        String city = args != null ? (String) args.get("city") : null;
        if (city == null || city.isEmpty()) {
            return ToolResult.error("missing required parameter: city",
                    System.currentTimeMillis() - start);
        }

        // 2. 调用天气 API（只读 GET）
        try {
            URL url = new URL(apiBaseUrl + "/v1/current?q=" + city + "&key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code != 200) {
                return ToolResult.error("weather API returned HTTP " + code,
                        System.currentTimeMillis() - start);
            }

            // 3. 解析响应（简化示例）
            String body = readAll(conn.getInputStream());
            String content = "# Weather: " + city + "\n" + body;
            return ToolResult.success(content, 1, System.currentTimeMillis() - start);

        } catch (IOException e) {
            return ToolResult.error("weather API call failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private String readAll(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) != -1) {
            buf.write(b, 0, n);
        }
        return buf.toString("UTF-8");
    }
}
```

### 对应的元数据声明

```java
package com.example.snapagent.tools;

import cn.watsontech.snapagent.core.tool.ToolPlugin;

import java.util.Collections;
import java.util.List;

/**
 * 天气查询插件的元数据声明。
 * 注册后可通过 GET /tools/plugins 查看插件信息。
 */
@Component
public class WeatherToolPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "天气查询插件——提供城市天气信息查询能力";
    }

    @Override
    public List<String> toolNames() {
        return Collections.singletonList("weather_query");
    }
}
```

### 注册方式

**无需任何配置**——两个类都标注 `@Component`，Spring 组件扫描会自动发现：

- `WeatherToolProvider` → 被 `ToolDispatcher` 的 `ObjectProvider<ToolProvider>` 收集 → LLM 可调用 `weather_query`
- `WeatherToolPlugin` → 被 `ToolPluginRegistry` 的 `ObjectProvider<ToolPlugin>` 收集 → `GET /tools/plugins` 返回此插件元数据

### JSON Schema 说明

工具 schema 遵循 Anthropic 工具格式：

```json
{
    "name": "weather_query",
    "description": "查询指定城市的当前天气信息。",
    "input_schema": {
        "type": "object",
        "properties": {
            "city": {
                "type": "string",
                "description": "城市名，如 'Beijing' 或 'Shanghai'"
            }
        },
        "required": ["city"]
    }
}
```

- `name`：必须与 `ToolProvider.name()` 一致
- `description`：LLM 据此判断何时调用此工具，应清晰描述用途
- `input_schema`：JSON Schema 格式，定义参数类型、描述和必填项
- `required`：标注哪些参数是必填的，LLM 缺少必填参数时会自行补充

---

## 8. REST API

### GET /tools

返回所有已注册工具的名称列表：

```json
{
    "tools": [
        { "name": "mysql_query" },
        { "name": "redis_get" },
        { "name": "log_read" },
        { "name": "code_read" },
        { "name": "project_structure" },
        { "name": "git_log" },
        { "name": "metrics_query" },
        { "name": "log_search" },
        { "name": "trace_search" },
        { "name": "config_read" },
        { "name": "code_graph_tools" },
        { "name": "weather_query" }
    ]
}
```

> 实际返回的工具列表取决于启用的配置项。例如 `mysql_query` 需要 DataSource 配置，`metrics_query` 需要 `snap-agent.metrics.enabled=true`。上面的列表展示了全部内置工具 + 一个自定义工具的场景。

### GET /tools/plugins

返回所有已注册插件的元数据（v1.0）：

```json
[
    {
        "name": "weather",
        "version": "1.0.0",
        "description": "天气查询插件——提供城市天气信息查询能力",
        "toolNames": ["weather_query"]
    },
    {
        "name": "inventory",
        "version": "2.1.0",
        "description": "库存诊断插件——库存检查与缺货预警",
        "toolNames": ["inventory_check", "stock_alert"]
    }
]
```

当没有 `ToolPlugin` bean 时，返回空数组 `[]`。

> 端点均需认证（`requireAuth()`），通过 `SecurityGateway` 校验权限。审计日志记录 `LIST_TOOLS` 和 `LIST_TOOL_PLUGINS` 操作。

---

## 9. v0.5 Plugin 架构（PluginDescriptor + PluginRegistry + 路由）

> 版本：v0.5 | 更新日期：2026-07-22

### 9.1 概述

v0.5 引入真正的 Plugin 抽象，取代 v1.0 的元数据层。新架构支持:

- 运行时注册/反注册/启停/设默认
- 同一 `toolType` 多个 Plugin (默认 + 显式覆盖)
- JAR 上传 + URLClassLoader 隔离
- `pluginOverrides` 路由 — LLM 只看 `toolType`，dispatcher 按覆盖路由

### 9.2 核心组件

ToolDispatcher 路由逻辑: dispatch(toolType, args, ctx) 先查 ctx.pluginOverrides[toolType] -> pluginId, 再查 registry.getDefault(toolType) -> pluginId, 最后 plugin.provider.execute(args, ctx)。

PluginRegistry 管理 plugins: Map<pluginId, PluginDescriptor>, 支持 register/unregister/enable/disable/setDefault。

PluginDescriptor 不可变数据模型: pluginId, toolType, displayName, description, version, isDefault(volatile), enabled(volatile), system(boolean), provider(ToolProvider), classLoader(URLClassLoader|null), jarPath(Path|null), pluginContext(PluginContext|null)。

### 9.3 @ToolPluginAnnotation 注解

RUNTIME retention 注解, 字段: id, toolType, displayName, description, version, isDefault。注解优先于 plugin-info.yml。扫描顺序: 先找注解类，若多个则用 YAML 指定的 providerClass，若无注解则用 YAML 全字段。

### 9.4 ToolContext 扩展

ToolContext 新增 pluginOverrides (Map<toolType, pluginId>) 和 pluginContext 字段。pluginOverrides 由 POST /runs 请求体传入，dispatcher 按 override 路由。pluginContext 由 ToolDispatcher 从 PluginDescriptor 取出注入。

### 9.5 Built-in 工具的透明包装

启动时所有 @Component ToolProvider bean 自动包装为 system plugin: pluginId=ToolProvider.name(), toolType=ToolProvider.name(), system=true(不可 unregister), isDefault=true(每 toolType 第一个注册者)。

向后兼容: 现有 skill 不传 pluginOverrides -> 走 default -> 命中 system plugin -> 行为与 v1.0 完全一致。

### 9.6 REST API 端点

- GET /tools/plugins — 列出所有 plugin (需要 snap-agent:plugin:read 权限)
- GET /tools/plugins/{id} — 获取单个 plugin 详情
- POST /tools/plugins/upload — 上传 plugin JAR (需要 snap-agent:plugin:manage 权限)
- DELETE /tools/plugins/{id} — 反注册 plugin (system plugin 返回 403)
- POST /tools/plugins/{id}/enable — 启用 plugin
- POST /tools/plugins/{id}/disable — 禁用 plugin
- PUT /tools/plugins/{id}/default — 设为该 toolType 的默认 plugin

### 9.7 v1.0 ToolPlugin SPI 兼容性

v1.0 的 ToolPlugin 接口已被 v0.5 架构取代，但接口保留以兼容现有实现。v0.5 的 @ToolPluginAnnotation 是新的元数据声明方式。GET /tools/plugins 响应格式已扩展。迁移建议: 新 Plugin 使用 @ToolPluginAnnotation + ToolProvider 实现。
