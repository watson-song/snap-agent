# Spec: SnapAgent Browser Network Bridge

> **Spec ID**: BRIDGE-001 | **Date**: 2026-07-29 | **Architecture Ref**: `docs/bridge-architecture-design.md` (v2.0)

## Context

SnapAgent deployed in K8s/containers often cannot reach internal services (Zentao, GitLab, Jira) because of network isolation. Users' browsers have full internal network access. This spec defines a browser-based network bridge that lets the SnapAgent server delegate HTTP calls through the user's browser when the browser extension is installed and activated.

**Routing model**: When the extension is installed AND the master switch is ON AND the per-service proxy is ON, the server routes HTTP calls directly through the bridge without trying direct connection first. When the extension is not installed or not activated, the server uses direct connection as before.

**Constraint**: When `snap-agent.bridge.enabled=false` (default), zero code changes, zero config, zero behavior difference for existing deployments.

## Current State

- `AbstractHttpIssueTracker.jsonRequest()` uses `java.net.HttpURLConnection` directly, hardcoding URL/headers/body construction inside each tracker implementation.
- `AbstractHttpVcsClient.jsonRequest()` has the same pattern for VCS clients.
- `IssueClosureService` injects `IssueTracker` and calls `createIssue()`, `updateStatus()`, `addComment()` directly.
- `ZentaoIssueTracker`, `GitHubIssueTracker`, `JiraIssueTracker` all extend `AbstractHttpIssueTracker` and call `jsonRequest()` for HTTP.
- `GitLabVcsClient`, `BitbucketVcsClient` extend `AbstractHttpVcsClient` with the same `jsonRequest()` pattern.
- `AbstractStreamingLlmClient` uses OkHttp SSE streaming — deferred to v2 (chunked relay).
- No SSE endpoints exist. No bridge infrastructure exists.
- Frontend `index.html` has no dynamic script loading mechanism.

## Proposed Change

### Phase 1: HttpExecutor Abstraction

Extract HTTP execution from `AbstractHttpIssueTracker.jsonRequest()` and `AbstractHttpVcsClient.jsonRequest()` into an injectable `HttpExecutor` interface.

**New files**:
- `snap-agent-core/.../issue/HttpExecutor.java` — interface with `execute(url, method, headers, body) → HttpResponse`
- `snap-agent-core/.../issue/HttpResponse.java` — DTO: statusCode, body (String), jsonBody (JsonNode)

**Modified files** (3):
- `AbstractHttpIssueTracker.java` — add `protected HttpExecutor httpExecutor = new DirectHttpExecutor()` field; `jsonRequest()` delegates HTTP call to `httpExecutor.execute()`, keeps error handling + JSON parsing
- `AbstractHttpVcsClient.java` — same refactor: add `protected HttpExecutor httpExecutor` field; `jsonRequest()` delegates to `httpExecutor.execute()`
- `SnapAgentController.java` — `getInfo()` response adds `bridgeEnabled` boolean field

**New implementation**:
- `snap-agent-spring-boot-2x-starter/.../issue/DirectHttpExecutor.java` — encapsulates existing `jsonRequest()` HTTP logic verbatim

**Non-invasive proof**: When `bridge.enabled=false`, `httpExecutor` field defaults to `DirectHttpExecutor` which contains the exact same `HttpURLConnection` logic. The `jsonRequest()` method's external behavior is byte-identical.

### Phase 2: Bridge Core

**New files**:
- `BridgeHttpExecutor.java` — implements `HttpExecutor`; checks `IssueBridgeService.isBridgeActive(serviceType)` first: if true → routes directly through SSE bridge (no direct attempt); if false → delegates to `DirectHttpExecutor`. Each instance tagged with a `serviceType` string (`"issue-tracker"`, `"vcs"`, etc.)
- `IssueBridgeService.java` — manages SSE emitters (`CopyOnWriteArrayList`), pending requests (`ConcurrentHashMap<String, CompletableFuture<HttpResponse>>`), `volatile BridgeClientStatus clientStatus` for extension state tracking; `isBridgeActive(serviceType)` returns true only when extension is installed + master enabled + service toggled on; `proxyRequest()` pushes SSE event and blocks on `CompletableFuture.get(timeout)`; `handleResult()` completes the future; `updateClientStatus()` updates the volatile status from frontend reports
- `BridgeClientStatus.java` — DTO: `installed` (boolean), `masterEnabled` (boolean), `services` (Map<String, Boolean>)
- `BridgeHttpExecutorPostProcessor.java` — `BeanPostProcessor`; when `bridge.enabled=true`, sets `AbstractHttpIssueTracker.httpExecutor` to `BridgeHttpExecutor` (tagged `"issue-tracker"`) and `AbstractHttpVcsClient.httpExecutor` to `BridgeHttpExecutor` (tagged `"vcs"`); skips `NoopIssueTracker`
- `BridgeAutoConfiguration.java` — `@ConditionalOnProperty(name="snap-agent.bridge.enabled", havingValue="true")`; creates `IssueBridgeService`, two `BridgeHttpExecutor` beans (one per service type), `BridgeHttpExecutorPostProcessor` beans
- `BridgeProperties.java` — `@ConfigurationProperties(prefix="snap-agent.bridge")`; fields: `enabled`, `requestTimeoutMs` (30000), `allowedHostPatterns` (List<String>)
- `BridgeRequest.java` — DTO: id, serviceType, url, method, headers (Map), body (Object)
- `BridgeResponse.java` — DTO: id, status (int), body (String), headers (Map), error (String)
- `BridgeStatus.java` — DTO: connected (boolean), emitterCount (int), pendingCount (int), installed (boolean), masterEnabled (boolean), services (Map)

### Phase 3: REST Endpoints

**New file**:
- `BridgeController.java` — `@RestController` at `${snap-agent.base-path}/bridge`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/bridge/stream` | SSE channel; returns `SseEmitter`; pushes `proxy-request` events |
| POST | `/bridge/result` | Receives `BridgeResponse` body; calls `issueBridgeService.handleResult()` |
| GET | `/bridge/status` | Returns `BridgeStatus` JSON (including extension status) |
| POST | `/bridge/status-update` | Receives `BridgeClientStatus`; calls `issueBridgeService.updateClientStatus()` |

All endpoints reuse SnapAgent `SecurityGateway` auth (same as existing endpoints).

### Phase 4: Frontend

**New file**: `snap-agent-demo/src/main/resources/static/bridge-client.js`

- `SnapAgentBridge` class: `connect()` opens `EventSource('/bridge/stream')`, listens for `proxy-request` events
- Listens for `SNAP_AGENT_BRIDGE_READY` message from extension's content script
- On extension ready or config change, POSTs status to `/bridge/status-update` (installed, masterEnabled, services map)
- `handleProxyRequest(req)`: dispatches to Chrome Extension (if extension ready) or direct `fetch()` (CORS-permitting), then POSTs result to `/bridge/result`
- Auto-init when `window.snapAgentBridgeEnabled === true`
- Indicator light: green when extension connected, gray when disconnected

**Modified file**: `SnapAgentController.java` `getInfo()` — adds `bridgeEnabled` to info response. Frontend `index.html` checks this, dynamically loads `bridge-client.js`, shows extension download banner when extension not detected.

### Phase 5: Chrome Extension

**New directory**: `snap-agent-bridge-extension/`

- `manifest.json` — MV3, `host_permissions: ["<all_urls>"]`, content script matches SnapAgent pages
- `background.js` — service worker; listens for `PROXY_REQUEST` messages; checks master switch + service toggle + host whitelist; `fetch()` without CORS, returns response
- `content.js` — injected into SnapAgent pages; posts `SNAP_AGENT_BRIDGE_READY`; on config change, posts `SNAP_AGENT_BRIDGE_CONFIG_CHANGED` with config payload; relays messages between page and background; injects indicator DOM
- `popup.html` / `popup.js` — master toggle, per-service toggles (issue-tracker, vcs, llm), domain whitelist editor, connection status display
- `bridge-indicator.css` — indicator LED styles

## Acceptance Criteria

1. When `snap-agent.bridge.enabled` is absent or `false`, `AbstractHttpIssueTracker.jsonRequest()` and `AbstractHttpVcsClient.jsonRequest()` behavior is identical to pre-change (verified by existing tests passing unmodified)
2. When `bridge.enabled=true` and extension is NOT active (not installed / master OFF / service OFF), `BridgeHttpExecutor` delegates to `DirectHttpExecutor` — no SSE event is sent (verified by `BridgeHttpExecutorTest`)
3. When `bridge.enabled=true` and `isBridgeActive("issue-tracker")` returns `true`, `BridgeHttpExecutor` routes directly through `IssueBridgeService.proxyRequest()` — no direct connection attempt is made (verified by `BridgeHttpExecutorTest`)
4. When frontend receives `proxy-request` and Chrome Extension is installed, `fetch()` is executed in extension background context without CORS preflight (verified by E2E test with Zentao mock)
5. `POST /bridge/result` with matching `id` completes the pending `CompletableFuture` within 100ms (verified by `IssueBridgeServiceTest`)
6. `GET /bridge/status` returns `{"connected": true, "emitterCount": 1, "pendingCount": 0, "installed": true, "masterEnabled": true, "services": {"issue-tracker": true, "vcs": false}}` when one SSE client is connected and extension is active (verified by `BridgeControllerTest`)
7. `POST /bridge/status-update` with `{"installed": true, "masterEnabled": true, "services": {"issue-tracker": true}}` causes subsequent `isBridgeActive("issue-tracker")` to return `true` (verified by `IssueBridgeServiceTest`)
8. `GET /snap-agent/info` response includes `"bridgeEnabled": false` when bridge disabled, `"bridgeEnabled": true` when enabled (verified by `SnapAgentControllerTest`)
9. URL whitelist rejects `proxyRequest("http://evil.com/api")` when `allowedHostPatterns` does not include `evil.com` (verified by `IssueBridgeServiceTest`)
10. Bridge request times out after `requestTimeoutMs` (30s default) if no frontend responds (verified by `IssueBridgeServiceTest`)
11. Chrome Extension `background.js` `fetch()` sends with correct method, headers, and body from `proxy-request` SSE event (verified by manual E2E)
12. `BridgeHttpExecutorPostProcessor` does not wrap `NoopIssueTracker` instances (verified by `BridgeHttpExecutorPostProcessorTest`)
13. `BridgeHttpExecutorPostProcessor` wraps both `AbstractHttpIssueTracker` and `AbstractHttpVcsClient` instances with their respective `serviceType`-tagged `BridgeHttpExecutor` (verified by `BridgeHttpExecutorPostProcessorTest`)
14. When extension config changes (master toggle or service toggle), `content.js` posts `SNAP_AGENT_BRIDGE_CONFIG_CHANGED` to page, `bridge-client.js` POSTs to `/bridge/status-update`, and subsequent `isBridgeActive()` calls reflect the new state within 1s (verified by integration test)

## Testing Plan

| Layer | What | Count |
|-------|------|-------|
| Unit | `DirectHttpExecutor` — verify output matches old `jsonRequest` for 200/4xx/5xx | +3 |
| Unit | `BridgeHttpExecutor` — extension active (bridge), extension inactive (direct), bridge timeout (error, no fallback) | +3 |
| Unit | `IssueBridgeService` — SSE push, result callback, timeout, no-emitter error, URL whitelist, status update, isBridgeActive | +7 |
| Unit | `BridgeHttpExecutorPostProcessor` — wraps AbstractHttpIssueTracker, wraps AbstractHttpVcsClient, skips NoopIssueTracker | +3 |
| Unit | `BridgeController` — SSE stream, result POST, status GET, status-update POST | +4 |
| Integration | Bridge end-to-end: status-update → extension active → proxyRequest → SSE → result → issue created | +1 |
| Integration | Bridge direct fallback: extension inactive → direct executor used → behavior matches bridge.enabled=false | +1 |
| E2E | Zentao mock Docker + Chrome Extension + SnapAgent demo → full issue closure via bridge | +1 |

## Rollback Plan

Set `snap-agent.bridge.enabled=false` (or remove the `bridge` config section). All bridge beans stop being created, `DirectHttpExecutor` is used, and behavior reverts to pre-change. No database migrations, no schema changes, no irreversible state.

## Effort Estimate

| Component | Effort |
|-----------|--------|
| `HttpExecutor` + `DirectHttpExecutor` + `HttpResponse` | 2h |
| `AbstractHttpIssueTracker` + `AbstractHttpVcsClient` refactor | 1.5h |
| `BridgeHttpExecutor` + `BridgeHttpExecutorPostProcessor` (multi-service) | 2.5h |
| `IssueBridgeService` + DTOs (including `BridgeClientStatus`) | 3h |
| `BridgeController` (including `/status-update`) | 1.5h |
| `BridgeAutoConfiguration` + `BridgeProperties` | 1h |
| `SnapAgentController` `getInfo()` change | 0.5h |
| `bridge-client.js` (status reporting + indicator) | 2.5h |
| Chrome Extension (manifest + background + content + popup + indicator) | 3.5h |
| Unit tests (20) | 5h |
| Integration tests (2) | 3h |
| E2E test | 2h |
| **Total** | **28h** |

## Files Reference

| File | Change |
|------|--------|
| `snap-agent-core/.../issue/HttpExecutor.java` | New — HTTP executor interface |
| `snap-agent-core/.../issue/HttpResponse.java` | New — HTTP response DTO |
| `snap-agent-spring-boot-2x-starter/.../issue/DirectHttpExecutor.java` | New — extracted from `AbstractHttpIssueTracker.jsonRequest()` |
| `snap-agent-spring-boot-2x-starter/.../issue/AbstractHttpIssueTracker.java` | Modified — delegate to `httpExecutor` field |
| `snap-agent-spring-boot-2x-starter/.../vcs/AbstractHttpVcsClient.java` | Modified — delegate to `httpExecutor` field |
| `snap-agent-spring-boot-2x-starter/.../issue/BridgeHttpExecutor.java` | New — status-first routing: bridge active → SSE, inactive → direct |
| `snap-agent-spring-boot-2x-starter/.../issue/IssueBridgeService.java` | New — SSE push + result management + status tracking |
| `snap-agent-spring-boot-2x-starter/.../issue/BridgeClientStatus.java` | New — DTO for extension status (installed, masterEnabled, services) |
| `snap-agent-spring-boot-2x-starter/.../issue/BridgeHttpExecutorPostProcessor.java` | New — BeanPostProcessor (multi-service) |
| `snap-agent-spring-boot-2x-starter/.../issue/BridgeRequest.java` | New — DTO (includes serviceType) |
| `snap-agent-spring-boot-2x-starter/.../issue/BridgeResponse.java` | New — DTO |
| `snap-agent-spring-boot-2x-starter/.../issue/BridgeStatus.java` | New — DTO (includes extension status) |
| `snap-agent-spring-boot-2x-starter/.../autoconfig/BridgeAutoConfiguration.java` | New — conditional bean wiring |
| `snap-agent-spring-boot-2x-starter/.../autoconfig/SnapAgentProperties.java` | Modified — add `Bridge` inner class |
| `snap-agent-spring-boot-2x-starter/.../web/SnapAgentController.java` | Modified — `getInfo()` adds `bridgeEnabled` |
| `snap-agent-spring-boot-2x-starter/.../web/BridgeController.java` | New — REST endpoints (stream, result, status, status-update) |
| `snap-agent-demo/src/main/resources/static/bridge-client.js` | New — frontend bridge client + status reporting + indicator |
| `snap-agent-bridge-extension/manifest.json` | New — Chrome Extension manifest |
| `snap-agent-bridge-extension/background.js` | New — Extension service worker (with serviceType routing) |
| `snap-agent-bridge-extension/content.js` | New — Extension content script (config change notification) |
| `snap-agent-bridge-extension/bridge-indicator.css` | New — indicator LED styles |
| `snap-agent-bridge-extension/popup.html` | New — Extension popup (master + service toggles) |
| `snap-agent-bridge-extension/popup.js` | New — Extension popup logic |

## Out of Scope

- Extension auto-update mechanism (Chrome Web Store publishing)
- Token independent storage in `chrome.storage` (v2 — current design sends token via authenticated SSE)
- Bridge for LLM streaming clients (`AbstractStreamingLlmClient` uses OkHttp SSE) — deferred to v2 (chunked relay)
- Bridge for non-HTTP issue trackers (e.g., database-backed trackers)
- Multiple concurrent browser bridge clients (first responder wins; no load balancing)
- WebSocket transport (SSE is sufficient for the request-response pattern)

## Dependency Graph

```
#1 HttpExecutor + DirectHttpExecutor ──┬──> #2 BridgeHttpExecutor + PostProcessor
                                       │
                                       └──> #3 AbstractHttpIssueTracker + AbstractHttpVcsClient refactor
                                                │
                          #2 ──────────────────┤
                                                └──> #4 IssueBridgeService + DTOs (incl. BridgeClientStatus)
                                                         │
                                                         ├──> #5 BridgeController
                                                         │
                                                         └──> #6 BridgeAutoConfiguration + Properties
                                                                  │
                                                                  └──> #7 SnapAgentController getInfo() change
                                                                           │
                                                                           ├──> #8 bridge-client.js
                                                                           │
                                                                           └──> #9 Chrome Extension

#10 Tests (unit + integration + E2E) — depends on all above
```

## Sequencing Rationale

1. **#1 + #3 first** (HttpExecutor abstraction + AbstractHttpIssueTracker/AbstractHttpVcsClient refactor) because everything depends on the abstraction existing. Also the highest-risk change (modifying existing working code), so we verify it early.
2. **#2 + #4** (BridgeHttpExecutor + IssueBridgeService) are the core bridge logic, built on the abstraction. IssueBridgeService now includes status tracking (BridgeClientStatus) which BridgeHttpExecutor queries before deciding routing.
3. **#5 + #6** (Controller + AutoConfiguration) wire the bridge into Spring. Controller adds `/status-update` endpoint for frontend status reporting.
4. **#7** (SnapAgentController change) is minimal but must come after #6.
5. **#8 + #9** (Frontend + Extension) are independent and can be built in parallel after #7. Frontend must implement status reporting; Extension must implement config change notification.
6. **#10** (Tests) run throughout but the E2E test requires all components.
