# SnapAgent Browser Network Bridge Architecture

> Version: v2.0 | Updated: 2026-07-29

## 1. Overview

The SnapAgent Browser Network Bridge is an optional network proxy mechanism that leverages the user's browser network environment to route SnapAgent server-side HTTP requests to external systems (Zentao, GitLab, etc.) through the browser. This solves the problem of containerized/K8s environments being unable to directly access internal network services.

### Core Design Principles

| Principle | Description |
|-----------|-------------|
| **Zero Intrusion** | When `bridge.enabled=false` (default), all code paths remain byte-for-byte identical |
| **Selective Activation** | Requires explicit `bridge.enabled=true` configuration |
| **Status-First Routing** | Extension installed + master ON + service ON → direct bridge routing, no direct connection attempt |
| **No Auto-Fallback** | On bridge request timeout or failure, errors are thrown directly — no silent fallback to direct connection |

---

## 2. Architecture Topology

```
┌─────────────────────────────────────────────────────────────────────┐
│  SnapAgent Server (Container/K8s)                                    │
│                                                                      │
│  IssueClosureService / FixExecutionService (unchanged)              │
│    └── IssueTracker / VcsClient (unchanged, transparent wrapping)    │
│          └── AbstractHttpIssueTracker                                │
│                └── HttpExecutor ← injectable abstraction             │
│                      ├── DirectHttpExecutor (default, original logic)│
│                      └── BridgeHttpExecutor (bridge mode)            │
│                                                                        │
│  BridgeController (new)                                              │
│    GET  /bridge/stream   → SSE channel                               │
│    POST /bridge/result   → Receive frontend results                  │
│    POST /bridge/status-update → Frontend reports extension status     │
│    GET  /bridge/status   → Bridge status query                        │
└──────────────────────────────┼──────────────────────────────────────┘
                               │ SSE push
┌──────────────────────────────┼──────────────────────────────────────┐
│  Browser                      │                                       │
│  SnapAgent Frontend (index.html)                                      │
│    bridge-client.js (conditional load)                               │
│      EventSource('/bridge/stream') → dispatch → POST /bridge/result   │
│                                                                        │
│  Chrome Extension (optional, standalone install)                    │
│    background.js: fetch() without CORS restrictions                   │
│    content.js: communicates with SnapAgent page + indicator          │
│    popup.html: master switch + service toggles                        │
│                                                                        │
│  External Systems (internal network)                                 │
│    Zentao / GitLab / Jira                                            │
└──────────────────────────────────────────────────────────────────────┘
```

### Layer Description

| Layer | Components | Responsibility |
|-------|-----------|----------------|
| Client | SnapAgent Web UI + Chrome Extension | SSE connection, request dispatch, CORS-free proxy |
| Gateway | BridgeController | REST endpoints, SSE channel management |
| Bridge Core | IssueBridgeService + BridgeHttpExecutor + DirectHttpExecutor | Status tracking, routing decisions, HTTP execution |
| Service Layer | IssueClosureService / FixExecutionService | Business logic unchanged, transparently uses bridge |
| External | Zentao / GitLab / LLM | Bridge mode via extension, direct mode via container network |

---

## 3. Chrome Extension Structure

```
snap-agent-bridge-extension/
├── manifest.json          # Manifest V3 configuration
├── background.js           # Service Worker — fetch proxy core
├── content.js              # Injected into SnapAgent pages — message relay + indicator
├── popup.html              # Control panel — master switch + service toggles
├── popup.js                # Config read/write (chrome.storage.local)
├── popup.css               # Popup styles
└── icon.png                # Extension icon
```

### manifest.json Key Configuration

- **Manifest V3**: Uses Service Worker instead of Background Page
- **permissions**: `storage` (config persistence), `activeTab`, `scripting`
- **host_permissions**: `<all_urls>` — allows proxying any target URL
- **content_scripts**: Matches all pages, injected at `document_idle`

### background.js — Proxy Core

The Service Worker listens for two message types:
1. `snapagent-fetch`: Executes HTTP proxy request, returns response
2. `snapagent-get-config`: Returns current extension configuration

Proxy flow: receive request → check master switch → check service switch → `fetch()` without CORS → return result

### content.js — Page Communication

- Injects proxy indicator DOM (🟢 connected / 🔴 disconnected)
- Bidirectional `postMessage` communication with SnapAgent page
- `SNAP_AGENT_BRIDGE_READY` signal notifies page that extension is installed
- `SNAP_AGENT_BRIDGE_CONFIG_CHANGED` config change notification
- Listens to `chrome.storage.onChanged` for real-time config sync

### popup.html — Control Panel

- **Master Switch**: Globally enable/disable bridge
- **Service Toggles**: Independent control per service type
  - `Issue Tracker` — Zentao/Jira/GitHub Issues proxy
  - `VCS` — GitLab/Bitbucket proxy
- **Status Display**: Current connection status, pending request count

### Default Configuration

```javascript
var DEFAULT_CONFIG = {
    masterEnabled: true,
    services: {
        'issue-tracker': true,
        'vcs': true
    }
};
```

---

## 4. Multi-Service Proxy Model

Each `BridgeHttpExecutor` instance is bound to a `serviceType` string identifying which service type it proxies:

| serviceType | Bound To | Proxy Target | Version |
|-------------|----------|-------------|---------|
| `issue-tracker` | `AbstractHttpIssueTracker` subclasses | Zentao/Jira/GitHub Issues | v1 |
| `vcs` | `AbstractHttpVcsClient` subclasses | GitLab/Bitbucket | v1 |
| `llm` | `AbstractStreamingLlmClient` subclasses | LLM gateway (streaming) | v2 (chunked relay) |
| `plugin-{name}` | Plugin custom `HttpExecutor` | User-uploaded plugins | v1 (optional for plugins) |

The Chrome Extension uses `serviceType` + its own configuration to decide whether to proxy a request.

### SSE proxy-request Event

```json
{
  "id": "req-a1b2c3d4",
  "serviceType": "issue-tracker",
  "url": "http://zentao.sfcloud.local/api.php/v1/products/1/bugs",
  "method": "POST",
  "headers": { "Authorization": "Token xxx" },
  "body": { "title": "..." }
}
```

---

## 5. Routing Strategy

```
                   ┌──────────────────────────────────┐
                   │ bridge.enabled = false (default) │
                   │ → DirectHttpExecutor only          │
                   │ → Existing behavior, zero change   │
                   └──────────────────────────────────┘

                   ┌──────────────────────────────────┐
                   │ bridge.enabled = true            │
                   │                                  │
  Extension active ┤ → BridgeHttpExecutor             │
  (installed +      │   → isBridgeActive() = true      │
  master ON +       │   → proxyRequest() via SSE      │
  service ON)       │   → Extension fetch() (no CORS) │
                   │   → Success or timeout error     │
                   │   → No auto-fallback to direct    │
                   │                                  │
  Extension inactive┤ → BridgeHttpExecutor             │
  (not installed /  │   → isBridgeActive() = false     │
  master OFF /       │   → DirectHttpExecutor (direct)  │
  service OFF)      │   → Same as bridge.enabled=false│
                   └──────────────────────────────────┘
```

### Non-Intrusiveness Verification

| Configuration | bridge.enabled | Effect |
|---------------|----------------|--------|
| No `bridge` section | (missing=false) | All use DirectHttpExecutor, behavior unchanged |
| `bridge.enabled: false` | false | All use DirectHttpExecutor, behavior unchanged |
| `bridge.enabled: true` + extension inactive | true | BridgeHttpExecutor internally uses direct, same behavior |
| `bridge.enabled: true` + extension active | true | BridgeHttpExecutor routes through SSE bridge |

---

## 6. Data Flow

### 6.1 Direct Mode (bridge.enabled=false)

```
User clicks "Create External Issue"
  → Frontend: POST /snap-agent/runs/{taskId}/issue
  → SnapAgentController.createIssue()
  → IssueClosureService.createExternalIssue()
  → ZentaoIssueTracker.createIssue()
  → AbstractHttpIssueTracker.jsonRequest()
  → DirectHttpExecutor.execute()           ← direct connection
  → HttpURLConnection → Zentao API
  → Returns {"id": 42}
```

### 6.2 Bridge Mode (extension active)

```
Prerequisite: Extension installed + master ON + issue-tracker proxy ON

User clicks "Create External Issue"
  → Frontend: POST /snap-agent/runs/{taskId}/issue
  → IssueClosureService.createExternalIssue()
  → ZentaoIssueTracker.createIssue()
  → AbstractHttpIssueTracker.jsonRequest()
  → BridgeHttpExecutor.execute()
      → bridgeService.isBridgeActive("issue-tracker") → true
      → bridgeService.proxyRequest()     ← direct bridge, no direct attempt
      → SSE push to connected frontend
           ↓
  → Frontend (bridge-client.js) receives SSE event
  → bridge-client.js.dispatch()
      → Chrome Extension sendMessage()
      → Extension background.js: fetch(zentaoUrl)  ← user's network
      → Zentao API returns {"id": 42}
      → Extension returns to content.js
      → content.js returns to bridge-client.js
  → Frontend: POST /snap-agent/bridge/result
  → IssueBridgeService.handleResult()
  → CompletableFuture completes
  → BridgeHttpExecutor returns HttpResponse
  → ZentaoIssueTracker returns "42"
  → IssueClosure status updated
```

### 6.3 Bridge Inactive (bridge.enabled=true, extension not active)

```
ZentaoIssueTracker.createIssue()
  → BridgeHttpExecutor.execute()
      → bridgeService.isBridgeActive("issue-tracker") → false
      → directExecutor.execute()        ← direct, same as bridge.enabled=false
```

---

## 7. REST API

### New Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/snap-agent/bridge/stream` | SSE channel, server pushes proxy requests |
| POST | `/snap-agent/bridge/result` | Frontend posts proxy results back |
| POST | `/snap-agent/bridge/status-update` | Frontend reports extension status |
| GET | `/snap-agent/bridge/status` | Bridge status query |

### Existing Endpoint Changes

`GET /snap-agent/info` response adds field:
```json
{
  "bridgeEnabled": false
}
```

---

## 8. Configuration Properties

```yaml
snap-agent:
  bridge:
    enabled: false                    # Default off, zero impact
    request-timeout-ms: 30000         # Single bridge request timeout
    sse-connection-timeout-ms: 0     # SSE keepalive (0=never timeout)
    allowed-host-patterns:            # Target URL whitelist (security)
      - "*.sfcloud.local"
      - "*.sf-express.com"
      - "localhost"
```

---

## 9. Security Design

### URL Whitelist

`IssueBridgeService.proxyRequest()` validates the target URL before sending SSE events. Only requests matching `allowed-host-patterns` are proxied.

### Authentication Passing

- SSE channel reuses SnapAgent's existing security framework (Basic Auth / Bearer Token / Cookie)
- Tokens are transmitted only within the authenticated SSE channel, not exposed in page DOM
- Chrome Extension's `background.js` executes `fetch()` in the Service Worker context — page JS cannot read it
- Extension also performs its own host whitelist check

### Request Timeout

- Bridge request: 30 seconds (configurable via `request-timeout-ms`)
- SSE connection: No timeout (persistent long connection, auto-cleanup on disconnect)
- When extension inactive, uses DirectHttpExecutor with default timeout

---

## 10. Installation & Usage

### Chrome Extension Installation

1. Download the `snap-agent-bridge-extension` directory or `.zip` file
2. Open Chrome and navigate to `chrome://extensions/`
3. Enable "Developer mode"
4. Click "Load unpacked" and select the extension directory
5. Verify the indicator turns green (🟢 connected) on the SnapAgent page

### Configuration Steps

1. Click the SnapAgent Bridge icon in the Chrome toolbar
2. Enable the Master Switch
3. Enable service proxies as needed (Issue Tracker / VCS)
4. Configure `snap-agent.bridge.enabled=true` in `application.yml`
5. Configure `allowed-host-patterns` whitelist

---

## 11. Deployment Scenarios

### Scenario A: Direct Connection Available (No Bridge)

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.internal.com
      token: xxx
      product-id: 1
```

**Behavior**: DirectHttpExecutor calls Zentao API directly. Zero extra steps.

### Scenario B: Container Network Isolation, Using Bridge

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: zentao
    zentao:
      base-url: http://zentao.sfcloud.local  # Internal address, container unreachable
      token: xxx
      product-id: 1
  bridge:
    enabled: true
    allowed-host-patterns:
      - "*.sfcloud.local"
```

**Behavior**: User installs Chrome Extension and enables master switch → requests are proxied through the browser to the internal Zentao.

### Scenario C: Bridge Enabled but Extension Inactive

```yaml
snap-agent:
  bridge:
    enabled: true  # Bridge enabled, but extension not installed
```

**Behavior**: BridgeHttpExecutor detects `isBridgeActive()=false` → uses DirectHttpExecutor directly. Same behavior as `bridge.enabled=false`.

---

## 12. Use Cases

### Case 1: Creating Zentao Bugs from K8s Containers

**Scenario**: SnapAgent deployed in K8s cluster, Zentao deployed on internal network, containers cannot directly access it.

**Flow**:
1. User completes diagnosis in SnapAgent UI
2. Clicks "Create External Issue"
3. SnapAgent server pushes request to frontend via SSE
4. Chrome Extension executes `fetch()` to Zentao API using the user's browser
5. Zentao creates Bug, returns ID
6. Result is posted back to the server, IssueClosure records external Issue ID and source

**Advantage**: No K8s network policy changes needed, no VPN required — the user's browser naturally has internal network access.

### Case 2: Creating Fix PRs on Internal GitLab

**Scenario**: After SnapAgent auto-diagnosis, needs to create fix branches and MRs via GitLab API.

**Flow**:
1. FixExecutionService calls GitLabVcsClient
2. BridgeHttpExecutor detects `isBridgeActive("vcs")=true`
3. Request goes through SSE → Chrome Extension → GitLab API
4. PR created successfully, result returned

### Case 3: Hybrid Mode — Some Services Direct, Some Bridged

**Scenario**: Zentao on internal network (needs bridge), but LLM gateway on public internet (direct access).

**Configuration**:
```yaml
snap-agent:
  bridge:
    enabled: true
    allowed-host-patterns:
      - "*.sfcloud.local"
```

**Extension config**:
- Issue Tracker: ON (Zentao via bridge)
- VCS: ON (GitLab via bridge)

**Behavior**: Zentao/GitLab requests go through bridge, LLM requests are unaffected (LlmClient uses OkHttp, to be integrated with bridge in v2).
