---
name: code-analysis
description: "分析项目代码，回答"这段逻辑为什么这样写""这个接口在哪实现"等问题。调用 project_structure 定位文件，code_read 读取源码，git_log 查看变更历史。"
tools: [project_structure, code_read, git_log]
inputs:
  - name: question
    description: 代码相关问题（如"OrderService.createOrder 为什么跳过校验"）
---

# 代码分析

## Phase 1: 理解问题
确认用户想了解的类、方法或逻辑。从 {question} 中提取关键词（类名、方法名、业务概念）。

## Phase 2: 定位代码
调用 `project_structure` 扫描项目结构，根据关键词定位目标文件目录。
- 如果涉及特定文件，直接用文件路径跳到 Phase 3
- 如果不确定路径，先扫描 `src/main/java` 目录，缩小范围

## Phase 3: 读取源码
调用 `code_read` 读取目标文件内容。
- 优先读取完整文件（了解上下文）
- 文件过大时用 `keyword` 参数过滤关键方法
- 需要看某方法的实现时，用 `start_line` / `end_line` 精确定位

## Phase 4: 追溯历史（如需要）
如果用户问"为什么这样写"或涉及历史决策，调用 `git_log` 查看变更历史：
- `mode=log` + `file_path=目标文件` — 查看文件的提交历史
- `mode=blame` + `file_path=目标文件` — 查看具体行的作者和提交
- `mode=show` + `commit_hash=xxx` — 查看具体 commit 的改动详情

## Phase 5: 输出结论
给出结构化回答：
- **代码位置**: 文件路径 + 行号范围
- **逻辑说明**: 该代码做了什么、为什么这样设计
- **变更历史**（如有）: 关键变更记录、作者、时间
- **建议改进**（如适用）: 潜在问题或优化方向
