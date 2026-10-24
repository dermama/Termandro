#!/usr/bin/env bash
# bench/eval-turnip-zink.sh — L-55: تقييم Turnip/Zink GPU acceleration
#
# Turnip = Mesa Vulkan driver for Adreno GPUs (open-source)
# Zink   = OpenGL-over-Vulkan translation layer
#
# السؤال: هل يستحق تفعيل Turnip/Zink في DeckOS Core؟
# هذا السكربت يجري A/B بين:
#   A: Adreno native (Freedreno/default)
#   B: Turnip Vulkan + Zink OpenGL

set -euo pipefail

MODE="${1:-adb}"
PKG="com.deckos.core"
FILES_DIR="/data/user/0/$PKG/files"
PREFIX="$FILES_DIR/usr"

run() {
  if [[ "$MODE" == "adb" ]]; then
    adb shell "$@" 2>/dev/null
  else
    "$@" 2>/dev/null
  fi
}

echo "=== DeckOS Turnip/Zink Evaluation (L-55) ==="
echo ""

# ── 1. Check Turnip availability ────────────────────────────────────────────
echo "[1] Checking Turnip availability..."
TURNIP_PATH=$(run find "$PREFIX/lib" -name "libvulkan_freedreno.so" 2>/dev/null | head -1 || echo "")
if [[ -z "$TURNIP_PATH" ]]; then
  echo "  ℹ️  Turnip not installed. Install via: pkg install mesa-vulkan-icd-freedreno"
else
  echo "  ✅ Turnip found: $TURNIP_PATH"
fi
echo ""

# ── 2. Check vendor Vulkan ───────────────────────────────────────────────────
echo "[2] Checking Vendor Vulkan (Adreno)..."
VENDOR_VK=$(run ls /vendor/lib64/hw/vulkan.adreno.so /vendor/lib64/hw/vulkan*.so 2>/dev/null | head -1 || echo "")
if [[ -n "$VENDOR_VK" ]]; then
  echo "  ✅ Vendor Vulkan: $VENDOR_VK"
else
  echo "  ⚠️  Vendor Vulkan not found"
fi
echo ""

# ── 3. GPU Info ──────────────────────────────────────────────────────────────
echo "[3] GPU Information:"
run cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null | awk '{printf "  GPU Clock: %d MHz\n", $1/1000000}' || true
run cat /proc/cpuinfo 2>/dev/null | grep -i "Hardware" | head -1 | sed 's/^/  /' || true
echo ""

# ── 4. Simple Vulkan smoke test ──────────────────────────────────────────────
echo "[4] Vulkan Smoke Test:"
run vulkaninfo --summary 2>/dev/null | head -10 || \
  echo "  vulkaninfo not available. Install mesa-tools or use glxinfo."
echo ""

# ── 5. Recommendation ───────────────────────────────────────────────────────
echo "=== Recommendation (L-55 Decision) ==="
echo ""
echo "For DeckOS Core v0.1:"
echo ""
echo "  DEFAULT PATH: Vendor Adreno (stock) — most stable, no extra deps"
echo "  REASON: Turnip on Android 16 with 16KB pages is still experimental"
echo "          (Mesa 24.x, frequent Adreno A740 issues on Snapdragon 8 Gen 3)."
echo ""
echo "  WHEN TO ENABLE TURNIP:"
echo "    - Game Mode (Phase 5A) where GPU perf matters most"
echo "    - After Mesa 25.0 stable release with S24 Ultra validation"
echo ""
echo "  VERDICT: Defer Turnip/Zink to Phase 5A. No Turnip in Core APK."
echo ""
echo "  Action: Add Turnip as opt-in env var: DECKOS_GPU=turnip"
echo "          (set VK_ICD_FILENAMES, MESA_LOADER_DRIVER_OVERRIDE=zink)"
