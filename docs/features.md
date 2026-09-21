# What Bello does, and what happens after it answers

Every request goes through `assistant/Router`:
1. `assistant/Intents` tries to match a local tool.
2. If a tool matches, it answers.
3. Anything else goes to an LLM provider (the "chat"). Only the chat can offer the details on the phone (a QR code page).

What the user can switch off is listed in `assistant/Features`. The switches are on the settings screen, section « Fonctions » (and « Fournisseurs » for the providers), or through `scripts/features.sh`.

## Three kinds of answer

| Kind | Examples | After it is spoken |
|---|---|---|
| **Command**: something done | change the channel, set a timer, remember a fact | The turn ends: no follow-up listening, no offer, no QR. An open microphone after "mets la 2" would only pick up the television. |
| **Information** from a tool | the weather, the time, a joke, a fuel price | Listens again for the follow-up window (`Relance`, 6 s by default), so « et demain ? » works without saying "Bello". |
| **Chat** with a provider | any other question | Same follow-up. If the answer is a recipe, a how-to or a list, Bello offers the details on the phone and listens for 10 s for « oui ». |

Failures (no network, the box does not answer) end the turn with a sad face. A timer or alarm that is ringing listens for « stop » with no time limit.

The rule is in one place:
- `Features.isCommand(intent)` classifies the intent.
- The Router sets `followUpMs = 0` on a successful command.
- `ConversationPolicy` does the rest.

## Every action

| Switch (`key`) | Intent | Said like | Service | Kind |
|---|---|---|---|---|
| — (always on) | Stop | « stop », « chut », « tais-toi » | — | ends at once, hides the QR |
| — | Time, Day | « quelle heure est-il », « quel jour on est » | — | information |
| Minuteurs et alarmes (`timers`) | TimerSet / TimerCancel | « mets un minuteur de 10 minutes pour les pâtes », « annule le minuteur » | AlarmManager | **command** |
| | TimerList | « combien de temps reste le minuteur » | — | information |
| | AlarmSet / AlarmCancel | « réveille-moi à 7 heures », « annule l'alarme » | AlarmManager | **command** |
| | AlarmList | « quelles alarmes » | — | information |
| Mémoire (`memory`) | Remember / Forget | « souviens-toi que… », « oublie mon café préféré » | SQLite | **command** |
| | ListMemories | « qu'est-ce que tu sais sur moi » | — | information |
| Télévision (`tv`) | TvPower | « allume la télé », « éteins la télé » | SFR decoder, ws :7682 | **command** |
| | TvChannel | « mets la 3 », « mets la chaîne deux », « passe sur France 2 » | same | **command** |
| | TvChannelStep, TvVolume, TvMute, TvKey | « chaîne suivante », « monte le son », « coupe le son », « pause » | same | **command** |
| | TvStatus | « la télé est allumée ? » | same | information |
| Météo (`weather`) | Weather | « quel temps fait-il demain à Nice » | Open-Meteo | information |
| Infos (`news`) | News | « les infos », « quoi de neuf » | RSS (Le Monde, franceinfo) + a provider's summary | information; without the chat, the titles are read as they are |
| Carburant (`fuel`) | Fuel | « où est le gazole le moins cher » | data.economie.gouv.fr + Open-Meteo geocoder | information |
| Wikipédia (`wikipedia`) | Encyclopedia | « qui est Marie Curie » (names only) | fr.wikipedia.org | information; nothing found → the chat |
| | OnThisDay | « que s'est-il passé un 14 juillet » | fr.wikipedia.org | information |
| Blagues (`jokes`) | Joke | « raconte une blague » | JokeAPI, bundled book offline | information |
| Jours fériés et vacances (`holidays`) | PublicHolidays, SchoolHolidays | « c'est férié demain ? », « c'est quand les vacances ? » | calendrier.api.gouv.fr, data.education.gouv.fr (cached) | information |
| Discussion (`chat`) | None | anything else | the LLM gateway | chat, may offer the QR page |
| Détails sur le téléphone (`pageOffers`) | « oui » after an offer | « oui », « vas-y » | provider + `net/PageServer` | ends; the QR code comes when the page is ready |

## When something is switched off

- **Asking for a feature that is off** → « La météo : c'est désactivé dans les réglages. » The turn ends. Nothing is sent anywhere, and the request never falls back to a provider: a weather or TV answer from a model would be invented.
- **Wikipédia off** → a question about a name goes straight to the chat.
- **Discussion off**:
  - a question no tool knows → « Je ne peux répondre qu'avec mes outils… »
  - the news → the titles are read without a summary
  - no page is offered
- **Mémoire off**:
  - remember, forget and list say it is off
  - remembered facts are no longer sent to providers
- **Minuteurs et alarmes off** → timers and alarms already set still ring. Only new requests are refused.
- **A provider switched off** (« Fournisseurs ») → `enabled: false` is written into `config.json` and the gateway is rebuilt through `MainActivity.reloadEverything()`. A provider without a key is shown, but it cannot be switched on from the screen.

The switches are read on every question, so a change applies at once. They travel with `scripts/settings.sh export|import` as `settings.disabledFeatures`, a list of keys. Everything is on by default.

```bash
scripts/features.sh status          # every switch
scripts/features.sh off weather     # FEATURE weather=off
scripts/features.sh on weather
```
