#!/bin/bash
# sync-from-2x.sh — Regenerate the 3.x starter source from the 2.x starter.
#
# Copies all Java sources and resources from the 2.x starter, applying
# Jakarta EE namespace transformations:
#   javax.servlet    → jakarta.servlet
#   javax.annotation → jakarta.annotation
#   javax.mail       → jakarta.mail
#
# Run from the project root:
#   cd snap-agent-spring-boot-3x-starter && ./scripts/sync-from-2x.sh
#
# This script is idempotent — safe to re-run after 2.x changes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$MODULE_DIR")"
SB2_SRC="$PROJECT_ROOT/snap-agent-spring-boot-2x-starter/src/main"
SB3_DST="$MODULE_DIR/src/main"

echo "Syncing 3.x starter source from 2.x starter..."
echo "  Source: $SB2_SRC"
echo "  Target: $SB3_DST"

# Clean generated Java sources
rm -rf "$SB3_DST/java/cn"

# Copy Java sources
cp -r "$SB2_SRC/java/cn" "$SB3_DST/java/cn"

# Apply Jakarta EE namespace transformations
find "$SB3_DST/java" -name "*.java" -exec sed -i '' \
    -e 's/import javax\.servlet/import jakarta.servlet/g' \
    -e 's/import javax\.annotation/import jakarta.annotation/g' \
    -e 's/import javax\.mail/import jakarta.mail/g' \
    -e 's/javax\.servlet/jakarta.servlet/g' \
    {} +

# Copy resources — copy directory contents (not the directory itself) to avoid nesting
mkdir -p "$SB3_DST/resources/META-INF/spring"

for dir in chrome-extension docs scripts static; do
    if [ -d "$SB2_SRC/resources/$dir" ]; then
        mkdir -p "$SB3_DST/resources/$dir"
        cp -r "$SB2_SRC/resources/$dir/"* "$SB3_DST/resources/$dir/" 2>/dev/null || true
    fi
done

# Copy META-INF contents
if [ -d "$SB2_SRC/resources/META-INF" ]; then
    mkdir -p "$SB3_DST/resources/META-INF"
    # Copy files and subdirectories inside META-INF
    for item in "$SB2_SRC/resources/META-INF/"*; do
        [ -e "$item" ] || continue
        base="$(basename "$item")"
        [ "$base" = "spring" ] && continue  # Will regenerate below
        cp -r "$item" "$SB3_DST/resources/META-INF/" 2>/dev/null || true
    done
fi

# Regenerate Spring Boot 3.x AutoConfiguration.imports from spring.factories
SB3_IMPORTS="$SB3_DST/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
if [ -f "$SB3_DST/resources/META-INF/spring.factories" ]; then
    grep -v '^\s*$' "$SB3_DST/resources/META-INF/spring.factories" | \
        sed 's/\\$//' | \
        grep 'cn\.watsontech' | \
        sed 's/^[[:space:]]*//;s/,$//' > "$SB3_IMPORTS"
fi

echo ""
echo "Done. Transformed javax → jakarta in all Java sources."
echo "spring.factories: $SB3_DST/resources/META-INF/spring.factories"
echo "AutoConfiguration.imports: $SB3_IMPORTS"
echo ""
echo "To build: cd snap-agent-spring-boot-3x-starter && mvn compile (requires JDK 17+)"
