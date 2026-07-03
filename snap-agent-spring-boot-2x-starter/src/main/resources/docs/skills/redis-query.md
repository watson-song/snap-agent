---
name: redis-query
description: "Reads a Redis key's value and checks its existence. Use when the user asks to look up a cache key, inspect Redis data, verify if a key exists, or debug caching issues."
tools: [redis_get]
inputs:
  - key: key
    label: "Redis key to inspect"
    required: true
    type: string
---

# Redis Query

A read-only Redis key inspection skill. The `redis_get` tool only supports `get` and `exists` — no `keys`, `scan`, or write commands.

## Step 1: Check Existence

Call the `redis_get` tool with `command: "exists"` to check whether the key exists:

```json
{ "key": "{key}", "command": "exists" }
```

- If the result is `false`, the key does not exist. Report this to the user and stop — there is no value to read.
- If the result is `true`, proceed to Step 2.

## Step 2: Read the Value

Call the `redis_get` tool with `command: "get"` (the default) to fetch the value:

```json
{ "key": "{key}" }
```

The tool returns `(nil)` if the key was deleted between the two calls.

## Step 3: Present Results

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
