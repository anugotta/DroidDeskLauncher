# DroidDesk Launcher (app)

Flutter + Android app for **DroidDesk** — full Linux desktop on ARM64 Android, with optional **home launcher** routing and phone-oriented XFCE defaults.

## Features (this fork)

- Standalone APK with embedded Termux:X11 (`DISPLAY=:0`)
- Setup wizard + Flutter dashboard
- **Home launcher:** boot / Home → Linux desktop when setup is complete; otherwise Flutter
- Floating overlay: **Keyboard**, input mode (**Trackpad** default), **Dashboard**, **Android** (stock home)
- XFCE mobile profile: bottom dock, top tasklist/clock, safe-area letterboxing, rotate-safe layout
- Hardened session start (FGS / surface timing) to reduce black screens on Home and boot

Screenshots and full docs: [repository README](../README.md).

## Build

```bash
cd app
flutter pub get
flutter build apk --release
```

APK: `build/app/outputs/flutter-apk/app-release.apk`

## Key Android packages

| Component | Role |
|-----------|------|
| `LauncherRouterActivity` | `HOME` / `DEFAULT` entry → desktop or Flutter |
| `DesktopActivity` | Fullscreen X11 surface + floating controls |
| `MainActivity` | Flutter dashboard / setup |
| `XfceMobileProfile` | Touch-oriented XFCE config (versioned markers) |
| `X11InputController` | Trackpad / direct touch / touchscreen |

## Related

- [APP_TROUBLESHOOTING.md](../APP_TROUBLESHOOTING.md) — Electron/Chromium `--no-sandbox` and related
- [NOTICE.md](../NOTICE.md) — attribution
