#!/usr/bin/env bash
# SnapAgent 发版脚本
#
# 用法:
#   ./scripts/release.sh 0.3.0                     # 只准备 release commit + tag（推荐，推 tag 后由 CI 发布）
#   ./scripts/release.sh 0.3.0 --next 0.4.0        # 同时准备下一个开发版本 0.4.0-SNAPSHOT
#   ./scripts/release.sh 0.3.0 --deploy-local      # 本地直接部署到 Central Portal（需本机配好 GPG + settings.xml）
#   ./scripts/release.sh 0.3.0 --skip-tests        # 跳过测试（不推荐）
#   ./scripts/release.sh 0.3.0 --dry-run           # 只打印将执行的命令，不改动任何东西
#
# 前置条件（仅 --deploy-local 需要）:
#   1. 本机已安装 gpg，公钥已上传到 keys.openpgp.org / keyserver.ubuntu.com
#   2. ~/.m2/settings.xml 配置了 <server><id>central</id> 的 Portal User Token
#   3. ~/.m2/settings.xml 的 mirror 不能用 mirrorOf=*（会拦截部署请求）
#
# 流程（默认模式）:
#   1. 校验版本号格式 / git 工作区干净 / 当前分支
#   2. mvn versions:set 把 parent + 3 个模块改为 <version>，demo 用 sed 同步
#   3. mvn clean verify 跑完整测试 + jacoco 覆盖率检查
#   4. git commit "release: v<version>" + git tag v<version>
#   5. （可选）改为下一个 -SNAPSHOT 版本并 commit
#   6. 打印推送指令；推送后 GitHub Actions 自动发布到 Maven Central + 创建 GitHub Release

set -euo pipefail

# ── 颜色 ─────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}→${NC} $*"; }
ok()    { echo -e "${GREEN}✓${NC} $*"; }
warn()  { echo -e "${YELLOW}!${NC} $*"; }
die()   { echo -e "${RED}✗ $*${NC}" >&2; exit 1; }

# ── 参数解析 ─────────────────────────────────────────────────────────
VERSION=""
NEXT_VERSION=""
DEPLOY_LOCAL=false
SKIP_TESTS=false
DRY_RUN=false

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --next)         NEXT_VERSION="${2:?--next 需要一个版本号}"; shift 2 ;;
        --deploy-local) DEPLOY_LOCAL=true; shift ;;
        --skip-tests)   SKIP_TESTS=true; shift ;;
        --dry-run)      DRY_RUN=true; shift ;;
        -h|--help)      usage 0 ;;
        -*)             die "未知参数: $1（-h 查看用法）" ;;
        *)              [[ -z "$VERSION" ]] || die "只能指定一个版本号"
                        VERSION="$1"; shift ;;
    esac
done

[[ -n "$VERSION" ]] || usage 1
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "版本号必须是 x.y.z 格式（如 0.3.0），且不能带 -SNAPSHOT: '$VERSION'"
if [[ -n "$NEXT_VERSION" ]]; then
    [[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
        || die "--next 版本号必须是 x.y.z 格式（脚本会自动加 -SNAPSHOT）: '$NEXT_VERSION'"
    [[ "$NEXT_VERSION" != "$VERSION" ]] || die "--next 不能与 release 版本相同"
fi
NEXT_SNAPSHOT="${NEXT_VERSION:+${NEXT_VERSION}-SNAPSHOT}"

# ── 定位仓库根目录 ───────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"
[[ -f pom.xml ]] || die "未找到根 pom.xml: $ROOT_DIR"

run() {
    if $DRY_RUN; then
        echo -e "${YELLOW}[dry-run]${NC} $*"
    else
        "$@"
    fi
}

# ── 前置检查 ─────────────────────────────────────────────────────────
info "前置检查..."

command -v mvn  >/dev/null || die "未找到 mvn，请先安装 Maven 3.6+"
command -v git  >/dev/null || die "未找到 git"
ok "maven: $(mvn --version | head -1)"

$DRY_RUN || {
    [[ -z "$(git status --porcelain)" ]] || die "git 工作区不干净，请先 commit 或 stash"
    git rev-parse --verify "refs/tags/v${VERSION}" >/dev/null 2>&1 \
        && die "tag v${VERSION} 已存在。如需重发请先删除: git tag -d v${VERSION} && git push origin :v${VERSION}"
}
ok "git 工作区干净，tag v${VERSION} 未占用"

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
info "当前分支: $CURRENT_BRANCH"

if $DEPLOY_LOCAL; then
    command -v gpg >/dev/null || die "--deploy-local 需要本机 gpg（brew install gnupg）"
    [[ -n "$(gpg --list-secret-keys 2>/dev/null)" ]] \
        || die "未找到 GPG 私钥。先生成: gpg --full-generate-key，并把公钥上传到 keyserver"
    ok "gpg: $(gpg --list-secret-keys --keyid-format=long | grep '^sec' | head -1 | awk '{print $2}')"
fi

# ── 第 1 步：设置 release 版本号 ─────────────────────────────────────
info "设置版本号为 ${VERSION} ..."
run mvn -q versions:set -DnewVersion="${VERSION}" -DprocessAllModules=true -DgenerateBackupPoms=false
# snap-agent-demo 是独立 pom（不在父 modules 中），用 sed 同步自身版本和 starter 依赖版本
run sed -i '' \
    -e "/<artifactId>snap-agent-demo<\/artifactId>/{n;s|<version>[^<]*</version>|<version>${VERSION}</version>|;}" \
    -e "/<artifactId>snap-agent-spring-boot-2x-starter<\/artifactId>/{n;s|<version>[^<]*</version>|<version>${VERSION}</version>|;}" \
    snap-agent-demo/pom.xml
$DRY_RUN || grep -q "<version>${VERSION}</version>" snap-agent-demo/pom.xml \
    || die "demo pom 版本号 sed 替换失败，请手动检查 snap-agent-demo/pom.xml"
ok "所有模块版本 → ${VERSION}"

# ── 第 2 步：构建 + 测试 ─────────────────────────────────────────────
if $SKIP_TESTS; then
    warn "跳过测试（--skip-tests）"
    run mvn clean install -DskipTests -Djacoco.skip=true -q
else
    info "运行完整构建（全量测试 + jacoco 覆盖率检查）..."
    run mvn clean verify -q
fi
ok "构建通过"

# ── 第 3 步：本地部署（可选） ────────────────────────────────────────
if $DEPLOY_LOCAL; then
    info "部署到 Central Publisher Portal ..."
    run mvn clean deploy -Prelease -DskipTests -Djacoco.skip=true
    ok "已发布到 Maven Central（全球同步约 15~30 分钟）"
fi

# ── 第 4 步：commit + tag ────────────────────────────────────────────
info "创建 release commit 和 tag ..."
run git add -A
run git commit -m "release: v${VERSION}"
run git tag -a "v${VERSION}" -m "SnapAgent v${VERSION}"
ok "tag v${VERSION} 已创建"

# ── 第 5 步：下一个开发版本（可选） ──────────────────────────────────
if [[ -n "$NEXT_SNAPSHOT" ]]; then
    info "设置下一个开发版本 ${NEXT_SNAPSHOT} ..."
    run mvn -q versions:set -DnewVersion="${NEXT_SNAPSHOT}" -DprocessAllModules=true -DgenerateBackupPoms=false
    run sed -i '' \
        -e "/<artifactId>snap-agent-demo<\/artifactId>/{n;s|<version>[^<]*</version>|<version>${NEXT_SNAPSHOT}</version>|;}" \
        -e "/<artifactId>snap-agent-spring-boot-2x-starter<\/artifactId>/{n;s|<version>[^<]*</version>|<version>${NEXT_SNAPSHOT}</version>|;}" \
        snap-agent-demo/pom.xml
    run git add -A
    run git commit -m "chore: start ${NEXT_SNAPSHOT} development"
    ok "开发版本 → ${NEXT_SNAPSHOT}"
fi

# ── 完成，打印后续指令 ───────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║              Release v${VERSION} 准备完成                       ${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
echo ""
if $DEPLOY_LOCAL; then
    echo "  已本地发布。推送 commit + tag 以触发 GitHub Release workflow:"
    echo ""
    echo -e "    ${CYAN}git push origin ${CURRENT_BRANCH} && git push origin v${VERSION}${NC}"
else
    echo "  推送 commit + tag，GitHub Actions 会自动:"
    echo "    1. maven-central.yml → 发布到 Maven Central"
    echo "    2. release.yml       → 创建 GitHub Release (jar + install.sh)"
    echo ""
    echo -e "    ${CYAN}git push origin ${CURRENT_BRANCH} && git push origin v${VERSION}${NC}"
fi
echo ""
echo "  发布后验证:"
echo "    https://central.sonatype.com/artifact/cn.watsontech.snapagent/snap-agent-core"
echo "    https://github.com/watson-song/snap-agent/actions"
echo ""
echo "  如需撤销本地改动（推送前）:"
echo "    git tag -d v${VERSION} && git reset --hard origin/${CURRENT_BRANCH}"
echo ""
