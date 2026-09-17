# GalaxyTab4 project

"Bello": an always-on, French-speaking Minion-face voice assistant app for this tablet. Zero running cost (free tiers only).
Requirements: [docs/requirements.md](docs/requirements.md). Feasibility spike results: [docs/feasibility-results.md](docs/feasibility-results.md) (spike app and tools in `spikes/`).

## Primary device

The primary Android device for this project is the **Samsung Galaxy Tab 4 10.1 (SM-T530)**, ADB serial `e3572b180497ec75` — Android 5.0.2 (API 21), 32-bit ARM (`armeabi-v7a`).

- Always target it explicitly: `adb -s e3572b180497ec75 ...` (or `ANDROID_SERIAL=e3572b180497ec75`).
- Full specs, commands and gotchas: [docs/device-galaxy-tab4.md](docs/device-galaxy-tab4.md).
- The device shell lacks `tail`, `timeout`, etc. — do filtering on the host side, and never pipe to missing tools on-device (it hangs).
