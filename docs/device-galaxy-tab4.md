# Primary Device: Samsung Galaxy Tab 4 10.1 (SM-T530)

> Primary test device for this project. Details captured via `adb` on 2026-09-17.

## Quick reference

| Property | Value |
|---|---|
| ADB serial | `e3572b180497ec75` |
| Manufacturer / model | Samsung SM-T530 (Galaxy Tab 4 10.1, Wi‑Fi) |
| Codename | `matissewifi` (product `matissewifixx`) |
| Android version | 5.0.2 Lollipop |
| API level | 21 |
| Build | `LRX22G.T530XXS1BRH1` |
| Security patch | 2017-03-01 |
| SoC | Qualcomm Snapdragon 400 (`msm8226`) |
| ABIs | `armeabi-v7a`, `armeabi` (32-bit ARM only) |
| Runtime | ART (`libart.so`) |
| RAM | ~1.4 GB |
| Dalvik heap size | 512m |
| Screen | 1280×800, 160 dpi (mdpi) |
| Storage (`/data`) | 11.9G total, 7.3G used, 4.5G free (at capture) |
| Connection | USB |

## Common commands

```bash
# Target this device explicitly
export ANDROID_SERIAL=e3572b180497ec75

adb devices -l                                   # confirm it's connected
adb install -r app.apk                           # install / reinstall
adb logcat                                       # live logs
adb shell dumpsys battery                        # battery state
adb exec-out screencap -p > screen.png           # screenshot
adb shell getprop ro.build.version.release       # Android version
```

## Gotchas

- **Minimal shell toolbox:** `tail`, `timeout` and other common utilities are missing on the device. Pipe output back to the host shell (`adb shell cmd | tail`) rather than piping on-device — an on-device pipe to a missing command can hang the adb session.
- **API 21 ceiling:** apps must set `minSdk` ≤ 21. Many modern libraries (and Play Services–dependent features) won't work.
- **32-bit ARM only:** native libraries must include an `armeabi-v7a` build; `arm64-v8a`/`x86_64`-only APKs will fail to install.
- **Low RAM (~1.4 GB):** expect aggressive background process killing; watch memory use during testing.
- **Charging over USB:** battery reports status 3 ("discharging/not charging") while USB-powered — use a wall charger for long sessions.
- **Old TLS/WebView:** outdated system WebView and CA store; HTTPS to modern endpoints may fail without a bundled TLS provider (e.g. Conscrypt).
