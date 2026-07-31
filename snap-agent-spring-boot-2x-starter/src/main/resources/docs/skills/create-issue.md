---
name: create-issue
description: "纯文本整理任务：根据已有的诊断结果，输出结构化的 Issue JSON。禁止调用任何工具，禁止查询数据库，禁止重新诊断。"
tools: []
inputs:
  - key: root_cause
    label: 根因分析/诊断报告
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

# Issue 数据整理（纯文本任务）

**重要：这是一个纯文本整理任务。你不需要、也不应该调用任何工具。不要查询数据库，不要执行任何诊断步骤，不要检查任何系统状态。**

用户已经完成了完整的诊断流程。你的唯一任务是：**阅读输入内容，然后输出一个结构化的 JSON 对象。**

## 禁止行为

- ❌ 不要调用 mysql_query、log_search、metrics_query 或任何其他工具
- ❌ 不要查询数据库
- ❌ 不要执行诊断步骤（Layer 0、Layer 1 等）
- ❌ 不要检查系统状态
- ❌ 不要重新分析根因

## 允许行为

- ✅ 阅读 `root_cause` 和 `original_query` 的内容
- ✅ 从中提取关键信息
- ✅ 整理成结构化 JSON 输出

## 输入

- **用户原始问题**: `{original_query}`
- **诊断报告**: `{root_cause}`

## 输出

直接输出以下 JSON，不要添加任何解释、分析或诊断步骤：

```json
{
  "title": "一句话概括核心问题（不超过80字符）",
  "description": "## 问题背景\n（从诊断报告中提取）\n\n## 根因分析\n（从诊断报告中提取）\n\n## 影响范围\n（从诊断报告中提取）",
  "severity": 2,
  "pri": 2,
  "acceptance_criteria": [
    {
      "id": "AC-1",
      "description": "从诊断报告中提取的验收条件",
      "tool": "mysql_query",
      "verification": "从诊断报告中提取的SQL或命令",
      "expected": "> 0"
    }
  ]
}
```

## 字段说明

- **title**: 简洁的问题标题，用于禅道 Bug 标题
- **description**: Markdown 格式的问题描述，从诊断报告中提取背景、根因、影响范围
- **severity**: 1(致命) 2(严重) 3(一般) 4(轻微) — 根据影响范围判断
- **pri**: 1(紧急) 2(高) 3(中) 4(低) — 根据业务影响判断
- **acceptance_criteria**: 从诊断报告中提取 2-4 条可验证的验收条件

## 再次强调

**直接输出 JSON。不要调用工具。不要查询数据库。不要重新诊断。不要添加任何分析过程。你的任务只是整理已有的诊断结果。**
