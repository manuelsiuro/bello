# Implementation Plan — "Bello" Minion Voice Assistant

| | |
|---|---|
| Status | Phases 0–6 done · Phase 7: 11 of 12 criteria pass, the 7-day soak restarted by the Phase 9 install · Phase 8 (the details on the phone) built and verified · Phase 9 (the television) built, keys to be checked on the screen |
| Date | 2026-09-18 |
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
│       ├── assets/                   # cacert.pem, face/, gemini/, page/
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

### Phase 4 — Memory and tools ☑

| ID | Task | Status |
|---|---|---|
| P4-1 | Session memory (last N turns, inactivity reset) and long-term facts store (SQLite) with "souviens-toi / oublie" (FR-MEM-*) | ☑ |
| P4-2 | Local intent matcher: time, timers, alarms, stop (FR-TOOL-01..04, 08) | ☑ |
| P4-3 | Timers/alarms with `AlarmManager`, persisted, rescheduled after boot; ringing UI + face alert state | ☑ |
| P4-4 | Weather tool (Open-Meteo, configurable city, geocoding) (FR-TOOL-05) | ☑ |
| P4-5 | News tool (RSS: Le Monde, franceinfo) summarized by LLM (FR-TOOL-06) | ☑ |
| P4-6 | Tool routing: local French intent matching for every tool; LLM function calling not needed (FR-TOOL-08) | ☑ |

**Done when:** acceptance criteria 2, 5, 6, 7 pass.

**Result (2026-09-18):** ✅ all four criteria pass on the tablet.

| Criterion | Result |
|---|---|
| 2 — spoken weather | "Bello, quel temps fait-il à Grasse ?" (conf 0.93) → *"À Grasse, il fait 21 degrés, plutôt ensoleillé. Aujourd'hui, entre 17 et 26 degrés."* in **627 ms**, face listening → thinking → speaking → idle |
| 5 — typed = spoken | Same questions by `scripts/text.sh` and by voice give the same answers |
| 6 — timer | Spoken "Mets un minuteur de 2 minutes pour les pâtes" → countdown on screen (`1:52`, ticking) → rang at **exactly 120 s**, spoke *"Ding ding ! C'est l'heure : les pâtes !"*, alert face; stopped by tap and by saying "stop" |
| 6 — alarm across a reboot | Alarm set for 9:12, tablet rebooted at 9:07 (`rescheduled 1, dropped 0 stale`), rang at **9:12:00.09** |
| 7 — memory | "Souviens-toi que mon café préféré est l'espresso" → asked again after a reinstall: *"ton café préféré c'est le délicieux espresso"*; listed with "qu'est-ce que tu sais de moi", deleted with "oublie mon café préféré" |
| Follow-up questions | "Qui a peint la Joconde ?" then "Et sa hauteur ?" → *"La Joconde mesure soixante-dix-sept centimètres"* — the session carries the context (FR-MEM-01) |
| The clock, locally | "Quelle heure est-il ?" → *"Il est 8 heures 55."* in **19 ms**, no provider call |
| News | 6 headlines from Le Monde and franceinfo, summarised by Gemini in 4 s into three spoken sentences |
| Tests | 102 JVM unit tests (35 new), including French durations and clock times, intent matching and every spoken reply |

**Findings:**
- **A cancelled recognizer reports an error, and that error was overwriting answers.** Asking a
  second question while Bello was listening for a follow-up replaced the answer on screen with
  "Mon micro est occupé". Recognizer failures are now ignored unless Bello is actually listening.
- **Free public services hiccup.** Open-Meteo answered 503 once during testing and the question was
  lost; tool requests now try a second time before giving up.
- **French numbers cannot be parsed with one regular expression.** "Un quart d'heure", "1 heure 30",
  "deux minutes trente" and "huit heures moins le quart" are all different shapes; the parser walks
  the words, reading the number *before* each unit and the refinement *after* it.
- **The recognizer keeps hyphens and apostrophes** ("réveille-moi", "qu'est-ce que"), so matching is
  done on a flattened copy of the text — same length, so a label or a city can still be cut out of
  the original with its accents.
- An expression (the alert badge) outlives a state change, so the face can keep looking alarmed
  while it speaks and then listens for "stop".

### Phase 5 — Wake word "Bello" ☑

| ID | Task | Status |
|---|---|---|
| P5-1 | Production wake listener (energy gate, pre-roll, onset-relative timing) + `WakeWordDecision` rule as a pure, unit-tested class | ☑ |
| P5-2 | Microphone arbitration: pause wake word during STT and TTS (FR-WAKE-04, 05) | ☑ |
| P5-3 | Provisional wake: open STT; if no speech follows, return to idle silently | ☑ |
| P5-4 | Validation and tuning on audio the rule has never seen; fallback "Salut Bello" if needed | ☑ — tuned on new audio; "Salut Bello" was not needed (false wakes went to zero without it) |
| P5-5 | Sensitivity and enable/disable settings (FR-WAKE-02, 03) | ☑ |

**Done when:** in the real home, detection ≥ 80 % and false wakes ≤ 1 / hour; idle CPU with wake word ≤ 35 %.

**Closed 2026-09-18, by the owner's decision,** on the measurements below: built, tuned and
measured on the tablet against material the rule had never seen — four French voices it was not
designed on, and that morning's news read aloud. Detection with a *real* voice in the real room was
not measured (one live call was seen, at conf 1.00); `scripts/wake-live.sh calls 10` prints it in
three minutes whenever it is worth knowing, and the thresholds follow from `wake-test.sh score`.

| Check | Result |
|---|---|
| Detection, played across the room | **14–16 of 20 (70–80 %)** — three runs of the same twenty utterances at the same settings gave 15, 16 (scored from the candidates) and 14. It sits *on* the 80 % target, not above it: the misses are confidences just under the threshold, 0.55–0.68 |
| False wakes | **0 in 21.4 min** of continuous French (that day's headlines and random articles, three voices) — the spike measured 4.3 / hour |
| CPU, non-stop speech | **19 %** (budget 35 %); the spike's own combined figure was 24 % |
| Window, after the gain | Lifting a quiet utterance makes the onset trip sooner, so the lower bound moved to 0.05 s as well — a real call was lost at 0.07 s. Of eighteen false candidates in 21.4 min, none reached the threshold anywhere inside [0.05, 0.60] |
| A voice from further away | The gate now lifts a quiet utterance to a comfortable level before the recogniser sees it, with the gain fixed from the opening 300 ms. On the same twenty recordings attenuated to imitate distance, confidences above the threshold go from **9/20 to 20/20 at −18 dB** and 12/20 to 20/20 at −12 dB. Played deliberately quietly across the room (55 % volume) the whole build gets **11/20** |
| CPU and memory, quiet room | **7–9 %** and **≈175 MB** with the wake word listening, against 6.6 % and 67 MB without it — the speech model is most of that memory (budget 35 % and 350 MB) |
| Wake → listening face | microphone released in **128 ms**, Google recogniser ready **169 ms** later |
| Wake decided after the word | **101–956 ms**, mostly ≈160 ms (NFR-PERF-01 wants ≤ 1 s) |
| A false wake costs | a listening face for three seconds, then silence — no error, no provider call |
| Settings | `scripts/wake.sh on/off/low/normal/high/status`; the status line is also on the debug overlay |
| Tests | 114 JVM unit tests (12 new) |

**Findings:**
- **The spike's confidence threshold was the wrong knob, and far too tight.** Across a room a real
  "Bello" comes back at 0.79–1.00, not the 0.99–1.00 of the spike's close trials, so `conf ≥ 0.99`
  threw away three quarters of them. At 0.70 — the loudest false candidate inside the window
  reached 0.62 — detection triples and no false wake appears.
- **The start window was the right idea and survived new audio.** Every real "Bello" the tablet
  heard started 0.13–0.26 s after the room got loud; the false ones sat past 1.5 s, deep in a
  sentence. The far edge had to move to 0.60 s, because a breath or a chair can open the utterance
  before the word does.
- **Nothing is wrong with the model or the grammar: the room is the whole problem.** Fed the same
  twenty files directly, the recogniser hears "bello" in **20/20** at 0.88–1.00. Adding decoy words
  ("bellot", "bela") makes it worse by splitting the confidence — SP-03 found this too.
- **The playback level is part of the instrument.** One run scored 0/20 for no other reason than
  the Mac's output volume having dropped to 38; another scored 2/20 at 70 % and 15/20 at full
  volume with the same build. `scripts/wake-test.sh` now sets the level itself and restores it.
- **A wake word that keeps decoding is what a 2014 processor cannot afford.** Not aborting an
  utterance that has not produced the keyword within 1.5 s doubled the cost of a talking room,
  42 % CPU against 19 %.
- **Distance is a volume problem, and volume is fixable.** A voice from the far end of the room
  arrives quiet, and a quiet voice comes back with a low confidence rather than a wrong word —
  which is exactly what the threshold then throws away. Lifting the utterance to a fixed level
  before decoding recovers nearly all of it on clean recordings (9/20 → 20/20 at −18 dB). In the
  air the gain also lifts the room's own noise, so the in-air figure is lower than that, and the
  false-wake pass predates the change: `scripts/wake-live.sh room 30` is what re-checks it.

### Phase 6 — Presence, night mode, settings ☑

| ID | Task | Status |
|---|---|---|
| P6-1 | Presence: front camera, grayscale fast path every 2 s, greeting after absence; paused at night (FR-PRES-*) | ☑ |
| P6-2 | Night mode: schedule, brightness, sleepy face, wake word still active (FR-ON-06) | ☑ |
| P6-3 | Hidden settings screen (long-press + optional PIN), test buttons (FR-SET-01..03, FR-ON-07) | ☑ |
| P6-4 | JSON config export/import, `adb push` + apply, keys excluded on export option (FR-SET-04..06) | ☑ |

**Done when:** acceptance criteria 8, 9, 10 pass.

**Result (2026-09-18):**

| Criterion | Result |
|---|---|
| 8 — night mode | Forced with `scripts/night.sh on`: face **sleepy**, screen brightness **12/255** where the system setting is 60. Tapping it: brightness back to full, listening → speaking. Back to idle: **sleepy and 12/255 again**, with the wake word still listening throughout (`WAKE_RESUMED reason=idle`) |
| 9 — greeting on arrival | Room empty for two minutes, threshold set to one: somebody looked at the tablet and `PRESENCE_ARRIVED_AND_MISSED` → `greeting` fired in the same millisecond, the face lighting up. Detection needs a *frontal* face, as SP-05 warned |
| 10 — settings round trip | `scripts/settings.sh export` → keys **masked** in the copy → city and two settings edited on the Mac → `import` → applied live (`CONFIG_APPLIED`), with the **real keys still on the tablet**. Long press opens the settings screen (also `scripts/settings.sh open`) |
| Cost of watching | Camera at 320×240, one frame looked at every 2 s: process CPU **4.6–11 %** with the wake word running too, 173–185 MB |
| Privacy | No frame is written or sent; the diagnostic reports brightness and a confidence number, never an image (FR-PRES-04) |
| Tests | 131 JVM unit tests (17 new) — the night window across midnight, arrivals and departures, and key masking |

**Findings:**
- **An export leaked the API keys.** The config parser accepts a key as either `key` or `apiKey`;
  the export masked only `apiKey`, so the file it wrote — and copied to the Mac, into a folder git
  did not ignore — contained both real keys in clear. Now both spellings are masked, `config/` is
  ignored apart from the example, the script verifies what actually arrived before keeping it, and
  a test asserts the masking. It took one wrong field name to undo "no API keys in the repository".
- **Restarting a camera races itself.** Stopping presence and starting it again on a fresh thread
  left the old thread still holding the camera, so the new one got "in use by something else" — and
  the failure was sticky, because an unavailable camera was never asked again. One thread for the
  life of the object, and a start that forgives a previous failure.
- **A greeting has to be earned.** The detector only sees faces looking straight at it, so somebody
  working at a desk appears and disappears constantly. Presence needs a minute of nothing before it
  believes the room is empty, and an arrival is only greeted after a configurable absence.
- **The screen dims through the window, not the system setting**, which means Bello never changes
  a setting the owner would have to put back: `screenBrightness` on its own window, visible as
  `mScreenBrightnessOverrideFromWindowManager` and gone the moment the app is not in front.

### Phase 7 — Hardening and acceptance ◐

| ID | Task | Status |
|---|---|---|
| P7-1 | Network loss/recovery, provider outage, low-memory (`onTrimMemory`) handling (NFR-REL-02) | ☑ |
| P7-2 | Debug overlay and rolling log retrieval (FR-DIAG-*) | ☑ |
| P7-3 | 7-day unattended soak test with CPU/memory/temperature logging (NFR-REL-01) | ◐ running since 2026-09-18 11:44 |
| P7-4 | Run all 12 acceptance criteria; document results | ☑ 11 of 12; the twelfth is the soak |
| P7-5 | Battery care: smart plug / charging schedule recommendation (NFR-HW-01) | ☑ |

**Done when:** all acceptance criteria pass and the 7-day soak completes without manual intervention.

**Acceptance criteria (2026-09-18).** Everything re-run against the build now on the tablet, except
where a phase's own measurement is cited.

| # | Criterion | Result |
|---|---|---|
| 1 | Face within 60 s of a cold boot | ✅ `FACE_READY` **4 s after `BOOT_COMPLETED`**; the tablet's own boot takes 162 s, which no app can shorten |
| 2 | Spoken weather, with the face states | ✅ voice round trip measured in Phase 4; re-run today by text: *"À Grasse, il fait 24 degrés, partiellement nuageux…"* in **987 ms** |
| 3 | Fallback to the next provider on a forced 429 | ✅ `scripts/fallback-test.sh`: `LLM_FAIL fake-429 RATE_LIMIT` → `LLM_OK fake-ok`, and the next question skips the provider in cooldown |
| 4 | Gemini Web answers; breaking it falls back | ✅ measured in Phase 3 (11–12 s warm, 69 ms to skip a broken page); the provider reports `state=available` today |
| 5 | Typed questions work like spoken ones | ✅ every criterion here was driven by text, and by voice in Phases 2, 4 and 5 |
| 6 | Timer rings and stops; an alarm survives a reboot | ✅ a timer set at 11:37:53 for 240 s **rang at 11:41:54 — across a full reboot**, 241 s later (`rescheduled 1, dropped 0 stale`) |
| 7 | A remembered fact is used later and can be deleted | ✅ "souviens-toi que mon dessert préféré est la tarte tatin" → recalled in the next answer; deletion by voice and from the settings screen |
| 8 | Night: dimmed, sleepy, and "Bello" still wakes it | ✅ face **sleepy**, brightness **12/255** (system setting 60); a conversation brings it to full and it dims again afterwards; the wake word listens throughout |
| 9 | Greeting after an absence | ✅ `PRESENCE_ARRIVED_AND_MISSED` → `greeting` the moment somebody looked at it |
| 10 | Settings: long press, export, edit on the Mac, push, import | ✅ exported with the keys **masked**, edited, imported, applied live (`CONFIG_APPLIED`), real keys untouched on the device |
| 11 | Wi-Fi off: offline face, local tools keep working; back without a restart | ✅ `NETWORK_LOST` → "hors ligne" on the face, clock in **34 ms**, a timer rang on time, a network question answered truthfully in **6 ms** instead of timing out; Wi-Fi back → `NETWORK_BACK`, Gemini answering in **2.0 s**, same process |
| 12 | Seven days unattended | ◐ **running.** `scripts/soak.sh report` tells you where it is |

**Findings:**
- **A missing permission is a crash loop, not a crash.** `ACCESS_NETWORK_STATE` was missing, so the
  activity died on launch — and the crash handler restarted it into the same crash every 1.2 s,
  which on an always-on tablet means a hot device and a flat battery until somebody notices. The
  restart now backs off (2 s, 4 s, 8 s… to a minute) and resets once a process has lived two
  minutes. Half an hour of a startup crash costs a few dozen restarts instead of fifteen hundred.
- **Reloading the configuration silently removed half of Bello.** `scripts/llm.sh reload` set the
  answer source back to the provider gateway, which was right in Phase 3 and wrong from Phase 4
  onwards: the clock, the timers, the weather and the memory all stopped being consulted until the
  next restart. Found by asking Bello to remember something right after a reload, in the middle of
  re-running the acceptance criteria — which is the argument for re-running them.
- **Knowing there is no network is worth more than handling the failure.** Three providers timing
  out politely take twenty seconds; asking the system first answers in six milliseconds, and says
  what Bello *can* still do rather than apologising.
- **Samsung will not let `adb` turn the Wi-Fi off** (`svc wifi disable` is killed), so the offline
  test drives the settings screen with `input tap`. Worth knowing before planning an outage test.

### Phase 8 — The details on the phone ◐

**Goal:** when an answer is really a recipe, a list or a set of steps, Bello offers the full
version on the phone: it writes a page, serves it from the tablet on the home Wi-Fi and shows a
QR code on the face (FR-PAGE-01..06). Added after Phase 7, on the branch `feature/qr-page`.

| ID | Task | Status |
|---|---|---|
| P8-1 | The offer and the yes/no: `assistant/PageOffer`, `assistant/YesNo` (pure), the offer held by the Router for 90 s, the `[détails]` tag in the persona, the sentences in `ToolReplies` (FR-PAGE-01, 02, 06) | ☑ |
| P8-2 | The page: a second gateway call with its own author and budget (`llm/AskOptions`), `tools/MarkdownLite`, `assets/page/page.html`, an answer cut for room says so (FR-PAGE-03) | ☑ |
| P8-3 | The server on the tablet: `net/PageServer`, `net/PageProtocol`, `net/PageStore`, `net/LocalAddress`, the `pagePort` setting (FR-PAGE-04, NFR-SEC-03) | ☑ |
| P8-4 | The code on the face: ZXing core, `tools/QrCode`, `bello.showQr`, brightness held while it shows (FR-PAGE-05) | ☑ |
| P8-5 | `scripts/page.sh demo|status|off|open|ask`, log tokens `PAGE_*`, the overlay line, the settings toggle | ☑ |
| P8-6 | On the tablet: the measurements below, acceptance criterion 13 | ☑ criterion 11 with an offer pending not re-run (Wi-Fi off needs the settings screen) |

**Done when:** a recipe question ends with the offer; "oui" puts a QR code on the face within the
page budget and a phone on the home Wi-Fi reads the page with quantities and numbered steps;
"non" drops it in one sentence; without Wi-Fi Bello says so; idle CPU and PSS unchanged with the
server up; all unit tests pass.

**Result (2026-09-18, installed at 15:39 on the tablet):**

| What | Measured on the device |
|---|---|
| The whole path without a provider | `scripts/page.sh demo`: `PAGE_SERVER_UP` → `PAGE_PUBLISHED` → `PAGE_SHOWN` in **73 ms**; a phone on the home Wi-Fi opened the page 14 s later (`PAGE_SERVED … from=192.168.1.108`) |
| The page from the Mac | over the LAN: 200, 2 771 bytes in **0.10 s**; unknown id 404, POST 400, a TLS hello closed silently, `/` answers the status line |
| "oui" by text | `PAGE_ACCEPTED` → "Je prépare la page" in **65 ms**; the page written by Gemini 3.6 Flash in **4.8 s** (1 449 chars), by Groq gpt-oss-20b in **0.9 s** (1 046–1 294 chars); shown at once |
| "oui" by voice, in the room | recognised in the 10 s follow-up window (conf 0.93), page shown **1.0 s** later, the announcement deferred while Bello was still speaking and said 3 s after; the phone fetched the page 8 s after that |
| "non", something else | declined in **7 ms**; "quelle heure est-il" dropped the offer and got the clock in **38 ms** |
| Detection | Gemini tags recipes `[détails]`; Groq did not tag a how-to, the question heuristic caught it (`PAGE_OFFERED by=question`) |
| The card | version 3 (29 modules) at 8 px per module; a tap hides it without starting to listen; `bello.isQrShown()` follows |
| A question while the card shows | the card stays, by design: the weather asked by wake word at 15:56 left the 15:55 card on screen until a tap removed it. Only a tap on the card, "stop", a newer page or the three-minute timeout hides it — a question or a follow-up never does |
| Night | card shown: brightness override **−1 (full)**; hidden: **12/255**; day: −1 |
| Cost | wake word paused, card shown, server up: **12.1–12.7 % CPU, 191 MB**; server down, card hidden: 13.6 %, 188 MB. No page thread ever shows in `top`; the swings between windows (14 → 26 %) follow the wake-word thread (13 % while the room talks) |
| Quota | Gemini's free tier answered 429 during the test and Groq took over; a page is one more call per question |
| Tests | 174 JVM unit tests (135 before) |

**Findings (from building it):**
- **Two questions cannot be answered in one call.** `Responder.answer()` is synchronous and the
  microphone is exclusive, so writing the page inside the "oui" turn would have meant a thinking
  face and a deaf Bello for up to a minute. The page is written on its own thread: "oui" is
  answered at once and the page is announced when it comes — only if Bello is idle, like a greeting.
- **The persona had to leave the room.** "Trois phrases, sans liste, sans markdown" contradicts
  everything a page is for; the page call replaces the system prompt instead of appending to it,
  and sends neither the session nor the remembered facts, so nothing personal goes on a page
  served to the network.
- **Nothing hides the card but the card.** Hiding it on the next turn would have let the first
  "merci" in the follow-up window wipe the code; a card lasts three minutes, and only a tap on it,
  "stop", a newer page or the timeout ends that. Night mode had two brightness rules, one of
  which dimmed the screen the moment a turn ended; it has one now, and a page on show counts as
  company.
- **A yes is a whole utterance, not a word.** "Non, mets un minuteur" must set the timer and
  "oui mais pour six personnes" is a new question; only an utterance made of nothing but yes and
  no words (fillers aside) answers the offer, and a no anywhere wins.
- **A fast provider beats the sentence that announces it.** Groq wrote the page in under a second,
  while Bello was still saying "je prépare la page"; the first build skipped the announcement as
  "busy". It now waits for the next quiet moment, and gives up after two minutes.
- **The measurement is the room.** Three cost windows disagreed by twelve points with nothing
  changing on screen; `top -t` showed the wake-word thread at 13 % and no page thread at all.
  Somebody was talking near the tablet. Pausing the wake word gave the number.
- **What was said aloud goes on the page.** The page prompt quotes the spoken answer for
  consistency, and the spoken answer may carry a remembered fact (the crêpes page mentioned the
  owner's favourite dessert). The page is served to the home network only, and the fact had
  already been said out loud in the room; worth knowing, not worth fixing.

## 4. Dependencies between phases

```
P0 ─▶ P1 ─▶ P2 ─▶ P3 ─▶ P4 ─▶ P7
             │            ▲
             └──▶ P5 ─────┤
       P1 ─────▶ P6 ──────┘
```

Phase 5 can start after Phase 2 (needs mic arbitration with STT/TTS). Phase 6 can run in parallel with Phases 3–5 after Phase 1.
Phase 8 needs Phase 4 (the Router and the gateway) and Phase 1 (the face); it was added after Phase 7 and does not gate it.

### Phase 9 — The television

The SFR TV decoder in the house, driven the way the SFR TV app drives it: [sfr-tv-box.md](sfr-tv-box.md).

| # | Task | Done |
|---|---|---|
| P9-1 | `tools/TvBox`: the STB8 protocol (pure, tested on the box's real replies) and a client that opens one WebSocket per command | ☑ |
| P9-2 | `assistant/Intents`: power, channel by number, by number word and by name, next/previous, volume, mute, pause and the other keys, status — in French, with the channel table from `config.json` | ☑ |
| P9-3 | `assistant/ToolReplies`: the short spoken confirmations; `Router`: the branch, « je n'arrive pas à joindre le décodeur » when the box does not answer | ☑ |
| P9-4 | `core/AppConfig`: `tvBox` (host `stb`, port 7682, `channels` = TNT numbering since June 2025, `okAfterDigits`, `enabled`) | ☑ |
| P9-5 | `scripts/tv.sh`: the box from the Mac (`status`, `on`, `off`, `key`, `channel`) and through Bello (`ask`) | ☑ |
| P9-6 | Every key name checked on the television, `power` from standby with and without CEC, channel entry with two digits | ☐ needs someone in front of the television |

**Done when:** « la télé est allumée ? » is answered from the box's state; « mets la 3 » changes the channel on the screen; « éteins la télé » puts the decoder in standby and « allume la télé » brings it back, each within 1.5 s (NFR-PERF-03).

**Result (2026-09-18):** built, 186 JVM unit tests pass (174 before), installed on the tablet. The status question is answered from the box in the room; the keys wait for the television to be watched (P9-6) — the box acknowledges any key name, so only the screen can confirm them.

## 5. Inputs needed from the user

| When | Input |
|---|---|
| ~~Before Phase 3~~ | ✅ Provided 2026-09-18: Gemini (AI Studio) and Groq keys, in `config/bello.local.json` |
| Phase 1 (optional) | Face design direction, or keep the SP-06 placeholder |
| Phase 5 (optional now; the phase was accepted without it) | Three minutes of your own voice — `scripts/wake-live.sh calls 10` prompts you to say "Bello" ten times and prints the detection rate — and, when convenient, `scripts/wake-live.sh room 30` with the television on for the false-wake half. Nothing is played from the Mac; the tablet listens to the room it lives in, and `scripts/wake-test.sh score` then picks thresholds from what it heard |
| Phase 7 (open) | Whether to put the tablet on a mains timer or smart plug, and on what schedule — a ten-year-old battery held at 100 % all day is the one thing that will end this project early. The options are written up in [device-galaxy-tab4.md](device-galaxy-tab4.md#keeping-the-battery-alive-nfr-hw-01), and every performance sample now records the battery level so a week of running shows whether a schedule works |

## 6. Key risks carried from the spikes

| Risk | Plan |
|---|---|
| Wake word false wakes above target (4.3 / h in the spike) | Largely answered: **0 in 21.4 min** of continuous French once the start window and isolation were enforced, and a false wake is now provisional — a listening face, then silence. The two-word "Salut Bello" fallback stays in reserve for the real room |
| Wake word misses a real call (the new risk) | Detection is bounded by the room, not the rule: the same audio scores 20/20 fed directly and 2/20 through a speaker at half volume. Sensitivity is a setting, and the tap never goes away |
| Gemini Web breaks or hangs | Never the only provider and never the first: it is skipped for 30 minutes as soon as its page cannot be driven, and the page is released 90 s after an answer |
| Vosk patched native lib is fragile | Keep shim + patch documented and scripted; pin Vosk 0.3.75 |
| Old system CA store | All HTTPS through `HttpClients` with bundled CA; WebView only for local assets and Gemini |
| CPU/heat on 2014 hardware | Budgets from spikes enforced and measured each phase; the finished stack sits at 12–16 % CPU and 33–34 °C against budgets of 35 % and 42 °C |
| The battery, held at 100 % for years | The one risk nothing in the code can fix: see "Inputs needed from the user" |

## 7. Where it stands (2026-09-18)

Every phase is built and running on the tablet. Eleven of the twelve acceptance criteria pass; the
twelfth is the seven-day unattended run, started 2026-09-18 11:44 (`scripts/soak.sh report`).

| What | Measured on the device |
|---|---|
| Answering | Gemini 1.2–2.1 s, Groq 0.6 s, fallback on quota or failure, key-free Gemini Web behind them |
| The details on the phone | the offer after a recipe or a how-to; "oui" by voice → a page written in 0.9–4.8 s, served by the tablet, read on a phone from the QR code on the face |
| Doing it itself | the clock in 19–34 ms, timers and alarms, weather in ~1 s, headlines, memory across restarts, the television's state from the decoder in 323 ms |
| Hearing its name | 14–16 of 20 calls across a room, 0 false wakes in 21.4 min of continuous French |
| Cost, everything running | 12–16 % CPU, 33–34 °C, ~167 MB (budgets: 35 %, 42 °C, 350 MB) |
| Cost, face alone | 6 % CPU, 67 MB |
| Recovering | crash → back in ~1 s with a backing-off restart; reboot → face 4 s after `BOOT_COMPLETED`, alarms re-armed; network gone → local tools keep working, answers in 6 ms, resumes by itself |
| Tests | 186 JVM unit tests (174 before Phase 9) |

**Still open, and recorded as such:**

1. **The soak** — nothing to do but leave it alone for a week.
2. **The wake word with a real voice.** Everything measured used synthetic French voices through a
   speaker; `scripts/wake-live.sh calls 10` measures the real thing in three minutes, and
   `room 30` re-checks false wakes with the television on — worth doing because the distance
   compensation was added after the false-wake measurement.
3. **The battery**, which is a decision rather than a task: see "Inputs needed from the user".
4. **Phase 9 is on the tablet** since 2026-09-18 17:59, and its install restarted the soak clock
   again (`scripts/soak.sh start` resets the report's baseline).
5. **The television's keys.** The decoder says `OK` to any key name, so every key but `mute` is
   confirmed only by watching the screen: ten minutes with `scripts/tv.sh` (P9-6), plus the
   household's channel names for `tvBox.channels` if they differ from the TNT table.
6. **Which free services to add next**: the study in [free-services.md](free-services.md) ranks
   thirteen features that need no sign-up and six that need a free one; the choice is the owner's.

**If someone picks this up later**, the two habits that caught the most problems were re-running
the acceptance criteria against the build in hand rather than trusting the last phase's result —
two real bugs surfaced that way on the last day — and making the device log enough that a
measurement could be scored afterwards instead of guessed at.
