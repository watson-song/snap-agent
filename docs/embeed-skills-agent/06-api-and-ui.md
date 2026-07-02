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
重新扫描 `skills-dir`。响应：
```json
{ "total":5, "available":4, "unavailable":1, "invalid":0 }
```
鉴权：需 `snap-agent.security.required-permission` 配的权限（默认空=已登录即可；建议宿主配 admin 权限码）。

### 1.5 `POST /runs`
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

### 1.6 `GET /runs/{id}/stream`（SSE）
`text/event-stream`，事件协议见 [03-agent-engine.md](03-agent-engine.md) §8。`event:` 类型：`thought` / `tool_call` / `tool_result` / `error` / `done`。

响应头必须含 `X-Accel-Buffering: no`（兼容 Nginx，禁用代理缓冲）。

### 1.7 `GET /runs/{id}/transcript`
返回该 task 完整 transcript（thought + tool_call + tool_result + 审计记录），事后复盘。鉴权：发起人或 admin。

### 1.8 `GET /runs/{id}`（状态查询）
```json
{ "taskId":"sa_...", "status":"RUNNING", "skillId":"...", "model":"...", "createdAt":"...", "updatedAt":"..." }
```

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
9. SSE `done` → 渲染最终报告（markdown），关 EventSource。
10. SSE `error` → 红色提示，关 EventSource。

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
