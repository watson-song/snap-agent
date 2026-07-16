# 10 — REST API 参考

> 本文是 SnapAgent REST + SSE 接口的完整参考。所有端点挂在 `snap-agent.base-path`（默认 `/snap-agent`）下。
> 设计文档与 SPA 交互见 [06-api-and-ui.md](06-api-and-ui.md)；鉴权细节见 [07-config-security.md](07-config-security.md)。

## 认证

所有受保护端点需通过 `SecurityGateway` 鉴权（委托宿主安全框架）。支持三种方式：

| 方式 | 配置 | 适用场景 |
|------|------|---------|
| Basic Auth | 默认（浏览器自动携带 cookie 也归此类） | Session 鉴权项目 |
| Token Header | `security.auth-token-header: token` | 前后端分离（JWT） |
| Token Cookie | `security.auth-token-cookie: a_authorization` | Token 存 cookie |
| Token localStorage | `security.auth-token-local-storage-key: TOKEN` | Token 存 localStorage |

公开端点（无需鉴权）：
- `GET /auth-config` — 返回前端鉴权配置（header/localStorage key/cookie name），供前端启动时读取。
- `GET /user-info` — 返回当前用户认证与授权状态。

> SSE 端点 `GET /runs/{id}/stream` 因 `EventSource` 不支持自定义 header，单独走 query param 鉴权（见下文）。

## Skill 管理

### GET /skills

列出所有已加载 Skill（builtin + custom，含 UNAVAILABLE/INVALID）。

**响应：**
```json
{
  "skills": [
    {
      "name": "database-query",
      "description": "执行只读 SQL 查询",
      "availability": "AVAILABLE",
      "source": "builtin",
      "overridesBuiltin": false,
      "tools": ["mysql_query"],
      "requiredPermission": "snap-agent:db-query",
      "inputs": [
        {"key": "env", "label": "环境", "required": true, "type": "enum", "options": ["sit", "uat"]}
      ]
    }
  ]
}
```

- `availability`：`AVAILABLE` / `UNAVAILABLE` / `INVALID`（前端灰显 + tooltip 显示 `unavailableReason`）
- `source`：`builtin`（JAR 内置） / `custom`（上传目录）
- `requiredPermission`：仅在 Skill frontmatter 声明了 `required-permission` 时出现
- 含 `log_read` 工具的 Skill 额外返回 `logPaths` 和 `appLogFile` 字段

### GET /skills/{name}

获取单个 Skill 详情。返回结构与 `GET /skills` 中单个 skill 对象一致。不存在返回 404。

### POST /skills/upload

上传单个 Skill 文件。`multipart/form-data`，字段名 `file`。

- `.md` 文件：直接写入 `upload-skills-dir`
- `.zip` 文件：解压到 `upload-skills-dir` 下独立子目录（目录型 Skill，入口 `SKILL.md`）
- 同名 custom 覆盖 builtin；上传后自动刷新 registry

**响应（200）：**
```json
{ "name": "my-custom-skill", "source": "custom", "overridesBuiltin": false, "availability": "AVAILABLE" }
```

错误：400（格式不支持 / frontmatter 缺字段）→ 标记 `INVALID`。

### POST /skills/upload-folder

上传整个 Skill 目录（多文件，支持目录型 Skill）。`multipart/form-data`，多个 `files` 字段。至少含一个 `SKILL.md` 或顶层 `*.md`。

**响应（200）：**
```json
{ "total": 3, "available": 3, "unavailable": 0, "invalid": 0 }
```

### PUT /skills/{name}

更新 Skill 内容（覆盖写入 `upload-skills-dir`）。仅对 `source=custom` 的 Skill 有效。

### DELETE /skills/{name}

删除自定义 Skill。

- 仅可删 `source=custom` 的 Skill
- 若该 custom 覆盖了同名 builtin，删除后恢复 builtin 版本（`restoredBuiltin: true`）
- builtin Skill 不可删 → 403
- 不存在 → 404

**响应（200）：**
```json
{ "deleted": "my-custom-skill", "restoredBuiltin": false }
```

### POST /skills/refresh

重新扫描 `upload-skills-dir` 并合并 builtin Skills。鉴权：需 `security.required-permission` 配的权限。

**响应：**
```json
{ "total": 5, "available": 4, "unavailable": 1, "invalid": 0 }
```

### GET /tools

列出已装配工具。

```json
{ "tools": [{"name": "mysql_query", "description": "..."}, {"name": "redis_get", "description": "..."}] }
```

### GET /models

返回默认模型与服务端白名单。前端用它清理过期 localStorage 模型缓存。

```json
{ "default": "claude-sonnet-4-6", "allowed": ["claude-sonnet-4-6", "claude-opus-4-6"] }
```

## 任务运行

### POST /runs

启动一次 Skill 运行。

**请求：**
```json
{
  "skillId": "database-query",
  "inputs": {
    "sku_code": "SKU-001",
    "env": "sit"
  },
  "model": "claude-sonnet-4-6"
}
```

**校验：**
- `skillId` 存在且 `AVAILABLE`，否则 404 / 409
- `inputs` 必填项齐全，否则 400
- `model`（若提供）必须 ∈ `allowed-models`，否则 400 `MODEL_NOT_ALLOWED`
- 限流（每用户并发 / 每小时 / 线程池），超限 429 + `Retry-After`
- Skill 级别权限校验（v0.6）：若 Skill 声明了 `required-permission` 且用户无此权限 → 403

**响应（202 Accepted）：**
```json
{
  "taskId": "sa_1234567890_abcdef123456",
  "status": "PENDING",
  "streamUrl": "/snap-agent/runs/sa_1234567890_abcdef123456/stream"
}
```

**Skill 级别权限不足（403）：**
```json
{
  "error": "insufficient permission",
  "requiredPermission": "snap-agent:db-query"
}
```

### GET /runs/{id}

查询任务状态和详情。

**响应：**
```json
{
  "taskId": "sa_1234567890_abcdef123456",
  "userId": "user1",
  "skillId": "database-query",
  "model": "claude-sonnet-4-6",
  "status": "SUCCEEDED",
  "createdAt": 1234567890,
  "updatedAt": 1234567990
}
```

`status` 取值：`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED`。

### GET /runs/{id}/transcript

获取任务完整 transcript（思考链 + 工具调用 + 结果 + 审计）。鉴权：发起人或 admin。

**响应：**
```json
{
  "transcript": [
    {"type": "thought", "text": "让我先查询...", "timestamp": 1234567891},
    {"type": "tool_call", "data": {"id": "call_1", "name": "mysql_query", "args": {"sql": "SELECT 1"}}, "timestamp": 1234567892},
    {"type": "tool_result", "data": {"id": "call_1", "rowCount": 1, "content": "1", "truncated": false}, "timestamp": 1234567893},
    {"type": "done", "data": {"status": "SUCCEEDED", "report": "诊断完成"}, "timestamp": 1234567990}
  ]
}
```

`tool_result.data.content` 为结果预览（截断 500 字符）；完整行数由 `rowCount` 标识，`truncated` 标识是否截断。

### GET /runs/{id}/report?format=md

下载诊断报告（Markdown 格式）。响应 `Content-Type: text/markdown`，`Content-Disposition: attachment`。

### POST /runs/{id}/cancel

取消运行中的任务。配合 OkHttp `Call.cancel()` 中断 LLM 流式调用。

**响应（200）：**
```json
{ "taskId": "sa_...", "status": "CANCELLED" }
```

任务已结束（SUCCEEDED/FAILED/CANCELLED）时返回 409。

### GET /runs?userId=&skillId=&page=&size=

分页查询历史任务。

| 参数 | 说明 |
|------|------|
| `userId` | 按用户过滤（admin 可查他人；普通用户只能查自己） |
| `skillId` | 按 Skill 过滤 |
| `page` | 页码（默认 0） |
| `size` | 每页条数（默认 20） |

**响应：**
```json
{
  "content": [
    {"taskId": "sa_...", "userId": "user1", "skillId": "database-query", "status": "SUCCEEDED", "createdAt": 1234567890, "updatedAt": 1234567990}
  ],
  "page": 0,
  "size": 20,
  "total": 42
}
```

## SSE 流式推送

### GET /runs/{id}/stream

SSE 流式获取任务实时事件。`Content-Type: text/event-stream`。

**认证：** SSE（`EventSource`）不支持自定义 header，通过 query param `?token=base64(user:pass)` 鉴权。Controller 解码后校验 task ownership（发起人或 admin）。端点本身 `permitAll`（放行进入 controller 后做 ownership check）。

**响应头：** `X-Accel-Buffering: no`（兼容 Nginx，禁用代理缓冲）。

**事件类型：**

| event | data | 说明 |
|-------|------|------|
| `thought` | `{"text": "..."}` | Agent 思考片段（token 级推送） |
| `tool_call` | `{"id": "call_1", "name": "mysql_query", "args": {...}}` | 工具调用 |
| `tool_result` | `{"id": "call_1", "rowCount": 1, "content": "...", "error": null}` | 工具返回结果（含内容预览） |
| `task_error` | `{"message": "..."}` | 任务错误（**不**用 `error`，避免触发浏览器内置 error handler） |
| `done` | `"SUCCEEDED"` | 任务完成（终态事件，前端据此关闭 `EventSource`） |

**心跳：** 每 15 秒发送 `:heartbeat` SSE comment，防止浏览器超时关闭连接。

**前端集成：** 见 [06-api-and-ui.md](06-api-and-ui.md) §2 交互流程。

> 注：`error` 作为 SSE event name 会触发浏览器 `EventSource` 内置 error handler，导致连接提前关闭并显示「连接断开」。SnapAgent 改发 `task_error` 事件；终端 `done` 事件是唯一的完成信号。

## 主动监控（v0.5+）

### POST /patrol/tasks

创建定时巡检任务。需 `patrol.enabled=true`，否则 503。

**请求：**
```json
{
  "skillName": "health-patrol",
  "cron": "0 */5 * * * *",
  "inputs": {"service": "order-service"}
}
```

Cron 使用 Spring 6 字段格式（秒 分 时 日 月 周）。

**响应：**
```json
{ "taskId": "patrol_xxx", "skillName": "health-patrol", "cron": "0 */5 * * * *", "status": "SCHEDULED" }
```

### GET /patrol/tasks

列出所有巡检任务。

### DELETE /patrol/tasks/{id}

取消巡检任务。

### GET /patrol/reports?page=&size=

分页查询巡检报告。报告含 Skill 运行结果、异常指标、根因摘要。

### GET /patrol/reports/{id}

获取单个巡检报告详情。

### GET /alerts?page=&size=&type=&status=

分页查询告警。可选按 `type`（异常类型）和 `status`（OPEN/RESOLVED）过滤。

### POST /alerts/{id}/resolve

手动 resolve 告警。

### POST /runs/{id}/bugfix-suggestion

从诊断 transcript 生成修复建议。基于 `code_read` 和 `git_log` 工具调用结果，结合根因分析。

**响应：**
```json
{
  "rootCause": "OrderService 第 87 行 order.getItem() 返回 null",
  "affectedFiles": ["OrderService.java"],
  "relatedCommits": [{"hash": "abc123", "author": "lisi", "message": "重构 setter 注入"}],
  "suggestion": "加 null 检查 或 改回构造函数注入",
  "confidence": "HIGH"
}
```

## 会话历史

### POST /conversations

保存/更新会话。

**请求：**
```json
{
  "id": null,
  "skillId": "database-query",
  "title": null,
  "messages": [
    {"role": "user", "content": "查询用户表"},
    {"role": "assistant", "content": "用户表结构如下..."}
  ]
}
```

`id` 为 null 自动生成；`title` 为 null 自动从首条用户消息截取前 30 字符。

**响应（200）：**
```json
{
  "id": "conv_202607081030_ab12",
  "skillId": "database-query",
  "title": "查询用户表",
  "createdAt": 1751950000,
  "updatedAt": 1751950001
}
```

### GET /conversations?skillId=

列出会话摘要（按 `updatedAt` 降序）。Query 参数 `skillId` 可选，用于按 Skill 过滤。所有方法带 `userId` ownership 校验。

**响应：**
```json
[
  {"id": "conv_...", "skillId": "database-query", "title": "查询用户表", "messageCount": 4, "createdAt": 1751950000, "updatedAt": 1751950001}
]
```

### GET /conversations/{id}

加载会话详情（含完整 messages）。非本人返回 404。

### GET /conversations/{id}/download

下载会话为 Markdown 文件。响应 `Content-Type: text/markdown`，`Content-Disposition: attachment; filename="对话标题.md"`。非本人返回 404。

### DELETE /conversations/{id}

删除会话（含 ownership 校验）。成功 200；不存在或非本人 404。

## 错误响应格式

统一格式：
```json
{
  "error": "RATE_LIMITED",
  "message": "max-concurrent-runs-per-user=1 reached",
  "retryAfterSeconds": 30
}
```

### 错误码

| 状态码 | 错误码 | 含义 |
|--------|--------|------|
| 200 | — | 成功 |
| 400 | `INVALID_INPUT` / `MODEL_NOT_ALLOWED` | 请求参数错误 / 模型不在白名单 |
| 401 | — | 未认证（principal 为空或解析失败） |
| 403 | `FORBIDDEN` | 无权限（全局 `required-permission` 或 Skill 级别 `required-permission`） |
| 404 | `SKILL_NOT_FOUND` / `TASK_NOT_FOUND` | 资源不存在 |
| 409 | `SKILL_UNAVAILABLE` | Skill 状态不可用 / 任务已结束无法取消 |
| 429 | `RATE_LIMITED` | 限流（每用户并发 / 每小时上限） |
| 500 | `INTERNAL` | 服务器内部错误 |
| 503 | — | 功能未启用（如 `patrol.enabled=false` 时访问巡检端点） |

## Java SDK

使用 `snap-agent-client` 模块快速接入。基于 `HttpURLConnection`，无 Spring 依赖，适合非 Spring 应用或微服务客户端。

### Maven 依赖

```xml
<dependency>
  <groupId>cn.watsontech.snapagent</groupId>
  <artifactId>snap-agent-client</artifactId>
  <version>0.6-SNAPSHOT</version>
</dependency>
```

### 快速开始

```java
import cn.watsontech.snapagent.client.SnapAgentClient;
import cn.watsontech.snapagent.client.dto.SkillDto;
import cn.watsontech.snapagent.client.dto.TranscriptEventDto;

// Basic Auth
SnapAgentClient client = new SnapAgentClient(
    "http://localhost:8080/snap-agent", "user", "pass");

// 或 Token 鉴权
SnapAgentClient tokenClient = new SnapAgentClient("http://localhost:8080/snap-agent")
    .withAuthHeader("Bearer " + token);

// 列出 Skills
List<SkillDto> skills = client.listSkills();

// 运行 Skill
String taskId = client.runSkill("database-query",
    Map.of("sku_code", "SKU-001", "env", "sit"));

// 获取状态
Map<String, Object> status = client.getRunStatus(taskId);

// 获取 Transcript
List<TranscriptEventDto> transcript = client.getTranscript(taskId);

// 取消任务
client.cancelRun(taskId);

// 获取用户信息
Map<String, Object> userInfo = client.getUserInfo();
```

### SDK 方法一览

| 方法 | 对应端点 | 返回 |
|------|---------|------|
| `listSkills()` | `GET /skills` | `List<SkillDto>` |
| `getSkill(name)` | `GET /skills`（客户端过滤） | `SkillDto` |
| `runSkill(skillName, inputs)` | `POST /runs` | `String`（taskId） |
| `getRunStatus(taskId)` | `GET /runs/{id}` | `Map<String, Object>` |
| `getTranscript(taskId)` | `GET /runs/{id}/transcript` | `List<TranscriptEventDto>` |
| `cancelRun(taskId)` | `POST /runs/{id}/cancel` | void |
| `getUserInfo()` | `GET /user-info` | `Map<String, Object>` |

错误处理：HTTP ≥ 400 抛 `SnapAgentClientException`（含 `statusCode` 字段），调用方按需捕获。

### 非Java 接入

REST API 为标准 JSON + SSE，任意语言可直接调用。示例（curl）：

```bash
# 列出 Skills
curl -u user:pass http://localhost:8080/snap-agent/skills

# 运行 Skill
curl -u user:pass -X POST http://localhost:8080/snap-agent/runs \
  -H "Content-Type: application/json" \
  -d '{"skillId":"database-query","inputs":{"sku_code":"SKU-001","env":"sit"}}'

# 订阅 SSE 流（token 鉴权）
curl -N "http://localhost:8080/snap-agent/runs/sa_xxx/stream?token=$(echo -n user:pass | base64)"
```

## 验证

- curl 跑一遍：`GET /skills` → `POST /runs` → `GET /runs/{id}/stream` 看到 `thought` / `tool_call` / `tool_result` / `done` 事件序列
- 限流：连续 `POST /runs` 两次（同用户）→ 第二次 429
- model 白名单：`POST /runs {"model":"gpt-4"}` → 400 `MODEL_NOT_ALLOWED`
- Skill 权限：用户无 `required-permission` → 403 `FORBIDDEN`
- SDK：`SnapAgentClient.listSkills()` 返回非空；`runSkill()` 返回 taskId；`cancelRun()` 无异常
