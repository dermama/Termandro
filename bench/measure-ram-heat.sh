#!/usr/bin/env bash
# bench/measure-ram-heat.sh — قياس RAM والحرارة (30 دقيقة أو --quick لـ 30 ثانية)
# الاستخدام: ./bench/measure-ram-heat.sh [adb|local] [--quick] [--full]

set -euo pipefail

MODE="${1:-adb}"
DURATION=1800  # 30 minutes
INTERVAL=30    # sample every 30s

for arg in "$@"; do
  [[ "$arg" == "--quick" ]] && DURATION=60  && INTERVAL=5
  [[ "$arg" == "--full"  ]] && DURATION=1800 && INTERVAL=30
done

PKG="com.deckos.core"

run() {
  if [[ "$MODE" == "adb" ]]; then
    adb shell "$@" 2>/dev/null
  else
    "$@" 2>/dev/null
  fi
}

echo "DeckOS RAM & Thermal Monitor"
echo "Mode: $MODE | Duration: ${DURATION}s | Sample interval: ${INTERVAL}s"
echo ""
printf "%-12s %-15s %-15s %-12s %-10s\n" "Time(s)" "App PSS(MB)" "Total PSS(MB)" "Thermal(°C)" "Status"
echo "----------------------------------------------------------------------"

START=$(date +%s)
PEAK_PSS=0
SAMPLE_COUNT=0
PSS_SUM=0

while true; do
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  [[ $ELAPSED -ge $DURATION ]] && break

  # App memory
  APP_PSS=$(run dumpsys meminfo "$PKG" 2>/dev/null | grep -oP 'TOTAL PSS:\s+\K[0-9]+' | head -1 || echo "0")
  APP_PSS_MB=$(echo "scale=1; ${APP_PSS:-0} / 1024" | bc 2>/dev/null || echo "?")

  # Total system memory used by deckos processes
  # Using /proc/meminfo approach
  TOTAL_PSS=$(run dumpsys meminfo "$PKG" 2>/dev/null | grep -oP 'TOTAL:\s+\K[0-9]+' | head -1 || echo "0")
  TOTAL_PSS_MB=$(echo "scale=1; ${TOTAL_PSS:-0} / 1024" | bc 2>/dev/null || echo "?")

  # Thermal (battery temp as proxy for SoC temp)
  THERMAL=$(run dumpsys battery 2>/dev/null | grep -oP 'temperature: \K[0-9]+' | head -1 || echo "0")
  THERMAL_C=$(echo "scale=1; ${THERMAL:-0} / 10" | bc 2>/dev/null || echo "?")

  # Track peak
  if [[ "$TOTAL_PSS" =~ ^[0-9]+$ ]] && (( TOTAL_PSS > PEAK_PSS )); then
    PEAK_PSS=$TOTAL_PSS
  fi
  PSS_SUM=$((PSS_SUM + ${TOTAL_PSS:-0}))
  SAMPLE_COUNT=$((SAMPLE_COUNT + 1))

  # Gate check
  STATUS="✅"
  TOTAL_PSS_INT=${TOTAL_PSS_MB%.*}
  [[ "${TOTAL_PSS_INT:-0}" -gt 250 ]] 2>/dev/null && STATUS="⚠️"

  printf "%-12s %-15s %-15s %-12s %-10s\n" \
    "${ELAPSED}s" "${APP_PSS_MB}MB" "Total PSS: ${TOTAL_PSS_MB}MB" "${THERMAL_C}°C" "$STATUS"

  sleep "$INTERVAL"
done

echo ""
echo "=== Summary ==="
PEAK_MB=$(echo "scale=1; $PEAK_PSS / 1024" | bc 2>/dev/null || echo "?")
AVG_PSS=$(echo "scale=1; $PSS_SUM / $SAMPLE_COUNT / 1024" | bc 2>/dev/null || echo "?")
echo "Peak RAM PSS:   ${PEAK_MB} MB"
echo "Average PSS:    ${AVG_PSS} MB"
echo "Samples:        $SAMPLE_COUNT over ${DURATION}s"
echo ""
echo "Gate 3 target: RAM PSS ≤ 250 MB"
PEAK_INT=${PEAK_MB%.*}
if (( ${PEAK_INT:-9999} <= 250 )) 2>/dev/null; then
  echo "Status: ✅ PASS"
else
  echo "Status: ⚠️  Peak ${PEAK_MB}MB — check for leaks"
fi
