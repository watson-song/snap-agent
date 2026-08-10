---
name: snap-agent-anchor-system
description: Anchor 锚点系统 — AnchorOrchestrator、三种模式、HtmlOutputConverter
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Anchor 锚点系统

## 1. 架构

Anchor 系统将网页内容作为上下文注入 Agent，支持三种模式。

```
AnchorOrchestrator（协调器）
  → AnchorContextSummarizer（摘要）
  → AnchorSkillClassifier（分类）
  → AnchorInjectionOrchestrator（注入）
  → AnchorInjectionCache / AnchorSummaryCache（缓存）
```

## 2. 三种模式 (core/anchor/)

| 类 | 说明 |
|----|------|
| `AnchorGraphFactory` | 根据模式构建不同 Graph |
| `HtmlOutputConverter` | LLM 输出 → HTML |

- **AUTO**: 完整 ReAct 图，anchor 作为 skill body
- **OFF**: 线性图 entry→answer→END，无工具
- **INJECT**: 线性图 entry→generate→cache→END

## 3. 组件 (boot2x/anchor/)

| 类 | 职责 |
|----|------|
| `AnchorOrchestrator` | 协调预处理+执行（非 Advisor）|
| `AnchorContext` | 上下文数据 |
| `AnchorContextSummarizer` | 摘要生成 |
| `AnchorSkillClassifier` | 技能分类 |
| `AnchorInjectionOrchestrator` | 注入编排 |
| `AnchorInjectionCache` | 注入缓存 |
| `AnchorSummaryCache` | 摘要缓存 |
| `ClassifyResult` / `PreprocessResult` / `InjectionRequest` / `InjectionResult` / `InjectionCacheEntry` | 数据模型 |

## 4. 配置

```yaml
snap-agent:
  anchor:
    enabled: true
    max-context-chars: 8000
    preprocess-enabled: true
    preprocess-timeout-ms: 5000
```
