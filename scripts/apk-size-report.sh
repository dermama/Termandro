#!/usr/bin/env bash
set -euo pipefail

apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$apk" ]]; then
  found="$(find app/build/outputs/apk -type f -name '*.apk' -print -quit 2>/dev/null || true)"
  if [[ -n "$found" ]]; then
    apk="$found"
  fi
fi

if [[ ! -f "$apk" ]]; then
  echo "APK not found: $apk" >&2
  find app/build/outputs -maxdepth 5 -type f 2>/dev/null >&2 || true
  exit 1
fi

bytes="$(stat -c%s "$apk")"
mib="$(awk -v b="$bytes" 'BEGIN { printf "%.2f", b / 1048576 }')"
sha="$(sha256sum "$apk" | awk '{print $1}')"

printf 'APK: %s\n' "$apk"
printf 'Size: %s bytes (%s MiB)\n' "$bytes" "$mib"
printf 'SHA-256: %s\n' "$sha"

printf '\nLargest entries:\n'
entries="$(unzip -l "$apk" | awk 'NR > 3 && NF >= 4 && $1 ~ /^[0-9]+$/ { printf "%10s  %s\n", $1, $4 }' | sort -nr)"
printf '%s\n' "$entries" | sed -n '1,20p'
