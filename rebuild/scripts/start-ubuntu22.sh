#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

if [ ! -d "$DIR/ubuntu22-fs" ]; then
    echo "ubuntu22-fs غير موجود في $DIR" >&2
    exit 1
fi

unset LD_PRELOAD

SHM_DIR="$DIR/ubuntu22-shm"
RUNTIME_DIR="$DIR/ubuntu22-fs/tmp/runtime-root"
mkdir -p "$SHM_DIR" "$RUNTIME_DIR" "$DIR/ubuntu22-fs/proc/fakethings"
chmod 1777 "$SHM_DIR" 2>/dev/null || true
chmod 700 "$RUNTIME_DIR" 2>/dev/null || true

cmd=(proot --kill-on-exit --link2symlink --sysvipc -0 -r "$DIR/ubuntu22-fs")
cmd+=(-b /dev -b /proc -b /sys)
[ -d /data ] && cmd+=(-b /data)
[ -d /sdcard ] && cmd+=(-b /sdcard)
cmd+=(-b "$SHM_DIR:/dev/shm")
cmd+=(-b /proc/self/fd/2:/dev/stderr)
cmd+=(-b /proc/self/fd/1:/dev/stdout)
cmd+=(-b /proc/self/fd/0:/dev/stdin)
cmd+=(-b /proc/self/fd:/dev/fd)
cmd+=(-b /dev/urandom:/dev/random)
cmd+=(-b "$DIR/ubuntu22-fs/proc/fakethings/stat:/proc/stat")
cmd+=(-b "$DIR/ubuntu22-fs/proc/fakethings/vmstat:/proc/vmstat")
cmd+=(-b "$DIR/ubuntu22-fs/proc/fakethings/version:/proc/version")

if [ -d "$PREFIX/tmp/.X11-unix" ]; then
    cmd+=(-b "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix")
elif [ -d /tmp/.X11-unix ]; then
    cmd+=(-b /tmp/.X11-unix:/tmp/.X11-unix)
fi

[ -f /etc/resolv.conf ] && cmd+=(-b /etc/resolv.conf:/etc/resolv.conf)
cmd+=(-w /root)

cmd+=(/usr/bin/env -i)
cmd+=(MOZ_FAKE_NO_SANDBOX=1)
cmd+=(ELECTRON_DISABLE_SANDBOX=1)
cmd+=(GALLIUM_DRIVER=llvmpipe)
cmd+=(DISPLAY=:1)
cmd+=(PULSE_SERVER=tcp:127.0.0.1:4713)
cmd+=(QT_QPA_PLATFORM=xcb)
cmd+=(HOME=/root)
cmd+=(PATH=/root/.local/bin:/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin:/usr/games:/usr/local/games)
cmd+=(TERM="${TERM:-xterm-256color}")
cmd+=(LANG=en_US.UTF-8)
cmd+=(LC_CTYPE=en_US.UTF-8)
cmd+=(XAUTHORITY=/root/.Xauthority)
cmd+=(XDG_RUNTIME_DIR=/tmp/runtime-root)
cmd+=(/bin/bash --login)

if [ "$#" -eq 0 ]; then
    exec "${cmd[@]}"
else
    exec "${cmd[@]}" -c "$*"
fi
