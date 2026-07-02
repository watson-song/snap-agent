---
name: slow-query-analyzer
description: "Analyzes slow queries by examining execution plans. Use when the user reports a slow query or wants to optimize SQL performance."
tools: [query_database]
inputs:
  - key: sql
    label: SQL Statement
    required: true
    type: string
---

# Slow Query Analyzer

A diagnostic skill that helps identify why a SQL query is slow.

## Step 1: Check Execution Plan

Use the `query_database` tool to run:

```sql
EXPLAIN {sql}
```

## Step 2: Analyze

Based on the execution plan, identify:
- Full table scans (type=ALL)
- Missing indexes (possible_keys=NULL)
- High rows estimate
- Using temporary or filesort

## Step 3: Recommendations

Suggest improvements:
- Add indexes on columns used in WHERE/JOIN/ORDER BY
- Rewrite subqueries as JOINs
- Add LIMIT to reduce result set
- Consider partitioning for large tables
