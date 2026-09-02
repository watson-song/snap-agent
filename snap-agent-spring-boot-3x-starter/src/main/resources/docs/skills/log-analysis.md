---
name: log-analysis
description: "Analyzes application log files to find exceptions, trace user operations, or diagnose issues. Use when the user asks to check logs, find errors, investigate exceptions, trace what a user did, or diagnose what happened during a specific period."
tools: [log_read]
shortcuts:
  - label: "🔴 最近错误"
    message: "查看最近的ERROR级别日志"
  - label: "⚠️ 最近警告"
    message: "查看最近的WARN级别日志"
  - label: "📋 最近日志"
    message: "查看最近的100行日志"
  - label: "🔍 异常堆栈"
    message: "查找日志中的异常堆栈信息"
---

# Log Analysis

Analyze application log files to answer the user's question about exceptions, user operations, or system behavior.

## Step 1: Determine the Log File Path

The application's log file path is provided in the `<user_inputs>` block as `{_app_log_file}`. **Always try this file first** — it is the host application's own log file and most likely contains the relevant logs.

If `{_app_log_file}` is empty or not found, fall back to the allowed log directories provided as `{_log_paths}`. Try common log file names in those directories:

- `{log_dir}/application.log` — main application log
- `{log_dir}/error.log` — error-only log (if configured)
- `{log_dir}/access.log` — access log (if configured)
- `{log_dir}/*.log` — any .log file in the directory

If the user mentions a specific log file, use that path instead. If the path is rejected by the guard, the error message will show the allowed directories — use those.

## Step 2: Understand the Question

Read the user's message carefully. Identify what they want:

- **Exception analysis**: Find ERROR/Exception stack traces, identify root cause
- **User operation tracing**: Find logs related to a specific user, request ID, or session
- **Time range analysis**: What happened between time A and time B
- **General diagnosis**: Anomalies, repeated errors, unusual patterns, slow requests

Extract key search terms: user ID, request ID, exception class name, timestamp, error message fragment.

## Step 3: Read the Log

Use the `log_read` tool strategically. Start broad, then narrow down:

### Strategy A: Recent issue (tail mode)
```
log_read(file_path=<path>, tail=true, max_lines=200)
```

### Strategy B: Specific error/exception
```
log_read(file_path=<path>, keyword="Exception", level=ERROR, max_lines=100)
```

### Strategy C: User/request tracing
```
log_read(file_path=<path>, keyword="userId12345", max_lines=100)
```

### Strategy D: Error-level overview
```
log_read(file_path=<path>, level=ERROR, max_lines=100)
```

## Step 4: Iterate if Needed

If the first read doesn't give enough context, call `log_read` again with different filters:

1. First: `tail=true, max_lines=200` to see recent activity
2. If you spot an exception: `keyword="<ExceptionClassName>", max_lines=100`
3. If you need surrounding context: `keyword="<timestamp or request id>", max_lines=50`

## Step 5: Analyze and Present Findings

### For exceptions
- List each exception with: timestamp, exception type, message, root cause
- Include the stack trace excerpt (first few frames)
- Note any patterns (same exception repeated, correlation with other events)

### For user operations
- Trace the sequence of actions the user performed, with timestamps
- Note any failures or anomalies in the flow
- If a request failed, identify at which step and why

### For general diagnosis
- Highlight anomalies, repeated errors, unusual patterns
- Note frequency of errors (e.g., "NullPointerException occurred 15 times in the last 500 log lines")
- Suggest potential root causes based on the log evidence

## Notes

- The tool only **reads** files; it cannot modify or delete logs.
- Very large files are rejected (configurable via `snap-agent.logs.max-file-bytes`).
- Output is capped at `max_lines` lines (default 500). Use strategic filtering to stay within limits.
- If no matching lines are found, say so explicitly and suggest alternative search strategies.
