---
name: db-table-inspector
description: "Inspects a database table structure and sample data. Use when the user asks to check table schema, columns, or sample rows."
tools: [query_database]
inputs:
  - key: tableName
    label: Table Name
    required: true
    type: string
---

# Database Table Inspector

A simple diagnostic skill that shows the structure and sample data of a database table.

## Step 1: Describe Table Structure

Use the `query_database` tool to run:

```sql
DESCRIBE {tableName}
```

This shows column names, types, and constraints.

## Step 2: Sample Data

Use the `query_database` tool to fetch a few sample rows:

```sql
SELECT * FROM {tableName} LIMIT 10
```

## Step 3: Summary

Present the table structure and sample data to the user in a readable format.
Highlight any interesting patterns (NULL columns, default values, index usage).
