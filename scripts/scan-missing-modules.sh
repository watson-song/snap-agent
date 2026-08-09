#!/bin/bash
# 扫描缺失的知识模块并自动生成文档

SKILLS_DIR="snap-agent-standalone/src/main/resources/docs/knowledge"
MISSING_MODULES=(
  "patrol:Patrol 系统"
  "vcs:VCS 集成"
  "domain:Domain Knowledge"
  "llm-client:LLM 客户端"
  "metrics:Metrics 监控"
  "issue:Issue 跟踪"
  "vectorstore:VectorStore"
)

echo "=== 扫描缺失模块 ==="

for item in "${MISSING_MODULES[@]}"; do
  module="${item%%:*}"
  desc="${item##*:}"
  
  if ls "$SKILLS_DIR"/*${module}*.md 1> /dev/null 2>&1; then
    echo "✅ $desc 已存在"
  else
    echo "❌ $desc 缺失 → 调用 skill 生成..."
    # 调用 technical-architecture-discovery skill
    curl -s -X POST http://localhost:8090/snap-agent/runs \
      -u demo:demo \
      -H "Content-Type: application/json" \
      -d "{
        \"skillId\": \"technical-architecture-discovery\",
        \"inputs\": {
          \"module\": \"$module\",
          \"description\": \"$desc\"
        }
      }" > /dev/null
    echo "   已提交生成任务"
  fi
done

echo ""
echo "=== 扫描完成 ==="
