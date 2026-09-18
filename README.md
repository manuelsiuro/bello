# Bello

An always-on, French-speaking voice assistant with a Minion face, running on a **Samsung Galaxy Tab 4
(2014, Android 5.0.2)** that would otherwise be in a drawer. Ask out loud or type; it answers out
loud and on screen. Running cost: **zero** — free API tiers only, and a key-free fallback.

![state](https://img.shields.io/badge/phases%200--7-built-brightgreen) ![criteria](https://img.shields.io/badge/acceptance-11%2F12-brightgreen) ![device](https://img.shields.io/badge/Android-5.0.2%20(API%2021)-blue)

## What works today

- **Always on.** The app is the home screen: it starts itself after a reboot, keeps the screen lit,
  comes back if it is pushed aside, and restarts itself after a crash without ever showing a system
  dialog — backing off if it ever crashes on startup, rather than looping.
- **It copes with the Wi-Fi going away.** The face says so quietly, the clock, timers and alarms
  carry on, and questions that need the network get a straight answer in milliseconds instead of a
  twenty-second wait. When the network returns it simply carries on.
- **It answers to its name.** Say « Bello » across the room and the face starts listening, offline,
  with no key and no network. If nobody then speaks, it goes quiet again without a word, so being
  wrong costs nothing. It catches roughly three calls in four from across a room, and the tap is
  always there for the fourth.
- **Conversation.** Tap the face and speak French, or type. The answer is spoken with a Minion voice
  (Google TTS, pitched up) and written under the face. It keeps listening for a few seconds so a
  follow-up question needs no second tap, and tapping while it talks interrupts it.
- **Answers from several free providers.** Gemini and Groq by default (~0.6–1.9 s), tried in the
  order you choose, with automatic fallback on quota, server or network errors, cooldowns and a
  daily counter per provider.
- **A brain that needs no key at all:** an experimental provider that drives `gemini.google.com`
  signed out, in a page hidden behind the face.
- **It remembers.** The last ten exchanges are kept, so "et sa hauteur ?" means something; the
  session starts fresh after ten quiet minutes. Say "souviens-toi que…" and the fact is stored on
  the tablet, used whichever provider answers, and forgotten on request.
- **Things it does itself, without asking anyone:** the time ("il est 9 heures 5", in 17 ms), timers
  and alarms with an on-screen countdown and a spoken label, the weather for any town (Open-Meteo,
  no key), and the day's headlines from Le Monde and franceinfo.
- **Alarms survive a reboot** — set one, restart the tablet, it still rings on time.
- **It notices you.** The front camera looks at one small frame every two seconds; walk up after a
  while away and Bello brightens up. No image is ever stored or sent — only "a face, or not" leaves
  the camera code — and it can be turned off.
- **It sleeps at night.** Between 23:00 and 07:00 the screen dims right down and the face dozes;
  talk to it and the screen comes back for as long as the conversation lasts. The wake word keeps
  listening through the night.
- **Settings without a computer.** Long-press the face (behind a PIN, if you set one) for the wake
  word, the voice, the night hours, the camera and four test buttons. The whole configuration
  exports to one JSON file, which you can edit on a computer and push back — with the API keys
  masked, so the file is safe to keep or share.
- **It costs little to leave running.** The face alone is ~6 % CPU and ~67 MB; everything at once —
  face, wake word listening, camera watching — is 12–16 % and ~167 MB on a 2014 tablet, against a
  budget of 35 % and 350 MB.

Eleven of the twelve acceptance criteria pass on the tablet; the twelfth is a seven-day unattended
run, currently under way. See the [implementation plan](docs/implementation-plan.md).

## Things you can say

Everything is in French, since that is the language it was built for.

| You say | What happens |
|---|---|
| « Bello » (then your question) | It starts listening without a tap |
| « Quelle heure est-il ? » | Answered on the spot, no network |
| « Mets un minuteur de 3 minutes pour les pâtes » | Countdown on screen, rings and says why |
| « Réveille-moi à 7 heures » · « Rappelle-moi à 18 heures de sortir les poubelles » | Alarm, with its label, surviving reboots |
| « Quel temps fait-il à Grasse ? » · « Quelle météo demain ? » | Open-Meteo, any town |
| « Donne-moi les infos » | Headlines, summarised in three sentences |
| « Souviens-toi que mon café préféré est l'espresso » | Remembered; ask "qu'est-ce que tu sais de moi ?" or "oublie…" |
| « Stop » (while it rings or talks) | Quiet, immediately |
| Anything else | Goes to a provider, with the conversation so far as context |

## Try it

Needs a Mac or Linux host with `adb`, JDK 17, and the tablet connected.

```bash
scripts/build.sh          # unit tests + debug APK
scripts/install.sh        # install and launch on the tablet
scripts/push-model.sh     # French speech model (once, ~65 MB)
scripts/selfcheck.sh      # TLS, speech model, providers
```

Then press Home on the tablet and choose Bello → "Always" to make it the home screen.

The rest of `scripts/` drives it from the Mac: `ask.sh` to ask a question, `wake.sh` and
`wake-live.sh` for the wake word, `night.sh` and `presence.sh` for the night and the camera,
`settings.sh` for the whole configuration as one file, `perf.sh` and `soak.sh` for how it is
holding up, `pull-logs.sh` for the log.

To give it API keys, copy `config/bello.example.json` to `config/bello.local.json` (git-ignored),
paste your free keys from [AI Studio](https://aistudio.google.com/apikey) and
[Groq](https://console.groq.com/keys), and run `scripts/push-config.sh`. **No key ever enters this
repository** — they live in a file pushed to the tablet.

Without keys it still answers, through the key-free Gemini Web provider (slower).

## Documentation

| Document | What is in it |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Entry point: commands, conventions, device gotchas |
| [docs/requirements.md](docs/requirements.md) | What it must do, and the current status per family |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases, what was measured on the device, findings |
| [docs/feasibility-results.md](docs/feasibility-results.md) | The six experiments run before writing the app |
| [docs/device-galaxy-tab4.md](docs/device-galaxy-tab4.md) | The tablet: specs, commands, limits |

## Notes for anyone doing this on old hardware

A few things this project had to work around on Android 5, written up in the docs above:

- The 2017 certificate store rejects half the modern web — HTTPS goes through Conscrypt with a
  bundled CA bundle.
- The Vosk speech library will not load at all (it needs symbols this Android does not have) until
  a tiny shim library is injected into it.
- Animating an SVG repaints the whole screen every frame: 13 % CPU for an idle face, versus 6 % for
  the same face in HTML and CSS.
- A loaded Gemini web page costs ~48 % CPU doing nothing, so it is only kept open around a question.
- Listening for a wake word all day is affordable only if the recogniser is fed sound and not
  silence, and if it stops decoding a sentence as soon as the word is not in it: 19 % of a 2014
  processor instead of 42 % while a room talks.
- On a device nobody looks at, a crash that happens *at startup* is not a crash but a loop: the
  restart has to back off, or the tablet spends the night restarting itself thirty times a minute.
- Free services fail in ordinary ways that are easy to forget: model names get retired and answer
  404 forever, a weather API returns 503 for one request, and a speech recognizer reports an error
  for the cancellation you asked for.
