#!/usr/bin/env bash
#
# Install SnapAgent git hooks into .git/hooks/
# Run once per clone: ./scripts/install-hooks.sh
#
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOKS_DIR="$REPO_ROOT/.git/hooks"
SOURCE_DIR="$REPO_ROOT/scripts/git-hooks"

echo "Installing SnapAgent git hooks..."

# Ensure hooks dir exists
mkdir -p "$HOOKS_DIR"

# Install each hook
for hook in pre-commit commit-msg; do
  SRC="$SOURCE_DIR/$hook"
  DST="$HOOKS_DIR/$hook"

  if [ ! -f "$SRC" ]; then
    echo "  ⚠ Source not found: $SRC — skipping"
    continue
  fi

  cp "$SRC" "$DST"
  chmod +x "$DST"
  echo "  ✅ Installed: $hook → $DST"
done

echo ""
echo "Done! Hooks are active for this repository."
echo ""
echo "Hooks installed:"
echo "  • pre-commit  — blocks bugfix/feature commits without test changes"
echo "  • commit-msg  — enforces conventional commit format"
echo ""
echo "To bypass for emergency commits (NOT recommended):"
echo "  git commit --no-verify"
echo ""
echo "To uninstall:"
echo "  rm .git/hooks/pre-commit .git/hooks/commit-msg"
