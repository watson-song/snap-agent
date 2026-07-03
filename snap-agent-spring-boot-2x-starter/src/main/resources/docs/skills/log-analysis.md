---
name: log-analysis
description: "Analyzes application log files to find exceptions, trace user operations, or diagnose issues within a time range. Use when the user asks to check logs, find errors, investigate exceptions, trace what a user did, or diagnose what happened during a specific period."
tools: [log_read]
inputs:
  - key: question
    label: "What to look for (e.g., 'exceptions in last hour', 'user ABC operations', 'why did request X fail')"
    required: true
    type: string
  - key: file_path
    label: "Log file path (must be under an allowed log directory)"
    required: true
    type: string
---

# Log Analysis

Analyze application log files to answer the user's question about exceptions, user operations, or system behavior within a time range.

## Step 1: Understand the Question

Read the `question` input carefully. Identify what the user wants:

- **Exception analysis**: Find ERROR/Exception stack traces, identify root cause
- **User operation tracing**: Find logs related to a specific user, request ID, or session
- **Time range analysis**: What happened between time A and time B
- **General diagnosis**: Anomalies, repeated errors, unusual patterns, slow requests

Extract key search terms from the question: user ID, request ID, exception class name, timestamp, error message fragment.

## Step 2: Read the Log

Use the `log_read` tool strategically. Start broad, then narrow down:

### Strategy A: Recent issue (tail mode)
If the user says "recent" or "just now":
```
log_read(file_path=<path>, tail=true, max_lines=200)
```
Then look for errors or relevant entries in the recent output.

### Strategy B: Specific error/exception
If the user mentions an exception type or error message:
```
log_read(file_path=<path>, keyword="Exception", level=ERROR, max_lines=100)
```
Or search for a specific class/message fragment:
```
log_read(file_path=<path>, keyword="NullPointerException", max_lines=50)
```

### Strategy C: User/request tracing
If tracing a specific user or request:
```
log_read(file_path=<path>, keyword="userId12345", max_lines=100)
```

### Strategy D: Error-level overview
```
log_read(file_path=<path>, level=ERROR, max_lines=100)
```

## Step 3: Iterate if Needed

If the first read doesn't give enough context, call `log_read` again with different filters. For example:

1. First call: `tail=true, max_lines=200` to see recent activity
2. If you spot an exception: `keyword="<ExceptionClassName>", max_lines=100` to find all occurrences
3. If you need surrounding context: `keyword="<timestamp or request id>", max_lines=50`

You may need to check multiple log files. Common patterns:
- Application log: `/app/logs/application.log`
- Error log: `/app/logs/error.log`
- Access log: `/app/logs/access.log`

## Step 4: Analyze and Present Findings

Summarize what you found:

### For exceptions
- List each exception with: timestamp, exception type, message, root cause (if visible)
- Include the stack trace excerpt (first few frames)
- Note any patterns (same exception repeated, correlation with other events)

### For user operations
- Trace the sequence of actions the user performed, with timestamps
- Note any failures or anomalies in the flow
- If a request failed, identify at which step and why

### For time range analysis
- Present a timeline of significant events
- Highlight errors, warnings, and unusual activity
- Note any correlations (e.g., error spike after a specific event)

### For general diagnosis
- Highlight anomalies, repeated errors, unusual patterns
- Note frequency of errors (e.g., "NullPointerException occurred 15 times in the last 500 log lines")
- Suggest potential root causes based on the log evidence

## Notes

- Log files must be under a directory configured in `snap-agent.logs.allowed-paths`. If you get a "path not allowed" error, tell the user to configure the log directory.
- The tool only **reads** files; it cannot modify or delete logs.
- Very large files are rejected (configurable via `snap-agent.logs.max-file-bytes`).
- Output is capped at `max_lines` lines (default 500). Use strategic filtering to stay within limits.
- If no matching lines are found, say so explicitly and suggest alternative search strategies.
