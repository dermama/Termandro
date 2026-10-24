#!/usr/bin/env bash
# bench/trim-runtime.sh — تقليم بيئة Termux للوصول إلى هدف Gate 3 (≤900MB)
#
# الاستخدام:
#   ./bench/trim-runtime.sh --dry-run   # معاينة ما سيُحذف
#   ./bench/trim-runtime.sh --apply     # تطبيق الحذف
#
# القواعد المتبعة:
#   1. حذف ملفات doc / man / locale غير المستخدمة
#   2. حذف ملفات .a (مكتبات ثابتة) — لا تُستخدم في runtime
#   3. حذف ملفات gzip/bz2 لـ dpkg غير اللازمة
#   4. حذف تعريفات header (.h) — لا تُستخدم إلا في تجميع
#   5. حذف ملفات تطوير pkg-config (.pc)
#   6. حذف ملفات الاختبار في share/
#   7. تجريد مكتبات .so من debug symbols (strip)
#   8. تقليم الخطوط (إبقاء العربية + mono + أساسية فقط)

set -euo pipefail

MODE="dry-run"
for arg in "$@"; do
  [[ "$arg" == "--apply" ]] && MODE="apply"
  [[ "$arg" == "--dry-run" ]] && MODE="dry-run"
done

PKG="com.deckos.core"
PREFIX="/data/user/0/$PKG/files/usr"

# If running locally (from PC), use ADB shell prefix
if ! [[ -d "$PREFIX" ]]; then
  if command -v adb &>/dev/null && adb devices 2>/dev/null | grep -q "device$"; then
    echo "Running via ADB (remote execution)"
    if [[ "$MODE" == "apply" ]]; then
      adb shell "$(cat "$0")" --apply-local
    else
      adb shell "$(cat "$0")" --dry-run-local
    fi
    exit 0
  else
    echo "ERROR: PREFIX $PREFIX not found and no ADB device. Run on device inside Termux."
    exit 1
  fi
fi

DRY_RUN=true
[[ "$MODE" == "apply" || "$1" == "--apply-local" ]] && DRY_RUN=false
[[ "$1" == "--dry-run-local" ]] && DRY_RUN=true

log()  { echo "$*"; }
maybe_rm() {
  local f="$1"
  if [[ "$DRY_RUN" == "true" ]]; then
    [[ -e "$f" ]] && echo "  WOULD DELETE: $f"
  else
    rm -rf "$f" 2>/dev/null && echo "  DELETED: $f" || true
  fi
}
maybe_strip() {
  local f="$1"
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "  WOULD STRIP: $f"
  else
    strip --strip-unneeded "$f" 2>/dev/null && echo "  STRIPPED: $f" || true
  fi
}

log "=== DeckOS Runtime Trimmer ==="
log "Mode: $([[ "$DRY_RUN" == true ]] && echo 'DRY RUN' || echo 'APPLY')"
log "PREFIX: $PREFIX"
log ""

# ── Before size ──────────────────────────────────────────────────────────────
BEFORE=$(du -sm "$PREFIX" 2>/dev/null | awk '{print $1}' || echo "?")
log "Size before: ${BEFORE}MB"
log ""

# ── 1. Documentation: man pages ──────────────────────────────────────────────
log "[1] Man pages (share/man/):"
find "$PREFIX/share/man" -type f 2>/dev/null | while read -r f; do maybe_rm "$f"; done
maybe_rm "$PREFIX/share/man"

# ── 2. Documentation: doc/ ───────────────────────────────────────────────────
log "[2] Doc files (share/doc/):"
find "$PREFIX/share/doc" -type f -name "*.html" -o -name "CHANGELOG*" -o -name "NEWS*" \
     -o -name "ChangeLog*" -o -name "AUTHORS*" -o -name "THANKS*" 2>/dev/null | \
  while read -r f; do maybe_rm "$f"; done

# ── 3. Locales (keep only ar + en) ───────────────────────────────────────────
log "[3] Unused locales (share/locale/):"
if [[ -d "$PREFIX/share/locale" ]]; then
  for locale_dir in "$PREFIX/share/locale"/*/; do
    locale_name=$(basename "$locale_dir")
    # Keep Arabic and English variants
    case "$locale_name" in
      ar|ar_*|en|en_*|C|POSIX) ;;
      *) maybe_rm "$locale_dir" ;;
    esac
  done
fi

# ── 4. Static libraries (.a) — not needed at runtime ─────────────────────────
log "[4] Static libraries (.a):"
find "$PREFIX/lib" -name "*.a" 2>/dev/null | while read -r f; do maybe_rm "$f"; done

# ── 5. Header files (.h) ─────────────────────────────────────────────────────
log "[5] Header files (include/):"
if [[ -d "$PREFIX/include" ]]; then
  maybe_rm "$PREFIX/include"
fi

# ── 6. pkg-config files (.pc) ────────────────────────────────────────────────
log "[6] pkg-config files:"
find "$PREFIX/lib/pkgconfig" "$PREFIX/share/pkgconfig" -name "*.pc" 2>/dev/null | \
  while read -r f; do maybe_rm "$f"; done

# ── 7. apt cache (downloaded .deb files) ─────────────────────────────────────
log "[7] APT package cache:"
find "$PREFIX/var/cache/apt/archives" -name "*.deb" 2>/dev/null | \
  while read -r f; do maybe_rm "$f"; done

# ── 8. Strip debug symbols from .so files ────────────────────────────────────
log "[8] Strip .so debug symbols:"
find "$PREFIX/lib" -name "*.so" -o -name "*.so.*" 2>/dev/null | \
  while read -r f; do
    # Skip socket_hook — it's ours and must stay aligned
    [[ "$f" == *"libsocket_hook.so"* ]] && continue
    # Only strip if not already stripped (check for debug section)
    if readelf -S "$f" 2>/dev/null | grep -q '\.debug_'; then
      maybe_strip "$f"
    fi
  done

# ── 9. Font trimming (keep: Noto Arabic, DejaVu, basic) ──────────────────────
log "[9] Font trimming (non-Arabic/non-mono):"
if [[ -d "$PREFIX/share/fonts" ]]; then
  find "$PREFIX/share/fonts" -name "*.ttf" -o -name "*.otf" 2>/dev/null | \
  while read -r f; do
    fname=$(basename "$f")
    case "$fname" in
      # Keep Arabic + DejaVu mono + Noto basics
      *Arabic*|*Arab*|*Naskh*|DejaVu*Mono*|DejaVuSans*|NotoMono*|NotoSans*[Rr]egular*) ;;
      # Remove heavy ornamental fonts
      *Emoji*|*emoji*|*CJK*|*Symbols*|*Extra*|*Condensed*|*Narrow*|*Light*|*Thin*|*Heavy*|*Black*|*Medium*)
        maybe_rm "$f" ;;
    esac
  done
fi

log ""
AFTER=$(du -sm "$PREFIX" 2>/dev/null | awk '{print $1}' || echo "?")
log "Size after: ${AFTER}MB"
if [[ "$BEFORE" =~ ^[0-9]+$ ]] && [[ "$AFTER" =~ ^[0-9]+$ ]]; then
  SAVED=$((BEFORE - AFTER))
  log "Saved: ${SAVED}MB"
  PCT=$(echo "scale=1; $SAVED * 100 / $BEFORE" | bc 2>/dev/null || echo "?")
  log "Reduction: ${PCTX}%"
fi
log ""
log "Gate 3 target: ≤ 900 MB"
if [[ "$AFTER" =~ ^[0-9]+$ ]] && (( AFTER <= 900 )); then
  log "Status: ✅ PASS (${AFTER}MB)"
else
  log "Status: ⚠️  ${AFTER}MB — may need additional trimming"
fi
