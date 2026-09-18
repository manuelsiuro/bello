# Implementation Plan — "Bello" Minion Voice Assistant

| | |
|---|---|
| Status | Phases 0–3 done · Phase 4 next |
| Date | 2026-09-17 |
| Inputs | [requirements.md](requirements.md) · [feasibility-results.md](feasibility-results.md) · [device-galaxy-tab4.md](device-galaxy-tab4.md) |
| Target | Galaxy Tab 4 SM-T530, Android 5.0.2 (API 21), `armeabi-v7a`, serial `e3572b180497ec75` |

## 1. Principles

- **Every phase ends on the tablet.** A phase is done only when its "done when" checks pass on the real device, not just in a build.
- **Reuse proven spike code, not spike structure.** The `spikes/` app stays as a test bench; production code lives in `app/` and is rewritten cleanly from what the spikes proved.
- **Performance budgets from the spikes are hard limits.** Idle face without continuous animation loops; wake word behind the energy gate; camera sampling every 2 s; bundled CA for every HTTPS call.
- **Free services only, zero API keys in the repository.** Keys live in on-device settings or in a git-ignored local config file pushed with `adb`.
- **Logs to the app's own file.** Logcat is unreliable on this device (camera HAL flooding); `scripts/pull-logs.sh` is the source of truth.

## 2. Repository layout (target)

```
GalaxyTab4/
├── app/                      # production Android app (com.bello.assistant)
│   └── src/main/
│       ├── java/com/bello/assistant/
│       │   ├── BelloApp.kt           # Application: logging, TLS provider, crash handler
│       │   ├── core/                 # logging, config, diagnostics
│       │   ├── net/                  # OkHttp + Conscrypt + bundled CA
│       │   ├── voice/                # wake word, STT, TTS (Phase 2, 5)
│       │   ├── llm/                  # providers, gateway, Gemini Web (Phase 3)
│       │   ├── assistant/            # conversation state machine, memory, tools (Phase 3–4)
│       │   ├── ui/                   # activity, face bridge, settings (Phase 1, 6)
│       │   └── service/              # foreground service, boot receiver (Phase 1)
│       ├── assets/                   # cacert.pem, face/, gemini/
│       └── jniLibs/armeabi-v7a/      # libvosk.so (patched), libstdiofix.so
├── scripts/                  # build / install / model / logs helpers (run on the Mac)
├── spikes/                   # feasibility test bench (unchanged)
└── docs/
```

## 3. Phases

Legend: ☐ to do · ◐ in progress · ☑ done

### Phase 0 — Project foundation ☑

Goal: a production app skeleton that builds, installs, logs to its own file and proves the risky platform pieces (TLS, Vosk native load) in a self-check.

| ID | Task | Status |
|---|---|---|
| P0-1 | Root Gradle project + `app` module: Kotlin, `minSdk 21`, `targetSdk 28`, `armeabi-v7a` only, core library desugaring, AGP 8.13.1 / Kotlin 2.2.21 / Gradle 8.13 | ☑ |
| P0-2 | Dependencies: OkHttp 4.12.0, Conscrypt 2.7.0, JNA 5.18.1 (aar), Vosk 0.3.75 classes jar + patched `libvosk.so` + `libstdiofix.so` (from SP-03) | ☑ |
| P0-3 | `FileLog`: size-capped rotating log in app external files dir, mirrored to logcat with tag prefix `Bello/` | ☑ |
| P0-4 | `BelloApp`: init logging, install Conscrypt as first security provider, uncaught-exception handler that logs the stack trace | ☑ |
| P0-5 | `HttpClients`: OkHttp client with Conscrypt + bundled `cacert.pem` trust manager (SP-04 mode "conscrypt+bundled-ca") | ☑ |
| P0-6 | `VoskRuntime`: load `stdiofix` then Vosk; locate and check the pushed model directory | ☑ |
| P0-7 | Self-check on launch (`Diagnostics`): device info, HTTPS to Open-Meteo + a Let's Encrypt site, Vosk native load, model presence; results shown on screen and logged | ☑ |
| P0-8 | Mac scripts: `build.sh`, `install.sh`, `push-model.sh` (download + push Vosk model), `pull-logs.sh`, `selfcheck.sh` | ☑ |
| P0-9 | JVM unit tests for pure logic (log rotation, PEM parsing) runnable with `./gradlew :app:testDebugUnitTest` | ☑ |
| P0-10 | Docs: build/run instructions in `CLAUDE.md` | ☑ |

**Done when:** `scripts/build.sh && scripts/install.sh && scripts/push-model.sh && scripts/selfcheck.sh` reports all checks OK on the tablet, and `scripts/pull-logs.sh` retrieves the log file.

**Result (2026-09-17):** ✅ 5/5 JVM unit tests pass; on the tablet the self-check passes 7/7 — HTTPS with TLS 1.3 to Open-Meteo, a Let's Encrypt site and the Gemini API through the bundled CA; patched Vosk native library loads; model present and loads in ≈4.1 s. Log file retrieved with `scripts/pull-logs.sh`.

### Phase 1 — Always-on shell and face ☑

| ID | Task | Status |
|---|---|---|
| P1-1 | `MainActivity`: fullscreen immersive, `FLAG_KEEP_SCREEN_ON`, landscape, HOME/launcher intent filter (FR-ON-01/02/04) | ☑ |
| P1-2 | `BootReceiver` → start service and activity after `BOOT_COMPLETED` (FR-ON-03) | ☑ |
| P1-3 | `AssistantService`: foreground service with notification, partial wake lock, `START_STICKY`, watchdog re-launching the activity (FR-ON-05, AD-03) | ☑ |
| P1-4 | Face WebView from local assets; JS bridge `setState(state)`, `setSubtitle(user, answer)`; states idle, listening, thinking, speaking, happy, confused, sad/offline, alert, sleepy (FR-FACE-01..03, 06, 07) | ☑ |
| P1-5 | Low-CPU idle: no continuous CSS/rAF loops, JS blink timer, per-minute clock (SP-06 finding) | ☑ |
| P1-6 | Original Minion-style artwork (FR-FACE-09) — placeholder SVG from SP-06 until a design is chosen | ☑ |
| P1-7 | Text input overlay (FR-CONV-03) and subtitle area | ☑ |

**Done when:** after a reboot the face appears within 60 s with no manual action; typed text shows as a subtitle; idle process CPU ≤ 12 %.

**Result (2026-09-17):** ✅ all criteria met on the tablet.

| Criterion | Result |
|---|---|
| Face after reboot | `FACE_READY` **3 s after `BOOT_COMPLETED`** (device boot itself ≈162 s), screen woken, no manual action |
| Typed text | Works from the on-screen bar (Aa button) and from `scripts/text.sh` |
| Idle CPU | **6.1 % avg / 8 % max** over 5 min; 60 MB PSS; 29 °C |
| Watchdog | Face restored 66 s after switching to Settings |
| Crash recovery | `scripts/crash-test.sh`: no system dialog, face back in ≈1 s |

**Findings that shaped the code:**
- **The face had to be rebuilt in HTML/CSS instead of SVG.** Animating an SVG repaints the whole picture every frame on this device: idle CPU was 13 % with an animated SVG and 0.3 % with a frozen one. HTML elements plus sparse idle animation (blink every 4.5–9 s, glance every 18–35 s) gives 6 %.
- **Crashes must be handled by the app.** Letting Android show "Unfortunately, Bello has stopped" would leave the dialog on screen forever; the app now logs, schedules a restart and ends its own process.
- **After a reboot the screen stays asleep**, so the activity needs `FLAG_TURN_SCREEN_ON`, `FLAG_SHOW_WHEN_LOCKED` and `FLAG_DISMISS_KEYGUARD`.
- Android 5's font has no ⌨ or ➤ glyphs (they render as boxes) — plain text labels are used instead.

### Phase 2 — Voice loop ☑

| ID | Task | Status |
|---|---|---|
| P2-1 | `SpeechInput` wrapping Android `SpeechRecognizer` fr-FR with partial results and error mapping (FR-STT-01, 05) | ☑ |
| P2-2 | `SpeechOutput` wrapping `TextToSpeech` fr-FR, pitch/rate settings, utterance progress → face mouth (FR-TTS-01..03) | ☑ |
| P2-3 | Conversation state machine: idle → listening → thinking → speaking → follow-up window → idle (FR-CONV-01..07, 10) | ☑ |
| P2-4 | Tap-to-talk, tap-to-stop speech (barge-in) | ☑ |
| P2-5 | `SpeechTextNormalizer`: numbers/times ("10 minutes", "7h30") and `TtsSanitizer` for markdown/emoji (FR-CONV-09) | ☑ |
| P2-6 | Echo/stub answer provider to test the loop without an LLM | ☑ |

**Done when:** tapping the face and speaking a French sentence produces a spoken + written stub reply with correct face states.

**Result (2026-09-17):** ✅ met on the tablet.

| Check | Result |
|---|---|
| Voice round trip | Tap → "Bonjour Bello, comment vas-tu ?" recognised (conf 0.93) → spoken + written reply, face listening → thinking → speaking → idle |
| Two turns without tapping | Second question answered inside the 6 s follow-up window; silence then ends the exchange quietly |
| Barge-in | Tap during speech stops it immediately and returns to idle |
| Loop latency | Recognition result → speech start: 18–30 ms (stub answer; network time arrives with Phase 3) |
| Voice | Google TTS, `fra_FRA`, pitch 1.6 / rate 1.05 |

**Findings:**
- **Name the TTS engine explicitly.** With no default engine set on this tablet, Samsung's voice service opened a Galaxy Store page over the face mid-conversation. The kiosk watchdog restored the face, but the app now asks for `com.google.android.tts`.
- **The recognizer reports BUSY** when started immediately after speaking; a 400 ms pause plus one silent retry fixes the follow-up window.
- Spoken-text clean-up must keep symbols like `°` while removing emoji and markdown.

### Phase 3 — LLM gateway ☑

| ID | Task | Status |
|---|---|---|
| P3-1 | `LlmProvider` interface + `OpenAiCompatibleProvider` (chat completions, per-request timeout, error mapping) | ☑ |
| P3-2 | Presets: Gemini API, Groq, Mistral, Cerebras, OpenRouter `:free` (FR-LLM-02) | ☑ |
| P3-3 | `LlmGateway`: ordered providers, fallback on 429/5xx/timeout/TLS, cooldown with `Retry-After`, daily counters (FR-LLM-03..06, 09, 10) | ☑ |
| P3-4 | Persona prompt (French, Minion-style, concise ≤ 3 sentences) + `[emotion]` tag → face expression (FR-CONV-08, 11, FR-FACE-04) | ☑ |
| P3-5 | `GeminiWebProvider` (experimental, disabled by default): hidden WebView, new chat per question, watchdog reload, speakable text extraction, updatable `gemini.js` (FR-GWEB-*, SP-02) | ☑ |
| P3-6 | Provider health status and debug overlay (FR-LLM-07, FR-DIAG-02) | ☑ |
| P3-7 | Config file with the keys, pushed with `adb`, reloadable without reinstalling | ☑ |

**Done when:** acceptance criterion 3 passes (fallback to next provider on forced 429) and criterion 4 passes with Gemini Web enabled.

**Result (2026-09-18):** ✅ met on the tablet. Streaming (FR-LLM-08, priority C) is not implemented;
the answer is spoken when it is complete.

| Check | Result |
|---|---|
| Real providers (keys added 2026-09-18) | Gemini `gemini-3.6-flash` **1.2–1.9 s**, Groq `openai/gpt-oss-20b` **0.6 s**, both in French and in persona |
| Voice round trip | Spoken "Bello, quelle est la hauteur de la tour Eiffel ?" (conf 0.88) → answered by Gemini in 1.2 s → *"Bello! La tour Eiffel mesure environ trois cent trente mètres… C'est presque aussi grand qu'une pyramide de banana géante!"* |
| Fallback on 429 (criterion 3) | `scripts/fallback-test.sh`: two fake providers reached through `adb reverse`, the first always 429. Log: `LLM_FAIL fake-429 RATE_LIMIT` → `LLM_OK fake-ok`, spoken answer |
| Cooldown | The next question skips the rate-limited provider: `skipped=fake-429 (cooldown 52s)`; `Retry-After` is honoured when sent |
| Gemini Web, no key (criterion 4) | "Qui a peint la Joconde ?" → *"Bello, c'est Léonard de Vinci qui a peint la fameuse Joconde… Poopaye et bonne journée dans le salon!"* — **11–12 s** on a warm page, 27 s including the page load |
| Gemini Web broken (criterion 4) | Page pointed at a URL with no chat editor: health check says "cannot be driven", the question is answered by the next provider in **69 ms** instead of waiting 20 s |
| Idle cost | Page released 90 s after an answer: **7.2–7.6 % CPU, 93 MB** (48 % and 190 MB while loaded) |
| Persona | Three sentences maximum, French, occasional Minion words, no markdown or emoji to read out |
| Emotion tag | `[happy]` parsed out of the answer and shown on the face while speaking; `idle+happy` confirmed over the DevTools bridge |
| Provider health | `scripts/llm.sh status` and `scripts/overlay.sh on`: per provider ok/fail counts, requests today, cooldown left, last error |
| Keys | `config/bello.local.json` (git-ignored) → `scripts/push-config.sh` → reloaded live with `scripts/llm.sh reload` |
| Tests | 64 JVM unit tests (40 new), including fallback, cooldown and `Retry-After` against a mock HTTP server |

**Findings:**
- **A loaded Gemini web page costs ~48 % CPU and ~190 MB while doing nothing** — four times the
  whole idle budget. The page is now loaded around a question and released 90 s later; idle returns
  to normal. This is the reason Gemini Web stays an experimental fallback, not the default.
- **The gateway belongs to the process, not to the activity.** Bello is the home screen and its
  activity can be created twice in a row (installing the APK is enough), which started two hidden
  browsers and two health checks. Provider counters and cooldowns also have to survive a restart.
- **A question that has been cancelled must not speak later.** Network answers arrive seconds after
  the fact; the conversation now tags each question and drops answers to older ones.
- **The follow-up window was wiping the written answer** — a question and its answer now stay on
  screen while Bello listens for the follow-up.
- **Free-tier model names rot, and a retired model answers 404 forever.** Both presets were dead
  within a day of being written (`gemini-2.0-flash`, `llama-3.3-70b-versatile`). `scripts/models.sh`
  lists what a key can actually use, and the config file overrides the preset.
- **Thinking models bill their thinking against `max_tokens`.** Gemini 3 was cutting answers in
  half mid-sentence and spending 400 tokens of quota on 50 tokens of answer. Providers now carry
  extra request fields (`reasoning_effort`), which also made answers three times faster.
- **"The page loaded" is not a health check.** A wrong URL loads a perfectly good 404 page, and the
  real page needs a few seconds before its editor exists. The check now waits for the page to
  actually accept a question, and an unusable page is skipped for 30 minutes.
- Gemini Web ignores the per-request timeout (it needs 10–45 s, the APIs get 20 s), so it belongs
  last in the provider order.

### Phase 4 — Memory and tools ☐

| ID | Task |
|---|---|
| P4-1 | Session memory (last N turns, inactivity reset) and long-term facts store (SQLite) with "souviens-toi / oublie" (FR-MEM-*) |
| P4-2 | Local intent matcher: time, timers, alarms, stop (FR-TOOL-01..04, 08) |
| P4-3 | Timers/alarms with `AlarmManager`, persisted, rescheduled after boot; ringing UI + face alert state |
| P4-4 | Weather tool (Open-Meteo, configurable city, geocoding) (FR-TOOL-05) |
| P4-5 | News tool (RSS: Le Monde, franceinfo) summarized by LLM (FR-TOOL-06) |
| P4-6 | LLM function calling for tools where supported; keyword fallback otherwise (FR-TOOL-07, 08) |

**Done when:** acceptance criteria 2, 5, 6, 7 pass.

### Phase 5 — Wake word "Bello" ☐

| ID | Task |
|---|---|
| P5-1 | Production `GatedWakeListener` (energy gate, pre-roll, onset-relative timing) + `WakeWordDecision` rule as a pure, unit-tested class |
| P5-2 | Microphone arbitration: pause wake word during STT and TTS (FR-WAKE-04, 05) |
| P5-3 | Provisional wake: open STT; if no speech follows, return to idle silently |
| P5-4 | Home validation: several hours of real TV/radio + real voices at 0.5–2 m; tune start window [0.10, 0.35] s and thresholds; fallback "Salut Bello" |
| P5-5 | Sensitivity and enable/disable settings (FR-WAKE-02, 03) |

**Done when:** in the real home, detection ≥ 80 % and false wakes ≤ 1 / hour; idle CPU with wake word ≤ 35 %.

### Phase 6 — Presence, night mode, settings ☐

| ID | Task |
|---|---|
| P6-1 | Presence: front camera, grayscale fast path every 2 s (+ hardware detection), greeting after absence; paused at night (FR-PRES-*) |
| P6-2 | Night mode: schedule, brightness, sleepy face, wake word still active (FR-ON-06) |
| P6-3 | Hidden settings screen (long-press + optional PIN), test buttons (FR-SET-01..03, FR-ON-07) |
| P6-4 | JSON config export/import, `adb push` + broadcast apply, keys excluded on export option (FR-SET-04..06) |

**Done when:** acceptance criteria 8, 9, 10 pass.

### Phase 7 — Hardening and acceptance ☐

| ID | Task |
|---|---|
| P7-1 | Network loss/recovery, provider outage, low-memory (`onTrimMemory`) handling (NFR-REL-02) |
| P7-2 | Debug overlay and rolling log retrieval (FR-DIAG-*) |
| P7-3 | 7-day unattended soak test with CPU/memory/temperature logging (NFR-REL-01) |
| P7-4 | Run all 12 acceptance criteria; document results |
| P7-5 | Battery care: smart plug / charging schedule recommendation (NFR-HW-01) |

**Done when:** all acceptance criteria pass and the 7-day soak completes without manual intervention.

## 4. Dependencies between phases

```
P0 ─▶ P1 ─▶ P2 ─▶ P3 ─▶ P4 ─▶ P7
             │            ▲
             └──▶ P5 ─────┤
       P1 ─────▶ P6 ──────┘
```

Phase 5 can start after Phase 2 (needs mic arbitration with STT/TTS). Phase 6 can run in parallel with Phases 3–5 after Phase 1.

## 5. Inputs needed from the user

| When | Input |
|---|---|
| ~~Before Phase 3~~ | ✅ Provided 2026-09-18: Gemini (AI Studio) and Groq keys, in `config/bello.local.json` |
| Phase 1 (optional) | Face design direction, or keep the SP-06 placeholder |
| Phase 5 | A few evenings of TV/radio near the tablet and real "Bello" utterances at several distances |
| Phase 7 | Smart plug or charging schedule decision |

## 6. Key risks carried from the spikes

| Risk | Plan |
|---|---|
| Wake word false wakes above target (4.3 / h in loud speech) | Provisional wake (P5-3), start-window rule validation (P5-4), two-word fallback |
| Gemini Web breaks or hangs | Disabled by default, watchdog reload, never the only provider |
| Vosk patched native lib is fragile | Keep shim + patch documented and scripted; pin Vosk 0.3.75 |
| Old system CA store | All HTTPS through `HttpClients` with bundled CA; WebView only for local assets and Gemini |
| CPU/heat on 2014 hardware | Budgets from spikes enforced; measured each phase |
