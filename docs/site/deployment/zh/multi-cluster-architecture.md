# SnapAgent 多集群部署架构

> 版本：v1.0 | 更新日期：2026-07-17

## 1. 部署场景

SnapAgent 以嵌入式库形态嵌入宿主 Spring Boot 应用。宿主应用在 Kubernetes 中通常以多副本（Deployment + replicas）方式部署，前面挂一个 Service 经 Ingress/LoadBalancer 对外暴露。SnapAgent 的任务状态（`TaskStore`）是**每个 Pod 的内存状态**，没有共享存储——也就是说，哪个 Pod 启动了一个诊断任务，这个任务的完整 transcript 就只存在于那个 Pod 的内存里。

这带来一个关键问题：用户的浏览器经负载均衡器连接到某个 Pod，该 Pod 开始执行一个任务并向浏览器推送 SSE 流。如果浏览器因为网络抖动、页面刷新等原因重新连接，负载均衡器可能将请求路由到**另一个 Pod**。而那个 Pod 的内存里并没有这个任务，无法直接继续推送流。此时需要**跨 Pod SSE 中继**：被路由到的 Pod 去探测兄弟 Pod，找到持有该任务的 Pod，把它的 SSE 流逐行转发回浏览器。

```
┌──────────┐      ┌───────────────┐      ┌────────────────────────────┐
│ Browser  │─────▶│ Ingress / LB  │─────▶│ Pod B（不持有 task）        │
│ (SSE)    │      │ (round-robin) │      │  - taskStore.get(id)=null   │
└──────────┘      └───────────────┘      │  - 触发 PeerSseRelay        │
     ▲                                   └──────────────┬─────────────┘
     │                                                   │ 1. probe 探测
     │                                                   │ 2. relay 中继流
     │                                                   ▼
     │                                   ┌────────────────────────────┐
     │  SSE events（逐行中继）           │ Pod A（持有 task）         │
     └───────────────────────────────────│  - taskStore 命中          │
                                         │  - InternalTaskController  │
                                         │    /tasks/{id}/stream     │
                                         └────────────────────────────┘
```

设计要点：
- **无共享状态**：任何 Pod 可以接收任何请求，不需要 sticky session
- **按需中继**：只有任务不在本地时才触发跨 Pod 探测，正常情况直接本地流式返回
- **降级友好**：没有配跨 Pod 路由时也能工作，只是断线重连会收到 `TASK_NOT_FOUND`

---

## 2. 跨 Pod 路由架构

跨 Pod 路由子系统由一个 SPI 接口、一个中继器和一个内部控制器组成，三者协作完成任务定位与流转发。

```
┌──────────────────────────────────────────────────────────────┐
│                    SnapAgentController                       │
│   GET /runs/{id}/stream                                      │
│   - taskStore.get(id) 命中 → 本地直推 SSE                    │
│   - 未命中 → peerSseRelay.tryRelay(emitter, id)              │
│   - 中继失败 → SSE error 事件 TASK_NOT_FOUND                 │
└──────────────────────┬───────────────────────────────────────┘
                       │ 委托
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                    PeerSseRelay                              │
│   - discoverPeers() → 逐个 probe                             │
│   - 首个 200 → 开 SSE 流 → pipeSse() 逐行转发                │
│   - internal-token 为空 → 中继关闭                            │
└──────────────────────┬───────────────────────────────────────┘
                       │ 发现 peers
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                    PeerRouter (SPI)                          │
│   - discoverPeers() → List<peerBaseURL>                     │
│   - mode() → "k8s-api" / "headless-dns" / "static" / "none"│
│   实现: K8sApiPeerRouter / HeadlessDnsPeerRouter /          │
│         StaticPeerRouter / NoopPeerRouter                   │
└──────────────────────────────────────────────────────────────┘
                       │ pod 间通信
                       ▼
┌──────────────────────────────────────────────────────────────┐
│               InternalTaskController                         │
│   挂载在 basePath 之外: /snap-agent-internal                 │
│   仅校验 X-Skills-Agent-Internal-Token                       │
│   - GET /tasks/{id}/probe  → 200/404/401                    │
│   - GET /tasks/{id}/stream  → SSE 流（轮询 transcript 200ms)│
└──────────────────────────────────────────────────────────────┘
```

### 核心接口

**`PeerRouter`** (SPI):
```java
public interface PeerRouter {
    /** 返回所有可达兄弟 Pod 的 base-URL（排除自身） */
    List<String> discoverPeers();
    /** 发现模式名：k8s-api / headless-dns / static / none */
    String mode();
}
```

peer base-URL 格式为 `http://{ip}:{port}`（Pod 服务器根，**不含** basePath）。`PeerSseRelay` 在其后追加 `internal-path`（默认 `/snap-agent-internal`）来访问 pod 间端点。

**`PeerSseRelay`** (中继器):
```java
public class PeerSseRelay {
    public static final String INTERNAL_TOKEN_HEADER = "X-Skills-Agent-Internal-Token";

    // 阻塞方法，必须在 worker 线程调用
    // 返回 true = 找到 peer 并中继完成；false = 无 peer 持有该 task
    public boolean tryRelay(SseEmitter emitter, String taskId) { ... }
}
```

认证采用单一共享密钥（`X-Skills-Agent-Internal-Token` 请求头），通过 `snap-agent.routing.internal-token` 配置。**如果 token 为空，中继自动关闭**——即使 `PeerRouter` 返回了 peers，也不会进行跨 Pod 路由。

**`InternalTaskController`** (内部端点):

挂载在 `${snap-agent.routing.internal-path:/snap-agent-internal}`，**位于用户可见的 basePath（默认 `/snap-agent`）之外**。这样宿主应用的用户安全过滤链不会拦截 pod 间流量。唯一凭证是共享密钥 token，每个请求都校验。宿主需要放行此路径（例如 Spring Security `antMatchers("/snap-agent-internal/**").permitAll()`）。

两个端点：
- `GET /tasks/{id}/probe` — 本 Pod 持有该 task 返回 200（含 taskId/status/ownerPod），不持有返回 404，token 无效返回 401
- `GET /tasks/{id}/stream` — SSE 流，与用户端点相同的 framing；不做用户鉴权/归属校验（发起 Pod 已做过）；任务不存在时发送 `error` SSE 事件；轮询 transcript 每 200ms，终端状态发送 `done` 事件后结束

---

## 3. Peer 发现策略

`PeerRouter` 有四个实现，通过 `snap-agent.routing.mode` 选择。降级链为 `k8s-api` → `headless-dns` → `static` → `none`。

### 3.1 k8s-api（K8s Endpoints API）

默认推荐方式（当 K8s ServiceAccount token 和 RBAC 可用时）。`K8sApiPeerRouter` 通过查询集群内 Kubernetes Endpoints API 发现兄弟 Pod：

- 读取 SA token：`/var/run/secrets/kubernetes.io/serviceaccount/token`
- 读取 namespace：`/var/run/secrets/kubernetes.io/serviceaccount/namespace`
- API host：`https://kubernetes.default.svc`
- 请求路径：`/api/v1/namespaces/{namespace}/endpoints/{serviceName}`
- 解析 `.subsets[].addresses[].ip` 和 `.subsets[].ports[]`，构造 `http://{ip}:{port}`
- 端口选择：优先选名为 `http` 的端口，否则取第一个端口
- 自我排除：通过 `MY_POD_IP` 环境变量（K8s downward API）排除自身 IP

**适用场景**：标准 K8s 集群部署，有 RBAC 权限配 SA token 读取 endpoints。

**限制**：需要 ServiceAccount 对 `endpoints` 资源有 `get` 权限；token 不可用时发现失败。

```yaml
snap-agent:
  routing:
    mode: k8s-api
    k8s-service-name: my-app-svc   # K8s Service 名称
    port: 8080                     # 可选，0=自动从 server.port 探测
    internal-token: ${INTERNAL_TOKEN}
```

### 3.2 headless-dns（Headless Service DNS）

`HeadlessDnsPeerRouter` 通过解析 Headless Service 的 DNS 名称发现兄弟 Pod：

- Headless Service（`clusterIP: None`）的集群 DNS 为每个 ready Pod 返回一条 A 记录
- 一次 `InetAddress.getAllByName(dnsName)` 调用即可枚举所有 peers
- DNS 名称通过 `k8s-service-name` 配置，可为完整 FQDN 或短名称（依赖 Pod 的 DNS 搜索域解析）
- 自我排除：通过 `MY_POD_IP` 环境变量

**适用场景**：不想或不能配 RBAC 的集群；Headless Service 已有部署。

**限制**：需要 Headless Service（`clusterIP: None`）；只返回 ready Pod；DNS 缓存可能有延迟。

```yaml
snap-agent:
  routing:
    mode: headless-dns
    k8s-service-name: my-app-headless   # Headless Service 名称或 FQDN
    port: 8080
    internal-token: ${INTERNAL_TOKEN}
```

### 3.3 static（静态列表）

`StaticPeerRouter` 使用配置的静态 peer base-URL 列表：

- 不可变列表，构造时一次性传入
- 不会自动伸缩——滚动更新后需手动更新列表

**适用场景**：无服务发现的环境（裸机、非 K8s），或 Pod 地址提前已知的场景。

**限制**：不随副本数自动变化；滚动发布期间列表会过时。

```yaml
snap-agent:
  routing:
    mode: static
    static-peers:
      - http://10.0.1.5:8080
      - http://10.0.1.6:8080
    internal-token: ${INTERNAL_TOKEN}
```

### 3.4 none（本地只读，默认）

`NoopPeerRouter` 返回空 peer 列表，`mode()` 返回 `"none"`。中继不会触发，stream 端点在任务不在本地时直接返回 `TASK_NOT_FOUND` SSE error 事件。

**适用场景**：单实例部署、开发/测试环境、或不关心断线重连的场景。

```yaml
snap-agent:
  routing:
    mode: none   # 默认值，可省略
```

### 策略对比

| 模式 | 发现方式 | 前置条件 | 自动伸缩 | 适用场景 |
|------|---------|----------|---------|---------|
| `k8s-api` | Endpoints API | SA token + RBAC | 是（实时） | 标准 K8s 集群 |
| `headless-dns` | DNS A 记录 | Headless Service | 是（DNS 延迟） | 无 RBAC 的集群 |
| `static` | 配置列表 | 已知 Pod 地址 | 否 | 裸机/非 K8s |
| `none` | 无 | 无 | — | 单实例/开发测试 |

---

## 4. SSE 中继流程

当 `GET /runs/{id}/stream` 请求经负载均衡器落到一个不持有该 task 的 Pod 时，完整的跨 Pod 中继流程如下：

1. 浏览器经 LB 连接到某 Pod 的 `/snap-agent/runs/{id}/stream`
2. 该 Pod 检查本地 `taskStore.get(id)` —— 命中则直接本地流式推送
3. 未命中，且 `peerSseRelay != null` —— 在 worker 线程调用 `peerSseRelay.tryRelay(emitter, id)`
4. Relay 调用 `peerRouter.discoverPeers()` 获取 peer 列表（排除自身）
5. 逐个 peer 探测：`GET {peerBase}/snap-agent-internal/tasks/{id}/probe`，携带 `X-Skills-Agent-Internal-Token` 头
6. 首个 200 → 该 peer 持有 task，打开 SSE 流 `GET {peerBase}/snap-agent-internal/tasks/{id}/stream`
7. 逐行读取 peer 的 SSE 字节流，重新组装事件（`event:`/`data:`/空行分发），通过本地 `SseEmitter` 转发给浏览器
8. 404 → 该 peer 不持有，尝试下一个；401 → 记录警告并尝试下一个；不可达 → 尝试下一个
9. 所有 peer 都不持有 → 返回 `false`，浏览器收到 `TASK_NOT_FOUND` SSE error 事件

```
Browser          Pod B（LB 路由到此）        Pod A（持有 task）
  │                   │                           │
  │ GET /runs/{id}/stream                        │
  │──────────────────▶│                           │
  │                   │ taskStore.get(id) = null   │
  │                   │ peerSseRelay.tryRelay()    │
  │                   │                           │
  │                   │ GET .../tasks/{id}/probe  │
  │                   │──────────────────────────▶│
  │                   │      HTTP 200 (owner)      │
  │                   │◀──────────────────────────│
  │                   │                           │
  │                   │ GET .../tasks/{id}/stream │
  │                   │──────────────────────────▶│
  │                   │    SSE event stream        │
  │                   │◀──────────────────────────│
  │   SSE events      │  （pipeSse 逐行转发）      │
  │◀──────────────────│                           │
  │   done            │                           │
  │◀──────────────────│                           │
```

### 关键细节

- **probe 是 GET 请求**（非 HEAD），返回 JSON body `{taskId, status, ownerPod:true}`
- **stream 端点不做用户鉴权**：发起 Pod 已完成用户鉴权和归属校验，pod 间只校验共享 token
- **中继失败可继续尝试**：如果某 peer 中继过程中断（peer 崩溃或 task 迁移），relay 会 `continue` 到下一个 peer
- **SSE framing**：`event: <name>`、`data: <payload>`、空行触发分发；多行 `data:` 用换行拼接；`:` 开头的注释行（心跳）被忽略
- **客户端断开**：`pipeSse` 在 `emitter.send()` 抛异常时停止转发（客户端已断开）
- **无 peerSseRelay 时**：stream 端点直接发送 `error` SSE 事件 `TASK_NOT_FOUND`

---

## 5. 缓存与降级

### 5.1 发现缓存

`K8sApiPeerRouter` 和 `HeadlessDnsPeerRouter` 都实现了 TTL 缓存，避免每次 stream 请求都触发一次发现调用：

- **TTL**：`snap-agent.routing.discovery-cache-ttl-seconds`（默认 10 秒）
- 缓存有效期内直接返回缓存的 peer 列表
- 缓存过期后重新调用发现 API

```java
// 伪代码（K8sApiPeerRouter / HeadlessDnsPeerRouter 共同逻辑）
if (cachedPeers != null && now < cacheExpiryMillis) {
    return cachedPeers;   // 缓存命中
}
try {
    List<String> fresh = fetchPeersFromApi();   // 重新发现
    cachedPeers = fresh;
    cacheExpiryMillis = now + cacheTtlSeconds * 1000;
    return fresh;
} catch (Exception e) {
    if (cachedPeers != null) {
        return cachedPeers;   // stale-OK：返回上次缓存
    }
    return emptyList;         // 无缓存可用，返回空
}
```

### 5.2 降级策略（stale-OK）

发现失败时（API 不可达、DNS 解析失败等），**返回上次成功缓存的结果**（stale-OK），而非空列表或抛异常。这保证瞬时的 API 错误不会破坏跨 Pod 中继——即使 Kubernetes API 暂时不可达，Pod 仍可使用最后已知的 peer 列表继续探测。

如果没有缓存可用（首次启动即失败），返回空列表，中继退化为本地只读行为。

### 5.3 超时配置

| 组件 | 连接超时 | 读取超时 | 说明 |
|------|---------|---------|------|
| `PeerSseRelay` httpClient | 2 秒 | 30 分钟 | 连接超时短（快速失败），读取超时长（SSE 长连接） |
| `K8sApiPeerRouter` httpClient | 3 秒 | 5 秒 | Endpoints API 是短请求，读取超时短 |

- **probe 探测**：使用 `PeerSseRelay` 的 httpClient，连接超时 2 秒——单个 peer 不可达时 2 秒后跳到下一个
- **stream 中继**：同一 httpClient，读取超时 30 分钟，支撑长生命周期 SSE 流
- **Endpoints API 调用**：使用 `K8sApiPeerRouter` 的 httpClient，连接 3 秒 + 读取 5 秒

### 5.4 完整配置项

```yaml
snap-agent:
  routing:
    mode: none                      # k8s-api / headless-dns / static / none（默认 none）
    internal-token: ""              # pod 间共享密钥，空 = 中继关闭
    k8s-service-name: ""            # K8s Service 名（k8s-api / headless-dns 模式用）
    port: 0                         # 0 = 自动从 server.port 探测
    static-peers: []                # 静态 peer 列表（static 模式用）
    discovery-cache-ttl-seconds: 10 # peer 发现缓存 TTL
    internal-path: /snap-agent-internal  # 内部端点挂载路径（basePath 之外）
    max-peers-to-probe: 20          # 最大探测 peer 数（限制爆炸半径）
```

---

## 6. K8s 部署示例

以下是一个完整的 K8s 部署清单，使用 `k8s-api` 发现模式，包含 Deployment、Service、RBAC 和必要的 env vars。

```yaml
# --- ServiceAccount ---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: snap-agent-sa
  namespace: production
---
# --- RBAC: 允许读取 endpoints ---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: snap-agent-endpoints-reader
  namespace: production
rules:
  - apiGroups: [""]
    resources: ["endpoints"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: snap-agent-endpoints-reader-binding
  namespace: production
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: snap-agent-endpoints-reader
subjects:
  - kind: ServiceAccount
    name: snap-agent-sa
    namespace: production
---
# --- Service (ClusterIP，供 k8s-api 模式读取 endpoints) ---
apiVersion: v1
kind: Service
metadata:
  name: my-app-svc
  namespace: production
spec:
  type: ClusterIP
  selector:
    app: my-app
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      protocol: TCP
---
# --- Deployment ---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: production
spec:
  replicas: 3
  selector:
    matchLabels:
      app: my-app
  template:
    metadata:
      labels:
        app: my-app
    spec:
      serviceAccountName: snap-agent-sa     # 挂载 SA token 到标准路径
      containers:
        - name: app
          image: my-app:latest
          ports:
            - containerPort: 8080
              name: http
          env:
            # K8s downward API — 自身 Pod IP，用于自我排除
            - name: MY_POD_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
            # 内部共享密钥（生产环境用 Secret 注入）
            - name: INTERNAL_TOKEN
              valueFrom:
                secretKeyRef:
                  name: snap-agent-secrets
                  key: internal-token
          envFrom:
            - configMapRef:
                name: my-app-config
          # SnapAgent 路由配置
          # 也可通过 envFrom ConfigMap 注入
          # SA token 自动挂载到 /var/run/secrets/kubernetes.io/serviceaccount/token
          # namespace 自动挂载到 /var/run/secrets/kubernetes.io/serviceaccount/namespace
          readinessProbe:
            httpGet:
              path: /snap-agent/health
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /snap-agent/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 20
---
# --- ConfigMap (SnapAgent 配置) ---
apiVersion: v1
kind: ConfigMap
metadata:
  name: my-app-config
  namespace: production
data:
  application.yml: |
    server:
      port: 8080
    snap-agent:
      enabled: true
      base-path: /snap-agent
      routing:
        mode: k8s-api
        k8s-service-name: my-app-svc
        port: 8080
        internal-token: ${INTERNAL_TOKEN}
        discovery-cache-ttl-seconds: 10
```

### 要点说明

| 资源 | 作用 |
|------|------|
| `ServiceAccount` | 自动将 SA token 挂载到 `/var/run/secrets/kubernetes.io/serviceaccount/token`，namespace 挂载到同级 `namespace` 文件，`K8sApiPeerRouter` 从这两个路径读取 |
| `Role` + `RoleBinding` | 授予 SA 对 `endpoints` 资源的 `get` 权限——没有这个，`K8sApiPeerRouter` 调用 Endpoints API 会返回 403 |
| `Service`（ClusterIP） | 名为 `my-app-svc`，端口名为 `http`——`K8sApiPeerRouter` 的端口选择逻辑优先选名为 `http` 的端口 |
| `MY_POD_IP` env | 来自 K8s downward API `status.podIP`，用于 peer 发现时排除自身 |
| `INTERNAL_TOKEN` env | 来自 Secret，注入 `snap-agent.routing.internal-token`；**空值会关闭中继** |
| 端口名 `http` | `selectPort()` 优先选名为 `http` 的端口，否则取第一个 |

### Headless Service（headless-dns 模式用）

如果使用 `headless-dns` 模式，需要额外创建一个 Headless Service：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app-headless
  namespace: production
spec:
  clusterIP: None          # Headless — DNS 返回每个 Pod 的 A 记录
  selector:
    app: my-app
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

对应配置：`mode: headless-dns`，`k8s-service-name: my-app-headless`。此模式不需要 RBAC。

---

## 7. 单实例部署

对于非 K8s 环境或单副本部署，最简单的配置是使用默认的 `none` 模式：

```yaml
snap-agent:
  routing:
    mode: none          # 默认值，可省略
    internal-token: ""  # 空值，中继不生效
```

此模式下：
- `NoopPeerRouter` 返回空 peer 列表
- 所有任务都在本地 `taskStore` 中
- `GET /runs/{id}/stream` 在任务不存在时直接返回 `TASK_NOT_FOUND` SSE error 事件
- 无跨 Pod 通信，无 RBAC 配置，无内部 token

**适用场景**：
- 开发/测试环境
- 小型应用单副本部署
- 不关心断线重连的场景

如果后续从单实例扩展为多副本，只需将 `mode` 改为 `k8s-api`/`headless-dns`/`static` 并配置 `internal-token` 即可启用跨 Pod 中继。

---

## 8. 已知限制

| 限制 | 说明 | 计划 |
|------|------|------|
| 无共享状态 | 任务状态存于 Pod 内存，Pod 死亡则其 in-memory 任务丢失 | 未来引入 Redis/共享状态存储持久化 |
| 无持久化 | Pod 重启丢失所有运行中任务 | 同上 |
| 中继增加延迟 | 每个 SSE 事件经 2 个 Pod 跳转（owner Pod → relay Pod → browser） | 可接受，SSE 本身为流式推送 |
| K8s API 需 RBAC | SA token 无 endpoints 读取权限时 `k8s-api` 发现失败 | 降级到 `headless-dns` 或 `static` |
| 无 sticky session | 不需要会话粘性——任何 Pod 可服务任何请求 | 设计如此，非限制 |
| 顺序探测 | peers 逐个探测（非并行），peer 数多时探测耗时 | 未来支持并行探测 |
| DNS 缓存延迟 | `headless-dns` 模式依赖集群 DNS，滚动发布期间可能有延迟 | 可减小 cache TTL |

### 容错行为

- **peer 中继中途断开**：`PeerSseRelay` 捕获异常后 `continue` 到下一个 peer，尝试继续中继
- **发现 API 不可达**：返回上次缓存结果（stale-OK），保证瞬时故障不破坏中继
- **internal-token 为空**：中继完全关闭，退化为本地只读，任务不在本地时返回 `TASK_NOT_FOUND`
- **无 peer 持有 task**：浏览器收到 `error` SSE 事件 `{error: "TASK_NOT_FOUND", message: "task not found locally or on any peer: {id}"}`
