---
name: redis-query
description: "Reads a Redis key's value and checks its existence. Use when the user asks to look up a cache key, inspect Redis data, verify if a key exists, or debug caching issues."
tools: [redis_get]
shortcuts:
  - label: "🔍 检查Key"
    message: "我想检查一个Redis Key是否存在，请先问我要查询的Key名称"
  - label: "📖 读取Key值"
    message: "我想读取一个Redis Key的值，请先问我要查询的Key名称"
---

# Redis Query

A read-only Redis key inspection skill. The `redis_get` tool only supports `get` and `exists` — no `keys`, `scan`, or write commands.

## Step 1: Get the Key Name

Ask the user for the Redis key name they want to inspect. The key name is provided in the user's message.

## Step 2: Check Existence

Call the `redis_get` tool with `command: "exists"` to check whether the key exists:

```json
{ "key": "{key}", "command": "exists" }
```

- If the result is `false`, the key does not exist. Report this and suggest possible reasons (expired, wrong name, never populated).
- If the result is `true`, proceed to Step 3.

## Step 3: Read the Value

Call the `redis_get` tool with `command: "get"` (the default) to fetch the value:

```json
{ "key": "{key}" }
```

## Step 4: Present Results

Summarize for the user:
- **Key**: the key name
- **Exists**: yes / no
- **Value**: the raw value (or `(nil)` / empty)
- **Value length**: character count (useful for spotting truncated or oversized cache entries)

If the key does not exist, suggest possible reasons:
- The key expired (TTL elapsed)
- The key name is wrong (typos, case sensitivity, missing prefix)
- The cache was never populated for this key

Note: This skill cannot list keys or scan patterns. If the user wants to find keys by pattern, ask them to provide the exact key name.
