---
name: snap-agent-anchor-system
description: Anchor 锚点系统详解 — AnchorGraphFactory、三种模式、HTML 注入
version: 1.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Anchor 锚点系统

## 1. 架构概述

Anchor 系统提供三种模式，将网页内容作为上下文注入 Agent：

```
─────────────────────────────────────────────────────────
│              AnchorGraphFactory                          │
│                                                         │
│  Mode.AUTO:   完整 ReAct 图，anchor 作为 skill body     │
│  Mode.OFF:    线性图 entry → answer → END (无工具)     │
│  Mode.INJECT: 线性图 entry → generate → cache → END    │
└────────────────────────────────────────────────────────┘
```

## 2. 三种模式

### 2.1 AUTO 模式

- 委托给 ReActGraphFactory
- Anchor 上下文作为 skill body
- 支持工具调用

### 2.2 OFF 模式

- 线性图: entry → answer → END
- 无工具节点
- LLM 仅基于 anchor 上下文回答
- 输出通过 HtmlOutputConverter 处理

### 2.3 INJECT 模式

- 线性图: entry → generate → cache → END
- 生成 HTML 内容并缓存
- 用于锚点注入场景

## 3. HtmlOutputConverter

```java
public class HtmlOutputConverter {
    /** 将 LLM 输出转换为 HTML 格式 */
    public String convert(String markdownOutput) {
        // Markdown → HTML 转换
    }
}
```

## 4. 使用场景

- **Q&A**: 用户提供网页 URL，Agent 基于网页内容回答
- **摘要生成**: 提取网页关键信息生成摘要
- **HTML 注入**: 将生成的内容注入到网页中

## 5. 配置属性

```yaml
snap-agent:
  anchor:
    enabled: true
    max-context-chars: 8000
    preprocess-enabled: true
    preprocess-timeout-ms: 5000
    summary-threshold-chars: 4000
    classifier-confidence-threshold: 0.5
    summary-cache-ttl-seconds: 600
    injection-cache-max-size: 512
```
