---
name: snap-agent-conversation
description: 会话管理 — ConversationStore SPI、FileConversationStore、REST API
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 会话管理

## 1. 核心 SPI (boot2x/conversation/)

| 类 | 职责 |
|----|------|
| `ConversationStore` | SPI: save / load / list(userId) / delete(id, userId) / exportMarkdown(id, userId) — 所有方法带 userId 归属校验 |
| `Conversation` | 会话数据（id, userId, skillId, title, messages）|
| `ConversationMessage` | 消息（role, content, timestamp）|
| `ConversationSummary` | 列表用摘要（无 messages body）|

## 2. 实现

| 类 | 模块 | 说明 |
|----|------|------|
| `FileConversationStore` | boot2x/conversation | JSON 文件: `{upload-dir}/conversations/{userId}/{id}.json` |

`@ConditionalOnMissingBean` — 宿主可替换为 DB 实现。

## 3. REST API

| 端点 | 说明 |
|------|------|
| `POST /conversations` | 保存/更新 |
| `GET /conversations?skillId=` | 列表（按 updatedAt 降序）|
| `GET /conversations/{id}` | 加载 |
| `GET /conversations/{id}/download` | 下载 Markdown |
| `DELETE /conversations/{id}` | 删除 |
