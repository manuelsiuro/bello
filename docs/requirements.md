# Requirements — "Bello" Minion Voice Assistant

| | |
|---|---|
| Status | v1.0 — in build; see [§11 Implementation status](#11-implementation-status) |
| Date | 2026-09-17 (status updated 2026-09-18) |
| Target device | Samsung Galaxy Tab 4 10.1 SM-T530 — see [device-galaxy-tab4.md](device-galaxy-tab4.md) |
| Progress | [implementation-plan.md](implementation-plan.md) — Phases 0–3 done, Phase 4 next |

## 1. Purpose

Turn the Galaxy Tab 4 into an always-on, French-speaking AI assistant with a Minion-style animated face. The user can talk to it (wake word or tap) or type, and receives answers both spoken and written. Running cost must be **zero**.

## 2. Scope

**In scope**
- Native Android app (single APK, sideloaded via `adb`) that owns the whole experience.
- Voice and text conversation in French.
- Animated Minion face with state-driven expressions.
- Multi-provider LLM access (free tiers) with automatic fallback, including an experimental Gemini Web provider.
- Local tools: clock, timers, alarms, weather, news, web information, conversation memory.
- Camera-based presence detection.
- Always-on kiosk-like behavior with night mode.
- Settings screen and JSON config import/export.

**Out of scope (v1)**
- Languages other than French.
- Children-specific safety modes (adults only).
- Smart-home control, music playback, phone calls, messaging.
- Paid services of any kind.
- Termux or any separate server process on the tablet.
- Cloud-hosted backend.
- Publishing on the Play Store or distributing to others.

## 3. Users and context

- **Users:** adults in a home setting.
- **Location:** tablet placed in a fixed spot, permanently plugged in, on home Wi-Fi.
- **Language:** French (device locale `fr_FR`). Default location for weather: Grasse, France (configurable).

## 4. Device constraints (baseline)

These are facts measured on the device and shape every requirement below.

| ID | Constraint | Impact |
|---|---|---|
| C-01 | Android 5.0.2, API 21, not rooted | `minSdk 21`; libraries pinned to API-21-compatible versions; no true kiosk/device-owner without factory reset |
| C-02 | 32-bit ARM (`armeabi-v7a`), Snapdragon 400, ~1.4 GB RAM | No on-device LLM; native libs must ship `armeabi-v7a`; tight memory budget |
| C-03 | WebView / Chrome 95 (final version for Android 5) | Face UI must use web features available in Chromium 95; no further browser updates possible |
| C-04 | System CA store from 2017; security patch 2017-03 | App must bundle its own TLS stack (Conscrypt) and CA roots |
| C-05 | Google app installed, Google recognizer set as default; Google TTS installed | Online speech recognition may work but is unverified on Android 5 today |
| C-06 | Microphone, speaker, front camera, Wi-Fi, Bluetooth present | All required hardware available |
| C-07 | 1280×800 @ 160 dpi LCD | Face designed for landscape 1280×800; no burn-in concern |
| C-08 | ~4.5 GB free storage | Room for Vosk French model (~41 MB) and local data |
| C-09 | Minimal on-device shell (no `tail`, `timeout`) | Tooling/scripts run on host side |

## 5. Architecture overview

```
┌─────────────────────────── Android app (Kotlin, minSdk 21) ───────────────────────────┐
│                                                                                        │
│  UI Activity (fullscreen, launcher)                                                    │
│   └─ Face WebView (local HTML/CSS/JS assets) ◄── state events ── Assistant Core        │
│                                                                                        │
│  Foreground Service "AssistantService" (partial wake lock, auto-start on boot)         │
│   ├─ Wake word: Vosk ("bello" grammar)                                                  │
│   ├─ Speech-to-text: Google SpeechRecognizer → fallbacks (Groq Whisper, Vosk)           │
│   ├─ Assistant Core: conversation state machine, memory, tool routing                   │
│   ├─ LLM Gateway: provider list + fallback                                              │
│   │    ├─ API providers (OkHttp + Conscrypt): Gemini, Groq, Mistral, Cerebras, OpenRouter│
│   │    └─ Gemini Web provider (experimental, hidden WebView automation)                 │
│   ├─ Tools: clock, timers/alarms, weather (Open-Meteo), news (RSS), web info            │
│   ├─ TTS: Android TextToSpeech (fr-FR, raised pitch)                                    │
│   ├─ Presence: Camera1 face detection (low rate)                                        │
│   └─ Storage: settings, memory, timers (SQLite / SharedPreferences)                     │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

Architecture decisions:
- **AD-01** Single native app; the relay (LLM calls, memory, tools) is implemented in Kotlin inside the app. No Termux.
- **AD-02** Face is rendered in a WebView from bundled local assets (no network needed to show the face).
- **AD-03** Long-running work lives in a foreground service so Android/Samsung is less likely to kill it.
- **AD-04** All HTTPS goes through OkHttp with Conscrypt as the first security provider and a bundled CA bundle.

## 6. Functional requirements

Priority: **M** = must (v1), **S** = should (v1 if feasible), **C** = could (later).

### 6.1 Conversation

| ID | Requirement | Pri |
|---|---|---|
| FR-CONV-01 | Saying the wake word **"Bello"** starts listening for a question. | M |
| FR-CONV-02 | Tapping the face starts listening for a question (works even if the wake word is disabled). | M |
| FR-CONV-03 | A text input lets the user type a question instead of speaking. | M |
| FR-CONV-04 | Every answer is **spoken** (TTS) and **displayed as text** under or beside the face. | M |
| FR-CONV-05 | The transcribed user question is displayed while/after it is recognized. | M |
| FR-CONV-06 | Tapping the face while it speaks stops the speech (barge-in by touch). | M |
| FR-CONV-07 | After an answer, the assistant listens for a follow-up for a configurable window (default 6 s) without needing the wake word again. | S |
| FR-CONV-08 | Answers are concise by default (target: ≤ 3 spoken sentences), with the full text still shown on screen when longer. | M |
| FR-CONV-09 | Markdown, links, tables and emoji are stripped or converted before TTS. | M |
| FR-CONV-10 | If no speech is detected or recognition fails, the face shows a "confused" state and a short spoken/visual message. | M |
| FR-CONV-11 | The assistant has a consistent persona: friendly, playful Minion-like character, answers in French, occasional Minion words (e.g. "Bello!", "Banana!") without harming clarity. Persona prompt is configurable. | S |

### 6.2 Wake word

| ID | Requirement | Pri |
|---|---|---|
| FR-WAKE-01 | Wake word detection runs fully offline using Vosk with the French small model and a restricted grammar (`bello`, `[unk]`). | M |
| FR-WAKE-02 | Wake word can be enabled/disabled in settings. | M |
| FR-WAKE-03 | Detection sensitivity/confidence threshold is configurable. | S |
| FR-WAKE-04 | Microphone is released to the speech recognizer while a question is captured, and wake word listening resumes afterwards. | M |
| FR-WAKE-05 | Wake word listening pauses while the assistant is speaking (avoid self-triggering). | M |

### 6.3 Speech-to-text (STT)

| ID | Requirement | Pri |
|---|---|---|
| FR-STT-01 | Primary STT: Android `SpeechRecognizer` (Google recognizer installed on the device), language `fr-FR`. | M |
| FR-STT-02 | STT engine is pluggable; fallback chain configurable in settings. | M |
| FR-STT-03 | Fallback: Groq Whisper (record audio, upload, free tier) when a Groq key is configured. | S |
| FR-STT-04 | Offline fallback: Vosk French model (free-form recognition) when no network. | C |
| FR-STT-05 | End of speech is detected automatically (silence), with a maximum utterance length (default 15 s). | M |

### 6.4 Text-to-speech (TTS)

| ID | Requirement | Pri |
|---|---|---|
| FR-TTS-01 | Use Android `TextToSpeech` with the installed French voice. | M |
| FR-TTS-02 | Pitch and speech rate configurable (default pitch raised, e.g. 1.6, rate 1.1) for a cartoonish voice. | M |
| FR-TTS-03 | TTS emits start/progress/end events that drive the face's mouth animation. | M |
| FR-TTS-04 | TTS engine abstraction allows plugging in another engine later. | C |

### 6.5 LLM gateway

| ID | Requirement | Pri |
|---|---|---|
| FR-LLM-01 | Support multiple providers, each defined by: type, base URL, API key, model, enabled flag, priority. | M |
| FR-LLM-02 | Built-in provider presets: Gemini API, Groq, Mistral, Cerebras, OpenRouter (`:free` models), all via OpenAI-compatible chat completions where available. | M |
| FR-LLM-03 | Provider order is configurable; no provider is hard-coded as primary. | M |
| FR-LLM-04 | On HTTP 429, 5xx, timeout, or network/TLS error, the gateway automatically tries the next enabled provider. | M |
| FR-LLM-05 | A provider that returns 429 is put on cooldown (configurable, default 60 s; respect `Retry-After` when present). | M |
| FR-LLM-06 | Per-request timeout configurable (default 20 s). | M |
| FR-LLM-07 | Each answer records which provider/model responded (visible in a debug overlay and logs). | S |
| FR-LLM-08 | Streaming responses are supported where the provider allows, so TTS can start before the full answer arrives. | C |
| FR-LLM-09 | Daily request counter per provider, shown in settings, to stay within free-tier limits. | S |
| FR-LLM-10 | When all providers fail, the assistant says so clearly and the face shows a "sad/offline" state. | M |

### 6.6 Gemini Web provider (experimental)

| ID | Requirement | Pri |
|---|---|---|
| FR-GWEB-01 | A provider type "Gemini Web (experimental)" loads `https://gemini.google.com/app` in a hidden, app-owned WebView, signed out, without any API key. | M |
| FR-GWEB-02 | It submits the prompt by injecting JavaScript into the page (fill the input, trigger send). | M |
| FR-GWEB-03 | It captures the answer via DOM observation (e.g. `MutationObserver`) and returns the final text to Kotlin through a JavaScript bridge; completion is detected when the answer stops changing / the "stop" control disappears. | M |
| FR-GWEB-04 | All page selectors and injected scripts are kept in one versioned, updatable script file (can be replaced via config import without rebuilding the APK). | S |
| FR-GWEB-05 | Handles known interruptions: consent/cookie dialogs, "sign in" prompts, page reloads; detects captcha/"unusual traffic" pages and reports failure. | M |
| FR-GWEB-06 | Health check on startup and periodically (e.g. hourly): if the page can't be driven, the provider is marked **unavailable** and the gateway falls back to the next provider. | M |
| FR-GWEB-07 | Rich answer content (weather cards, tables, images) is reduced to speakable text; if nothing speakable can be extracted, fall back to the next provider. | S |
| FR-GWEB-08 | The provider can be placed anywhere in the provider order, or disabled entirely. Disabled by default until the feasibility spike (SP-02) passes. | M |
| FR-GWEB-09 | A new Gemini conversation is started when the local conversation session resets (memory is supplied by our own memory component, not Gemini history). | S |
| FR-GWEB-10 | The hidden WebView is released/reloaded when memory pressure is high (`onTrimMemory`). | S |

> **Note:** This provider relies on automating Google's consumer website. It may break at any time (UI changes, end of Chrome 95 support, blocking) and automated use is not permitted by Google's Terms of Service. It is intended for personal, low-volume use only and must never be the only enabled provider.

### 6.7 Memory

| ID | Requirement | Pri |
|---|---|---|
| FR-MEM-01 | Short-term memory: the current session keeps the last N turns (default 10) and sends them as context. | M |
| FR-MEM-02 | A session ends after inactivity (default 10 min); a new session starts fresh. | M |
| FR-MEM-03 | Long-term memory: facts the user explicitly asks to remember ("souviens-toi que…") are stored locally and included in the system prompt. | S |
| FR-MEM-04 | The user can list and delete long-term memories by voice ("oublie…") and in settings. | S |
| FR-MEM-05 | Memory is stored only on the tablet and is provider-independent (survives provider fallback). | M |

### 6.8 Tools

| ID | Requirement | Pri |
|---|---|---|
| FR-TOOL-01 | **Clock:** idle screen shows current time and date. "Quelle heure est-il ?" is answered locally without an LLM call. | M |
| FR-TOOL-02 | **Timers:** set, list and cancel timers by voice ("minuteur de 10 minutes"); multiple concurrent timers; visible countdown on screen. | M |
| FR-TOOL-03 | **Alarms/reminders:** set, list and cancel alarms at a time/date with an optional label; survive app restart and reboot. | M |
| FR-TOOL-04 | Timer/alarm ringing: sound + face "alert" animation + spoken label; stopped by tap or voice ("stop"). | M |
| FR-TOOL-05 | **Weather:** current conditions and forecast from Open-Meteo (no key) for the configured city, or a city named in the question. | M |
| FR-TOOL-06 | **News:** headlines from configurable RSS feeds (defaults: Le Monde, franceinfo), summarized by the LLM. | S |
| FR-TOOL-07 | **Web info:** questions needing current information use a search-capable provider (Gemini API grounding, Groq compound, or Gemini Web). | S |
| FR-TOOL-08 | Tool routing: simple intents (time, timers, alarms, stop) are matched locally first; other tools are selected by LLM function calling where the provider supports it, else by local keyword intent matching. | M |
| FR-TOOL-09 | Tool results are formatted into short French sentences for speech. | M |

### 6.9 Minion face

| ID | Requirement | Pri |
|---|---|---|
| FR-FACE-01 | Full-screen, landscape animated Minion-style face (yellow skin, goggle with one or two eyes, mouth), rendered with SVG/CSS/Canvas in the WebView from local assets. | M |
| FR-FACE-02 | States: **idle** (random blinks, eyes looking around), **listening**, **thinking**, **speaking** (mouth animated while TTS plays), **happy**, **confused**, **sad/offline**, **alert** (timer/alarm), **sleepy** (night mode). | M |
| FR-FACE-03 | State changes are driven by the Kotlin core via a JS bridge; the face never blocks the assistant. | M |
| FR-FACE-04 | The LLM can optionally tag an emotion for its answer (e.g. `[happy]`), which the face displays while speaking. | S |
| FR-FACE-05 | Eyes follow the detected face position when presence detection is active. | C |
| FR-FACE-06 | Subtitle area shows the user question and the answer text; auto-scrolls for long answers and fades after a delay. | M |
| FR-FACE-07 | Idle overlay shows clock and active timers without covering the face. | M |
| FR-FACE-08 | Animations sustain ≥ 30 fps on the device in idle and speaking states. | S |
| FR-FACE-09 | Artwork is an original "Minion-style" design (no copied official assets). | M |

### 6.10 Presence detection

| ID | Requirement | Pri |
|---|---|---|
| FR-PRES-01 | Front camera samples at low resolution and low rate (≈ 1 fps) to detect a human face using Camera1 face detection or `android.media.FaceDetector`. | S |
| FR-PRES-02 | When a person appears after absence (configurable, default ≥ 5 min), the face wakes up and greets (visual greeting; spoken greeting optional, off by default). | S |
| FR-PRES-03 | Presence detection can be disabled; it is automatically paused during night mode. | M |
| FR-PRES-04 | No images are stored or sent anywhere; frames are processed in memory only. | M |
| FR-PRES-05 | Presence detection is automatically disabled if it causes CPU/thermal limits to be exceeded (see NFR-PERF). | S |

### 6.11 Always-on and night mode

| ID | Requirement | Pri |
|---|---|---|
| FR-ON-01 | Screen stays on while the app is in foreground (`FLAG_KEEP_SCREEN_ON`). | M |
| FR-ON-02 | App can be set as the default launcher (Home) so it returns after any interruption. | M |
| FR-ON-03 | App starts automatically after device boot (`BOOT_COMPLETED`). | M |
| FR-ON-04 | Immersive fullscreen (hide status and navigation bars); screen pinning supported. | M |
| FR-ON-05 | App restarts its service automatically after crash (sticky service + watchdog). | M |
| FR-ON-06 | **Night mode** during configurable hours (default 23:00–07:00): brightness dims to a low level, face shows sleepy state, wake word stays active; on wake word the screen brightens for the interaction then dims again. | M |
| FR-ON-07 | Settings exit / leaving kiosk mode requires a long-press gesture plus a PIN (optional). | S |

### 6.12 Settings and configuration

| ID | Requirement | Pri |
|---|---|---|
| FR-SET-01 | Hidden settings screen opened by long-pressing the face (e.g. 3 s). | M |
| FR-SET-02 | Settings include: providers (keys, models, order, enable), STT chain, wake word on/off and threshold, TTS pitch/rate, persona prompt, city, RSS feeds, night hours and brightness, presence detection on/off, follow-up window, memory management, debug overlay. | M |
| FR-SET-03 | Test buttons: "test provider", "test TTS", "test microphone/STT", "test wake word". | S |
| FR-SET-04 | Export full configuration to a JSON file on device storage; import from JSON file. | M |
| FR-SET-05 | Configuration can be pushed from the Mac via `adb push` and applied (import on start or via a broadcast intent). | M |
| FR-SET-06 | API keys are masked in the UI; export offers an option to exclude keys. | S |

### 6.13 Diagnostics

| ID | Requirement | Pri |
|---|---|---|
| FR-DIAG-01 | Structured logs to logcat with a single tag prefix (e.g. `Bello/…`). | M |
| FR-DIAG-02 | Debug overlay (toggle) shows state, active provider, last latency, memory use, temperature. | S |
| FR-DIAG-03 | Rolling local log file (size-capped) retrievable via `adb pull`. | S |

## 7. Non-functional requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-COST-01 | Cost | Zero recurring cost: only free tiers and free/open-source components. |
| NFR-PERF-01 | Latency | Wake word → listening feedback (face state change + sound) ≤ 1 s. |
| NFR-PERF-02 | Latency | End of speech → start of spoken answer ≤ 6 s median with API providers; ≤ 10 s with Gemini Web. |
| NFR-PERF-03 | Latency | Local intents (time, timers, stop) answered ≤ 1.5 s. |
| NFR-PERF-04 | Memory | App total PSS ≤ 350 MB in steady state (Gemini Web provider disabled); ≤ 500 MB with it enabled. |
| NFR-PERF-05 | CPU/thermal | Idle with wake word active: average CPU ≤ 35 %, battery temperature ≤ 42 °C after 1 h. |
| NFR-REL-01 | Reliability | Runs 7 days unattended without manual intervention (auto-recovery from crashes, network loss, provider failure). |
| NFR-REL-02 | Reliability | Network loss: face shows offline state; local tools (clock, timers, alarms) keep working; recovers automatically. |
| NFR-SEC-01 | Security | All network calls over HTTPS with Conscrypt + bundled CA roots (independent of the 2017 system store, and of the GlobalSign cross-sign expiring 2028-01-28). |
| NFR-SEC-02 | Security | API keys stored only in app-private storage; never logged. |
| NFR-PRIV-01 | Privacy | Microphone audio leaves the device only after the wake word/tap (for STT). Camera frames never leave the device. |
| NFR-PRIV-02 | Privacy | Settings screen shows a notice that free-tier providers (notably Gemini) may use prompts for training/human review. |
| NFR-COMP-01 | Compatibility | `minSdk 21`, `targetSdk` chosen for API-21 compatibility of all libraries; APK includes `armeabi-v7a` native libs only. |
| NFR-COMP-02 | Compatibility | Face web code works in Chromium 95 (no newer CSS/JS features without fallbacks). |
| NFR-MAINT-01 | Maintainability | Providers, STT engines, TTS and tools behind interfaces so they can be added/replaced independently. |
| NFR-MAINT-02 | Maintainability | Gemini Web selectors/scripts isolated and updatable without code changes (FR-GWEB-04). |
| NFR-UX-01 | Usability | All user-facing text and speech in French. |
| NFR-HW-01 | Hardware care | Documentation recommends a smart plug / charging schedule to avoid keeping the battery at 100 % 24/7. |

## 8. Feasibility spikes (must run first)

| ID | Spike | Pass criteria | If it fails |
|---|---|---|---|
| SP-01 | Google `SpeechRecognizer` on Android 5 in a minimal app, `fr-FR` | Recognizes 10 French test phrases with usable accuracy, no errors | Groq Whisper becomes primary STT; Vosk offline fallback |
| SP-02 | Gemini Web in an **app WebView** (not Chrome): load signed out, inject prompt, capture answer | 10 consecutive Q&A round-trips succeed, text extracted cleanly, no captcha | Keep provider disabled; rely on API providers |
| SP-03 | Vosk "bello" wake word on the device | ≥ 80 % detection at 2 m in a quiet room; ≤ 1 false trigger/hour with TV/talk in background; CPU within NFR-PERF-05 | Tune grammar/threshold; fall back to tap-only + evaluate openWakeWord |
| SP-04 | OkHttp + Conscrypt TLS on API 21 | Successful calls to Gemini API, Groq, Mistral, Open-Meteo | Pin older OkHttp/Conscrypt versions or bundle CA only |
| SP-05 | Camera1 face detection on front camera | Detects a face at 0.5–2 m, CPU impact ≤ 10 % | Use `android.media.FaceDetector` on sampled frames or drop feature |
| SP-06 | Face animation in WebView 95 | ≥ 30 fps with idle/speaking animations while wake word runs | Simplify animations (CSS transforms only, fewer layers) |

## 9. Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-01 | Free-tier limits reduced or removed | Medium | High | Multi-provider fallback, request counters, Gemini Web as extra option |
| R-02 | Gemini Web breaks (DOM change, Chrome 95 dropped, blocking, captcha) | High | Medium | Experimental, updatable scripts, health check, never sole provider |
| R-03 | Automated use of gemini.google.com violates Google ToS | Certain | Low–Medium | Personal low-volume use; easily disabled; API providers as default |
| R-04 | Google speech recognizer stops working on Android 5 | Medium | High | SP-01 early; Groq Whisper and Vosk fallbacks |
| R-05 | Free-tier data use (prompts used for training / human review) | Certain (Gemini free) | Medium | Privacy notice; avoid personal data; provider choice is configurable |
| R-06 | Samsung/Android kills background service (low RAM) | Medium | High | Foreground service, launcher app, watchdog, memory budget |
| R-07 | Wake word false triggers / misses | Medium | Medium | Threshold tuning, tap fallback, SP-03 |
| R-08 | Heat and battery wear from 24/7 charging and constant listening | High | Medium | Night dim, thermal monitoring, smart plug recommendation |
| R-09 | CA / TLS changes on API endpoints | Low (until 2028) | High | Conscrypt + bundled CA updated with each release |
| R-10 | API-21-compatible library versions become unavailable or insecure | Medium | Medium | Pin versions, vendor critical dependencies |
| R-11 | Minion IP (Universal/Illumination) | Low (personal use) | Low | Original Minion-style artwork; no distribution |
| R-12 | Gemini API free tier EEA clause ("only Paid Services when making API Clients available to users in the EEA") | Low | Medium | Personal single-user use; other providers configurable |

## 10. Acceptance criteria (v1)

1. From a cold boot, the tablet shows the Minion face within 60 s without any manual action.
2. Saying "Bello, quel temps fait-il à Grasse ?" produces a spoken and written French answer with current weather, with the face going through listening → thinking → speaking → idle.
3. Tapping the face and asking a general question returns an answer from the first available provider; disabling that provider (or forcing a 429) makes the next provider answer transparently.
4. With Gemini Web enabled as first provider, a question is answered through the hidden WebView; breaking it (e.g. airplane-mode-like block of that domain) falls back automatically.
5. Typed questions work identically to spoken ones.
6. "Mets un minuteur de 2 minutes" starts a visible countdown; it rings, speaks, and stops by voice or tap. An alarm set for later survives a reboot.
7. A remembered fact ("souviens-toi que mon café préféré est l'espresso") is used in a later session and can be deleted.
8. Between 23:00 and 07:00 the screen is dimmed with a sleepy face, and "Bello" still wakes it.
9. Walking in front of the tablet after 5+ minutes of absence triggers the greeting animation (if enabled).
10. Settings: long-press opens settings; config exported to JSON, modified on the Mac, pushed via `adb`, and imported successfully.
11. With Wi-Fi off: face shows offline state, clock/timers/alarms keep working; with Wi-Fi back, conversation resumes without restart.
12. 7-day unattended run with no crash requiring manual action; NFR-PERF targets met.

## 11. Implementation status

Requirement families against the phases that deliver them (2026-09-18).

| Family | Phase | State |
|---|---|---|
| FR-ON (always on, boot, kiosk), FR-FACE (face, states) | 1 | ✅ built and verified on the tablet |
| FR-CONV (conversation), FR-STT, FR-TTS | 2 | ✅ built; wake word itself is FR-WAKE, Phase 5 |
| FR-LLM (providers, fallback), FR-GWEB (Gemini Web), FR-DIAG-02 (overlay) | 3 | ✅ built |
| FR-MEM (memory), FR-TOOL (clock, timers, alarms, weather, news) | 4 | ☐ next |
| FR-WAKE (wake word "Bello") | 5 | ☐ spike passed with a caveat (4.3 false wakes/hour) |
| FR-PRES (presence), FR-ON-06 (night mode), FR-SET (settings, config import) | 6 | ☐ |
| NFR-REL, NFR-PERF, acceptance criteria run | 7 | ☐ |

**Deviations decided while building**

| Requirement | What was built, and why |
|---|---|
| FR-LLM-08 (streaming, priority C) | Not built. Answers are spoken when complete; at 0.6–1.9 s per answer, streaming would save little and complicates barge-in. |
| FR-GWEB-01 (hidden WebView) | The page is loaded around a question and released 90 s later, instead of staying open: idle, a loaded Gemini page costs ≈48 % CPU and ≈190 MB on this tablet. First question after a pause pays ≈6 s. |
| FR-GWEB-06 (hourly health check) | Checked when the face starts and after a release; a page that cannot be driven is skipped for 30 minutes. An hourly check would mean loading the page hourly, at the CPU cost above, for no benefit while other providers work. |
| FR-LLM-02 (presets) | Preset model names go stale — two were already retired 404s. The config file overrides the model, and `scripts/models.sh` lists what a key accepts. |
| FR-CONV-08 (≤ 3 sentences) | Enforced by the persona prompt, not by truncation, so answers are never cut mid-sentence. |

## 12. Glossary

| Term | Meaning |
|---|---|
| Wake word | Spoken keyword ("Bello") that starts listening |
| STT / TTS | Speech-to-text / text-to-speech |
| Provider | An LLM backend (API service or Gemini Web automation) |
| Fallback | Automatically trying the next provider after a failure |
| Grounding | LLM answer backed by live web search results |
| Spike | Short technical experiment to validate feasibility before building |
| Conscrypt | Google's modern TLS provider library usable on old Android |
| Vosk | Open-source offline speech recognition toolkit |
