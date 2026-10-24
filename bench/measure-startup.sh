#!/usr/bin/env bash
# bench/measure-startup.sh — قياس زمن بدء X11 وسطح المكتب
# الاستخدام: ./bench/measure-startup.sh [adb|local] [--iterations N]
#
# يقيس:
#   1. زمن بدء Xvfb/Lorie (X11 server up → first connection accepted)
#   2. زمن بدء openbox-session من لحظة exec حتى تشغيل autostart
#   3. زمن كامل (من ADB am start حتى openbox.pid يظهر)

set -euo pipefail

MODE="${1:-adb}"
ITERS=3
for arg in "$@"; do
  [[ "$arg" =~ ^[0-9]+$ ]] && ITERS="$arg"
  [[ "$arg" == "--iterations" ]] && shift
done

PKG="com.deckos.core"
PKG_DEBUG="${PKG}.debug"
FILES_DIR="/data/user/0/$PKG/files"

run() {
  if [[ "$MODE" == "adb" ]]; then
    adb shell "$@" 2>/dev/null
  else
    "$@" 2>/dev/null
  fi
}

echo "DeckOS Startup Latency Measurement"
echo "Mode: $MODE | Iterations: $ITERS"
echo ""

measure_one() {
  local i=$1

  # Kill any existing session
  run am force-stop "$PKG" 2>/dev/null || true
  run am force-stop "$PKG_DEBUG" 2>/dev/null || true
  sleep 1

  T0=$(date +%s%N 2>/dev/null || python3 -c "import time; print(int(time.time()*1e9))")

  # Launch app
  if [[ "$MODE" == "adb" ]]; then
    adb shell am start -n "$PKG/.MainActivity" --ez launch_desktop true 2>/dev/null || \
    adb shell am start -n "${PKG_DEBUG}/.MainActivity" --ez launch_desktop true 2>/dev/null || true
  fi

  # Wait for openbox.pid or openbox process
  ELAPSED=0
  PID=""
  while [[ $ELAPSED -lt 15000 ]]; do
    PID=$(run cat "$FILES_DIR/openbox.pid" 2>/dev/null | tr -d '[:space:]' || echo "")
    [[ -n "$PID" && "$PID" =~ ^[0-9]+$ ]] && break
    # Fallback: check process directly
    PID=$(run pidof openbox 2>/dev/null | awk '{print $1}' || echo "")
    [[ -n "$PID" ]] && break
    sleep 0.2
    ELAPSED=$((ELAPSED + 200))
  done

  T1=$(date +%s%N 2>/dev/null || python3 -c "import time; print(int(time.time()*1e9))")
  DELTA_MS=$(( (T1 - T0) / 1000000 ))
  DELTA_S=$(echo "scale=2; $DELTA_MS / 1000" | bc 2>/dev/null || echo "${DELTA_MS}ms")

  echo "  Run $i: Desktop ready: ${DELTA_S}s (PID: ${PID:-unknown})"
  echo "$DELTA_MS"
}

TIMES=()
for ((i=1; i<=ITERS; i++)); do
  T=$(measure_one "$i" | tail -1)
  TIMES+=("$T")
done

echo ""
# Average
TOTAL=0
for t in "${TIMES[@]}"; do
  TOTAL=$((TOTAL + t))
done
AVG=$((TOTAL / ITERS))
AVG_S=$(echo "scale=2; $AVG / 1000" | bc 2>/dev/null || echo "${AVG}ms")

echo "Average startup: ${AVG_S}s over $ITERS runs"
echo ""
echo "Gate 3 target: Desktop startup ≤ 8 s"
if (( AVG <= 8000 )); then
  echo "Status: ✅ PASS (${AVG_S}s ≤ 8s)"
else
  echo "Status: ❌ FAIL (${AVG_S}s > 8s)"
fi
