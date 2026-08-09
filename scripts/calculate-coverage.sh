#!/bin/bash
# 计算知识库覆盖率

SKILLS_DIR="snap-agent-standalone/src/main/resources/docs/knowledge"
TOTAL_MODULES=20

if [ -d "$SKILLS_DIR" ]; then
  COVERED=$(ls "$SKILLS_DIR"/*.md 2>/dev/null | wc -l | tr -d ' ')
else
  COVERED=0
fi

COVERAGE=$(( COVERED * 100 / TOTAL_MODULES ))

echo "覆盖率: $COVERED / $TOTAL_MODULES 模块 ($COVERAGE%)"
echo "$COVERAGE"
