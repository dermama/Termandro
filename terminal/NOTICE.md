# Prebuilt native library provenance

`src/main/jniLibs/arm64-v8a/libtermux.so` is extracted unmodified from the official Termux
`v0.118.3` arm64-v8a release APK.

- Source: https://github.com/termux/termux-app
- Source commit: `8629e632fcb95da272221be327db653fb24befe9`
- APK: https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_arm64-v8a.apk
- SHA-256 of `libtermux.so`: `41fad4b73caa033dc4b3de113f743efbd0039af6889ae7dd40ecb739fef17242`
- License: GPL-3.0-only (see the Termux source tree).

This prebuilt is temporary so Phase 1 can be built without the Android NDK on the ARM64
host. It must be rebuilt from `terminal-emulator/src/main/jni/termux.c` for the 16 KB page
size target before release.
