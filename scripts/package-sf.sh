#!/usr/bin/env bash
#
# SnapAgent SF 离线安装包打包脚本
#
# 用法: ./scripts/package-sf.sh
#
# 产出: sf-dist/snap-agent-sf-install-v{VERSION}.zip
#
# 打包内容:
#   - lib/  本地 Maven 文件仓库（jar + 独立 pom，CICD 无外网可直接用）
#   - pom-snippet.xml      粘贴到宿主 pom.xml 的 repository + dependency
#   - application-sf.yml   SF 默认配置（cc-switch 模型 + /app/deploy/skills）
#   - INTEGRATION-AI.md    AI 可执行集成指南
#   - README-SF.md         快速参考
#
# 版本号自动从 pom.xml 读取，无需手动指定。
# 后续发布新版本时只需改 pom.xml 版本后重新运行此脚本。

set -euo pipefail

# ---- 定位项目根目录 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATES_DIR="$SCRIPT_DIR/sf-templates"
cd "$PROJECT_ROOT"

# ---- 1. 读取版本号 ----
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null) || VERSION=""
if [ -z "$VERSION" ]; then
    echo "✗ 无法从 pom.xml 读取版本号"
    exit 1
fi
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  SnapAgent SF 离线安装包打包 — v${VERSION}                       ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ---- 2. 构建 jar ----
echo "▶ 构建 jar（跳过测试和 jacoco）..."
mvn clean install -DskipTests -Djacoco.skip=true -q
echo "✓ 构建完成"
echo ""

# 校验 jar 存在
CORE_JAR="snap-agent-core/target/snap-agent-core-${VERSION}.jar"
STARTER_JAR="snap-agent-spring-boot-2x-starter/target/snap-agent-spring-boot-2x-starter-${VERSION}.jar"
if [ ! -f "$CORE_JAR" ] || [ ! -f "$STARTER_JAR" ]; then
    echo "✗ jar 文件不存在: $CORE_JAR / $STARTER_JAR"
    exit 1
fi

# ---- 3. 准备输出目录 ----
SF_DIST="$PROJECT_ROOT/sf-dist"
STAGE_NAME="snap-agent-sf-install-v${VERSION}"
STAGE="$SF_DIST/${STAGE_NAME}"
echo "▶ 准备输出目录: sf-dist/${STAGE_NAME}"
rm -rf "$SF_DIST"
mkdir -p "$STAGE"

# ---- 4. 组装 lib/ Maven 文件仓库 ----
echo "▶ 组装 lib/ Maven 文件仓库..."
LIB_BASE="$STAGE/lib/com/watsontech/snapagent"
mkdir -p "$LIB_BASE/snap-agent-core/${VERSION}"
mkdir -p "$LIB_BASE/snap-agent-spring-boot-2x-starter/${VERSION}"

cp "$CORE_JAR" "$LIB_BASE/snap-agent-core/${VERSION}/"
cp "$STARTER_JAR" "$LIB_BASE/snap-agent-spring-boot-2x-starter/${VERSION}/"

# ---- 5. 生成独立 POM（与 release.yml 逻辑一致）----
# 独立 POM 不依赖 parent，列出完整依赖树，用于文件仓库场景。
# 注意：如果依赖列表变更，需同步更新 .github/workflows/release.yml。
echo "▶ 生成独立 POM..."

cat > "$LIB_BASE/snap-agent-core/${VERSION}/snap-agent-core-${VERSION}.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.watsontech.snapagent</groupId>
    <artifactId>snap-agent-core</artifactId>
    <version>${VERSION}</version>
    <packaging>jar</packaging>
    <name>SnapAgent Core</name>
    <description>Pure SPI layer: skill parsing, agent loop, LLM SPI, tool SPI</description>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>2.5.15</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><optional>true</optional></dependency>
        <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId><optional>true</optional></dependency>
    </dependencies>
</project>
EOF

cat > "$LIB_BASE/snap-agent-spring-boot-2x-starter/${VERSION}/snap-agent-spring-boot-2x-starter-${VERSION}.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>${VERSION}</version>
    <packaging>jar</packaging>
    <name>SnapAgent Spring Boot 2.x Starter</name>
    <description>AutoConfig + Controller + Filter + Security + Tools + LLM for Spring Boot 2.x</description>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>2.5.15</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
        <dependency><groupId>com.watsontech.snapagent</groupId><artifactId>snap-agent-core</artifactId><version>${VERSION}</version></dependency>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId><optional>true</optional></dependency>
        <dependency><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId><scope>provided</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId></dependency>
        <dependency><groupId>com.squareup.okhttp3</groupId><artifactId>okhttp</artifactId><optional>true</optional></dependency>
        <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
        <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId></dependency>
        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-core</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.apache.shiro</groupId><artifactId>shiro-core</artifactId><version>1.8.0</version><optional>true</optional></dependency>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-jdbc</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework.data</groupId><artifactId>spring-data-redis</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
    </dependencies>
</project>
EOF

# ---- 6. 复制并模板化 SF 配置文件 ----
echo "▶ 生成 SF 配置文件..."
for f in pom-snippet.xml application-sf.yml INTEGRATION-AI.md README-SF.md install-sf.sh; do
    if [ ! -f "$TEMPLATES_DIR/$f" ]; then
        echo "✗ 模板文件缺失: $TEMPLATES_DIR/$f"
        exit 1
    fi
    sed "s/__VERSION__/${VERSION}/g" "$TEMPLATES_DIR/$f" > "$STAGE/$f"
done
chmod +x "$STAGE/install-sf.sh"

# ---- 7. 打 zip ----
echo "▶ 打包 zip..."
cd "$SF_DIST"
zip -r -q "snap-agent-sf-install-v${VERSION}.zip" "$STAGE_NAME"
echo "✓ 打包完成"
echo ""

# ---- 8. 汇总 ----
ZIP_FILE="$SF_DIST/snap-agent-sf-install-v${VERSION}.zip"
ZIP_SIZE=$(ls -lh "$ZIP_FILE" | awk '{print $5}')
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ✓ 打包完成                                               ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  版本:  v${VERSION}                                            "
echo "║  zip:   sf-dist/snap-agent-sf-install-v${VERSION}.zip  (${ZIP_SIZE})  "
echo "║  目录:  sf-dist/${STAGE_NAME}/                              "
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "包内容:"
cd "$STAGE"
find . -type f | sort | sed 's|^./|  |'
