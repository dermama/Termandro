#!/usr/bin/env bash
# bench/run-all.sh — DeckOS شامل قياس الأداء
# الاستخدام: ./bench/run-all.sh [--adb | --local]
# --adb   : تشغيل على الجهاز عبر ADB (افتراضي إذا اتُصل بالجهاز)
# --local : تشغيل مباشرة داخل Termux على الهاتف

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIMESTAMP="$(date +%Y-%m-%d_%H-%M)"
RESULTS_DIR="$SCRIPT_DIR/results/$TIMESTAMP"
mkdir -p "$RESULTS_DIR"

log()  { echo "[bench] $*" | tee -a "$RESULTS_DIR/run-all.log"; }
hr()   { echo "-------------------------------------------" | tee -a "$RESULTS_DIR/run-all.log"; }

MODE="auto"
for arg in "$@"; do
  case "$arg" in
    --adb)   MODE="adb" ;;
    --local) MODE="local" ;;
  esac
done

# Detect ADB device
if [[ "$MODE" == "auto" ]]; then
  if command -v adb &>/dev/null && adb devices 2>/dev/null | grep -q "device$"; then
    MODE="adb"
  else
    MODE="local"
  fi
fi

log "DeckOS Bench — $TIMESTAMP"
log "Mode: $MODE"
hr

# ── 1. APK size ─────────────────────────────────────────────────────────────
log "[L-50/L-51] APK Size Report"
"$SCRIPT_DIR/apk-size.sh" 2>&1 | tee "$RESULTS_DIR/apk-size.txt"
hr

# ── 2. Runtime disk usage ────────────────────────────────────────────────────
log "[L-51] Runtime Disk Usage"
"$SCRIPT_DIR/runtime-disk.sh" "$MODE" 2>&1 | tee "$RESULTS_DIR/runtime-disk.txt"
hr

# ── 3. Startup latency ───────────────────────────────────────────────────────
log "[L-52/L-53] Startup Latency"
"$SCRIPT_DIR/measure-startup.sh" "$MODE" 2>&1 | tee "$RESULTS_DIR/startup.txt"
hr

# ── 4. RAM snapshot ──────────────────────────────────────────────────────────
log "[L-57] RAM Snapshot (30-second sample)"
"$SCRIPT_DIR/measure-ram-heat.sh" "$MODE" --quick 2>&1 | tee "$RESULTS_DIR/ram-heat.txt"
hr

# ── 5. Summary ───────────────────────────────────────────────────────────────
log "Results saved to: $RESULTS_DIR"
log "Gate 3 targets: APK≤70MB  runtime≤900MB  RAM≤250MB  desktop≤8s"

# Extract key numbers into a summary table
APK_SIZE=$(grep -oP 'APK total: \K[0-9.]+[KMG]B' "$RESULTS_DIR/apk-size.txt" 2>/dev/null || echo "?")
RUNTIME_SIZE=$(grep -oP 'files dir: \K[0-9.]+[KMG]B' "$RESULTS_DIR/runtime-disk.txt" 2>/dev/null || echo "?")
RAM_PSS=$(grep -oP 'Total PSS: \K[0-9]+' "$RESULTS_DIR/ram-heat.txt" 2>/dev/null | head -1 || echo "?")
STARTUP=$(grep -oP 'Desktop ready: \K[0-9.]+s' "$RESULTS_DIR/startup.txt" 2>/dev/null || echo "?")

{
  echo "DeckOS Bench Summary — $TIMESTAMP"
  echo "========================================"
  printf "%-25s %10s %10s %6s\n" "Metric" "Actual" "Target" "Pass?"
  echo "----------------------------------------"
  printf "%-25s %10s %10s %6s\n" "APK size"          "$APK_SIZE"      "≤70 MB"  "?"
  printf "%-25s %10s %10s %6s\n" "Runtime on-disk"   "$RUNTIME_SIZE"  "≤900 MB" "?"
  printf "%-25s %10s %10s %6s\n" "RAM PSS (MB)"      "${RAM_PSS} MB"  "≤250 MB" "?"
  printf "%-25s %10s %10s %6s\n" "Desktop startup"   "$STARTUP"       "≤8 s"    "?"
} | tee "$RESULTS_DIR/SUMMARY.txt"

log "Done."
