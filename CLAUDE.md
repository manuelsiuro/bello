# GalaxyTab4 project

"Bello": an always-on, French-speaking Minion-face voice assistant app for this tablet. Zero running cost (free tiers only).

- Requirements: [docs/requirements.md](docs/requirements.md)
- Feasibility spike results: [docs/feasibility-results.md](docs/feasibility-results.md) (test bench in `spikes/`, a separate Gradle project)
- Implementation plan and phase status: [docs/implementation-plan.md](docs/implementation-plan.md)

## Primary device

The primary Android device for this project is the **Samsung Galaxy Tab 4 10.1 (SM-T530)**, ADB serial `e3572b180497ec75` — Android 5.0.2 (API 21), 32-bit ARM (`armeabi-v7a`).

- Always target it explicitly: `adb -s e3572b180497ec75 ...` (or `ANDROID_SERIAL=e3572b180497ec75`).
- Full specs, commands and gotchas: [docs/device-galaxy-tab4.md](docs/device-galaxy-tab4.md).
- The device shell lacks `tail`, `timeout`, `pidof`, etc. — do filtering on the host side, and never pipe to missing tools on-device (it hangs).

## Build and run (production app `app/`, package `com.bello.assistant`)

Run from the repo root on the Mac (JDK 17 is selected by the scripts):

```bash
scripts/build.sh        # JVM unit tests + debug APK
scripts/install.sh      # install on the tablet and launch
scripts/push-model.sh   # download (cached in .cache/) and push the Vosk French model, once
scripts/selfcheck.sh    # run the on-device platform self-check (TLS, Vosk) and print results
scripts/pull-logs.sh    # copy app logs to ./logs and print the tail
```

## Conventions and constraints

- `minSdk 21`, `armeabi-v7a` only; core library desugaring is required (JNA/Vosk).
- All HTTPS goes through `net/HttpClients` (Conscrypt + bundled `assets/cacert.pem`); the device's system CA store rejects Let's Encrypt sites.
- Vosk: the bundled `libvosk.so` is patched to need `libstdiofix.so`; always call `VoskRuntime.load()` before using Vosk.
- Log with `core/FileLog` — logcat is flooded by the Samsung camera HAL; the app log file is the source of truth.
- No API keys in the repository.
