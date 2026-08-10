---
name: snap-agent-workflow
description: 工作流引擎 — WorkflowDefinition、YamlWorkflowLoader、SimpleWorkflowEngine
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 工作流引擎

## 1. 架构

```
YAML 文件 → YamlWorkflowLoader → WorkflowDefinition
    → SimpleWorkflowEngine.execute(definition, inputs)
        → 顺序执行 WorkflowStep[]
            → 每个 step 调 AgentService 跑 skill
            → 条件分支 + onFailure(STOP/SKIP/RETRY)
    → WorkflowResult
```

## 2. 数据模型 (boot2x/workflow/)

| 类 | 职责 |
|----|------|
| `WorkflowDefinition` | 工作流定义（name, description, steps）|
| `WorkflowStep` | 步骤（name, skill, condition, inputs, onFailure）|
| `WorkflowEngine` | 执行 SPI |

## 3. 数据模型

| 类 | 说明 |
|----|------|
| `WorkflowStatus` | RUNNING / COMPLETED / ABORTED / FAILED |
| `StepResult` | stepName + taskId + status + report |
| `WorkflowResult` | status + Map<String, StepResult> |

## 4. 实现

| 类 | 说明 |
|----|------|
| `YamlWorkflowLoader` | SnakeYAML 解析 .yml |
| `SimpleWorkflowEngine` | 顺序执行，条件解析，onFailure 处理 |

## 5. REST API

| 端点 | 说明 |
|------|------|
| `GET /workflows` | 工作流列表 |
| `GET /workflows/{name}` | 工作流详情 |
| `POST /workflows/{name}/run` | 执行工作流 |

## 6. 配置

```yaml
snap-agent:
  workflows:
    enabled: false
    dir: /path/to/workflows/
```
