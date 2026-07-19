# SnapAgent Multi-Cluster Deployment Architecture

> Version: v1.0 | Updated: 2026-07-17

## 1. Deployment Scenarios

SnapAgent is embedded as a library inside the host Spring Boot application. The host app is typically deployed as multiple replicas (Deployment + replicas) in Kubernetes, fronted by a Service exposed via Ingress/LoadBalancer. SnapAgent's task state (`TaskStore`) is an **in-memory state local to each pod** — there is no shared store. Whichever pod starts a diagnostic task holds that task's complete transcript in its own memory.

This creates a key challenge: the user's browser connects to one pod via the load balancer, which starts a task and pushes an SSE stream to the browser. If the browser reconnects (network blip, page refresh), the load balancer may route the request to a **different pod**. That pod does not have the task in memory and cannot continue streaming directly. This is where **cross-pod SSE relay** kicks in: the pod that received the request probes its sibling pods to find the one holding the task, then relays its SSE stream line-by-line back to the browser.

```
┌──────────┐      ┌───────────────┐      ┌────────────────────────────┐
│ Browser  │─────▶│ Ingress / LB  │─────▶│ Pod B (does not own task)  │
│ (SSE)    │      │ (round-robin) │      │  - taskStore.get(id)=null  │
└──────────┘      └───────────────┘      │  - triggers PeerSseRelay   │
     ▲                                   └──────────────┬─────────────┘
     │                                                   │ 1. probe for owner
     │                                                   │ 2. relay the stream
     │                                                   ▼
     │                                   ┌────────────────────────────┐
     │  SSE events (relayed line-by-line)│ Pod A (owns task)          │
     └───────────────────────────────────│  - taskStore hit           │
                                         │  - InternalTaskController  │
                                         │    /tasks/{id}/stream     │
                                         └────────────────────────────┘
```

Design highlights:
- **No shared state**: any pod can serve any request; no sticky session required
- **On-demand relay**: cross-pod probing is only triggered when the task is not local; the common case streams directly from the local pod
- **Graceful degradation**: works without cross-pod routing configured — a reconnection simply receives a `TASK_NOT_FOUND` event

---

## 2. Cross-Pod Routing Architecture

The cross-pod routing subsystem consists of an SPI interface, a relay component, and an internal controller that cooperate to locate tasks and forward streams.

```
┌──────────────────────────────────────────────────────────────┐
│                    SnapAgentController                       │
│   GET /runs/{id}/stream                                      │
│   - taskStore.get(id) hit → stream SSE locally              │
│   - miss → peerSseRelay.tryRelay(emitter, id)               │
│   - relay failed → SSE error event TASK_NOT_FOUND          │
└──────────────────────┬───────────────────────────────────────┘
                       │ delegates to
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                    PeerSseRelay                              │
│   - discoverPeers() → probe each peer                        │
│   - first 200 → open SSE stream → pipeSse() line-by-line    │
│   - internal-token empty → relay disabled                    │
└──────────────────────┬───────────────────────────────────────┘
                       │ discovers peers via
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                    PeerRouter (SPI)                          │
│   - discoverPeers() → List<peerBaseURL>                     │
│   - mode() → "k8s-api" / "headless-dns" / "static" / "none"│
│   impls: K8sApiPeerRouter / HeadlessDnsPeerRouter /         │
│          StaticPeerRouter / NoopPeerRouter                  │
└──────────────────────────────────────────────────────────────┘
                       │ pod-to-pod communication
                       ▼
┌──────────────────────────────────────────────────────────────┐
│               InternalTaskController                         │
│   mounted outside basePath: /snap-agent-internal           │
│   only checks X-Skills-Agent-Internal-Token                  │
│   - GET /tasks/{id}/probe  → 200/404/401                     │
│   - GET /tasks/{id}/stream  → SSE stream (polls 200ms)      │
└──────────────────────────────────────────────────────────────┘
```

### Core Interfaces

**`PeerRouter`** (SPI):
```java
public interface PeerRouter {
    /** Returns base-URLs of all reachable sibling pods (excluding self) */
    List<String> discoverPeers();
    /** Discovery mode name: k8s-api / headless-dns / static / none */
    String mode();
}
```

The peer base-URL format is `http://{ip}:{port}` (the pod's server root, **not** including the basePath). `PeerSseRelay` appends the `internal-path` (default `/snap-agent-internal`) to reach the pod-to-pod endpoints.

**`PeerSseRelay`** (relay component):
```java
public class PeerSseRelay {
    public static final String INTERNAL_TOKEN_HEADER = "X-Skills-Agent-Internal-Token";

    // Blocking — must be called from a worker thread.
    // Returns true if a peer was found and the stream was relayed;
    // false if no peer owned the task or relay is disabled.
    public boolean tryRelay(SseEmitter emitter, String taskId) { ... }
}
```

Authentication uses a single shared secret (`X-Skills-Agent-Internal-Token` request header) configured via `snap-agent.routing.internal-token`. **If the token is empty, relay is automatically disabled** — even if a `PeerRouter` returns peers, no cross-pod routing occurs.

**`InternalTaskController`** (internal endpoints):

Mounted at `${snap-agent.routing.internal-path:/snap-agent-internal}`, **outside the user-facing basePath** (default `/snap-agent`). This ensures the host app's user-facing security filter chain does not gate pod-to-pod traffic. The only credential is the shared secret token, verified on every request. Hosts must permit this path (e.g. Spring Security `antMatchers("/snap-agent-internal/**").permitAll()`).

Two endpoints:
- `GET /tasks/{id}/probe` — returns 200 if this pod owns the task (body includes taskId/status/ownerPod), 404 if not, 401 if the token is invalid
- `GET /tasks/{id}/stream` — SSE stream with the same framing as the user-facing endpoint; no user auth/ownership check (the originating pod already did that); sends an `error` SSE event if the task is not found locally; polls the transcript every 200ms; sends a `done` event and completes on terminal status

---

## 3. Peer Discovery Strategies

`PeerRouter` has four implementations, selected via `snap-agent.routing.mode`. The degradation chain is `k8s-api` → `headless-dns` → `static` → `none`.

### 3.1 k8s-api (K8s Endpoints API)

The recommended default when a K8s ServiceAccount token and RBAC are available. `K8sApiPeerRouter` discovers sibling pods by querying the in-cluster Kubernetes Endpoints API:

- Reads SA token from: `/var/run/secrets/kubernetes.io/serviceaccount/token`
- Reads namespace from: `/var/run/secrets/kubernetes.io/serviceaccount/namespace`
- API host: `https://kubernetes.default.svc`
- Request path: `/api/v1/namespaces/{namespace}/endpoints/{serviceName}`
- Parses `.subsets[].addresses[].ip` and `.subsets[].ports[]` to build `http://{ip}:{port}`
- Port selection: prefers the port named `http`, otherwise takes the first port
- Self-exclusion: excludes its own IP via the `MY_POD_IP` env var (K8s downward API)

**When to use**: standard K8s cluster deployment with RBAC permission to let the SA token read endpoints.

**Limitations**: requires the ServiceAccount to have `get` on the `endpoints` resource; discovery fails if the token is unavailable.

```yaml
snap-agent:
  routing:
    mode: k8s-api
    k8s-service-name: my-app-svc   # K8s Service name
    port: 8080                     # optional, 0=auto-detect from server.port
    internal-token: ${INTERNAL_TOKEN}
```

### 3.2 headless-dns (Headless Service DNS)

`HeadlessDnsPeerRouter` discovers sibling pods by resolving a headless Service DNS name via the cluster DNS:

- A headless Service (`clusterIP: None`) makes the cluster DNS return one A record per ready pod
- A single `InetAddress.getAllByName(dnsName)` call enumerates all peers
- The DNS name is configured via `k8s-service-name`, as either a full FQDN or a short name (resolved via the pod's DNS search domains)
- Self-exclusion: via the `MY_POD_IP` env var

**When to use**: clusters where you cannot or do not want to configure RBAC; where a headless Service is already deployed.

**Limitations**: requires a headless Service (`clusterIP: None`); only returns ready pods; DNS caching may introduce lag.

```yaml
snap-agent:
  routing:
    mode: headless-dns
    k8s-service-name: my-app-headless   # headless Service name or FQDN
    port: 8080
    internal-token: ${INTERNAL_TOKEN}
```

### 3.3 static (static list)

`StaticPeerRouter` uses a statically configured list of peer base-URLs:

- Immutable list, passed in once at construction
- Does not auto-scale — the list must be updated on rollout

**When to use**: environments without service discovery (bare metal, non-K8s), or where pod addresses are known ahead of time.

**Limitations**: does not change with replica count; the list becomes stale during rolling deploys.

```yaml
snap-agent:
  routing:
    mode: static
    static-peers:
      - http://10.0.1.5:8080
      - http://10.0.1.6:8080
    internal-token: ${INTERNAL_TOKEN}
```

### 3.4 none (local-only, default)

`NoopPeerRouter` returns an empty peer list and `mode()` returns `"none"`. Relay never triggers; the stream endpoint returns a `TASK_NOT_FOUND` SSE error event directly when the task is not local.

**When to use**: single-instance deployment, dev/test environments, or scenarios where reconnection resilience is not needed.

```yaml
snap-agent:
  routing:
    mode: none   # default value, may be omitted
```

### Strategy Comparison

| Mode | Discovery | Prerequisites | Auto-scaling | Use case |
|------|-----------|---------------|-------------|----------|
| `k8s-api` | Endpoints API | SA token + RBAC | Yes (real-time) | Standard K8s cluster |
| `headless-dns` | DNS A records | Headless Service | Yes (DNS lag) | Cluster without RBAC |
| `static` | Configured list | Known pod addresses | No | Bare metal / non-K8s |
| `none` | None | None | — | Single instance / dev-test |

---

## 4. SSE Relay Flow

When a `GET /runs/{id}/stream` request lands on a pod that does not own the task (via the load balancer), the full cross-pod relay flow is:

1. The browser connects to some pod's `/snap-agent/runs/{id}/stream` via the LB
2. The pod checks `taskStore.get(id)` — if found, streams locally
3. Not found, and `peerSseRelay != null` — calls `peerSseRelay.tryRelay(emitter, id)` on a worker thread
4. Relay calls `peerRouter.discoverPeers()` to get the peer list (excluding self)
5. Probes each peer sequentially: `GET {peerBase}/snap-agent-internal/tasks/{id}/probe` with the `X-Skills-Agent-Internal-Token` header
6. First 200 → that peer owns the task; open the SSE stream `GET {peerBase}/snap-agent-internal/tasks/{id}/stream`
7. Read the peer's SSE byte stream line-by-line, re-assemble events (`event:`/`data:`/blank line dispatch), and forward them through the local `SseEmitter` to the browser
8. 404 → that peer does not own it, try the next; 401 → log a warning and try the next; unreachable → try the next
9. No peer owns the task → return `false`; the browser receives a `TASK_NOT_FOUND` SSE error event

```
Browser          Pod B (LB routed here)     Pod A (owns task)
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
  │   SSE events      │  (pipeSse line-by-line)    │
  │◀──────────────────│                           │
  │   done            │                           │
  │◀──────────────────│                           │
```

### Key Details

- **The probe is a GET request** (not HEAD); it returns a JSON body `{taskId, status, ownerPod:true}`
- **The stream endpoint performs no user auth**: the originating pod already did user auth and ownership checks; pod-to-pod traffic only verifies the shared token
- **Relay failure continues to next peer**: if a peer relay breaks mid-stream (peer crash or task migration), the relay `continue`s to the next peer
- **SSE framing**: `event: <name>`, `data: <payload>`, blank line dispatches; multi-line `data:` is concatenated with newlines; comment lines starting with `:` (heartbeats) are ignored
- **Client disconnect**: `pipeSse` stops forwarding when `emitter.send()` throws (client gone)
- **No peerSseRelay configured**: the stream endpoint sends an `error` SSE event `TASK_NOT_FOUND` directly

---

## 5. Caching & Degradation

### 5.1 Discovery Cache

Both `K8sApiPeerRouter` and `HeadlessDnsPeerRouter` implement a TTL cache to avoid triggering a discovery call on every stream request:

- **TTL**: `snap-agent.routing.discovery-cache-ttl-seconds` (default 10 seconds)
- Within the cache validity window, the cached peer list is returned directly
- After expiry, the discovery API is called again

```java
// Pseudocode (shared logic of K8sApiPeerRouter / HeadlessDnsPeerRouter)
if (cachedPeers != null && now < cacheExpiryMillis) {
    return cachedPeers;   // cache hit
}
try {
    List<String> fresh = fetchPeersFromApi();   // re-discover
    cachedPeers = fresh;
    cacheExpiryMillis = now + cacheTtlSeconds * 1000;
    return fresh;
} catch (Exception e) {
    if (cachedPeers != null) {
        return cachedPeers;   // stale-OK: return last good result
    }
    return emptyList;         // no cache available, return empty
}
```

### 5.2 Degradation Strategy (stale-OK)

On discovery failure (API unreachable, DNS resolution failure, etc.), the router **returns the last successfully cached result** (stale-OK) instead of an empty list or an exception. This ensures transient API errors do not break cross-pod relay — even if the Kubernetes API is temporarily unreachable, pods can keep probing using the last known peer list.

If no cache is available (first-call failure at startup), an empty list is returned and relay degrades to local-only behavior.

### 5.3 Timeout Configuration

| Component | Connect timeout | Read timeout | Notes |
|-----------|----------------|-------------|-------|
| `PeerSseRelay` httpClient | 2 seconds | 30 minutes | Short connect (fast failure); long read (SSE long-lived stream) |
| `K8sApiPeerRouter` httpClient | 3 seconds | 5 seconds | Endpoints API is a short request; short read timeout |

- **Probe**: uses `PeerSseRelay`'s httpClient with a 2-second connect timeout — an unreachable peer is skipped after 2 seconds
- **Stream relay**: same httpClient with a 30-minute read timeout to sustain long-lived SSE streams
- **Endpoints API call**: uses `K8sApiPeerRouter`'s httpClient with 3s connect + 5s read

### 5.4 Full Configuration

```yaml
snap-agent:
  routing:
    mode: none                      # k8s-api / headless-dns / static / none (default none)
    internal-token: ""              # pod-to-pod shared secret; empty = relay disabled
    k8s-service-name: ""            # K8s Service name (for k8s-api / headless-dns modes)
    port: 0                         # 0 = auto-detect from server.port
    static-peers: []                # static peer list (for static mode)
    discovery-cache-ttl-seconds: 10 # peer discovery cache TTL
    internal-path: /snap-agent-internal  # internal endpoint mount path (outside basePath)
    max-peers-to-probe: 20          # max peers to probe (limits blast radius)
```

---

## 6. K8s Deployment Example

Below is a complete K8s manifest using the `k8s-api` discovery mode, including Deployment, Service, RBAC, and the required env vars.

```yaml
# --- ServiceAccount ---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: snap-agent-sa
  namespace: production
---
# --- RBAC: allow reading endpoints ---
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
# --- Service (ClusterIP, read by k8s-api mode via endpoints API) ---
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
      serviceAccountName: snap-agent-sa     # mounts SA token to standard path
      containers:
        - name: app
          image: my-app:latest
          ports:
            - containerPort: 8080
              name: http
          env:
            # K8s downward API — own pod IP, used for self-exclusion
            - name: MY_POD_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
            # Internal shared secret (inject from Secret in production)
            - name: INTERNAL_TOKEN
              valueFrom:
                secretKeyRef:
                  name: snap-agent-secrets
                  key: internal-token
          envFrom:
            - configMapRef:
                name: my-app-config
          # SnapAgent routing config
          # Can also be injected via envFrom ConfigMap
          # SA token is auto-mounted to /var/run/secrets/kubernetes.io/serviceaccount/token
          # namespace is auto-mounted to /var/run/secrets/kubernetes.io/serviceaccount/namespace
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
# --- ConfigMap (SnapAgent config) ---
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

### Key Notes

| Resource | Purpose |
|----------|---------|
| `ServiceAccount` | Auto-mounts the SA token to `/var/run/secrets/kubernetes.io/serviceaccount/token` and the namespace to the sibling `namespace` file; `K8sApiPeerRouter` reads both paths |
| `Role` + `RoleBinding` | Grants the SA `get` on the `endpoints` resource — without this, `K8sApiPeerRouter` gets a 403 from the Endpoints API |
| `Service` (ClusterIP) | Named `my-app-svc` with a port named `http` — `K8sApiPeerRouter`'s port selection logic prefers the port named `http`, otherwise takes the first |
| `MY_POD_IP` env | From the K8s downward API `status.podIP`, used to exclude self during peer discovery |
| `INTERNAL_TOKEN` env | From a Secret, injected into `snap-agent.routing.internal-token`; **an empty value disables relay** |
| Port name `http` | `selectPort()` prefers the port named `http`, otherwise takes the first |

### Headless Service (for headless-dns mode)

If you use `headless-dns` mode, create an additional headless Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app-headless
  namespace: production
spec:
  clusterIP: None          # Headless — DNS returns one A record per pod
  selector:
    app: my-app
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

Corresponding config: `mode: headless-dns`, `k8s-service-name: my-app-headless`. This mode does not require RBAC.

---

## 7. Single-Instance Deployment

For non-K8s environments or single-replica deployments, the simplest configuration is the default `none` mode:

```yaml
snap-agent:
  routing:
    mode: none          # default value, may be omitted
    internal-token: ""  # empty, relay does not take effect
```

Under this mode:
- `NoopPeerRouter` returns an empty peer list
- All tasks live in the local `taskStore`
- `GET /runs/{id}/stream` returns a `TASK_NOT_FOUND` SSE error event directly when the task does not exist
- No cross-pod communication, no RBAC configuration, no internal token

**Use cases**:
- Development / test environments
- Small applications with a single replica
- Scenarios where reconnection resilience is not a concern

If you later scale from a single instance to multiple replicas, simply change `mode` to `k8s-api`/`headless-dns`/`static` and configure `internal-token` to enable cross-pod relay.

---

## 8. Known Limitations

| Limitation | Description | Plan |
|------------|-------------|------|
| No shared state | Task state lives in pod memory; if a pod dies, its in-memory tasks are lost | Future: Redis / shared state store for persistence |
| No persistence | Pod restart loses all running tasks | Same as above |
| Relay adds latency | Each SSE event hops through 2 pods (owner pod → relay pod → browser) | Acceptable; SSE is inherently streaming |
| K8s API needs RBAC | `k8s-api` discovery fails if the SA token cannot read endpoints | Degrade to `headless-dns` or `static` |
| No sticky session | No session affinity required — any pod can serve any request | By design, not a limitation |
| Sequential probing | Peers are probed one by one (not in parallel); probing is slower with many peers | Future: parallel probing |
| DNS cache lag | `headless-dns` mode relies on cluster DNS; rolling deploys may lag | Can reduce cache TTL |

### Fault-Tolerance Behavior

- **Peer relay breaks mid-stream**: `PeerSseRelay` catches the exception and `continue`s to the next peer, attempting to keep relaying
- **Discovery API unreachable**: returns the last cached result (stale-OK), ensuring transient failures do not break relay
- **internal-token empty**: relay is fully disabled, degrading to local-only; a non-local task returns `TASK_NOT_FOUND`
- **No peer owns the task**: the browser receives an `error` SSE event `{error: "TASK_NOT_FOUND", message: "task not found locally or on any peer: {id}"}`
