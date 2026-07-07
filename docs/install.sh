#!/usr/bin/env bash
# SnapAgent 一键安装脚本 (GitHub Release)
# 用法: bash install.sh
# 从 GitHub Release 下载 JAR+POM 并安装到本地 Maven 仓库

set -euo pipefail

VERSION="0.1"
RELEASE_URL="https://github.com/watson-song/snap-agent/releases/download/v${VERSION}"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║          SnapAgent — 一键安装 (v${VERSION})                    ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# 1. 检查 Maven
if ! command -v mvn &>/dev/null; then
    echo "✗ 未找到 mvn 命令，请先安装 Maven 3.6+"
    exit 1
fi
echo "✓ Maven: $(mvn --version | head -1)"

# 2. 临时目录
TMP_DIR=$(mktemp -d /tmp/snap-agent-install.XXXXXX)
trap "rm -rf $TMP_DIR" EXIT

echo ""
echo "→ 下载 SnapAgent artifacts..."
cd "$TMP_DIR"

curl -sL "${RELEASE_URL}/snap-agent-core-${VERSION}.jar" -o "snap-agent-core-${VERSION}.jar"
curl -sL "${RELEASE_URL}/snap-agent-core-${VERSION}.pom" -o "snap-agent-core-${VERSION}.pom"
curl -sL "${RELEASE_URL}/snap-agent-spring-boot-2x-starter-${VERSION}.jar" -o "snap-agent-spring-boot-2x-starter-${VERSION}.jar"
curl -sL "${RELEASE_URL}/snap-agent-spring-boot-2x-starter-${VERSION}.pom" -o "snap-agent-spring-boot-2x-starter-${VERSION}.pom"

echo "→ 安装到本地 Maven 仓库..."

# 3. 先装 core
mvn install:install-file \
  -Dfile="snap-agent-core-${VERSION}.jar" \
  -DpomFile="snap-agent-core-${VERSION}.pom" \
  -DgroupId=cn.watsontech.snapagent \
  -DartifactId=snap-agent-core \
  -Dversion="${VERSION}" \
  -Dpackaging=jar -q

# 4. 再装 starter
mvn install:install-file \
  -Dfile="snap-agent-spring-boot-2x-starter-${VERSION}.jar" \
  -DpomFile="snap-agent-spring-boot-2x-starter-${VERSION}.pom" \
  -DgroupId=cn.watsontech.snapagent \
  -DartifactId=snap-agent-spring-boot-2x-starter \
  -Dversion="${VERSION}" \
  -Dpackaging=jar -q

echo ""
echo "✓ 安装完成！"
echo ""
echo "→ 在宿主项目中添加依赖："
echo ""
echo "  <dependency>"
echo "      <groupId>cn.watsontech.snapagent</groupId>"
echo "      <artifactId>snap-agent-spring-boot-2x-starter</artifactId>"
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
echo "详细集成文档: https://github.com/watson-song/snap-agent#readme"
