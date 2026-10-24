#!/usr/bin/env bash
# bench/apk-size.sh — تقرير حجم APK ومكتباته
# يعمل محلياً من مجلد المشروع

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Find the latest debug APK
APK=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | sort | tail -1)
if [[ -z "$APK" ]]; then
  echo "No APK found. Run: ./gradlew :app:assembleDebug"
  exit 1
fi

echo "APK: $APK"
APK_BYTES=$(stat -c%s "$APK")
APK_MB=$(python3 -c "print(f'{$APK_BYTES / (1024*1024):.1f}')")
echo "APK total: ${APK_MB}MB (${APK_BYTES} bytes)"
echo ""

# List .so files inside APK
echo "Native libraries (.so) inside APK:"
unzip -l "$APK" 'lib/**/*.so' 2>/dev/null | \
  awk 'NR>3 && /\.so$/ {printf "  %-55s %s MB\n", $4, int($1/1024/10.24)/100}' | sort -t'M' -k1 -rn 2>/dev/null || \
  unzip -l "$APK" 2>/dev/null | grep '\.so' | \
  awk '{printf "  %-55s %d KB\n", $4, int($1/1024)}'

echo ""
# Check res/ and assets/
echo "Assets:"
unzip -l "$APK" 'assets/**' 2>/dev/null | tail -5 || echo "  (none)"

echo ""
echo "Gate 3 target: APK ≤ 70 MB"
if python3 -c "import sys; sys.exit(0 if float('$APK_MB') <= 70 else 1)"; then
  echo "Status: ✅ PASS (${APK_MB}MB ≤ 70MB)"
else
  echo "Status: ❌ FAIL (${APK_MB}MB > 70MB) — needs trimming"
fi
