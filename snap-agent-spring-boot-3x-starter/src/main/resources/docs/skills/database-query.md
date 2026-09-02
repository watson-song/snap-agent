---
name: database-query
description: "Queries the application database with read-only SQL. Use when the user asks to look up data, inspect records, count rows, or answer business questions from database data."
tools: [mysql_query]
shortcuts:
  - label: "📋 所有表"
    message: "列出当前数据库的所有表"
  - label: "📊 表数据量"
    message: "查看当前数据库各表的数据量"
  - label: "🔗 当前连接"
    message: "查看当前数据库的连接列表"
  - label: "💾 数据库版本"
    message: "查看数据库版本信息"
---

# Database Query

A general-purpose read-only database query skill. Translate the user's natural-language question into a safe `SELECT` query and execute it.

## Step 1: Understand the Question

Read the user's message carefully. Identify:
- What entity the user wants (orders, users, products, etc.)
- What filter / condition applies (status, date range, id)
- What aggregation is needed (count, sum, latest)

If the question is already valid SQL, skip to Step 3.

## Step 2: Discover the Schema (if needed)

If you don't know the table/column names, inspect `INFORMATION_SCHEMA` first:

```sql
SELECT TABLE_NAME, TABLE_ROWS
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME
LIMIT 50
```

Then check columns of the target table:

```sql
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '{table}'
ORDER BY ORDINAL_POSITION
```

## Step 3: Build the Query

Construct a read-only `SELECT` query that answers the question. Rules:
- Start with `SELECT`, `WITH`, `SHOW`, `DESCRIBE`, or `EXPLAIN` only — write keywords are blocked.
- Always include an explicit `LIMIT` (the guard will inject one if missing, but explicit is clearer).
- Never use `SELECT *` on wide tables — pick the columns the user cares about.

## Step 4: Execute

Call the `mysql_query` tool with the SQL. The tool returns a pipe-delimited table.

## Step 5: Present Results

Summarize the result for the user:
- Restate the question in one line.
- Present the data as a readable table or bullet list.
- If the result is empty, say so explicitly and suggest why.
- If the query was rejected by the read-only guard, explain that only read queries are allowed.
