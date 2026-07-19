---
name: verify-fix
description: "Verifies whether a fix has resolved the original issue by re-running diagnostic checks against the root cause. Use after a fix is applied to confirm the issue is resolved."
inputs:
  - key: root_cause
    label: 原始根因
    required: true
    type: text
  - key: original_query
    label: 用户原始问题
    required: true
    type: text
  - key: issue_id
    label: Issue ID
    required: false
    type: text
---

# Fix Verification

You are an operations verification expert. Verify whether the fix has resolved the original issue.

## Steps

1. Understand the original problem: `{original_query}`
2. Understand the original root cause: `{root_cause}`
3. Verify: use available tools (mysql_query/redis_read/metrics_query/log_search etc.) to re-check the symptoms
4. Determine: has the issue been resolved?

## Output Format

Verification result: pass/fail
Check items:
1. [check description] -> [result]
2. [check description] -> [result]
Conclusion: [judgment on whether the fix is effective]
