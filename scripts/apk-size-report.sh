#!/usr/bin/env bash
set -euo pipefail

apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$apk" ]]; then
  echo "APK not found: $apk" >&2
  exit 1
fi

bytes="$(stat -c%s "$apk")"
mib="$(awk -v b="$bytes" 'BEGIN { printf "%.2f", b / 1048576 }')"
sha="$(sha256sum "$apk" | awk '{print $1}')"

printf 'APK: %s\n' "$apk"
printf 'Size: %s bytes (%s MiB)\n' "$bytes" "$mib"
printf 'SHA-256: %s\n' "$sha"

printf '\nLargest entries:\n'
unzip -l "$apk" | awk 'NR > 3 && NF >= 4 && $1 ~ /^[0-9]+$/ { printf "%10s  %s\n", $1, $4 }' | sort -nr | head -20
