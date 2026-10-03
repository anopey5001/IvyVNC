# IvyVNC

A lightweight native VNC viewer for Android, built on
[LibVNCClient](https://github.com/LibVNC/libvncserver) (vendored in
`app/src/main/cpp/libvncclient`).

## Building

Requirements: JDK 17, Android SDK (platform 34, build-tools), NDK 26.3.11579264, CMake 3.22.1.

    gradle assembleRelease      # or ./gradlew if you add the Gradle wrapper
    gradle assembleDebug

Release builds are unsigned; F-Droid signs them itself.

`tools/termux-build.sh` is the original on-device (Termux) build script, kept for
building without Gradle. It signs with a throwaway debug key generated into `keystore/`.

## Notes

- Only permission: `INTERNET`.
- VNC passwords are saved in the app's private storage in plain text and the
  VNC protocol is unencrypted by default — use a trusted network, VPN or SSH tunnel.
- Backups are disabled (`allowBackup=false`) because of the stored passwords.

## License

GPL-2.0-or-later (see `LICENSE`), as required by the bundled LibVNCClient.
