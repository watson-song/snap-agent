# 嵌入式领域认知引擎 — 让 AI 比开发者更懂项目

> 日期: 2026-08-04 | 状态: 设计中 | 版本: v0.1

## 1. 课题背景

SnapAgent 当前是一个功能完整的**嵌入式 AI 工具框架**：
- 17 个内置工具（SQL/Redis/日志/指标/链路追踪/代码图谱/...）
- RAG 知识检索 + CodeGraph 代码图谱 + 模块架构图
- 4 个业务诊断 Skill（调拨/补货/品仓/供应计划）
- 问题闭环 + 知识沉淀 + 巡检调度

但它仍然是**被动的**——等用户提问，然后调工具。它不知道：
- "调拨计划"是什么业务概念，涉及哪些表、类、规则
- 上次这个问题的根因是什么，怎么解的
- 表与表之间的数据流向和生命周期
- 代码里有哪些已知陷阱和 workaround

**目标**：让 SnapAgent 从"工具集合"进化为"项目专家"——比开发者更快定位问题，因为它**记住了所有历史经验、理解了所有业务概念、掌握了所有代码关系**。

## 2. 核心差距分析

| # | 开发者知道但 AI 不知道 | 差距本质 | 解决方案 |
|---|----------------------|---------|---------|
| 1 | **业务语义** — "调拨计划"涉及哪些表、类、规则 | 没有领域模型 | 领域知识图谱 |
| 2 | **历史经验** — 上次这个问题怎么解的 | 没有诊断记忆 | 诊断经验库 |
| 3 | **数据关系** — 表之间的外键、数据流向 | 没有数据图谱 | Schema 图谱 |
| 4 | **已知陷阱** — "这个接口库存为0时返回500" | 没有陷阱知识库 | 陷阱知识条目 |
| 5 | **变更影响** — 这次改动影响哪些业务流程 | 没有变更-业务映射 | 变更影响分析 |
| 6 | **部署差异** — SIT/UAT/PROD 配置不同 | 没有部署感知 | 部署感知 |

## 3. 路线图

```
Phase 0 (✅ 已完成)           Phase 1 (🔲 当前)          Phase 2 (🔲 后续)
嵌入式工具框架                 领域认知引擎                 自主诊断闭环
─────────────────             ─────────────               ─────────────
✅ 17个内置工具                🔲 领域知识加载器            🔲 自主巡检
✅ RAG知识检索                 🔲 诊断经验自动沉淀          🔲 自动修复建议
✅ CodeGraph代码图谱           🔲 Schema图谱               🔲 预防性告警
✅ 模块架构图                  🔲 知识库按来源分类          🔲 跨系统关联
✅ 问题闭环                    🔲 陷阱知识库               🔲 知识自演化
✅ 知识库UI + 代码图谱Tab
✅ 版本号管理
```

## 4. Phase 1 设计要点

### 4.1 领域知识文件格式：Markdown + YAML Frontmatter

```markdown
---
name: 调拨计划
tables: [drp_allocation_plan, drp_allocation_detail]
services: [AllocationPlanService, BalanceAlgorithmService]
entry_points: [ReplenishmentPlanTask.generate()]
related_concepts: [补货策略, 安全库存]
tags: [replenishment, allocation]
---

# 调拨计划

## 业务描述
航材消耗件在多基地间的库存平衡调拨...

## 业务规则
1. 只有航材消耗件才走多基地平衡算法
2. 调拨数量 = max(0, 安全库存 - 可用库存)

## 已知陷阱
- 并发场景下 getAvailableStock() 可能返回过期数据
- 安全库存表有15分钟延迟

## SQL 示例
SELECT * FROM drp_allocation_plan WHERE sku_code = ?
```

**为什么选这个格式**：
- 现有 `MarkdownDocumentReader` + `HeadingChunker` 直接 ingest，零改动
- Frontmatter 可被 `DomainKnowledgeLoader` 解析，自动建立 概念→代码 映射
- Body 通过 RAG 自然检索
- 开发者用 Markdown 写，维护成本低

### 4.2 核心组件

| 组件 | 职责 |
|------|------|
| `DomainKnowledgeLoader` | 启动时扫描 `domain-knowledge/*.md`，解析 frontmatter，存入 VectorStore |
| `DomainKnowledgeIndex` | 维护 概念名→Document 映射，支持按 table/service 反查概念 |
| `DomainKnowledgeTools` | Agent 工具：查询概念详情、按表/类反查概念、注册新概念 |
| `DiagnosisExperienceExtractor` | IssueClosure 结束时自动提取诊断经验，存入 VectorStore |

### 4.3 诊断联动流程（目标状态）

```
用户: "SKU A123 为什么没有调拨计划？"

Agent 思考:
  1. RAG 检索 "调拨计划"
     → 命中 domain-knowledge/allocation-plan.md
     → 注入上下文: 表 drp_allocation_plan, 入口 ReplenishmentPlanTask.generate()

  2. 自动执行:
     - mysql_query: SELECT * FROM drp_allocation_plan WHERE sku_code = 'A123'
     - call_chain: ReplenishmentPlanTask.generate()
     - reverse_chain: AllocationPlanService.createPlan()

  3. 命中 known_pitfalls: "安全库存表有15分钟延迟"

  4. 命中历史经验: "2026-07-15 类似问题: SKU B456 因安全库存未更新导致跳过"

  5. 结论:
     "A123 没有调拨计划。根因: 安全库存表尚未更新（Dolphin任务执行后需15分钟），
      当前可用库存=50 > 安全库存=30，不满足调拨条件。
      参考: 7月15日类似问题(B456)也是同一根因，等待后恢复正常。"
```

## 5. 预期价值

| 指标 | 当前 | Phase 1 后 |
|------|------|-----------|
| 首次诊断耗时 | 30-60 min（人工） | 5-10 min（AI引导） |
| 重复问题诊断 | 每次重新排查 | 直接命中历史经验 |
| 新人上手 | 需要老员工带 | AI 直接提供业务上下文 |
| 知识流失 | 离职带走 | 沉淀在领域知识中 |
| 跨模块理解 | 需要看多个代码文件 | AI 自动关联概念→代码 |

## 6. 实施计划

| 步骤 | 内容 | 依赖 |
|------|------|------|
| S1 | 设计文档 + TDD spec | 无 |
| S2 | `DomainKnowledgeLoader` (core) | S1 |
| S3 | `DomainKnowledgeTools` (starter) | S2 |
| S4 | `DiagnosisExperienceExtractor` | IssueClosure |
| S5 | 前端领域知识 Tab | S3 |
| S6 | 示例领域知识文件（调拨计划） | S2 |
| S7 | E2E 测试：完整诊断联动 | S2-S5 |
