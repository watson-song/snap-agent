# 10 — REST API 参考

完整 REST API 参考。所有接口挂在 `snap-agent.base-path`（默认 `/snap-agent`）下。鉴权委托宿主 Spring Security（见 [07-config-security.md](07-config-security.md)）。

> **Java SDK**: `snap-agent-client` 模块提供 `SnapAgentClient` 类，封装了所有 REST 调用。Maven 依赖：
> ```xml
> <dependency>
>   <groupId>cn.watsontech.snapagent</groupId>
>   <artifactId>snap-agent-client</artifactId>
>   <version>1.0.0-SNAPSHOT</version>
> </dependency>
> ```

---

## 认证

两种方式：

| 方式 | Header | 适用场景 |
|------|--------|---------|
| Basic Auth | `Authorization: Basic <base64(user:pass)>` | 浏览器、SDK 默认 |
| Bearer Token | `Authorization: Bearer <token>` | API 网关注入 |

SDK 示例：
```java
// Basic Auth
SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent", "admin", "secret");

// Bearer Token
SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent")
    .withAuthHeader("Bearer my-token");
```

---

## Skill 管理

### GET /skills

列出所有已加载 skill（含 `UNAVAILABLE`/`INVALID`）。

**响应**：
```json
{
  "skills": [
    {
      "name": "health-check",
      "description": "检查 MySQL 和 Redis 连接状态",
      "availability": "AVAILABLE",
      "source": "builtin",
      "overridesBuiltin": false,
      "requiredPermission": "",
      "tools": ["mysql_query", "redis_get"],
      "inputs": [
        {"key": "env", "label": "环境", "required": true, "type": "string"}
      ]
    }
  ]
}
```

**SDK**: `client.listSkills()` → `List<SkillDto>`

### GET /skills/{name}

获取单个 skill 详情。

**响应**: 同上单个 skill 对象。

**SDK**: `client.getSkill("health-check")` → `SkillDto`

### POST /skills/refresh

重新扫描 `upload-skills-dir` 并合并 builtin skills。需管理权限。

**响应**:
```json
{ "total": 5, "available": 4, "unavailable": 1, "invalid": 0 }
```

### POST /skills/upload

上传单个 skill 文件（`.md` 或 `.zip`）。`multipart/form-data`，字段名 `file`。

### POST /skills/upload-folder

上传整个 skill 目录（多文件）。`multipart/form-data`，多个 `files` 字段。

### DELETE /skills/{name}

删除自定义 skill。仅可删 `source=custom`；builtin 不可删（403）。

---

## 诊断执行

### POST /runs

发起一次诊断。

**请求**:
```json
{
  "skillId": "health-check",
  "inputs": { "env": "sit" },
  "model": "claude-sonnet-4-6"
}
```

**响应** (202 Accepted):
```json
{
  "taskId": "task_1234567890_abcdef",
  "status": "PENDING"
}
```

错误：400（skillId 缺失）、403（无 skill 级权限）、503（skill 不可用）。

**SDK**: `client.runSkill("health-check", inputs)` → `String taskId`

### GET /runs/{taskId}/status

轮询任务状态。

**响应**:
```json
{
  "taskId": "task_...",
  "status": "RUNNING",
  "skillName": "health-check",
  "startedAt": 1234567890,
  "completedAt": null,
  "report": null
}
```

`status` 枚举: `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `TIMEOUT` / `CANCELLED`

**SDK**: `client.getRunStatus(taskId)` → `Map<String, Object>`

### GET /runs/{taskId}/stream

SSE 流式传输。实时推送 transcript 事件（thought / tool_call / tool_result / response / done / task_error）。

鉴权：SSE `EventSource` 不支持自定义 header → 使用 query param `?token=base64(user:pass)`。

事件类型:
| event | data | 说明 |
|-------|------|------|
| `thought` | `{content, index}` | LLM 思考过程 |
| `tool_call` | `{tool, args, index}` | 工具调用开始 |
| `tool_result` | `{tool, content, error, index}` | 工具返回结果 |
| `response` | `{content, index}` | LLM 最终回复片段 |
| `done` | status string | 任务完成信号 |
| `task_error` | error message | 任务错误 |

心跳：每 15 秒发送 SSE comment `heartbeat`。

### POST /runs/{taskId}/cancel

取消运行中的任务。仅 `PENDING`/`RUNNING` 状态可取消；终态返回 409。

**响应** (200):
```json
{ "taskId": "task_...", "status": "CANCELLED" }
```

**SDK**: `client.cancelRun(taskId)`

### GET /runs/{taskId}/transcript

获取任务完整 transcript（非流式，用于历史回放）。

**响应**:
```json
{
  "events": [
    {"type": "thought", "content": "...", "timestamp": 1234567890},
    {"type": "tool_call", "tool": "mysql_query", "args": {...}, "timestamp": 1234567891},
    {"type": "tool_result", "tool": "mysql_query", "content": "...", "timestamp": 1234567892},
    {"type": "response", "content": "...", "timestamp": 1234567893},
    {"type": "done", "status": "SUCCEEDED", "timestamp": 1234567894}
  ]
}
```

**SDK**: `client.getTranscript(taskId)` → `List<TranscriptEventDto>`

---

## 会话历史

### POST /conversations

保存/更新会话。自动生成 ID 和 title。

### GET /conversations?skillId={skillId}

列出指定 skill 的会话（按 updatedAt 降序）。

### GET /conversations/{id}

加载会话详情（含 messages）。

### GET /conversations/{id}/download

下载会话 Markdown。

### DELETE /conversations/{id}

删除会话。

---

## 问题闭环 (v0.9+)

需 `snap-agent.issue-closure.enabled=true`。

| 端点 | 说明 |
|------|------|
| `POST /runs/{taskId}/solution` | 为任务生成解决方案建议 |
| `POST /runs/{taskId}/issue` | 创建外部 Issue（Jira/GitHub） |
| `GET /issues/{issueId}` | 查询 Issue 状态 |
| `POST /issues/{issueId}/verify` | 验证修复 |
| `POST /issues/{issueId}/close` | 关闭 Issue + 知识沉淀 |

---

## 工作流 (v1.0+)

需 `snap-agent.workflows.enabled=true`。

| 端点 | 说明 |
|------|------|
| `GET /workflows` | 列出所有工作流定义 |
| `GET /workflows/{name}` | 获取工作流详情 |
| `POST /workflows/{name}/run` | 执行工作流 |

---

## 巡检与告警 (v0.5+)

需 `snap-agent.patrol.enabled=true`。

| 端点 | 说明 |
|------|------|
| `POST /patrol/tasks` | 创建定时巡检任务 |
| `GET /patrol/tasks` | 列出巡检任务 |
| `DELETE /patrol/tasks/{id}` | 取消巡检任务 |
| `GET /patrol/reports` | 列出巡检报告（分页） |
| `GET /patrol/reports/{id}` | 获取巡检报告详情 |
| `GET /alerts` | 列出告警（分页 + type 过滤） |
| `POST /alerts/{id}/resolve` | 手动解决告警 |

---

## 成本核算 (v1.0+)

需 `snap-agent.cost.enabled=true`。

| 端点 | 说明 |
|------|------|
| `GET /cost/summary` | 全局成本摘要 |
| `GET /cost/users/{userId}/summary` | 按用户成本摘要 |
| `GET /cost/skills/{skillName}/summary` | 按 skill 成本摘要 |

---

## 工具插件 (v1.0+)

| 端点 | 说明 |
|------|------|
| `GET /tools/plugins` | 列出已注册工具插件元数据 |

---

## 用户信息

### GET /user-info

返回当前用户信息、可用权限、激活的 Spring profiles。

**响应**:
```json
{
  "userId": "admin",
  "authorized": true,
  "permission": "admin",
  "activeProfiles": ["sit"],
  "skills": [...]
}
```

**SDK**: `client.getUserInfo()` → `Map<String, Object>`

---

## SDK 使用示例

```java
// 创建客户端
SnapAgentClient client = new SnapAgentClient(
    "http://localhost:8080/snap-agent", "admin", "secret");

// 列出可用 skill
List<SkillDto> skills = client.listSkills();
skills.stream().filter(SkillDto::isAvailable).forEach(s ->
    System.out.println(s.getName() + ": " + s.getDescription()));

// 执行诊断
Map<String, String> inputs = new HashMap<>();
inputs.put("env", "sit");
String taskId = client.runSkill("health-check", inputs);

// 轮询状态
while (true) {
    Map<String, Object> status = client.getRunStatus(taskId);
    String s = (String) status.get("status");
    if ("SUCCEEDED".equals(s) || "FAILED".equals(s)) break;
    Thread.sleep(2000);
}

// 获取 transcript
List<TranscriptEventDto> transcript = client.getTranscript(taskId);
for (TranscriptEventDto event : transcript) {
    System.out.println(event.getType() + ": " + event.getContent());
}

// 取消任务
client.cancelRun(taskId);
```

> **注意**: SDK 不支持 SSE 实时流。如需实时 transcript，直接使用 `GET /runs/{taskId}/stream` SSE 端点。
