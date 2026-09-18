# Feasibility Spike Results — "Bello"

| | |
|---|---|
| Date | 2026-09-17 |
| Device | Galaxy Tab 4 SM-T530, Android 5.0.2 (API 21), serial `e3572b180497ec75` |
| Spike app | `spikes/` (Kotlin, `com.bello.spikes`), driven from the Mac via `adb` + `spikes/tools/*.py` |
| Raw logs | `spikes/results/` (git-ignored) |
| Requirements | [requirements.md](requirements.md) §8 |

## Summary

| Spike | Verdict | Key numbers |
|---|---|---|
| SP-01 Google `SpeechRecognizer` (fr-FR) | ✅ **Pass** | 10/10 phrases recognized; result 50–180 ms after end of speech |
| SP-02 Gemini Web in app WebView | ✅ **Pass (conditional)** | 10/10 consecutive answers; median 9.5 s (first text ~4.5 s); ~220 MB |
| SP-03 Vosk "bello" wake word | 🔶 **Partial pass** | 26/26 detected; CPU 2.5 % silence / 17 % speech; false wakes **4.3/h** in loud continuous speech (target ≤ 1/h) |
| SP-04 OkHttp + Conscrypt TLS | ✅ **Pass** | 11/11 endpoints, TLS 1.3, bundled CA |
| SP-05 Camera face detection | ✅ **Pass (conditional)** | Detects frontal faces at desk distance; 7.5 % CPU |
| SP-06 Face animation in WebView 95 | ✅ **Pass** | 60 fps idle/speaking with wake word running |

## Test method and limits

- Voice input was simulated with macOS French voices (`say`, 9 voices, varied speech rates) played from the Mac speaker next to the tablet. Levels at the tablet mic: about −30 to −50 dBFS. Real human voices, other rooms and 2 m distance were **not** tested.
- CPU figures are **process CPU as a share of all 4 cores**, sampled every 5 s from `/proc`. Temperature is the battery sensor.
- Logcat is flooded by the Samsung camera HAL; authoritative results come from the app's own `files/results/<spike>.log`.

---

## SP-01 — Google SpeechRecognizer (fr-FR)

**Verdict: Pass.**

- `SpeechRecognizer.isRecognitionAvailable() = true`, service `com.google.android.googlequicksearchbox/…GoogleRecognitionService` (online).
- 10/10 French test phrases recognized. Remaining differences are formatting only ("dix" → `10`, "sept heures trente" → `7h30`, "douze fois quinze" → `12 x 15`) plus one homophone ("l'espresso" → "Nespresso").
- Partial results stream while speaking; final result 50–180 ms after end of speech.
- Early `NO_MATCH` failures were a test artifact (slow `say` start-up); fixed by pre-rendering audio.

**Implications:** keep Google as primary STT. Normalize numbers/times before intent matching (`10 minutes`, `7h30`).

## SP-02 — Gemini Web (signed out) in an app WebView

**Verdict: Pass, with required workarounds.**

| Approach | Result |
|---|---|
| Follow-up question in the same chat | ❌ hangs forever (`pending-request` never resolves) |
| Reload page before each question | ✅ 10/10, but median **34 s** (≈13 s page load on this CPU) |
| **"Nouvelle discussion" in-page + confirm dialog** | ✅ **10/10, median 9.5 s, max 28 s (weather card)**, first text ≈4.5 s |

Findings:
- Page loads signed out in the WebView (UA contains `; wv`, no block). Model: Flash-Lite. Cookie banner must be refused (`Tout refuser`).
- **New chat per question is required.** Signed out, the side-nav "Nouvelle discussion" opens a confirmation dialog; its own "Nouvelle discussion" button (`gem-button`) resets the chat.
- About 1 request in 10–15 hangs even in a new chat. **A page reload recovers it** → implement a watchdog (no first text in ~20 s → reload + retry once).
- **Speakable text extraction:** use `message-content .markdown-main-panel` and remove `.attachment-container, card, weather-card, sources-carousel-inline, source-footnote, source-inline-chip, button, mat-icon`. Lists are converted to `- ` lines. Verified on weather, time, news and list answers.
- Selectors used (subject to change by Google): editor `rich-textarea .ql-editor[contenteditable=true]`, send `button.send-button`, stop `button[aria-label*="Interrompre"]`, pending `pending-request`, response `model-response`.
- Resource use: PSS ≈220 MB, process CPU ≈40 % while generating.
- No captcha or "unusual traffic" page across ~40 requests in one afternoon.
- Answer quality note: signed-out Gemini sometimes mixes English into French answers ("mostly sunny") and answered "Tokyo time" with a wrong hour once.

Script: `spikes/app/src/main/assets/gemini/gemini.js`.

**What Phase 3 added (2026-09-18).** The spike measured the page while it was being used; in
production the page also has to *sit there*. Idle, loaded and doing nothing, it costs **≈48 % CPU
and ≈190 MB** on this tablet — four times the whole idle budget. The provider therefore loads the
page around a question and releases it 90 s later (7.2 % and 93 MB once released), at the cost of
about 6 s on the first question. Two more production findings: a wrong URL loads a perfectly good
404 page, so the health check has to check that the page *accepts a question*, not that it loaded;
and the real page needs a few seconds after loading before its editor exists.
Production script: `app/src/main/assets/gemini/gemini.js` (can be replaced on the device).

## SP-03 — Vosk offline wake word "bello"

**Verdict: Partial pass.** Offline wake word runs on the device with detection and CPU within targets, but the false-wake target is not yet met under loud, continuous background speech.

| Criterion | Target | Result |
|---|---|---|
| Detection | ≥ 80 % | ✅ **26/26 (100 %)** with final rule; median 169 ms after end of audio |
| False wakes | ≤ 1 / hour | ❌ **2 in 27.9 min (4.3 / h)** of loud, non-stop French speech incl. unseen material |
| CPU (idle, wake word on) | ≤ 35 % | ✅ **2.5 %** in silence, **17 %** during continuous speech |
| Temperature | ≤ 42 °C | ✅ ≤ 31 °C |

**Recommended configuration (for implementation):** energy-gated `GatedWakeListener` + grammar `["bello","[unk]"]`, 200 ms pre-roll, onset +9 dB over noise floor, reset at onset, and accept only if `conf ≥ 0.99`, onset-relative start ≤ 0.35 s, and no word starts within 0.5 s after "bello".

**Next rule to validate (not yet proven):** every real "Bello" across all runs started 0.16–0.28 s after onset; every false wake started at −0.34…0.17 s. A start window of **[0.10, 0.35] s** would have rejected all observed false wakes. This was derived after seeing the data, so it must be validated on new audio (ideally hours of real TV/radio and real voices) before relying on it.

**Mitigations to plan regardless:**
- Treat a wake as provisional: open the Google recognizer and only proceed if real speech follows; otherwise return silently to idle (a false wake then costs a brief "listening" face, no LLM call).
- Tap-to-talk always available; wake word sensitivity configurable; wake word auto-paused while the assistant speaks.
- Fallback option if false wakes remain too frequent in the real home: a two-word phrase (e.g. "Salut Bello").

**What Phase 5 added (2026-09-18).** The rule above was carried into the app and then measured
against material it had never seen: twenty utterances in four macOS voices the spike never used,
and twenty-one minutes of that morning's headlines and random Wikipedia articles read aloud in
three others. Two of the three numbers changed.

| | SP-03 | Production, new audio |
|---|---|---|
| Confidence threshold | `conf ≥ 0.99` | **`conf ≥ 0.70`** |
| Start window after onset | [0.10, 0.35] s (unvalidated) | **[0.10, 0.60] s** (validated) |
| Isolation | no word within 0.5 s | unchanged |
| Detection | 26/26, close trials | **14–16 of 20 (70–80 %)** across a room, varying run to run |
| False wakes | 2 in 27.9 min (4.3 / h) | **0 in 21.4 min** |
| CPU, continuous speech | 17 % | 19 % (whole app, face included) |

- **The threshold was the wrong knob and far too tight.** Across a room a real "Bello" comes back
  at 0.79–1.00, not the 0.99–1.00 of the spike's louder trials; 0.99 discarded three quarters of
  them. The loudest false candidate that fell inside the window reached 0.62.
- **The start window held**, which is what SP-03 asked to be checked on new audio: every real
  "Bello" started 0.13–0.26 s after the room got loud, every false one past 1.5 s. Its far edge
  moved to 0.60 s because an utterance can open with a breath or a chair before the word.
- **The model and the grammar were never the problem.** Fed the same twenty files directly, the
  recogniser hears "bello" in 20/20 at 0.88–1.00 — the losses are entirely the room. Decoy
  grammars were tried again and again made it worse, splitting the confidence across "bellot" and
  "bela".
- **The level of the test is part of the test.** The same build and the same files scored 2/20 at
  70 % speaker volume, 15/20 at full volume, and 0/20 on a run where the Mac's volume had silently
  dropped to 38. `scripts/wake-test.sh` now sets and restores the level itself.
- **A wake word must stop decoding a room that is only talking to itself.** Continuing past 1.5 s
  when the keyword has not appeared cost 42 % CPU instead of 19 %.
- **The same twenty files, the same settings, three runs: 15, 16, 14.** Detection over the air is
  not a fixed number but a distribution, and this one straddles the 80 % target. The misses are
  confidences just below the threshold (0.55–0.68), so the room, the distance and the voice decide
  it — which is the argument for measuring in the room that matters.
- Still open, and not answerable from a laptop speaker: **real voices at 0.5–2 m with the
  television on**, which is what the acceptance figure means.

### Getting Vosk to run on Android 5

1. **Native library:** every `vosk-android` release (0.3.32 → 0.3.75) references `stdin`/`stdout`/`stderr`, which API 21 bionic does not export → `dlopen failed: cannot locate symbol "stderr"`.
   Fix: tiny shim `libstdiofix.so` (`spikes/native/stdiofix/stdiofix.c`) defining them from `__sF`, `patchelf --add-needed libstdiofix.so libvosk.so`, and `System.loadLibrary("stdiofix")` before Vosk. Vosk Java classes are used from the AAR's `classes.jar`.
2. **JNA 5.18 lambdas** need `java.util.function` → enable **core library desugaring** (`desugar_jdk_libs 2.1.5`).
3. Model `vosk-model-small-fr-0.22` (41 MB zip / 65 MB unpacked) loads in ≈4.5 s; "bello" is in the vocabulary.

### Iterations

| # | Configuration | Detection | False wakes (background French speech) | CPU silence / speech |
|---|---|---|---|---|
| 1 | `SpeechService`, grammar `["bello","[unk]"]`, "bello" anywhere | 10/10 | **≈740 / h** | 46 % / 46 % |
| 2 | + keyword must be first word, conf ≥ 0.95 | 18/25 (72 %) | 3.9 / h | 46 % / 43 % |
| 3 | **Energy gate** (feed Vosk only on sound, reset at onset, 200 ms pre-roll, abort after 1.5 s if not starting with keyword) | quiet playback: 14–17/26 · normal level: 25/26 | 0 in 15 min (quiet) | **2.5 % / 16 %** |
| 4 | Gate + conf ≥ 0.99 + keyword starts ≤ 0.35 s after onset | 23/26 (88 %) | 2 in 15.4 min live (≈7.8 / h) | 2.5 % / 16–24 % |
| 5 | #4 + **isolation** (no word starts within 0.5 s after "bello") | **26/26 (100 %)** | 2 in 27.9 min live (**4.3 / h**) | 2.5 % / 17 % |

Other findings:
- Decoy grammars (similar words, common French words) failed: the model hears "Bello" as "bellot".
- Low detection in early gated runs came from low playback level at the tablet, not the gate.
- Vosk word timestamps are cumulative across `reset()`; onset-relative timing must be tracked by the app.

## SP-04 — OkHttp + Conscrypt TLS

**Verdict: Pass.** OkHttp 4.12.0, `conscrypt-android` 2.7.0 (minSdk 21, armeabi-v7a).

| Mode | Result |
|---|---|
| System TLS + 2017 system CA | TLS 1.2 only; **fails** Open-Meteo, franceinfo, Let's Encrypt (`Trust anchor not found`) |
| Conscrypt + system CA | TLS 1.3, same 3 failures |
| **Conscrypt + bundled CA (curl.se `cacert.pem`, 121 roots)** | **11/11 OK**, TLS 1.3, HTTP/2 |

Endpoints: Gemini API, Groq, Mistral, Cerebras, OpenRouter, Anthropic, Open-Meteo, Le Monde RSS, franceinfo RSS, gemini.google.com, Let's Encrypt ISRG test site. API endpoints returned 401/403 without keys (TLS/HTTP path verified; authenticated completions not tested — no keys).

**Implication:** bundling the CA file is mandatory today, not just after 2028. Note the WebView also uses the old system store, so Let's-Encrypt-hosted pages will not load in it.

## SP-05 — Front camera face detection

**Verdict: Pass, with limits.**

- Front camera id 1, preview 320×240, hardware face detection supported (`maxNumDetectedFaces=2`) and works with an off-screen `SurfaceTexture` (score 52–66).
- `android.media.FaceDetector` works on a grayscale bitmap built directly from the NV21 Y plane, downscaled to 160×120: ≈54 ms per detection.
- Both detectors only find **frontal** faces; a person in profile is not detected.
- CPU: JPEG round-trip at 1 fps ≈18 % → **grayscale fast path every 2 s ≈7.5 %**; camera preview alone ≈6.4 %.
- Detection confirmed at desk distance (estimated 0.5–1 m). **2 m not tested.**

**Implications:** presence = "a frontal face seen at least once in the last N seconds", not continuous tracking. Camera frames are processed in memory only (spike snapshots were deleted).

## SP-06 — Face animation in WebView 95

**Verdict: Pass.** Minion-style SVG face (`spikes/app/src/main/assets/face/index.html`).

- **60 fps** (p95 frame 16 ms) in idle and speaking states with the wake word running.
- CPU cost depends on continuous animation, not on the face itself (process CPU with wake word running):

| Face variant | CPU |
|---|---|
| All CSS animations looping + rAF fps meter | 48 % |
| Static face, clock updated every second | 12.5 % |
| Static face, no clock updates | 4.3 % |
| **Blink every 4 s via JS, no CSS loops** | **10 %** |

**Implication:** animate continuously only in listening/thinking/speaking states; idle uses timed blinks and a per-minute clock.

## Combined run (wake word + low-CPU face + camera)

15.4 min of loud French background speech: process CPU **23.9 % avg / 34.7 % max**, battery 29.9 → 30.7 °C, PSS ≈170 MB. Live false wakes with rule #4: 2 (led to rule #5).

## Impact on requirements and plan

| Item | Change |
|---|---|
| FR-STT-01 | Confirmed: Google recognizer is primary STT. |
| FR-GWEB-* | Add: new chat per question via confirm dialog; watchdog reload on no first text in ~20 s; text extraction rules above. Keep provider disabled by default but viable. |
| FR-WAKE-01 | Vosk requires the `libstdiofix` shim + patched `libvosk.so` + core library desugaring; use the energy-gated listener, not `SpeechService`. |
| FR-WAKE / R-07 | False-wake target not yet met; add provisional-wake behaviour and further validation with real audio. |
| AD-04 / NFR-SEC-01 | Bundled CA is required today (Let's Encrypt sites already fail on the system store). |
| FR-FACE-08 | Add: idle face must avoid continuous CSS/rAF loops (blink timer, per-minute clock). |
| FR-PRES-01 | Use grayscale Y-plane fast path every 2 s (or hardware detection); frontal faces only. |
| NFR-PERF-04/05 | Measured full stack (wake + face + camera): ≈24 % CPU, ≈170 MB, ≈31 °C — within targets; Gemini Web adds ≈220 MB while active. |
