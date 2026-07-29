# SnapAgent 浏览器网络桥接 E2E 测试手册

> 本文档记录如何使用 Docker 搭建 ZenTao（禅道）和 Bitbucket 本地环境，
> 并通过 SnapAgent 浏览器网络桥接（Bridge）完成 Issue 创建和 Git 操作的端到端测试。

---

## 目录

1. [架构概览](#1-架构概览)
2. [前置条件](#2-前置条件)
3. [Docker 环境搭建](#3-docker-环境搭建)
   - 3.1 [Docker 网络创建](#31-docker-网络创建)
   - 3.2 [ZenTao（禅道）搭建](#32-zentao禅道搭建)
   - 3.3 [Bitbucket 搭建](#33-bitbucket-搭建)
4. [SnapAgent Demo 配置与启动](#4-snapagent-demo-配置与启动)
5. [Chrome 扩展安装](#5-chrome-扩展安装)
6. [MCP Chrome 扩展模拟器](#6-mcp-chrome-扩展模拟器)
7. [E2E 测试场景](#7-e2e-测试场景)
   - 7.1 [场景一：Bridge 连接与状态验证](#71-场景一bridge-连接与状态验证)
   - 7.2 [场景二：通过 Bridge 创建 ZenTao Issue](#72-场景二通过-bridge-创建-zentao-issue)
   - 7.3 [场景三：Bridge 路由降级验证](#73-场景三bridge-路由降级验证)
8. [故障排查](#8-故障排查)
9. [清理与回收](#9-清理与回收)

---

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│  Browser (Chrome)                                           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ SnapAgent UI (index.html)                          │    │
│  │  └─ bridge-client.js                               │    │
│  │       ├─ EventSource → GET /bridge/stream (SSE)     │    │
│  │       ├─ POST /bridge/result                        │    │
│  │       └─ POST /bridge/status-update                 │    │
│  │                                                     │    │
│  │  Chrome Extension (content.js + background.js)     │    │
│  │       └─ fetch(url, init) — 无 CORS 限制            │    │
│  └─────────────────────────────────────────────────────┘    │
│                            │                                │
└────────────────────────────┼────────────────────────────────┘
                             │ SSE (proxy-request →)
                             │ HTTP POST (← proxy-response)
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  SnapAgent Server (localhost:8080)                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ BridgeController                                     │    │
│  │  ├─ GET  /bridge/stream   → SseEmitter              │    │
│  │  ├─ POST /bridge/result   → handleResult()          │    │
│  │  ├─ GET  /bridge/status   → BridgeStatus            │    │
│  │  └─ POST /bridge/status-update → updateClientStatus │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ IssueBridgeService                                  │    │
│  │  ├─ isBridgeActive(serviceType)                     │    │
│  │  ├─ proxyRequest() → SSE push + CompletableFuture  │    │
│  │  └─ handleResult() → complete future                │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ BridgeHttpExecutor (status-first routing)          │    │
│  │  ├─ bridge active  → IssueBridgeService.proxyRequest│   │
│  │  └─ bridge inactive → DirectHttpExecutor            │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ ZentaoIssueTracker                                 │    │
│  │  └─ jsonRequest() → httpExecutor.execute()         │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
           ┌──────────────┐  ┌──────────────┐
           │ ZenTao :8083 │  │ Bitbucket   │
           │ (Docker)     │  │ :7990       │
           └──────────────┘  └──────────────┘
```

**核心路由逻辑**：`BridgeHttpExecutor` 在每次 HTTP 请求前检查 `isBridgeActive(serviceType)`：
- **Bridge 激活** → 通过 SSE 推送到浏览器，由扩展（或模拟器）执行 `fetch()`，结果通过 `POST /bridge/result` 回传
- **Bridge 未激活** → 直接使用 `DirectHttpExecutor`（`java.net.HttpURLConnection`）

---

## 2. 前置条件

| 项目 | 要求 |
|------|------|
| Docker Desktop | 4.x+（支持 `--platform linux/amd64`） |
| Java | Corretto 1.8.0_492（`/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home`） |
| Maven | 3.9.15（`/opt/homebrew/Cellar/maven/3.9.15/bin/mvn`） |
| Chrome | 已安装，支持 Chrome Extension MV3 |
| SnapAgent 源码 | `/Users/HuaSheng.Song/IdeaProjects/skills-agent` 分支 `2.x` |

**Docker 镜像预下载**：

```bash
docker pull hub.zentao.net/app/zentao:18.5
docker pull mariadb:10.6
docker pull mysql:8.0.33
docker pull atlassian/bitbucket:8.19.0
```

---

## 3. Docker 环境搭建

### 3.1 Docker 网络创建

所有容器接入同一网络，以便互相访问：

```bash
docker network create bridge-net 2>/dev/null
```

### 3.2 ZenTao（禅道）搭建

#### 3.2.1 问题背景

ZenTao 18.5 官方镜像（`hub.zentao.net/app/zentao:18.5`）为 amd64-only，在 Apple Silicon (ARM64) 上：
- 镜像内置的 MariaDB 10.6.15 在 Rosetta 2 模拟下**无法启动**
- 必须使用外部的 arm64 原生 MariaDB

#### 3.2.2 启动 MariaDB（ZenTao 数据库）

```bash
docker run -d \
  --name zentao-mariadb \
  --network bridge-net \
  -p 3308:3306 \
  -e MYSQL_ROOT_PASSWORD=zentao123 \
  -e MYSQL_DATABASE=zentao \
  mariadb:10.6 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_general_ci
```

验证：

```bash
docker exec zentao-mariadb mysql -uroot -pzentao123 -e "SHOW DATABASES;"
# 应看到 zentao 数据库
```

#### 3.2.3 启动 ZenTao 容器

> **关键**：ZenTao 使用 `MYSQL_PASSWORD`（不是 `MYSQL_ROOT_PASSWORD`）环境变量，
> 默认密码是 `123456`。必须设置 `MYSQL_USER=root` 和 `MYSQL_PASSWORD=zentao123`。

```bash
docker run -d \
  --name zentao \
  --network bridge-net \
  -p 8083:80 \
  --platform linux/amd64 \
  -e MYSQL_INTERNAL=false \
  -e MYSQL_HOST=zentao-mariadb \
  -e MYSQL_PORT=3306 \
  -e MYSQL_USER=root \
  -e MYSQL_PASSWORD=zentao123 \
  -e MYSQL_DB=zentao \
  hub.zentao.net/app/zentao:18.5
```

等待启动（约 30 秒）：

```bash
# 查看启动日志
docker logs -f zentao

# 健康检查
curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/
# 应返回 200 或 302
```

#### 3.2.4 ZenTao 初始化向导

1. 浏览器访问 `http://localhost:8083/`
2. 进入安装向导，选择「已有的数据库」
3. 数据库参数：
   - Host: `zentao-mariadb`（容器名）
   - Port: `3306`
   - User: `root`
   - Password: `zentao123`
   - Database: `zentao`
4. 设置管理员账号：用户名 `admin`，密码 `ZenTao@123`
5. 完成安装

#### 3.2.5 获取 API Token

ZenTao REST API v1 使用 `Token` 请求头（**不是** `Authorization: Bearer`）：

```bash
# 获取 Token
curl -s -X POST http://localhost:8083/api.php/v1/tokens \
  -H "Content-Type: application/json" \
  -d '{"account":"admin","password":"ZenTao@123"}' | python3 -m json.tool
```

响应示例：

```json
{
  "token": "256ef3a6e9cf07df35125ccaf7db4fa7"
}
```

保存此 Token，后续配置 SnapAgent 时需要使用。

#### 3.2.6 创建产品

在 ZenTao Web UI 中：
1. 进入「产品」→「创建产品」
2. 产品名称：`SnapAgent测试产品`
3. 记录产品 ID（通常为 `1`）

验证 API：

```bash
# 列出产品下的 bug
curl -s http://localhost:8083/api.php/v1/products/1/bugs \
  -H "Token: 256ef3a6e9cf07df35125ccaf7db4fa7" | python3 -m json.tool
```

#### 3.2.7 ZenTao API 速查

| 操作 | 方法 | 路径 | 请求头 |
|------|------|------|--------|
| 获取 Token | POST | `/api.php/v1/tokens` | `Content-Type: application/json` |
| 创建 Bug | POST | `/api.php/v1/products/{productId}/bugs` | `Token: {token}` |
| 解决 Bug | POST | `/api.php/v1/bugs/{id}/resolve` | `Token: {token}` |
| 关闭 Bug | POST | `/api.php/v1/bugs/{id}/close` | `Token: {token}` |
| 添加评论 | POST | `/api.php/v1/bugs/{id}/comments` | `Token: {token}` |
| 查看 Bug | GET | `/bug-view-{id}.html` | — |

创建 Bug 的最小请求体：

```json
{
  "title": "测试Bug标题",
  "desc": "测试Bug描述",
  "severity": 3,
  "pri": 3,
  "type": "codeerror",
  "openedBuild": "trunk"
}
```

> **注意**：`openedBuild` 字段在 ZenTao 18.x 中为必填项，缺失会返回 400 错误。

### 3.3 Bitbucket 搭建

#### 3.3.1 启动 MySQL（Bitbucket 数据库）

```bash
docker run -d \
  --name bitbucket-mysql \
  --network bridge-net \
  -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=bitbucket123 \
  -e MYSQL_DATABASE=bitbucket \
  -e MYSQL_USER=bitbucket \
  -e MYSQL_PASSWORD=bitbucket123 \
  mysql:8.0.33 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci \
  --innodb-log-file-size=256M \
  --max-allowed-packet=32M
```

#### 3.3.2 构建 Bitbucket 自定义镜像（含 MySQL 驱动）

Bitbucket 8.19.0 不含 MySQL JDBC 驱动，需要自定义镜像：

```bash
# 下载 MySQL JDBC 驱动
curl -sL -o /tmp/mysql-connector-j.jar \
  https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar

# 创建 Dockerfile
cat > /tmp/Dockerfile.bitbucket << 'EOF'
FROM atlassian/bitbucket:8.19.0
USER root
RUN mkdir -p /var/atlassian/application-data/bitbucket/lib
COPY mysql-connector-j.jar /var/atlassian/application-data/bitbucket/lib/mysql-connector-j.jar
RUN chown -R bitbucket:bitbucket /var/atlassian/application-data/bitbucket/lib
USER 2003
EOF

# 构建镜像
docker build -t bitbucket-with-mysql:8.19.0 -f /tmp/Dockerfile.bitbucket /tmp/
```

#### 3.3.3 启动 Bitbucket 容器

```bash
docker run -d \
  --name bitbucket \
  --network bridge-net \
  -p 7990:7990 \
  -p 7999:7999 \
  -e JDBC_DRIVER=com.mysql.cj.jdbc.Driver \
  -e JDBC_URL='jdbc:mysql://bitbucket-mysql:3306/bitbucket?useSSL=false' \
  -e JDBC_USER=bitbucket \
  -e JDBC_PASSWORD=bitbucket123 \
  bitbucket-with-mysql:8.19.0
```

等待启动（约 2 分钟）：

```bash
docker logs -f bitbucket
# 看到 "Bitbucket starting" 后等待 "Server STARTED" 日志
```

#### 3.3.4 Bitbucket 初始化

1. 访问 `http://localhost:7990/`
2. 数据库连接页面会自动填入 JDBC 环境变量
3. **License 页面**：需要 Atlassian 评估许可证
   - 访问 https://my.atlassian.com 生成评估 License（需 Atlassian 账号）
   - 或使用 Server ID 手动生成
4. 管理员账号设置

> **已知限制**：Bitbucket License 需要有效的 Atlassian 账号。
> 如果暂时没有 License，可以跳过 Bitbucket 测试，仅测试 ZenTao Issue 流程。

#### 3.3.5 Bitbucket API 速查

| 操作 | 方法 | 路径 | 认证 |
|------|------|------|------|
| 创建仓库 | POST | `/rest/api/1.0/projects/{key}/repos` | HTTP Basic |
| 列出仓库 | GET | `/rest/api/1.0/projects/{key}/repos` | HTTP Basic |
| 获取文件 | GET | `/rest/api/1.0/projects/{key}/repos/{slug}/raw/{path}` | HTTP Basic |
| 创建分支 | POST | `/rest/api/1.0/projects/{key}/repos/{slug}/branches` | HTTP Basic |

---

## 4. SnapAgent Demo 配置与启动

### 4.1 配置文件

编辑 `snap-agent-demo/src/main/resources/application-local.yml`：

```yaml
snap-agent:
  # ... 其他配置省略 ...

  # === v0.9 问题闭环 ===
  issue-closure:
    enabled: true
    system-user-id: system
    tracker-type: zentao
    zentao:
      base-url: http://localhost:8083
      token: 256ef3a6e9cf07df35125ccaf7db4fa7  # 替换为你的 Token
      product-id: 1                              # 替换为你的产品 ID
      project-id: 0

  # === v2.0 浏览器网络桥接 ===
  bridge:
    enabled: true
    request-timeout-ms: 30000
    allowed-host-patterns:
      - "*.example.com"
      - localhost           # 允许 localhost 请求
      - "*.sfcloud.local"
      - "*.sf-express.com"
```

**关键配置说明**：
- `bridge.enabled: true` — 启用桥接功能，注入 `BridgeHttpExecutor` 和 `IssueBridgeService`
- `allowed-host-patterns` — 白名单，Bridge 只代理匹配的 host（`localhost` 必须包含）
- `zentao.base-url` — 指向 Docker 容器映射的端口

### 4.2 构建

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" \
/opt/homebrew/Cellar/maven/3.9.15/bin/mvn clean package -DskipTests \
  -pl snap-agent-demo -am \
  -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml
```

### 4.3 启动

```bash
# 查找 JAR（版本号可能变化）
ls snap-agent-demo/target/snap-agent-demo-*.jar

# 启动
java -jar snap-agent-demo/target/snap-agent-demo-0.6.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --logging.file.name=/tmp/skills-agent-demo.log &
```

验证：

```bash
# 检查进程
ps aux | grep snap-agent-demo

# 检查 Bridge 状态
curl -s http://localhost:8080/snap-agent/bridge/status | python3 -m json.tool

# 预期响应（无扩展连接时）:
# {
#     "connected": false,
#     "emitterCount": 0,
#     "pendingCount": 0,
#     "installed": false,
#     "masterEnabled": false,
#     "services": {}
# }
```

---

## 5. Chrome 扩展安装

### 5.1 加载未打包扩展

1. 打开 `chrome://extensions/`
2. 开启「开发者模式」
3. 点击「加载已解压的扩展程序」
4. 选择目录：`/Users/HuaSheng.Song/IdeaProjects/skills-agent/snapagent-bridge-extension/`
5. 确认扩展已加载，状态为「已启用」

### 5.2 扩展配置

点击扩展图标，在 Popup 中：
- **Master Toggle**: ON
- **Issue Tracker Proxy**: ON
- **VCS Proxy**: ON

### 5.3 验证扩展状态

访问 SnapAgent UI `http://localhost:8080/snap-agent/index.html`：
- 右下角应显示绿色指示灯：`Bridge: ON (issue-tracker, vcs)`
- 服务器 Bridge 状态应变为 `installed: true`

```bash
curl -s http://localhost:8080/snap-agent/bridge/status | python3 -m json.tool
# {
#     "connected": true,
#     "emitterCount": 1,
#     "pendingCount": 0,
#     "installed": true,
#     "masterEnabled": true,
#     "services": {
#         "issue-tracker": true,
#         "vcs": true
#     }
# }
```

---

## 6. MCP Chrome 扩展模拟器

MCP 控制的 Chrome 以 `--disable-extensions` 启动，无法加载真实扩展。
通过 `evaluate_script` 注入 JavaScript 模拟扩展行为。

### 6.1 导航到 SnapAgent 页面

> **重要**：不要在 URL 中包含 Basic Auth 凭证（`http://demo:demo@localhost:8080/...`），
> 否则 `fetch()` 会报错 "Request cannot be constructed from a URL that includes credentials"。

```
navigate_page → http://localhost:8080/snap-agent/index.html
```

### 6.2 注入扩展模拟器

使用 `evaluate_script` 注入以下代码：

```javascript
// 1. 移除旧模拟器（如果存在）
if (window.__bridgeSimulatorHandler) {
    window.removeEventListener('message', window.__bridgeSimulatorHandler);
}

// 2. 注入新模拟器
window.__bridgeSimulatorHandler = function(event) {
    if (event.data && event.data.type === 'snapagent-proxy-request') {
        var req = event.data.request;
        var init = {
            method: req.method || 'GET',
            headers: req.headers || {}
        };
        if (req.body && req.method !== 'GET' && req.method !== 'HEAD') {
            init.body = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
        }
        fetch(req.url, init).then(function(resp) {
            var headers = {};
            resp.headers.forEach(function(v, k) { headers[k] = v; });
            return resp.text().then(function(body) {
                window.postMessage({
                    type: 'snapagent-proxy-response',
                    response: {
                        id: req.id,
                        status: resp.status,
                        body: body,
                        headers: headers
                    }
                }, '*');
            });
        }).catch(function(e) {
            window.postMessage({
                type: 'snapagent-proxy-response',
                response: {
                    id: req.id,
                    error: e.message
                }
            }, '*');
        });
    }
};

window.addEventListener('message', window.__bridgeSimulatorHandler);

// 3. 通知 bridge-client.js 扩展已安装
window.postMessage({
    type: 'snapagent-bridge-status',
    status: {
        installed: true,
        masterEnabled: true,
        services: {
            'issue-tracker': true,
            'vcs': true
        }
    }
}, '*');
```

### 6.3 验证模拟器

```bash
curl -s http://localhost:8080/snap-agent/bridge/status | python3 -m json.tool
# 预期: installed=true, masterEnabled=true, services 全部 true
```

---

## 7. E2E 测试场景

### 7.1 场景一：Bridge 连接与状态验证

**目标**：验证 SSE 连接、扩展状态上报、状态查询端点。

**步骤**：

| # | 操作 | 预期结果 |
|---|------|---------|
| 1 | 访问 `http://localhost:8080/snap-agent/index.html` | 页面加载，bridge-client.js 自动初始化 |
| 2 | 检查右下角指示灯 | 初始为 "Extension not installed"（红色） |
| 3 | 注入扩展模拟器（或安装真实扩展） | 指示灯变为 "Bridge: ON"（绿色） |
| 4 | `GET /bridge/status` | `connected: true, emitterCount: 1, installed: true` |
| 5 | 关闭页面 | SSE 断开，`GET /bridge/status` 返回 `connected: false, emitterCount: 0` |

**验证命令**：

```bash
# 状态查询
curl -s http://localhost:8080/snap-agent/bridge/status | python3 -m json.tool

# SSE 流测试（命令行接收 SSE 事件）
curl -N http://localhost:8080/snap-agent/bridge/stream
# 保持连接，等待 proxy-request 事件
```

### 7.2 场景二：通过 Bridge 创建 ZenTao Issue

**目标**：验证完整的 Issue 创建链路通过 Bridge 代理。

**调用链**：
```
POST /runs/{taskId}/issue
  → IssueClosureService.createExternalIssue()
  → ZentaoIssueTracker.createIssue()
  → jsonRequest(url, "POST", tokenHeader(token), body)
  → BridgeHttpExecutor.execute()
  → IssueBridgeService.proxyRequest()  [Bridge 激活]
  → SSE push "proxy-request" → 浏览器
  → 扩展/模拟器 fetch("http://localhost:8083/api.php/v1/products/1/bugs")
  → ZenTao 返回 {"id": 123}
  → POST /bridge/result → complete future
  → Issue 创建成功
```

**前置条件**：需要一个已完成的 Agent Task（有 `taskId`）。

**步骤**：

1. **触发 Agent 诊断任务**：
   - 在 SnapAgent UI 中选择一个 Skill，输入问题并执行
   - 等待任务完成（状态为 `SUCCEEDED` / `TIMEOUT` / `FAILED`）
   - 记录 `taskId`

2. **提议解决方案**（可选前置步骤）：

```bash
TASK_ID="<替换为实际 taskId>"

curl -s -X POST "http://localhost:8080/snap-agent/runs/${TASK_ID}/solution" \
  -u demo:demo \
  -H "Content-Type: application/json" | python3 -m json.tool
```

3. **创建 Issue（通过 Bridge 代理到 ZenTao）**：

```bash
curl -s -X POST "http://localhost:8080/snap-agent/runs/${TASK_ID}/issue" \
  -u demo:demo \
  -H "Content-Type: application/json" \
  -d '{"selected_solution": "solution_1"}' | python3 -m json.tool
```

**预期响应**：

```json
{
    "issueId": "...",
    "externalIssueId": "123",
    "externalIssueUrl": "http://localhost:8083/bug-view-123.html",
    "status": "OPEN"
}
```

**验证 Issue 已在 ZenTao 中创建**：

```bash
# 通过 API 验证
curl -s http://localhost:8083/api.php/v1/products/1/bugs \
  -H "Token: 256ef3a6e9cf07df35125ccaf7db4fa7" | python3 -m json.tool

# 或在 ZenTao Web UI 中查看
# http://localhost:8083/bug-view-123.html
```

**监控手段**：

- **服务端日志**：`tail -f /tmp/skills-agent-demo.log` 中搜索 `Bridge active` 或 `proxying`
- **浏览器控制台**：`[BridgeClient]` 前缀日志显示 SSE 事件接收和 fetch 结果
- **Bridge 状态**：`GET /bridge/status` 中 `pendingCount` 在请求期间应短暂为 1

### 7.3 场景三：Bridge 路由降级验证

**目标**：当扩展未激活时，HTTP 请求应直接走 `DirectHttpExecutor`。

**步骤**：

| # | 操作 | 预期结果 |
|---|------|---------|
| 1 | 关闭扩展 Master Toggle（或移除模拟器） | `installed: false` 或 `masterEnabled: false` |
| 2 | `GET /bridge/status` | `installed: false` 或 `masterEnabled: false` |
| 3 | 触发 Issue 创建 | 请求直接发送到 ZenTao（不走 SSE） |
| 4 | 检查 ZenTao | Issue 创建成功（前提：服务器能直连 ZenTao） |
| 5 | 检查服务端日志 | 无 `Bridge active` 日志，走 `DirectHttpExecutor` |

**降级模拟器**：

```javascript
// 关闭 Bridge
window.postMessage({
    type: 'snapagent-bridge-status',
    status: {
        installed: true,
        masterEnabled: false,   // Master 开关关闭
        services: {}
    }
}, '*');
```

---

## 8. 故障排查

### 8.1 ZenTao 相关

| 症状 | 原因 | 解决方案 |
|------|------|---------|
| ZenTao 容器反复重启 | 内置 MariaDB 在 ARM64 上无法启动 | 使用外部 MariaDB 10.6（见 3.2.2） |
| `Access denied for user 'root'` | ZenTao 用 `MYSQL_PASSWORD` 而非 `MYSQL_ROOT_PASSWORD` | 设置 `MYSQL_USER=root` 和 `MYSQL_PASSWORD=zentao123` |
| API 返回 400 `openedBuild` required | ZenTao 18.x 必填字段 | 代码已修复，`ZentaoIssueTracker` 已添加 `openedBuild: "trunk"` |
| API 返回 401 Unauthorized | Token 请求头错误 | 使用 `Token: {token}` 而非 `Authorization: Bearer {token}` |

### 8.2 Bitbucket 相关

| 症状 | 原因 | 解决方案 |
|------|------|---------|
| `No suitable driver found` | Bitbucket 不含 MySQL JDBC 驱动 | 构建自定义镜像（见 3.3.2） |
| `AccessDeniedException` on `/lib/native` | Volume mount 权限问题 | 使用 Dockerfile `COPY` 而非 `-v` 挂载 |
| 卡在 License 页面 | 需要 Atlassian 评估许可证 | 访问 my.atlassian.com 生成（需账号） |

### 8.3 Bridge 相关

| 症状 | 原因 | 解决方案 |
|------|------|---------|
| `fetch` 报 "URL includes credentials" | URL 包含 Basic Auth 凭证 | 不使用 `http://user:pass@host` 格式 |
| SSE 连不上 | 认证拦截 | 确保页面已通过 Basic Auth 登录 |
| `No bridge client connected` | 无 SSE 连接 | 刷新页面，检查 bridge-client.js 是否加载 |
| Bridge 请求超时 | 30 秒内无响应 | 检查浏览器控制台是否收到 `proxy-request` 事件 |
| `URL not allowed by bridge host patterns` | host 不在白名单 | 在 `allowed-host-patterns` 中添加目标 host |

### 8.4 MCP Chrome 相关

| 症状 | 原因 | 解决方案 |
|------|------|---------|
| 扩展无法加载 | MCP Chrome 以 `--disable-extensions` 启动 | 使用 `evaluate_script` 注入模拟器 |
| `developerPrivate.loadUnpacked` 失败 | 同上 | 同上 |
| Chrome 重连失败 | 手动 kill Chrome 后 MCP 无法重连 | kill 手动启动的 Chrome 进程，让 MCP 自动重连 |

---

## 9. 清理与回收

### 9.1 停止所有容器

```bash
docker stop zentao zentao-mariadb bitbucket bitbucket-mysql 2>/dev/null
docker rm zentao zentao-mariadb bitbucket bitbucket-mysql 2>/dev/null
```

### 9.2 删除网络

```bash
docker network rm bridge-net 2>/dev/null
```

### 9.3 停止 SnapAgent

```bash
# 查找并 kill SnapAgent 进程
ps aux | grep snap-agent-demo | grep -v grep | awk '{print $2}' | xargs kill
```

### 9.4 清理临时文件

```bash
rm -f /tmp/mysql-connector-j.jar /tmp/Dockerfile.bitbucket
rm -f /tmp/skills-agent-demo.log
```

---

## 附录 A：容器端口映射

| 服务 | 容器名 | 容器端口 | 主机端口 | 用途 |
|------|--------|---------|---------|------|
| ZenTao | zentao | 80 | 8083 | Issue Tracker Web UI + REST API |
| MariaDB (ZenTao) | zentao-mariadb | 3306 | 3308 | ZenTao 数据库 |
| Bitbucket | bitbucket | 7990 | 7990 | VCS Web UI + REST API |
| Bitbucket SSH | bitbucket | 7999 | 7999 | Git SSH |
| MySQL (Bitbucket) | bitbucket-mysql | 3306 | 3307 | Bitbucket 数据库 |
| SnapAgent | — | 8080 | 8080 | SnapAgent Server |

## 附录 B：Bridge API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/snap-agent/bridge/stream` | SSE 通道，服务器推送 `proxy-request` 事件 |
| POST | `/snap-agent/bridge/result` | 前端回传 HTTP 响应（BridgeResponse） |
| GET | `/snap-agent/bridge/status` | 查询 Bridge 状态（BridgeStatus） |
| POST | `/snap-agent/bridge/status-update` | 上报扩展状态（installed, masterEnabled, services） |

## 附录 C：Issue Closure API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/snap-agent/runs` | 创建并执行 Agent 任务 |
| GET | `/snap-agent/runs` | 列出任务 |
| GET | `/snap-agent/runs/{id}` | 查看任务详情 |
| POST | `/snap-agent/runs/{taskId}/bugfix-suggestion` | 生成修复建议 |
| POST | `/snap-agent/runs/{taskId}/solution` | 提议解决方案 |
| POST | `/snap-agent/runs/{taskId}/issue` | 在外部 Tracker 创建 Issue |
| POST | `/snap-agent/runs/{taskId}/auto-fix` | 自动修复 |
| GET | `/snap-agent/issues` | 列出所有 Issue |
| GET | `/snap-agent/issues/{issueId}` | 查看 Issue 详情 |
| POST | `/snap-agent/issues/{issueId}/verify` | 验证 Issue |
| POST | `/snap-agent/issues/{issueId}/close` | 关闭 Issue |

## 附录 D：扩展模拟器通信协议

```
Page (bridge-client.js)
  │
  │ window.postMessage({ type: 'snapagent-proxy-request', request: {...} })
  ▼
Extension / Simulator (content.js)
  │
  │ chrome.runtime.sendMessage({ type: 'snapagent-fetch', request: {...} })
  ▼
Background (background.js)
  │
  │ fetch(url, init) — 无 CORS 限制
  │
  │ sendResponse({ id, status, body, headers })
  ▼
Extension / Simulator (content.js)
  │
  │ window.postMessage({ type: 'snapagent-proxy-response', response: {...} })
  ▼
Page (bridge-client.js)
  │
  │ fetch('/bridge/result', { method: 'POST', body: response })
  ▼
Server (BridgeController → IssueBridgeService.handleResult → complete future)
```

**扩展状态上报**：

```
Extension (content.js)
  │
  │ window.postMessage({ type: 'snapagent-bridge-status', status: {...} })
  ▼
Page (bridge-client.js)
  │
  │ fetch('/bridge/status-update', { method: 'POST', body: status })
  ▼
Server (BridgeController → IssueBridgeService.updateClientStatus)
```
