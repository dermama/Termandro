#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

if command -v pulseaudio >/dev/null 2>&1; then
    if ! pidof pulseaudio >/dev/null 2>&1; then
        rm -rf "$PREFIX/tmp"/pulse-* 2>/dev/null || true
        pulseaudio --start --exit-idle-time=-1 --disallow-exit
        sleep 2
    fi
fi

if command -v pactl >/dev/null 2>&1; then
    pactl load-module module-native-protocol-tcp port=4713 listen=127.0.0.1 auth-ip-acl=127.0.0.1 >/dev/null 2>&1 || true
fi

if command -v termux-x11 >/dev/null 2>&1; then
    pkill -f 'termux-x11 :1' 2>/dev/null || true
    sleep 1
    termux-x11 :1 -ac >"$PREFIX/tmp/termux-x11.log" 2>&1 &
    sleep 2
else
    echo "termux-x11 غير مثبت" >&2
    exit 1
fi

exec bash "${DIR}/start-ubuntu22.sh" "$@"
