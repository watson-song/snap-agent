---
name: health-check
description: A trivial demo skill that verifies the agent loop is wired. Use when the user asks to run a health check.
tools: [echo]
inputs:
  - key: message
    label: 消息
    required: true
    type: string
---

# 健康检查 Skill

这是一个用于 E2E 测试的最简 skill。给定 {message}，Agent 应回答"已收到：{message}"。

## Phase 1: 回显
直接回显用户输入的 {message}，不做任何工具调用。
