# Anchor Q&A Feature Integration Guide

> SnapAgent v0.4 — Enable page-section anchors for in-context LLM Q&A on host application pages

## Overview

The SnapAgent anchor Q&A feature lets you attach "anchor icons" to any region of your host application's pages. Users click an anchor icon → a right-side drawer slides out → they ask LLM questions about that section's content—without leaving the current page or losing context.

**Typical scenarios**:
- Documentation site: User reads "Order Status" section, wants to ask "How long does shipped-but-unsigned usually take?"
- Ops dashboard: User sees an abnormal metric, wants to ask "Why is this QPS dropping?"
- Business form: User filling a form, wants to ask "What format should this field use?"
- Admin list: User sees an anomalous SKU row, wants to ask "Why didn't this SKU generate a replenishment strategy?"

### Drawer UI Composition

When the drawer opens, it shows three visible regions:

1. **Header bar**: Left side shows the anchor name (`data-snap-anchor` value) + a content summary subtitle (first 80 chars, single-line). Right side has the close button. The drawer's right-side corners are rounded (`border-radius:0 16px 16px 0`).
2. **Skill info bar** (optional): If the anchor declares `data-snap-skill`, the current skill name + description is shown.
   - `data-snap-skill="auto"` → Shows "Smart Routing (Auto)" + "Picks the most appropriate skill based on anchor content and question"
   - Specific skill name → Fetches `GET /snap-agent/skills` to get the skill's displayName and description
3. **Conversation area**: Shows user messages, AI replies, error messages; input box + Send button at the bottom.

## Architecture

```
┌─ Host App Page ────────────────────────────────────────────┐
│  <main>                                                    │
│    <section data-snap-anchor="Order Status">  ┌──┐         │
│      ...page content...                       │💬│ ← anchor │
│    </section>                                  └──┘   icon  │
│  </main>                                                   │
│                                                            │
│  <script src="/snap-agent/anchor.js" defer></script>       │
└─────────────────────────────┬──────────────────────────────┘
                              │ click anchor
                              ▼
┌─ Right Drawer (Shadow DOM, rounded right corners) ───────┐
│  💬 Order Status                                    ✕    │
│  ## Order Status / Current status: Shipped / ... ← subtitle│
│  ──────────────────────────────────────────────────────── │
│  ⚡ Smart Routing (Auto)                                  │
│  Picks the most appropriate skill based on anchor...      │
│  ──────────────────────────────────────────────────────── │
│  User: Why hasn't this order arrived yet?                │
│  AI: Based on the logistics info...                      │
│  ──────────────────────────────────────────────────────── │
│  [Input box]                                      [Send] │
└──────────────────────────────────────────────────────────┘
```

## Integration Steps

### Step 1: Include the Script

Add the script tag to your host application's global page template (e.g., Thymeleaf layout, Vue/React root component):

```html
<script src="/snap-agent/anchor.js" defer></script>
```

On load, the script automatically:
1. Calls `GET /snap-agent/auth-config` to get the token strategy (shared with SnapAgent SPA)
2. Calls `GET /snap-agent/user-info` to verify permissions (silently disabled if unauthorized — no icons rendered)
3. Calls `GET /snap-agent/anchor/config` to get the blacklist path config
4. Scans the `main` region for anchor elements and injects anchor icons

### Step 2: Provide a SecurityGateway Bean (Critical)

`anchor.js` calls `GET /snap-agent/user-info` on startup to check authorization. If the host provides no `SecurityGateway` bean, the endpoint returns `authenticated: false, message: "security not configured"`, and `anchor.js` **silently renders no icons**.

**Two solutions:**

**Option A — Implement a custom SecurityGateway (recommended)**

```java
@Configuration
public class MySecurityGateway implements SecurityGateway {
    @Override
    public String currentUserId() {
        // Extract from Spring Security / Shiro / custom session
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof UserDetails
                ? ((UserDetails) principal).getUsername()
                : "anonymous";
    }

    @Override
    public boolean hasPermission(String code) {
        // Return true if the current user has SnapAgent access
        // Can integrate with RBAC, ACL, etc.
        return true;
    }
}
```

**Option B — Anonymous degraded mode (public docs site)**

```yaml
snap-agent:
  security:
    required-permission: ""   # empty string = allow anonymous access
```

Also provide a `SecurityGateway` returning a fixed user ID:

```java
@Configuration
public class AnonymousSecurityGateway implements SecurityGateway {
    @Override
    public String currentUserId() { return "anonymous"; }
    @Override
    public boolean hasPermission(String code) { return true; }
}
```

### Step 3: Annotate Anchor Regions (Recommended)

Add the `data-snap-anchor` attribute to page regions that should support Q&A:

```html
<section data-snap-anchor="Order Status">
  <h2>Order Status</h2>
  <p>Current status: Shipped</p>
  <p>Tracking number: SF1234567890</p>
  <!-- ... -->
</section>

<section data-snap-anchor="Logistics Trail" data-snap-skill="patrol">
  <h2>Logistics Trail</h2>
  <!-- ... -->
</section>

<section data-snap-anchor="SKU Overview" data-snap-skill="auto">
  <!-- data-snap-skill="auto" explicitly enables smart routing -->
</section>
```

Attribute reference:

| Attribute | Required | Description |
|-----------|----------|-------------|
| `data-snap-anchor` | Yes | Anchor name (shown to users in the drawer header) |
| `data-snap-skill` | No | SnapAgent skill name; `auto` (default) = smart routing; empty = smart routing; `off` = disable (show content only, no Q&A) |

### Step 4: Fallback Auto-Discovery (Optional)

If the page has no `data-snap-anchor` annotations, the script auto-scans semantic elements within the `main` region:
- `<section>`
- `<h2>` / `<h3>` with `id` attribute

Documentation-style pages work out of the box with zero annotation.

### Step 5: Configuration (Optional)

Adjust anchor behavior in `application.yml`:

```yaml
snap-agent:
  anchor:
    enabled: true                              # master switch (default true)
    disabled-paths:                            # blacklist paths (no scanning)
      - "/payment/**"
      - "/admin/security/**"
    max-context-chars: 8000                    # max chars sent to LLM per request
    preprocess-enabled: true                   # pre-summarize on click (reduces first-token latency)
    preprocess-timeout-ms: 5000                # abort preprocess if user doesn't submit within 5s
    summary-threshold-chars: 4000              # skip summarizer for content shorter than this
    summary-cache-ttl-seconds: 600             # summary cache TTL (Caffeine LRU, default 10 min)
    classifier-model: ""                       # classifier model override (empty = use default)
    classifier-confidence-threshold: 0.5       # below this, fall back to general LLM
```

See [§Configuration Reference](#configuration-reference) below for full details.

## How It Works

### User Interaction Flow

1. User visits a host page
2. `anchor.js` loads, completes auth check and config fetch
3. Script scans DOM, injects anchor icons at annotated regions
4. User clicks an anchor icon → right drawer slides out (Shadow DOM isolation)
5. **Drawer header**: shows anchor name + content summary subtitle (first 80 chars)
6. **Skill info bar**: if `data-snap-skill` is not `off`, shows skill name + description
7. Client immediately sends `POST /snap-agent/anchor/preprocess`, server's `AnchorOrchestrator` starts in parallel:
   - **LLM summarizer** (`AnchorContextSummarizer`): compresses page content to a ≤1500-char summary
   - **LLM classifier** (`AnchorSkillClassifier`): determines which SnapAgent skill to route to based on question + content snippet
8. User types a question in the drawer and submits
9. `anchor.js` calls `POST /snap-agent/runs` (with `skillId: "auto"` + `anchor` field + optional `preprocessId`)
10. Server's `AnchorOrchestrator.executeWithAnchor()`:
    - Picks up summary from preprocess result or cache (long content auto-summarized, short content used as-is)
    - Picks up `ClassifyResult` from preprocess (confidence ≥ threshold → route to skill; else fall back to general LLM direct answer)
    - Builds augmented user message: page URL + anchor name + truncation flag + section content (or summary) + user question
    - Calls `LlmClient.stream()` to stream output via SSE
11. SSE streams tokens back to the drawer, conversation completes

### Context Extraction

The client uses a built-in HTML→Markdown converter (no external dependencies — **does NOT require Turndown**) to convert the anchor region's DOM to Markdown:

| HTML | Markdown |
|------|----------|
| `<h1>` / `<h2>` / `<h3>` / `<h4>` | `#` / `##` / `###` / `####` |
| `<strong>` / `<b>` | `**text**` |
| `<em>` / `<i>` | `*text*` |
| `<code>` | `` `code` `` |
| `<pre>` | ` ```code``` ` |
| `<a href="...">` | `[text](url)` |
| `<ul>` / `<ol>` / `<li>` | `- item` |
| `<table>` | Markdown table (with `|---|` separator after header row) |
| `<br>` | `\n` |

Markdown saves 60-70% tokens compared to raw HTML, and LLMs understand Markdown better.

### Pre-summary and Cache

Server-side `AnchorContextSummarizer` triggers an LLM call when content exceeds `summary-threshold-chars` (default 4000):

- Short content (≤4000 chars): used as-is, no LLM call
- Long content: LLM compresses to ≤1500 chars, preserving key info (titles, data, status values, table structure)
- LLM failure: original content returned as fallback

`AnchorSummaryCache` uses Caffeine LRU + TTL cache:

- Key: SHA-256 hash of the content (avoids large strings as keys)
- TTL: `summary-cache-ttl-seconds` (default 600s / 10 min)
- Capacity: default 256 entries
- Cache hit on second click of the same content — no LLM call needed

### Smart Skill Routing

`AnchorSkillClassifier` uses an LLM to determine "question + content → skillId":

1. Builds a prompt listing all `AVAILABLE` skills (name + description)
2. LLM returns JSON: `{"skillId": "...", "confidence": 0.0..1.0, "reason": "..."}`
3. Confidence ≥ `classifier-confidence-threshold` (default 0.5) AND `skillId` non-empty → route to that skill
4. Confidence < threshold or no match → fall back to general LLM direct answer (still with page context, calls `LlmClient.stream()` directly)

Classifier model can be overridden: `classifier-model` (empty = use `snap-agent.llm.model`). Failures auto-degrade silently; the user always gets a response.

### Drawer UI Details

**Shadow DOM isolation**: The drawer uses `attachShadow({mode: 'open'})`, so its styles neither pollute the host page nor are affected by host page CSS.

**Header subtitle**: When the drawer opens, the first 80 chars of captured Markdown (single-line, newlines removed) are shown as the subtitle below the anchor name — so users can quickly confirm the context is correct.

**Skill info bar**:
- `data-snap-skill="auto"` or omitted: shows "Smart Routing (Auto)" + description
- `data-snap-skill="<skill-name>"`: calls `GET /snap-agent/skills` to fetch displayName + description
- `data-snap-skill="off"`: hides the skill info bar, shows content only, no Q&A

**Rounded corners**: The drawer container uses `border-radius:0 16px 16px 0;overflow:hidden` — left corners are square (flush with page right edge), right corners are 16px rounded, visually layered above the host page.

## Permission Model

The anchor feature fully inherits SnapAgent's existing permission checks:

- **No SecurityGateway bean provided** → `user-info` returns `authenticated: false` → `anchor.js` silently renders no icons
- **Authenticated but not authorized for SnapAgent** → `user-info` returns `authorized: false` → `anchor.js` silently renders no icons
- **Authenticated and authorized** → anchors enabled

Public documentation sites can use anonymous degraded mode (`snap-agent.security.required-permission: ""` + a custom `SecurityGateway` returning a fixed user ID), rate-limited by IP.

## SPA Compatibility

`anchor.js` is compatible with Vue / React / Angular single-page applications:
- `DOMContentLoaded` initial scan
- `MutationObserver` watches the `main` region subtree, debounced (800ms) incremental scan
- Exposes `window.__SNAP_AGENT_RESCAN__()` global function for host-driven immediate scan

```javascript
// Vue Router example
router.afterEach(() => {
  window.__SNAP_AGENT_RESCAN__ && window.__SNAP_AGENT_RESCAN__();
});
```

## Mobile

- Desktop: right-side drawer (400px wide)
- Mobile (<768px): bottom sheet (70vh height)
- Anchor icons always visible on mobile, 28×28 size for touch targets

## Error Handling

Identical to SnapAgent's main SPA:

| HTTP | Error code | Drawer behavior |
|------|------------|-----------------|
| 401 | `UNAUTHORIZED` | "Login required" |
| 403 | `FORBIDDEN` | "Permission denied" |
| 429 | `RATE_LIMITED` | "Rate limited. Please try again later." |
| Other !ok | - | "Request failed (status)" |
| Network error | - | "Network error: ..." |
| SSE `task_error` | - | "Error: ..." |
| SSE disconnected | - | Partial reply preserved + "[Connection lost. Resend to retry.]" |

## FAQ

### Q: Anchor icons don't appear?

Check in order:

1. **Check browser console for errors**
2. **Check the `user-info` endpoint**: visit `GET /snap-agent/user-info`
   - If it returns `authenticated: false` or `message: "security not configured"` → No `SecurityGateway` bean provided, see [Step 2](#step-2-provide-a-securitygateway-bean-critical)
   - If it returns `authorized: false` → User lacks `snap-agent:access` permission, or `required-permission` is misconfigured
3. **Check the `anchor/config` endpoint**: `GET /snap-agent/anchor/config` should return `{"enabled": true, "disabledPaths": [...]}`
4. **Check path blacklist**: confirm the current URL is not in `snap-agent.anchor.disabled-paths`
5. **Check DOM**: confirm the page has a `<main>` element, or has `data-snap-anchor` annotations

### Q: First answer is slow?

- Long content (>4000 chars) triggers the summarizer, adding one LLM call
- Pre-summary leverages "user thinking time" (anchor click → user types question); typical first-token latency is 2-3 seconds
- If persistently slow, check the LLM config:
  ```yaml
  snap-agent:
    llm:
      timeout-seconds: 120
      model: claude-sonnet-4-6
    anchor:
      summary-threshold-chars: 4000   # Lower this to send more content through the original-text path
  ```
- After a cache hit, the second Q&A on the same content responds in seconds

### Q: How to disable anchors on certain pages?

```yaml
snap-agent:
  anchor:
    disabled-paths:
      - "/payment/**"
      - "/admin/security/**"
```

Path matching rules:
- `/payment/**`: matches `/payment` and `/payment/anything`
- `/payment`: matches `/payment` and `/payment/anything` (prefix match)

### Q: How to force a specific skill for an anchor?

```html
<section data-snap-anchor="Ops Metrics" data-snap-skill="patrol">
  <!-- ... -->
</section>
```

If `data-snap-skill` is omitted or set to `auto`, the smart routing classifier determines it. Set to `off` to disable Q&A.

### Q: How to completely disable the anchor feature?

```yaml
snap-agent:
  anchor:
    enabled: false
```

When disabled, `anchor.js` does not scan the DOM or render icons, `AnchorOrchestrator` is not assembled (checked in `SnapAgentAutoConfiguration` via `isEnabled() && llmClient != null`), and all anchor-related endpoints return `503 ANCHOR_DISABLED`.

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/snap-agent/anchor.js` | `GET` | Static resource: anchor script (no auth required) |
| `/snap-agent/anchor/config` | `GET` | Public: returns `{enabled, disabledPaths}` for the client |
| `/snap-agent/anchor/preprocess` | `POST` | Auth required: pre-summarize + pre-classify on anchor click, returns `{preprocessId, status}` |
| `/snap-agent/runs` | `POST` | Extended: request body adds `anchor` field + `skillId: "auto"` triggers smart routing |

### POST /snap-agent/runs (Anchor Mode)

**Request body:**

```json
{
  "skillId": "auto",
  "inputs": {
    "message": "How many SKUs are in this category?"
  },
  "anchor": {
    "name": "SKU Overview",
    "content": "## SKU Overview\n\n| Category | SKU Count | Brands |\n|---|---|---|\n| Bakery | 3 | 1 |",
    "truncated": false,
    "originalLength": 0,
    "pageUrl": "/skus"
  },
  "preprocessId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response (202 Accepted):**

```json
{
  "taskId": "sa_1784556364221_60c600a7a559",
  "status": "PENDING",
  "streamUrl": "/snap-agent/runs/sa_1784556364221_60c600a7a559/stream"
}
```

**Audit log**: `action=RUN_ANCHOR_QA, details={skillId=auto, anchor=SKU Overview, taskId=...}`

The subsequent SSE stream is identical to a normal skill run — see [User Manual §10 REST API Reference](../manual/en/user-manual.md#10-rest-api-reference).

## Configuration Reference

| Config key | Default | Description |
|------------|---------|-------------|
| `snap-agent.anchor.enabled` | `true` | Master switch. When off, `AnchorOrchestrator` is not assembled, `anchor.js` does not scan the DOM |
| `snap-agent.anchor.disabled-paths` | `[]` | Blacklist paths (Ant-style); no scanning under these paths |
| `snap-agent.anchor.max-context-chars` | `8000` | Max chars sent to LLM per request. Longer content is truncated, `AnchorContext.truncated=true` |
| `snap-agent.anchor.preprocess-enabled` | `true` | Enable pre-summarize + pre-classify. When off, all computation happens synchronously in `POST /runs` |
| `snap-agent.anchor.preprocess-timeout-ms` | `5000` | Preprocess timeout (ms). If the user doesn't submit within 5s, preprocessing is cancelled |
| `snap-agent.anchor.summary-threshold-chars` | `4000` | Short content skips the summarizer (uses original text). Lower this to send more content through the original-text path, reducing LLM calls |
| `snap-agent.anchor.summary-cache-ttl-seconds` | `600` | Summary cache TTL (Caffeine LRU, 10 min). Second click on the same content hits the cache |
| `snap-agent.anchor.classifier-model` | `""` | Classifier model override. Empty = use `snap-agent.llm.model`. Use a cheap small model to reduce cost |
| `snap-agent.anchor.classifier-confidence-threshold` | `0.5` | Below this, fall back to general LLM direct answer. Higher = more conservative (more direct answers), lower = more aggressive (more skill routing) |

Full YAML example:

```yaml
snap-agent:
  anchor:
    enabled: true
    disabled-paths:
      - "/payment/**"
      - "/admin/security/**"
    max-context-chars: 8000
    preprocess-enabled: true
    preprocess-timeout-ms: 5000
    summary-threshold-chars: 4000
    summary-cache-ttl-seconds: 600
    classifier-model: ""              # use a cheap model like gpt-3.5 to reduce classification cost
    classifier-confidence-threshold: 0.5
```

## Auto-configuration

`SnapAgentAutoConfiguration` auto-wires `AnchorOrchestrator` and injects it into `SnapAgentController` when **both** conditions are met:

1. `snap-agent.anchor.enabled=true` (default true)
2. `LlmClient` is assembled (i.e., `snap-agent.llm.base-url` and credentials are configured)

Wiring chain:

```
LlmClient + SnapAgentProperties.Anchor
        │
        ▼
AnchorSummaryCache (Caffeine LRU + TTL)
        │
        ▼
AnchorContextSummarizer ──┐
                          ├──> AnchorOrchestrator ──> SnapAgentController.setAnchorOrchestrator()
AnchorSkillClassifier ────┘
```

Log line to confirm: `AnchorOrchestrator wired (anchor feature enabled)`

## Further Reading

- [Design Spec](../../../superpowers/specs/2026-07-20-host-page-anchor-qa-design.md)
- [Host Integration Guide](./host-integration-guide.md)
- [User Manual — Anchor Q&A](../manual/en/user-manual.md)
- [System Architecture](../architecture/en/system-architecture.md)
