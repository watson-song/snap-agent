# 知识库 Review 补充机制

## 1. 现状分析

### 1.1 覆盖率统计

| 类别 | 模块数 | 已覆盖 | 覆盖率 |
|------|--------|--------|--------|
| Core SPI | 10 | 7 | 70% |
| Starter 集成 | 10 | 3 | 30% |
| **总计** | **20** | **10** | **50%** |

### 1.2 缺失模块清单

**P0 (核心功能)** - 本周补充:
- [ ] RAG 系统 (`core/rag`)
- [ ] Embedding (`core/embedding`)
- [ ] Anchor 系统 (`core/anchor`)

**P1 (重要功能)** - 下周补充:
- [ ] Patrol 系统 (`boot2x/patrol`)
- [ ] VCS 集成 (`boot2x/vcs`)
- [ ] Domain Knowledge (`core/domain`)

**P2 (辅助功能)** - 后续补充:
- [ ] Metrics 监控 (`core/metrics`)
- [ ] Issue 跟踪 (`core/issue`)
- [ ] VectorStore (`core/vectorstore`)
- [ ] LLM 客户端详解 (`core/llm`)

## 2. 自动化扫描机制

### 2.1 使用集成阶段知识生成工具

> **注意**：`domain-knowledge-discovery` 和 `technical-architecture-discovery` 已从运行时内置 skill 移至 `docs/skills/` 作为集成阶段工具。不在运行时通过 REST API 调用，而是在集成阶段直接读取源码文件执行。

**执行方式**（集成阶段，直接读取源码）:

```bash
# 在集成阶段使用 AI Agent 执行 docs/skills/ 下的知识生成工具
# 工具直接读取项目源码文件，生成知识库 .md 文件到 output_dir

# 1. 业务领域知识生成（Service/Controller/Entity → 业务概念文档）
# 输入: project_root, output_dir, scan_packages
# 输出: allocation-plan.md, order-create.md 等

# 2. 技术架构知识生成（SPI/AutoConfig/Advisor → 技术架构文档）
# 输入: project_root, output_dir
# 输出: myapp-auth-system.md, myapp-cache-layer.md 等

# 3. 批量扫描所有缺失模块
for module in rag embedding anchor patrol vcs domain; do
  echo "Scanning $module..."
  # 集成阶段工具直接扫描并生成
done
```

### 2.2 增量更新检测

```python
#!/usr/bin/env python3
"""
检测代码变更并触发文档重新生成
"""
import subprocess
import os

def get_changed_modules():
    """获取 Git 变更的模块"""
    result = subprocess.run(
        ['git', 'diff', '--name-only', 'main...HEAD'],
        capture_output=True, text=True
    )
    
    changed_files = result.stdout.strip().split('\n')
    modules = set()
    
    for file in changed_files:
        if file.endswith('.java'):
            # 提取模块名
            parts = file.split('/')
            if len(parts) >= 4:
                module = parts[3]  # e.g., 'rag', 'embedding'
                modules.add(module)
    
    return modules

def needs_regeneration(module):
    """检查模块是否需要重新生成文档"""
    doc_file = f"snap-agent-standalone/src/main/resources/docs/knowledge/snap-agent-{module}-system.md"
    
    if not os.path.exists(doc_file):
        return True  # 文档不存在，需要生成
    
    # 检查文档最后修改时间 vs 代码最后修改时间
    doc_mtime = os.path.getmtime(doc_file)
    
    result = subprocess.run(
        ['find', f'snap-agent-core/src/main/java/cn/watsontech/snapagent/core/{module}',
         '-name', '*.java', '-newer', doc_file],
        capture_output=True, text=True
    )
    
    return len(result.stdout.strip()) > 0

def main():
    modules = get_changed_modules()
    
    for module in modules:
        if needs_regeneration(module):
            print(f" Regenerating docs for: {module}")
            # 调用 skill 重新生成
        else:
            print(f"✅ {module} docs are up-to-date")

if __name__ == '__main__':
    main()
```

## 3. 质量检查机制

### 3.1 代码引用验证

```bash
#!/bin/bash
# validate-code-references.sh

echo "验证知识文档中的代码引用..."

for doc in snap-agent-standalone/src/main/resources/docs/knowledge/*.md; do
    echo "Checking: $(basename $doc)"
    
    # 提取所有类名引用
    grep -oE '\b[A-Z][a-zA-Z]+\.java\b' "$doc" | while read class_file; do
        class_name="${class_file%.java}"
        
        # 检查类是否存在
        if ! find snap-agent-*/src/main/java -name "${class_name}.java" | grep -q .; then
            echo "  ⚠️  Class not found: $class_name"
        fi
    done
    
    # 提取所有配置项引用
    grep -oE 'snap-agent\.[a-z.-]+' "$doc" | sort -u | while read prop; do
        prop_name="${prop##*.}"
        
        # 检查配置项是否在 Properties 类中定义
        if ! grep -rq "$prop_name" snap-agent-spring-boot-2x-starter/src/main/java/*Properties.java 2>/dev/null; then
            echo "  ⚠️  Property may be outdated: $prop"
        fi
    done
done
```

### 3.2 配置项验证

```python
#!/usr/bin/env python3
"""
验证文档中的配置项是否与实际代码一致
"""
import re
import os
from pathlib import Path

def extract_doc_properties(doc_path):
    """从文档中提取配置项"""
    with open(doc_path, 'r') as f:
        content = f.read()
    
    # 匹配 snap-agent.xxx 格式
    pattern = r'snap-agent\.([a-z0-9.-]+)'
    return set(re.findall(pattern, content))

def extract_code_properties(properties_file):
    """从 Properties 类中提取配置项"""
    with open(properties_file, 'r') as f:
        content = f.read()
    
    # 匹配 @ConfigurationProperties 字段
    pattern = r'private\s+\w+\s+(\w+);'
    return set(re.findall(pattern, content))

def validate_document(doc_path, properties_files):
    """验证单个文档"""
    doc_props = extract_doc_properties(doc_path)
    code_props = set()
    
    for prop_file in properties_files:
        code_props.update(extract_code_properties(prop_file))
    
    missing = doc_props - code_props
    if missing:
        print(f"️  {os.path.basename(doc_path)}:")
        for prop in sorted(missing):
            print(f"   - snap-agent.{prop}")

def main():
    docs_dir = "snap-agent-standalone/src/main/resources/docs/knowledge"
    properties_files = list(Path("snap-agent-spring-boot-2x-starter/src/main/java").rglob("*Properties.java"))
    
    for doc in Path(docs_dir).glob("*.md"):
        validate_document(doc, properties_files)

if __name__ == '__main__':
    main()
```

## 4. Review 流程

### 4.1 AI 自动 Review

**检查项**:
- [ ] 代码引用准确性（类名、方法名）
- [ ] 配置项完整性（所有配置项都有文档）
- [ ] 流程图正确性（符合实际执行顺序）
- [ ] 示例代码可运行性
- [ ] 架构图清晰度

**自动化脚本**:

```bash
#!/bin/bash
# ai-review.sh

echo "=== AI 自动 Review ==="

# 1. 代码引用验证
./validate-code-references.sh

# 2. 配置项验证
python3 validate-config-properties.py

# 3. 生成 review 报告
cat > /tmp/review-report.md << EOF
# Knowledge Base Review Report

Date: $(date)
Coverage: $(./calculate-coverage.sh)

## Issues Found
$(cat /tmp/validation-results.txt)

## Recommendations
1. ...
2. ...
EOF

echo "Review report generated: /tmp/review-report.md"
```

### 4.2 人工 Review

**Review Checklist**:

| 检查项 | 标准 | 状态 |
|--------|------|------|
| 架构准确性 | 图与实际代码一致 | ⬜ |
| 代码示例 | 可直接复制运行 | ⬜ |
| 配置完整 | 所有配置项都有说明 | ⬜ |
| 新人友好 | 无需看代码就能理解 | ⬜ |
| 更新及时 | 代码变更后文档同步 | ⬜ |

**Review 流程**:

```
1. AI 生成文档
   ↓
2. AI 自动 Review（代码引用、配置项）
   ↓
3. 人工 Review（架构、示例、可读性）
   ↓
4. 修改完善
   ↓
5. 提交合并
```

## 5. CI/CD 集成

### 5.1 GitHub Actions 工作流

```yaml
name: Knowledge Base CI

on:
  push:
    paths:
      - 'snap-agent-*/src/main/java/**/*.java'
  pull_request:
    paths:
      - 'snap-agent-*/src/main/java/**/*.java'

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Java
        uses: actions/setup-java@v3
        with:
          java-version: '8'
      
      - name: Validate Code References
        run: ./scripts/validate-code-references.sh
      
      - name: Validate Config Properties
        run: python3 scripts/validate-config-properties.py
      
      - name: Calculate Coverage
        run: ./scripts/calculate-coverage.sh
      
      - name: Generate Report
        run: ./scripts/generate-review-report.sh
      
      - name: Upload Report
        uses: actions/upload-artifact@v3
        with:
          name: knowledge-review-report
          path: /tmp/review-report.md
```

### 5.2 自动重新生成

```yaml
  regenerate:
    needs: validate
    if: needs.validate.outputs.coverage < 90
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Start SnapAgent
        run: |
          mvn clean package -DskipTests
          java -jar snap-agent-standalone/target/snap-agent-standalone.jar &
          sleep 30
      
      - name: Scan Missing Modules
        run: |
          for module in $(./scripts/list-missing-modules.sh); do
            curl -X POST http://localhost:8090/snap-agent/runs \
              -d '{"skillId":"technical-architecture-discovery","inputs":{"module":"'$module'"}}'
          done
      
      - name: Commit Generated Docs
        run: |
          git add snap-agent-standalone/src/main/resources/docs/knowledge/
          git commit -m "docs: auto-generate missing knowledge files"
          git push
```

## 6. 度量指标

### 6.1 覆盖率指标

```yaml
metrics:
  module_coverage:
    target: 0.9  # 90%
    current: 0.5  # 50%
    trend: ↑
  
  code_reference_accuracy:
    target: 0.95  # 95%
    current: 0.85  # 85%
    trend: →
  
  config_property_coverage:
    target: 1.0  # 100%
    current: 0.7  # 70%
    trend: ↑
```

### 6.2 质量指标

```yaml
quality:
  avg_review_time: 2h  # 平均 Review 时间
  issues_per_doc: 1.5  # 每篇文档平均问题数
  auto_fix_rate: 0.6  # 自动修复率
  manual_review_rate: 0.4  # 人工 Review 率
```

## 7. 执行计划

| 阶段 | 任务 | 时间 | 负责人 |
|------|------|------|--------|
| Week 1 | 补充 P0 模块 (RAG/Embedding/Anchor) | 3 天 | AI + 人工 |
| Week 2 | 补充 P1 模块 (Patrol/VCS/Domain) | 3 天 | AI + 人工 |
| Week 3 | 建立自动化扫描流程 | 2 天 | AI |
| Week 4 | 集成 CI/CD + Review 机制 | 3 天 | AI + 人工 |

## 8. 验收标准

- [ ] 覆盖率达到 90%+ (18/20 模块)
- [ ] 代码引用准确率 95%+
- [ ] 配置项覆盖 100%
- [ ] CI/CD 自动验证通过
- [ ] Review 流程文档化
