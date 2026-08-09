# 知识库持续优化计划

## 1. 优化目标

- **覆盖率**：从 40% → 90%+（核心模块全覆盖）
- **准确率**：从 70% → 95%+（代码引用准确）
- **响应时间**：从 30s → 10s（知识检索优化）

## 2. 优化维度

### 2.1 完整性优化

**缺失模块**：
- [ ] `snap-agent-client.md` — 客户端 SDK 详解
- [ ] `snap-agent-security.md` — 安全框架（SqlGuard、AuditStore）
- [ ] `snap-agent-cost-tracking.md` — 成本追踪系统
- [ ] `snap-agent-patrol.md` — 主动巡检系统
- [ ] `snap-anchor-system.md` — Anchor 锚点系统

**优先级**：P1（本周完成）

### 2.2 准确性优化

**验证方法**：
```bash
# 1. 代码引用验证
grep -rn "class Xxx" docs/knowledge/*.md | while read line; do
    file=$(echo $line | cut -d: -f1)
    class=$(echo $line | grep -o "class [A-Za-z]*" | cut -d' ' -f2)
    if ! find snap-agent-*/src/main/java -name "${class}.java" | grep -q .; then
        echo " Class not found: $class in $file"
    fi
done

# 2. 配置项验证
grep -o "snap-agent\\.[a-z.-]*" docs/knowledge/*.md | sort -u | while read prop; do
    if ! grep -rq "${prop##*.}" snap-agent-*/src/main/java; then
        echo "⚠️  Property may be outdated: $prop"
    fi
done
```

### 2.3 查询优化

**向量检索优化**：
- 增加文档 chunk 大小（512 → 1024 tokens）
- 优化 embedding 模型（text-embedding-3-large）
- 添加元数据过滤（module、version）

## 3. 自动化生成

### 3.1 使用 technical-architecture-discovery skill

```bash
# 扫描所有模块
for module in snap-agent-core snap-agent-spring-boot-2x-starter snap-agent-client; do
    echo "Scanning $module..."
    # 调用 skill 生成技术文档
done
```

### 3.2 增量更新机制

```python
# 检测代码变更
git diff --name-only main...HEAD | grep ".java$" | while read file; do
    module=$(echo $file | cut -d'/' -f1)
    # 重新生成相关文档
done
```

## 4. 验证标准

### 4.1 完整性验证

| 查询 | 期望结果 | 状态 |
|------|----------|------|
| "SnapAgent 如何组装 memory？" | 完整架构图 + 代码流程 | ✅ |
| "Agent 引擎如何执行任务？" | ReAct Graph 流程 | ✅ |
| "Advisor 如何注入上下文？" | Order 排序 + beforeNode/afterNode | ✅ |
| "Tool 如何注册和发现？" | ToolCallbackRegistry 流程 | ✅ |
| "Graph 状态如何管理？" | GraphState + Checkpoint | ✅ |
| "Security 如何保护系统？" | SqlGuard + AuditStore | 🔄 |
| "Cost 如何追踪消耗？" | CostTracker + BudgetEnforcer | 🔄 |

### 4.2 准确性验证

- [ ] 所有类名在代码中存在
- [ ] 所有配置项在 Properties 类中定义
- [ ] 所有流程图符合实际执行顺序

## 5. 执行时间表

| 阶段 | 任务 | 时间 | 负责人 |
|------|------|------|--------|
| Week 1 | 补充 5 个缺失模块文档 | 3 天 | AI |
| Week 2 | 代码引用准确性验证 | 2 天 | AI + 人工 |
| Week 3 | 向量检索优化 | 2 天 | AI |
| Week 4 | 自动化生成流程 | 3 天 | AI |

## 6. 度量指标

```yaml
metrics:
  coverage:
    target: 0.9  # 90% 模块覆盖
    current: 0.6  # 60% 当前覆盖
  
  accuracy:
    target: 0.95  # 95% 准确率
    current: 0.85  # 85% 当前准确率
  
  response_time:
    target: 10s  # 10 秒响应
    current: 25s  # 25 秒当前响应
```
