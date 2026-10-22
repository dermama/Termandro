# Termandro / DeckOS Core

Experimental Android desktop environment that combines a native Termux-style terminal with the Termux:X11 display stack.

## Modules

- `:app` — DeckOS Compose UI and terminal entry point.
- `:terminal` — native terminal emulator module.
- `:lorie` — Termux:X11 display library, cloned from `termux/termux-x11` during CI.

## Build locally

Requirements: JDK 17, Android SDK 35, Build Tools 36.0.0, NDK 29.0.14206865, CMake 3.22.1.

```bash
git clone --depth 1 https://github.com/termux/termux-x11.git sources/termux-x11
git -C sources/termux-x11 fetch --depth 1 origin 5986cfc39458630dadd191735601cf2adc23a19f
git -C sources/termux-x11 checkout --detach FETCH_HEAD
git -C sources/termux-x11 submodule update --init --recursive
git -C sources/termux-x11 apply patches/termux-x11-deck.patch

./gradlew :app:assembleDebug
```

For aarch64 hosts, point `android.aapt2FromMavenOverride` at an x86_64 `aapt2` (for example in `~/.gradle/gradle.properties`).

## CI

`.github/workflows/core-ci.yml` runs on every push and pull request. It installs the Android SDK, fetches the pinned Termux:X11 source, applies the integration patch, builds `:app:assembleDebug`, runs lint and uploads the debug APK as `deckos-core-debug`.

## Status

Work in progress. The X11 host integration is being wired into `:app`; the standalone Termux:X11 APK build is verified, while the combined DeckOS build is still under CI validation. Do not install experimental builds over an existing Termux:X11 installation.

## License

GPL-3.0. See `LICENSE`.
