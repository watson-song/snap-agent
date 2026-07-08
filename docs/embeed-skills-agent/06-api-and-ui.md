# 06 — API 与 UI

所有接口挂在 `snap-agent.base-path`（默认 `/snap-agent`）下。鉴权委托宿主（见 [07](07-config-security.md)）；本库只读已认证 principal。

## 1. REST 接口

### 1.1 `GET /skills`
列出已加载 skill。

响应：
```json
{
  "skills": [
    {
      "name": "sep-wh-replenish-diagnose",
      "description": "分仓补货计划生成问题分析...",
      "availability": "AVAILABLE",
      "source": "builtin",
      "overridesBuiltin": false,
      "inputs": [
        {"key":"skuCode","label":"件号编码","required":true,"type":"string"},
        {"key":"env","label":"环境","required":true,"type":"enum","options":["sit","uat","prod"]}
      ],
      "tools": ["mysql_query"]
    }
  ]
}
```
`UNAVAILABLE`/`INVALID` 的 skill 也列出（前端灰显 + tooltip 说明 `unavailableReason`）。

含 `log_read` 工具的 skill 额外返回日志路径信息：
```json
{
  "name": "log-analysis",
  "description": "Analyzes application log files...",
  "availability": "AVAILABLE",
  "tools": ["log_read"],
  "logPaths": ["/opt/app/logs"],
  "appLogFile": "/opt/app/logs/application.log"
}
```
- `logPaths`：`snap-agent.logs.allowed-paths` 配置的目录白名单。
- `appLogFile`：应用日志文件路径，自动从 Spring `logging.file.name` 解析（可被 `snap-agent.logs.app-log-file` 显式覆盖）。前端在 skill 上下文栏显示此路径。

### 1.2 `GET /tools`
列出已装配工具。
```json
{ "tools": [{"name":"mysql_query","description":"..."}, {"name":"redis_get","description":"..."}] }
```

### 1.3 `GET /models`
```json
{ "default":"claude-sonnet-4-6", "allowed":["claude-sonnet-4-6","claude-opus-4-6"] }
```
前端用它清理过期 localStorage 模型缓存。

### 1.4 `POST /skills/refresh`
重新扫描 `upload-skills-dir` 并合并 builtin skills。响应：
```json
{ "total":5, "available":4, "unavailable":1, "invalid":0 }
```
鉴权：需 `snap-agent.security.required-permission` 配的权限（默认空=已登录即可；建议宿主配 admin 权限码）。

### 1.5 `POST /skills/upload`
上传单个 skill 文件（`.md` 或 `.zip`）。`multipart/form-data`，字段名 `file`。

- `.md` 文件：直接写入 `upload-skills-dir`，frontmatter 校验同 builtin。
- `.zip` 文件：解压到 `upload-skills-dir` 下独立子目录（目录型 skill，入口 `SKILL.md`）。
- 同名 custom 覆盖 builtin；上传后自动刷新 registry。

响应（200）：
```json
{ "name": "my-custom-skill", "source": "custom", "overridesBuiltin": false, "availability": "AVAILABLE" }
```
错误：400（格式不支持 / frontmatter 缺字段）→ `INVALID`。

### 1.6 `POST /skills/upload-folder`
上传整个 skill 目录（多文件，支持目录型 skill）。`multipart/form-data`，多个 `files` 字段。

- 全部文件写入 `upload-skills-dir` 下同一子目录。
- 至少含一个 `SKILL.md`（目录型 skill 入口）或顶层 `*.md`。
- 上传后自动刷新 registry。

响应（200）：
```json
{ "total":3, "available":3, "unavailable":0, "invalid":0 }
```

### 1.7 `DELETE /skills/{name}`
删除一个自定义 skill。

- 仅可删除 `source=custom` 的 skill。
- 若该 custom 覆盖了同名 builtin，删除后恢复 builtin 版本。
- builtin skill（无 custom 覆盖）不可删除 → 403。
- skill 不存在 → 404。

响应（200）：
```json
{ "deleted": "my-custom-skill", "restoredBuiltin": false }
```

### 1.8 `POST /runs`
发起一次诊断。请求：
```json
{
  "skillId": "sep-wh-replenish-diagnose",
  "inputs": { "skuCode":"A001", "warehouseCode":"", "env":"sit", "tenantId":"", "generateDate":"" },
  "model": "claude-sonnet-4-6"
}
```
校验：
- `skillId` 存在且 `AVAILABLE`，否则 404/409。
- `inputs` 必填项齐全，否则 400。
- `model`（若提供）∈ `allowed-models`，否则 400。
- 限流（每用户并发 / 每小时 / 线程池），超限 429 + `Retry-After`。

响应（202 Accepted）：
```json
{ "taskId": "sa_202606291030_ab12", "status": "PENDING", "streamUrl": "/snap-agent/runs/sa_.../stream" }
```

### 1.9 `GET /runs/{id}/stream`（SSE）
`text/event-stream`，事件协议见 [03-agent-engine.md](03-agent-engine.md) §8。`event:` 类型：`thought` / `tool_call` / `tool_result` / `task_error` / `done`。

> 注：错误事件使用 `task_error` 而非 `error`，因为 `error` 作为 SSE event name 会触发浏览器 `EventSource` 内置 error handler，导致连接提前关闭并显示「连接断开」。

响应头必须含 `X-Accel-Buffering: no`（兼容 Nginx，禁用代理缓冲）。

### 1.10 `GET /runs/{id}/transcript`
返回该 task 完整 transcript（thought + tool_call + tool_result + 审计记录），事后复盘。鉴权：发起人或 admin。

### 1.11 `GET /runs/{id}`（状态查询）
```json
{ "taskId":"sa_...", "status":"RUNNING", "skillId":"...", "model":"...", "createdAt":"...", "updatedAt":"..." }
```

### 1.12 `POST /conversations`
保存或更新对话。请求：
```json
{
  "id": null,                          // null=新建，非 null=更新
  "skillId": "database-query",
  "title": null,                        // null=自动从首条用户消息截取前 30 字符
  "messages": [
    {"role":"user","content":"查询用户表"},
    {"role":"assistant","content":"用户表结构如下..."}
  ]
}
```
响应（200）：
```json
{ "id":"conv_202607081030_ab12", "skillId":"database-query", "title":"查询用户表", "createdAt":1751950000, "updatedAt":1751950001 }
```

### 1.13 `GET /conversations`
列出当前用户的对话。Query 参数 `skillId` 可选，用于按 skill 过滤。结果按 `updatedAt` 降序排列。
```json
[
  {"id":"conv_...", "skillId":"database-query", "title":"查询用户表", "messageCount":4, "createdAt":1751950000, "updatedAt":1751950001}
]
```

### 1.14 `GET /conversations/{id}`
加载完整对话（含 ownership 校验，非本人返回 404）。
```json
{
  "id":"conv_...", "userId":"user1", "skillId":"database-query", "title":"查询用户表",
  "createdAt":1751950000, "updatedAt":1751950001,
  "messages":[{"role":"user","content":"...","timestamp":1751950000}, ...]
}
```

### 1.15 `GET /conversations/{id}/download`
下载对话为 Markdown 文件。响应 `Content-Type: text/markdown`，`Content-Disposition: attachment; filename="对话标题.md"`。非本人返回 404。

### 1.16 `DELETE /conversations/{id}`
删除对话（含 ownership 校验）。成功 200，不存在或非本人 404。

## 2. 单页 SPA（无构建）

资源：`classpath:/static/snap-agent/`（`index.html` / `app.js` / `style.css`），starter 自动当静态资源暴露。

### 技术选型
- 原生 HTML + 原生 JS（fetch + EventSource）。**无 React/Vue/构建工具**，避免引入 npm 供应链。
- 样式用原生 CSS（可轻量 CSS 框架如 Pico.css 内联一份）。

### 页面结构
```
[顶栏: 模型下拉 (localStorage 缓存) | 刷新 Skills 按钮]
[左栏: skill 列表 (AVAILABLE 高亮, UNAVAILABLE/INVALID 灰显)]
[右栏:
  - 当前 skill 的 inputs 表单
  - Run 按钮
  - transcript 实时区 (SSE 渲染: thought 折叠, tool_call 展开参数与结果)
  - 最终报告区 (markdown 渲染)
]
```

### 交互
1. 打开 `/snap-agent/`（或宿主挂载路径）→ 调 `GET /skills` + `GET /models`。
2. 选 skill → 右栏渲染 `inputs` 表单（按 `type`：string=text、enum=select、date=date）。
3. localStorage 缓存「最近 inputs」（per skill）→ 表单预填，省得重输。
4. 选 model → localStorage 缓存；页面 load 时若缓存 model 不在 `allowed` → 清除回退默认。
5. 点 Run → `POST /runs` → 拿 taskId → `new EventSource(streamUrl)` 订阅。
6. SSE `thought` 事件 → 追加到 transcript 区（灰底）。
7. SSE `tool_call` → 渲染工具名 + 参数（SQL 高亮）。
8. SSE `tool_result` → 在对应 tool_call 下方展开结果（表格，限制显示行数）。
9. SSE `done` → 渲染最终报告（markdown），关 EventSource，自动保存对话历史到后端。
10. SSE `task_error` → 红色提示，关 EventSource。

### 对话历史（Per-Skill）
- 每个 skill 维护独立的对话历史，切换 skill 后自动加载该 skill 的最近一次对话。
- **切换 skill 时自动保存**：如果上一个 skill 的对话仍在 streaming，切换前会自动保存已累积的 AI 回复到该 skill 的对话历史（通过 stream 取消机制）。
- 底部输入区有 📜 历史按钮 → 弹出历史对话列表模态框，**按当前选中 skill 过滤**（调用 `GET /conversations?skillId=xxx`）。
- 历史列表按 `updatedAt` 降序，显示标题、时间；每条可：
  - **恢复**：加载该对话到聊天区，继续追问。
  - **下载**：导出为 Markdown 文件。
  - **删除**：删除该对话。

## 3. localStorage 缓存策略（决策 #4 模型临时修改）

| key | 值 | 失效策略 |
|-----|----|---------|
| `snap-agent.model` | 上次选的 model | 页面 load 时对 `GET /models.allowed` 校验，不在则删 |
| `snap-agent.inputs.{skillName}` | 上次提交的 inputs JSON | 手动清；永不过期（用户可重复排查同 SKU） |
| `snap-agent.lastSkill` | 上次选的 skill 名 | 启动时预选 |

- localStorage 纯 UX，无安全权重；服务端每次都按 `allowed-models` 强制校验。
- 「模型临时修改」语义：改的是这一次 run 用的 model，不持久到服务端 session。

## 4. SSE 代理兼容（风险）

- Nginx：`proxy_buffering off; proxy_read_timeout 3600s;` + 响应头 `X-Accel-Buffering: no`。
- 其他反向代理须支持 SSE 长连接（无缓冲、不提前 close）。
- 见 [09-integration-guide.md](09-integration-guide.md) §4。

## 5. 错误响应格式

统一：
```json
{ "error": "RATE_LIMITED", "message": "max-concurrent-runs-per-user=1 reached", "retryAfterSeconds": 30 }
```
错误码：`SKILL_NOT_FOUND` / `SKILL_UNAVAILABLE` / `INVALID_INPUT` / `MODEL_NOT_ALLOWED` / `RATE_LIMITED` / `TASK_NOT_FOUND` / `INTERNAL`。

## 6. 验证

- curl 跑一遍：`GET /skills` → `POST /runs` → `GET /runs/{id}/stream` 看到 thought/tool_call/tool_result/done 事件序列。
- 限流：连续 `POST /runs` 两次（同用户）→ 第二次 429。
- model 白名单：`POST /runs {model:"gpt-4"}` → 400 `MODEL_NOT_ALLOWED`。
- localStorage 失效：admin 从 allowed-models 删某 model，前端 reload → 旧缓存被清，回退默认。
