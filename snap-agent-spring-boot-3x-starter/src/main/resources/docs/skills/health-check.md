---
name: health-check
description: "Performs a basic health check of the application — verifies database connectivity and key configuration. Use when the user asks 'is the system healthy?' or 'check health'."
tools: [mysql_query]
---

# Health Check

## Step 1: Verify Database Connectivity
Use the `mysql_query` tool to run a simple query:
```sql
SELECT 1 AS ok
```
If this fails, report that the database is unreachable.

## Step 2: Check Table Counts
Check if key tables exist and have data:
```sql
SELECT TABLE_NAME, TABLE_ROWS
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME
LIMIT 20
```

## Step 3: Report Summary
Summarize the health status:
- Database connectivity: OK / FAILED
- Table count summary
- Any anomalies detected
