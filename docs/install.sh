#!/usr/bin/env bash
# SnapAgent 一键安装脚本
# 用法: curl -sL <script-url> | bash
# 或:   bash install.sh [MAVEN_GROUP_ID:MAVEN_ARTIFACT_ID:VERSION]

set -euo pipefail

# 默认坐标
COORDS="${1:-com.watsontech.snapagent:snap-agent-spring-boot-2x-starter:1.0.0-SNAPSHOT}"
GROUP_ID=$(echo "$COORDS" | cut -d: -f1)
ARTIFACT_ID=$(echo "$COORDS" | cut -d: -f2)
VERSION=$(echo "$COORDS" | cut -d: -f3)

echo "╔══════════════════════════════════════════════════════════╗"
echo "║          SnapAgent — 一键安装                          ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "坐标: ${GROUP_ID}:${ARTIFACT_ID}:${VERSION}"
echo ""

# 1. 检查 Maven
if ! command -v mvn &>/dev/null; then
    echo "✗ 未找到 mvn 命令，请先安装 Maven 3.6+"
    exit 1
fi
echo "✓ Maven: $(mvn --version | head -1)"

# 2. 克隆并安装到本地仓库
TMP_DIR=$(mktemp -d /tmp/snap-agent-install.XXXXXX)
trap "rm -rf $TMP_DIR" EXIT

echo ""
echo "→ 克隆仓库..."
git clone https://github.com/<your-org>/snap-agent.git "$TMP_DIR/snap-agent" 2>/dev/null || {
    echo "  远程克隆失败，尝试本地路径..."
    if [ -d "./snap-agent" ]; then
        cp -r ./snap-agent "$TMP_DIR/snap-agent"
    else
        echo "✗ 无法获取源码，请手动 git clone 后运行 mvn install"
        exit 1
    fi
}

echo "→ 构建并安装到本地 Maven 仓库..."
cd "$TMP_DIR/snap-agent"
mvn clean install -DskipTests -q

echo ""
echo "✓ 安装完成！"
echo ""
echo "→ 接下来在宿主项目中添加依赖："
echo ""
echo "  <dependency>"
echo "      <groupId>${GROUP_ID}</groupId>"
echo "      <artifactId>${ARTIFACT_ID}</artifactId>"
echo "      <version>${VERSION}</version>"
echo "  </dependency>"
echo ""
echo "→ 在 application.yml 中添加："
echo ""
echo "  snap-agent:"
echo "    enabled: true"
echo "    llm:"
echo "      api-key: \${LLM_API_KEY}"
echo "      model: claude-sonnet-4-6"
echo "    jdbc:"
echo "      enabled: true"
echo ""
echo "→ 提供只读 DataSource Bean (名称: snapAgentReadOnlyDataSource)"
echo ""
echo "详细集成文档: docs/INTEGRATION.md"
