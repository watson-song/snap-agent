#!/bin/bash
# 验证知识文档中的代码引用

DOCS_DIR="snap-agent-standalone/src/main/resources/docs/knowledge"
ERRORS=0

echo "=== 验证代码引用 ==="

for doc in "$DOCS_DIR"/*.md; do
  echo "Checking: $(basename $doc)"
  
  # 提取类名引用
  grep -oE '\b[A-Z][a-zA-Z]+\.java\b' "$doc" 2>/dev/null | while read class_file; do
    class_name="${class_file%.java}"
    if ! find snap-agent-*/src/main/java -name "${class_name}.java" 2>/dev/null | grep -q .; then
      echo "  ⚠️  Class not found: $class_name"
      ((ERRORS++))
    fi
  done
done

echo ""
echo "总计 $ERRORS 个问题"
