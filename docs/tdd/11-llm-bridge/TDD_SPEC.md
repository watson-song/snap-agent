# TDD需求规格说明书 — LLM Bridge (精简版)

> 版本: 1.0
> 适用: AI辅助开发 + TDD流程
> 关联: [llm-bridge-design.md](../../site/bridge/zh/llm-bridge-design.md)

---

## 1. 需求元信息

```yaml
需求ID: REQ-LLM-BRIDGE
需求名称: LLM Bridge — 浏览器端 LLM 调用，Server 纯工具服务
优先级: P1
迭代: Sprint 2
负责人: snap-agent team
状态: 设计完成
```

### 1.1 背景与目标
- **业务背景**: 当前 SnapAgent 的 LLM 调用是服务端直连，需要配置 API Key。用户部署 standalone JAR 后需手动配置，且 API Key 暴露在 Server。浏览器 bridge 插件已有 LLM 能力，可以将 LLM 调用移到浏览器，Server 退化为纯工具提供者。
- **用户价值**: 零配置启动（无需 API Key），API Key 安全隔离（存在浏览器插件），Server 无 LLM 依赖（部署极简）。
- **成功指标**: Bridge 模式延迟 < 50ms，HTTP 请求数 < 10 次/对话，Server 线程零阻塞。

### 1.2 范围边界
- **包含**: `BridgeLlmClient` 实现、`/bridge/llm-result` 端点、浏览器插件 LLM 代理逻辑、前端 `llm-bridge-client.js`、降级策略。
- **不包含**: 浏览器插件的 UI 改造（popup.html）、API Key 管理界面、LLM 模型选择界面。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 | 负责人 |
|--------|------|------|------|----------|--------|
| R1 | 浏览器插件未安装时 LLM 不可用 | 中 | 高 | 降级到直连模式（需配置 fallback api-key） | team |
| R2 | LLM 响应超时导致 Agent Loop 阻塞 | 低 | 中 | 设置 120s 超时，超时后返回错误 | team |
| R3 | tool_call 参数解析失败 | 低 | 中 | 返回 tool_result error，让 LLM 重试 | team |
| R4 | 浏览器插件配置错误（API Key 无效） | 中 | 中 | 返回明确错误信息，引导用户检查配置 | team |

**关键假设**:
- 假设1: 浏览器插件已安装且 LLM toggle 已开启。
- 假设2: 浏览器能访问外部 LLM API（无网络隔离）。
- 假设3: Server 的 Tool 执行正常（mysql_query 等工具可用）。

---

## 2. 用户故事 (User Stories)

### US-1: 零配置启动
```gherkin
As a standalone user
I want to use SnapAgent without configuring LLM API Key
So that I can start using it immediately after docker run
```

**验收标准 (AC):**
```gherkin
AC1: 无需 API Key 启动
  Given snap-agent.llm.api-type=bridge
  And 未配置 api-key / auth-token
  When Server 启动
  Then 启动成功，Health=UP

AC2: 浏览器插件已激活时正常对话
  Given bridge.enabled=true
  And 浏览器插件已安装 + LLM toggle ON
  When 用户发送消息
  Then Agent Loop 正常运行
  And LLM 调用通过浏览器插件完成
```

### US-2: Server 纯工具服务
```gherkin
As a server administrator
I want the server to only provide tool execution
So that I don't need to manage LLM API keys on the server
```

**验收标准 (AC):**
```gherkin
AC1: Server 不存储 API Key
  Given llm.api-type=bridge
  Then application.yml 中无 api-key / auth-token 字段
  And Server 日志中无 API Key 泄露

AC2: Tool 执行正常
  Given 浏览器插件返回 tool_call: mysql_query
  When Server 执行 mysql_query
  Then 返回查询结果
  And 结果回传给浏览器插件
```

### US-3: 降级策略
```gherkin
As a user
I want automatic fallback to direct LLM when bridge is unavailable
So that I can still use SnapAgent even without the browser extension
```

**验收标准 (AC):**
```gherkin
AC1: 插件未安装时降级
  Given bridge.enabled=true
  And 浏览器插件未安装
  When 用户发送消息
  Then Server 使用 fallback-api-key 直连 LLM
  And 返回正常响应

AC2: 插件 LLM toggle OFF 时降级
  Given bridge.enabled=true
  And 浏览器插件已安装但 LLM toggle OFF
  When 用户发送消息
  Then Server 使用 fallback-api-key 直连 LLM
```

### US-4: 安全隔离
```gherkin
As a security-conscious user
I want my LLM API Key to stay in the browser extension
So that it never touches the server or appears in logs
```

**验收标准 (AC):**
```gherkin
AC1: API Key 不经过 Server
  Given llm.api-type=bridge
  When 用户对话
  Then Server 日志中无 API Key
  And network trace 中 Server → 浏览器 的 prompt 不含 API Key

AC2: 域名白名单校验
  Given bridge.allowed-host-patterns=["api.anthropic.com"]
  When 浏览器插件尝试代理非白名单域名
  Then 请求被拒绝
  And 返回错误信息
```

---

## 3. 技术方案

### 3.1 Server 端改造

#### 新增类

| 类名 | 职责 | 包路径 |
|------|------|--------|
| `BridgeLlmClient` | 实现 `StreamingLlmClient`，通过 bridge 调用 LLM | `cn.watsontech.snapagent.boot2x.llm` |
| `BridgeLlmProperties` | 配置属性 (`fallback-api-key`, `fallback-base-url`) | `cn.watsontech.snapagent.boot2x.autoconfig` |
| `LlmBridgeController` | `/bridge/llm-result` 端点 | `cn.watsontech.snapagent.boot2x.web` |

#### 修改类

| 类名 | 变更 | 原因 |
|------|------|------|
| `LlmClientFactory` | 新增 `api-type: bridge` 分支 | 支持 BridgeLlmClient |
| `SnapAgentProperties` | 新增 `llm.fallback-*` 字段 | 降级配置 |

### 3.2 浏览器插件改造

#### 新增文件

| 文件 | 职责 |
|------|------|
| `background.js` (LLM 代理部分) | 接收 `llm-request`，调用 LLM API，流式返回 |
| `popup.html` (LLM 配置) | API Key 输入框、LLM toggle 开关 |

#### 修改文件

| 文件 | 变更 |
|------|------|
| `manifest.json` | 新增 LLM 域名权限 (`api.anthropic.com`) |
| `content.js` | 新增 `SNAP_AGENT_LLM_READY` 信号 |

### 3.3 前端改造

#### 新增文件

| 文件 | 职责 |
|------|------|
| `llm-bridge-client.js` | 监听 prompt，转发给插件，接收 LLM 响应，回传 Server |

#### 修改文件

| 文件 | 变更 |
|------|------|
| `index.html` | 条件加载 `llm-bridge-client.js`（当 `api-type=bridge`） |

---

## 4. 测试策略

### 4.1 单元测试

| 测试类 | 测试内容 |
|--------|----------|
| `BridgeLlmClientTest` | prompt 发送、LLM 响应解析、tool_call 处理、超时处理 |
| `LlmBridgeControllerTest` | `/bridge/llm-result` 端点、消息转发、错误处理 |
| `BridgeLlmPropertiesTest` | 配置加载、fallback 配置、默认值 |

### 4.2 集成测试

| 测试类 | 测试内容 |
|--------|----------|
| `LlmBridgeE2ETest` | 完整对话流程（prompt → LLM → tool_call → result → done） |
| `LlmBridgeFallbackTest` | 插件不可用时降级到直连模式 |

### 4.3 性能测试

| 测试场景 | 指标 |
|----------|------|
| 单次对话延迟 | < 50ms 额外延迟 |
| 并发 10 用户 | POST /秒 < 100，线程零阻塞 |
| 内存占用 | < 10MB 额外内存 |

---

## 5. 实现优先级

### P0 (必须)

- [ ] `BridgeLlmClient` 实现
- [ ] `/bridge/llm-result` 端点
- [ ] 浏览器插件 LLM 代理逻辑
- [ ] 基础降级策略（插件未安装 → 直连）

### P1 (应该)

- [ ] 前端 `llm-bridge-client.js`
- [ ] 本地打字效果显示
- [ ] 域名白名单校验

### P2 (可以)

- [ ] API Key 管理界面
- [ ] LLM 模型选择界面
- [ ] 高级降级策略（部分工具走 bridge，部分走直连）

---

## 6. 验收标准汇总

| ID | 验收标准 | 优先级 | 状态 |
|----|----------|--------|------|
| AC-1 | 无需 API Key 启动 | P0 | ⬜ |
| AC-2 | 浏览器插件已激活时正常对话 | P0 |  |
| AC-3 | Server 不存储 API Key | P0 | ⬜ |
| AC-4 | Tool 执行正常 | P0 | ⬜ |
| AC-5 | 插件未安装时降级 | P0 | ⬜ |
| AC-6 | 插件 LLM toggle OFF 时降级 | P1 | ⬜ |
| AC-7 | API Key 不经过 Server | P0 | ⬜ |
| AC-8 | 域名白名单校验 | P1 | ⬜ |
| AC-9 | 单次对话延迟 < 50ms | P1 | ⬜ |
| AC-10 | 并发 10 用户线程零阻塞 | P1 | ⬜ |

---

## 7. 附录

### 7.1 消息格式

参考 [llm-bridge-design.md#附录 A](../../site/bridge/zh/llm-bridge-design.md#附录-a-消息格式)

### 7.2 配置示例

```yaml
# 纯 Bridge 模式（推荐）
snap-agent:
  llm:
    api-type: bridge
  bridge:
    enabled: true
    allowed-host-patterns:
      - "api.anthropic.com"

# 混合模式（Bridge 优先，直连备用）
snap-agent:
  llm:
    api-type: bridge
    fallback-api-key: sk-xxx
    fallback-base-url: https://api.anthropic.com
  bridge:
    enabled: true
```

### 7.3 与现有 Bridge 的关系

参考 [llm-bridge-design.md#附录 B](../../site/bridge/zh/llm-bridge-design.md#附录-b-与现有-bridge-的关系)

---

## 8. 多宿主隔离需求（新增）

### 8.1 背景

用户可能同时运行多个 SnapAgent 实例（dev / staging / prod），每个实例需要独立的 LLM 配置，互不干扰。

### 8.2 用户故事

```gherkin
As a multi-environment user
I want each SnapAgent host to have its own LLM configuration
So that dev/staging/prod don't interfere with each other
```

### 8.3 验收标准

```gherkin
AC-11: 多宿主配置隔离
  Given 用户访问 localhost:8090 并配置 API Key "sk-dev"
  And 用户访问 staging.sfcloud.local 并配置 API Key "sk-staging"
  When 用户在 localhost:8090 发送消息
  Then LLM 调用使用 "sk-dev"
  And staging.sfcloud.local 的配置不受影响

AC-12: 新宿主默认配置
  Given 用户首次访问 prod.sfcloud.local
  When 打开 Popup
  Then 显示默认配置（空 API Key）
  And 提示用户配置 LLM

AC-13: 配置持久化
  Given 用户在 localhost:8090 配置了 LLM
  When 刷新页面
  Then 配置仍然生效
  And 无需重新配置
```

### 8.4 技术方案

#### 浏览器插件存储结构

```javascript
// chrome.storage.local
{
  "llm-config:http://localhost:8090": {
    apiKey: "sk-dev-key",
    baseUrl: "https://api.anthropic.com",
    model: "claude-sonnet-4-20250514",
    maxTokens: 4096
  },
  "llm-config:https://staging.sfcloud.local": {
    apiKey: "sk-staging-key",
    baseUrl: "https://api.anthropic.com",
    model: "claude-opus-4-20250514",
    maxTokens: 8192
  },
  "llm-config:https://prod.sfcloud.local": {
    apiKey: "sk-prod-key",
    baseUrl: "https://api.anthropic.com",
    model: "claude-opus-4-20250514",
    maxTokens: 16384
  }
}
```

#### 域名识别

```javascript
function getHostNamespace() {
  const origin = window.location.origin;  // "http://localhost:8090"
  return `llm-config:${origin}`;
}

async function getLlmConfig() {
  const key = getHostNamespace();
  const config = await chrome.storage.local.get(key);
  return config[key] || getDefaultConfig();
}
```

#### Popup 界面

- 显示当前宿主域名
- 每个宿主独立的配置表单
- 已配置宿主列表

### 8.5 安全隔离

| 维度 | 实现 |
|------|------|
| 配置隔离 | `chrome.storage.local` 按域名 key |
| API Key 隔离 | 每宿主独立 Key |
| 请求隔离 | `background.js` 按来源域名选配置 |
| 权限隔离 | 域名白名单按宿主独立 |

---

## 9. 更新后的验收标准汇总

| ID | 验收标准 | 优先级 | 状态 |
|----|----------|--------|------|
| AC-1 | 无需 API Key 启动 | P0 |  |
| AC-2 | 浏览器插件已激活时正常对话 | P0 | ⬜ |
| AC-3 | Server 不存储 API Key | P0 | ⬜ |
| AC-4 | Tool 执行正常 | P0 |  |
| AC-5 | 插件未安装时降级 | P0 | ⬜ |
| AC-6 | 插件 LLM toggle OFF 时降级 | P1 | ⬜ |
| AC-7 | API Key 不经过 Server | P0 | ⬜ |
| AC-8 | 域名白名单校验 | P1 | ⬜ |
| AC-9 | 单次对话延迟 < 50ms | P1 | ⬜ |
| AC-10 | 并发 10 用户线程零阻塞 | P1 | ⬜ |
| **AC-11** | **多宿主配置隔离** | **P0** |  |
| **AC-12** | **新宿主默认配置** | **P1** | ⬜ |
| **AC-13** | **配置持久化** | **P0** | ⬜ |
