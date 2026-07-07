#!/usr/bin/env bash
#
# SnapAgent SF 一键安装脚本
#
# 用法:
#   bash install-sf.sh [宿主项目路径]
#
# 不传路径则默认当前目录。脚本会：
#   1. 把 lib/（Maven 文件仓库）复制到宿主项目根目录
#   2. 把 application-sf.yml 和 README.md 复制到宿主项目根目录
#   3. 打印一段 AI 提示词，复制给 Claude Code 即可自动完成剩余集成
#
# 剩余的代码改动（pom.xml 依赖、DataSource Bean、Security 放行等）
# 由 Claude Code 读 README.md 自动完成。

set -euo pipefail

# 定位安装包目录（脚本自身所在目录）
PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${1:-$(pwd)}"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  SnapAgent SF 安装脚本                                    ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# 校验目标是 Maven 项目
if [ ! -f "$TARGET_DIR/pom.xml" ]; then
    echo "✗ 目标目录没有 pom.xml，不是 Maven 项目: $TARGET_DIR"
    echo "  用法: bash install-sf.sh /path/to/your/spring-boot-project"
    exit 1
fi
echo "✓ 目标项目: $TARGET_DIR"
echo ""

# 1. 复制 lib/ Maven 文件仓库
echo "▶ 复制 lib/ 到 $TARGET_DIR/lib/ ..."
if [ -d "$TARGET_DIR/lib" ]; then
    echo "  ⚠ lib/ 已存在，将合并覆盖 SnapAgent 相关文件"
fi
mkdir -p "$TARGET_DIR/lib/cn/watsontech/snapagent"
cp -r "$PKG_DIR/lib/cn/watsontech/snapagent/"* "$TARGET_DIR/lib/cn/watsontech/snapagent/"
echo "✓ lib/ 已复制（Maven 会从这里解析 snap-agent jar，不访问外网）"
echo ""

# 2. 复制参考文件
echo "▶ 复制配置参考文件..."
cp "$PKG_DIR/application-sf.yml" "$TARGET_DIR/application-sf.yml"
cp "$PKG_DIR/README.md" "$TARGET_DIR/snap-agent-README.md"
cp "$PKG_DIR/pom-snippet.xml" "$TARGET_DIR/pom-snippet.xml"
echo "✓ 已复制: application-sf.yml, snap-agent-README.md, pom-snippet.xml"
echo ""

# 3. 打印 AI 提示词
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ✓ 机械部分完成，接下来交给 Claude Code                    ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  已复制到项目根目录:                                       ║"
echo "║    lib/                  ← Maven 本地仓库（jar+pom）       ║"
echo "║    application-sf.yml    ← 默认配置（cc-switch 模型）       ║"
echo "║    snap-agent-README.md  ← AI 集成指南（8 步 + 验证 + 排查）║"
echo "║    pom-snippet.xml       ← pom.xml 要粘贴的片段            ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " 在 Claude Code 中粘贴以下提示词："
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "按 snap-agent-README.md 把 SnapAgent 集成到当前项目。先问我要两个信息：1) 工号用于 auth-token；2) 项目前端 token 存在哪里（localStorage 还是 cookie？header 名和 key/cookie 名是什么？）如果项目用 session 鉴权则跳过第 2 个。然后逐步执行步骤 2 到 9，每步完成后向我确认。特别注意步骤 3b：检查项目根目录是否有 docs/skills/ 目录，如果有则自动在 pom.xml 中添加 resources 配置把宿主自有 Skill 打入 classpath。完成后执行验证和排查部分。额外检查：如果验证时 user-info 返回 authorized: false，检查宿主项目的权限是否存在 principal 自定义字段（如 LoginUser.permissionList）而非 GrantedAuthority 中，如果是则按问题排查 §6 创建自定义 SecurityGateway bean。"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Claude Code 会读 snap-agent-README.md，自动完成："
echo "  • pom.xml 添加 repository + dependency"
echo "  • 检查 docs/skills/ 并配置 Maven resources（如有宿主自有 Skill）"
echo "  • application.yml 合并 snap-agent 配置（含问你工号）"
echo "  • 提示你创建只读 DataSource Bean"
echo "  • Spring Security 放行 /snap-agent/**"
echo "  • SSE 响应包装过滤器豁免"
echo "  • Nginx 代理配置"
echo "  • 启动验证"
echo ""
