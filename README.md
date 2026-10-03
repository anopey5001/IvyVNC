# IvyVNC

A lightweight VNC viewer for Android, written in plain Java with a native
[LibVNCClient](https://github.com/LibVNC/libvncserver) core

[![Build](https://github.com/anopey5001/IvyVNC/actions/workflows/release.yml/badge.svg)](https://github.com/anopey5001/IvyVNC/actions/workflows/release.yml)
[![License: GPL v2+](https://img.shields.io/badge/license-GPL--2.0--or--later-blue.svg)](LICENSE)

## Features

- Saved connection profiles (add, edit, view details)
- On-screen toolbar with main keys, function keys, Ctrl/Alt modifiers and
  your own custom key buttons
- Touch controls with adjustable mouse sensitivity and two-finger scrolling
- Display options: Full Color / 256 Colors / Grayscale, Fit / Stretch / 1:1
  scaling, fast or smooth rendering
- System, dark or light theme
- Keep-screen-awake option and local Delete→Backspace remap

## Install

Grab the latest APK from the
[Releases page](https://github.com/anopey5001/IvyVNC/releases) and install it.
Every push to `main` publishes a new release with a signed release APK
(`IvyVNC-vX.Y.Z.apk`) and a debug APK (`...-debug.apk`).

- Requires **Android 10 (API 29)** or newer
- Built for **arm64-v8a** devices
- Each release includes a `SHA256SUMS.txt` file so you can verify the download

## Security note

This build is compiled **without TLS/OpenSSL/GnuTLS**, so it speaks plain VNC
(VNC password auth only). Traffic is not encrypted. Use it over a trusted
network, a VPN (e.g. WireGuard/Tailscale), or an SSH tunnel.

## Building

Everything is driven by `build.sh`. It cross-compiles LibVNCClient and the JNI
bridge with the NDK, then packages, aligns and signs the APK with the Android
build-tools.

**Requirements:** Android NDK, an `android.jar` for API 34, CMake, JDK 17, and
the build-tools (`aapt2`, `zipalign`, `d8`, `apksigner`) plus `zip`.

```bash
export NDK=/path/to/android-ndk
export ANDROID_JAR=/path/to/platforms/android-34/android.jar
bash build.sh
# -> built/IvyVNC.apk  (signed with an auto-generated debug key)
```

## License

IvyVNC is free software, licensed under the **GNU General Public License,
version 2 or (at your option) any later version** — see [LICENSE](LICENSE).
It statically links LibVNCClient, which is itself GPL-2.0-or-later, so the
combined work must be GPL too.

