#!/usr/bin/env bash
# bench/runtime-disk.sh — قياس حجم بيئة Termux على الجهاز
# الاستخدام: ./bench/runtime-disk.sh [adb|local]

set -euo pipefail

MODE="${1:-adb}"
PKG="com.deckos.core"
FILES_DIR="/data/user/0/$PKG/files"

run() {
  if [[ "$MODE" == "adb" ]]; then
    adb shell "$@" 2>/dev/null
  else
    # Running inside Termux/device
    "$@" 2>/dev/null
  fi
}

echo "DeckOS Runtime Disk Usage ($MODE mode)"
echo "Package: $PKG"
echo "Files dir: $FILES_DIR"
echo ""

# Total size
TOTAL=$(run du -sh "$FILES_DIR" 2>/dev/null | awk '{print $1}' || echo "N/A")
echo "files dir: $TOTAL"
echo ""

# Per-subdirectory breakdown
echo "Subdirectory breakdown:"
run du -sh "$FILES_DIR/usr/bin" 2>/dev/null | awk '{printf "  %-20s %s\n", "usr/bin", $1}' || true
run du -sh "$FILES_DIR/usr/lib" 2>/dev/null | awk '{printf "  %-20s %s\n", "usr/lib", $1}' || true
run du -sh "$FILES_DIR/usr/share" 2>/dev/null | awk '{printf "  %-20s %s\n", "usr/share", $1}' || true
run du -sh "$FILES_DIR/usr/etc" 2>/dev/null | awk '{printf "  %-20s %s\n", "usr/etc", $1}' || true
run du -sh "$FILES_DIR/usr/var" 2>/dev/null | awk '{printf "  %-20s %s\n", "usr/var", $1}' || true
run du -sh "$FILES_DIR/home" 2>/dev/null | awk '{printf "  %-20s %s\n", "home", $1}' || true
echo ""

# File count
FILE_COUNT=$(run find "$FILES_DIR" -type f 2>/dev/null | wc -l || echo "?")
echo "Total files: $FILE_COUNT"
echo ""

echo "Gate 3 target: runtime ≤ 900 MB"
# Check
TOTAL_MB=$(run du -sm "$FILES_DIR" 2>/dev/null | awk '{print $1}' || echo "9999")
if [[ "$TOTAL_MB" != "N/A" ]] && (( TOTAL_MB <= 900 )); then
  echo "Status: ✅ PASS (${TOTAL_MB}MB ≤ 900MB)"
else
  echo "Status: ⚠️  ${TOTAL_MB}MB — trimming needed (target ≤ 900MB)"
fi
