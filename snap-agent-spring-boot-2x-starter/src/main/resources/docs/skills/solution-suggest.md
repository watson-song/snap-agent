---
name: solution-suggest
description: "Based on diagnostic root cause analysis, generate 2-3 candidate solutions with recommendation levels. Use after a diagnostic task completes to propose remediation options."
inputs:
  - key: root_cause
    label: 根因摘要
    required: true
    type: text
  - key: original_query
    label: 用户原始问题
    required: true
    type: text
  - key: task_id
    label: 关联诊断任务 ID
    required: false
    type: text
---

# Solution Suggestion Generation

You are an operations diagnosis expert. Based on the root cause analysis result, provide 2-3 candidate solutions.

## Steps

1. Understand the root cause: `{root_cause}`
2. Consider the user's original question: `{original_query}`
3. If needed, use `code_graph_impact_analysis` to check the modification impact scope
4. Generate solutions:
   - Solution 1: [description] (recommendation: high/medium/low)
   - Solution 2: [description] (recommendation: high/medium/low)
5. Recommend one solution and explain the reasoning

## Output Format

Solution 1: [description]
Recommendation: high
Reason: [why recommended]

Solution 2: [description]
Recommendation: medium
Reason: [why]

Recommendation: Solution 1 (immediate) + Solution 2 (long-term)
Related code: [file:line]
