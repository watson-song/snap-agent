# API Reference

All REST endpoints exposed by SnapAgent. Base path defaults to `/snap-agent`.

## Skills

### List Skills

```
GET /skills
```

Returns all registered skills with their metadata.

```json
{
  "skills": [
    {
      "name": "log-analysis",
      "description": "Analyzes application logs",
      "inputs": [
        { "key": "environment", "label": "Environment", "required": true }
      ],
      "shortcuts": [
        { "label": "🔴 Recent Errors", "message": "Show recent ERROR logs" }
      ]
    }
  ]
}
```

### Refresh Skills

```
POST /skills/refresh
```

Re-scans skill directories and reloads changed skills. Returns the number of skills loaded.

### Delete Skill

```
DELETE /skills/{name}
```

Deletes an uploaded skill. Built-in skills (from classpath) cannot be deleted.

### Upload Skill

```
POST /skills/upload
Content-Type: multipart/form-data

file: <SKILL.md>
```

Uploads a single skill Markdown file.

### Upload Skill Folder

```
POST /skills/upload-folder
Content-Type: multipart/form-data

files: <multiple SKILL.md files>
```

Uploads multiple skill files at once.

## Runs

### Create Run

```
POST /runs
Content-Type: application/json

{
  "skillId": "log-analysis",
  "inputs": {
    "environment": "production"
  },
  "conversationId": "conv-abc123",
  "message": "Check for errors in the last hour"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `skillId` | Yes | Skill to execute |
| `inputs` | No | Skill input values |
| `conversationId` | No | Continue an existing conversation |
| `message` | No | User message (for chat-mode skills) |

Response:

```json
{
  "id": "run-xyz789",
  "status": "RUNNING",
  "conversationId": "conv-abc123"
}
```

### List Runs

```
GET /runs?skillId=log-analysis&limit=20&offset=0
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `skillId` | string | — | Filter by skill |
| `limit` | int | `20` | Max results |
| `offset` | int | `0` | Pagination offset |

### Get Run

```
GET /runs/{id}
```

Returns run details including status, token usage, cost, and duration.

### Get Run Transcript

```
GET /runs/{id}/transcript
```

Returns the full conversation transcript (all messages including tool calls).

### Get Run Report

```
GET /runs/{id}/report
```

Returns the agent's final report/output for this run.

### Stream Run (SSE)

```
GET /runs/{id}/stream
Accept: text/event-stream
```

Server-Sent Events stream for real-time output. Events:

| Event | Data |
|-------|------|
| `token` | Individual token from LLM |
| `tool_call` | Tool invocation details |
| `tool_result` | Tool execution result |
| `complete` | Run finished |
| `error` | Error occurred |

### Cancel Run

```
POST /runs/{id}/cancel
```

Cancels a running execution. The agent stops at the next iteration boundary.

### Bugfix Suggestion

```
POST /runs/{id}/bugfix-suggestion
```

Generate a fix suggestion for an issue found during the run.

### Auto-Fix

```
POST /runs/{taskId}/auto-fix
```

Attempt automatic fix based on the run's analysis.

### Create Solution

```
POST /runs/{taskId}/solution
```

Create a solution record from the run's findings.

### Create Issue

```
POST /runs/{taskId}/issue
```

Create an issue in the configured tracker (GitHub, Jira, etc.) from the run's findings.

## Conversations

### Create Conversation

```
POST /conversations
Content-Type: application/json

{
  "skillId": "log-analysis",
  "title": "Production error investigation"
}
```

### List Conversations

```
GET /conversations?skillId=log-analysis&limit=20
```

### Get Conversation

```
GET /conversations/{id}
```

Returns conversation with full message history.

### Download Conversation

```
GET /conversations/{id}/download
```

Downloads conversation as a file (for export/sharing).

### Delete Conversation

```
DELETE /conversations/{id}
```

## Patrol

### Create Patrol Task

```
POST /patrol/tasks
Content-Type: application/json

{
  "name": "hourly-health-check",
  "skill": "ops-health-check",
  "cron": "0 * * * *",
  "inputs": {},
  "enabled": true
}
```

### List Patrol Tasks

```
GET /patrol/tasks
```

### Delete Patrol Task

```
DELETE /patrol/tasks/{id}
```

### Toggle Patrol Task

```
PATCH /patrol/tasks/{id}/toggle
Content-Type: application/json

{ "enabled": false }
```

### Infer Patrol from Skill

```
POST /patrol/infer
Content-Type: application/json

{ "skillId": "ops-health-check" }
```

Uses the LLM to suggest patrol configuration based on the skill's capabilities.

### List Patrol Reports

```
GET /patrol/reports?limit=20
```

### Get Patrol Report

```
GET /patrol/reports/{id}
```

## Alerts

### List Alerts

```
GET /alerts?resolved=false&limit=50
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `resolved` | boolean | — | Filter by resolution status |
| `limit` | int | `50` | Max results |

### Resolve Alert

```
POST /alerts/{id}/resolve
Content-Type: application/json

{ "note": "Fixed in deployment v1.2.3" }
```

## Issues

### List Recent Runs with Issues

```
GET /issues/recent-runs
```

### List Issues

```
GET /issues?status=open&limit=20
```

## Utility

### Get Auth Config

```
GET /auth-config
```

Returns authentication configuration for the UI.

### Get Anchor Config

```
GET /anchor/config
```

Returns anchor injection configuration.

### Anchor Preprocess

```
POST /anchor/preprocess
Content-Type: application/json
```

Preprocess text through the anchor injection system.

### Anchor Inject

```
POST /anchor/inject
Content-Type: application/json
```

Inject anchors into text.

### Get User Info

```
GET /user-info
```

Returns current authenticated user information.

### List Tools

```
GET /tools
```

Returns all registered tools with their definitions.

### List Models

```
GET /models
```

Returns available LLM models.

### Audit Log

```
GET /audit?limit=50
```

Returns security audit entries.

## Error Responses

All endpoints return errors in this format:

```json
{
  "error": "SKILL_NOT_FOUND",
  "message": "Skill 'foo' not found",
  "status": 404
}
```

| Status | Error | Description |
|--------|-------|-------------|
| 400 | `INVALID_REQUEST` | Missing required fields |
| 401 | `UNAUTHORIZED` | No authentication |
| 403 | `ACCESS_DENIED` | Missing permission |
| 404 | `NOT_FOUND` | Resource not found |
| 409 | `RUN_ALREADY_ACTIVE` | Run already in progress |
| 429 | `RATE_LIMITED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Server error |
| 507 | `BUDGET_EXCEEDED` | Cost limit reached |
