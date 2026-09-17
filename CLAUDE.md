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
scripts/face.sh <state> # idle listening thinking speaking happy confused sad alert sleepy
scripts/text.sh "…"     # send a typed question
scripts/kiosk.sh on|off # watchdog that brings the face back (turn off to use other apps)
scripts/perf.sh [n]     # summarise the last n PERF samples (CPU, temperature, memory)
scripts/reboot-test.sh  # reboot the tablet and wait for the face to be ready
scripts/crash-test.sh   # debug builds: crash on purpose and check the app restarts itself
scripts/speak.sh "…"    # make Bello say a sentence (tests the voice)
scripts/voice.sh 1.6 1.05   # set TTS pitch and rate
scripts/ask-voice.sh "…"    # full voice round trip: taps the face, plays the phrase from the Mac
```

Inspect the face page from the Mac (debug builds):

```bash
PID=$(adb -s e3572b180497ec75 shell ps | grep com.bello.assistant | awk '{print $2}' | tr -d '\r' | head -1)
adb -s e3572b180497ec75 forward tcp:9222 localabstract:webview_devtools_remote_$PID
python3 spikes/tools/cdp.py 'bello.getState()'
```

Home screen: after installing, press Home on the tablet and choose Bello → "Always" to make it the launcher.

## Conventions and constraints

- `minSdk 21`, `armeabi-v7a` only; core library desugaring is required (JNA/Vosk).
- All HTTPS goes through `net/HttpClients` (Conscrypt + bundled `assets/cacert.pem`); the device's system CA store rejects Let's Encrypt sites.
- Vosk: the bundled `libvosk.so` is patched to need `libstdiofix.so`; always call `VoskRuntime.load()` before using Vosk.
- Log with `core/FileLog` — logcat is flooded by the Samsung camera HAL; the app log file is the source of truth.
- Face (`assets/face/`): HTML/CSS, never SVG for animated parts — animating SVG repaints the whole face every frame (13 % CPU idle vs 6 % now). No infinite animation in idle/sleepy; idle life comes from sparse JS timers. Chromium 95 features only.
- Crashes are handled by the app (log, schedule restart, kill own process) so Android never shows its crash dialog on this always-on device.
- Voice: speech in/out live in `voice/`; Google's TTS engine is requested by name, otherwise the system may open a store page over the face. Start the recognizer with a short delay after speaking (it reports BUSY otherwise).
- No API keys in the repository.
