# Free services for Bello — a study

| | |
|---|---|
| Status | Done — about 80 services considered in four areas, every key-free candidate called for real; 13 features recommended without any sign-up, 6 with a free one (§4) |
| Date | 2026-09-18 |
| Branch | `research/free-services` |
| Question | Which free web services could give Bello new, genuinely useful features, at zero running cost? |
| Inputs | [requirements.md](requirements.md) · [implementation-plan.md](implementation-plan.md) · the code in `app/` |

## 1. What was asked, and how it was checked

Three feature areas were chosen for the study — **daily life at home**, **knowledge and language**,
**entertainment** — plus the one requirement still open in v1, **fresh information from the web**
(FR-TOOL-07), and the voice. Both key-free public APIs and free tiers behind an account are
acceptable, as long as each service is flagged with what it needs; anything that wants a credit card
on file is out, "free" or not.

Every shortlisted service was **called for real from the Mac on 2026-09-18**: the same request Bello
would send, the real response, its size and latency. Services that need a key were checked against
their official documentation (sign-up steps, quota, terms) and, when a documented test key exists,
called with it. No account was created and no key was entered for this study. The two keys already
on the tablet (Gemini, Groq) were used once each for the live-information tests in §6.

## 2. The ground rules a service has to fit

These come from the device and from how Bello is built; they decide as much as the quotas do.

| Rule | Why it matters here |
|---|---|
| Zero running cost, no card | NFR-COST-01. A free plan that needs a card is a paid plan with a delay. |
| Spoken in French, three sentences | An API is only useful if its data makes a good sentence to say aloud. Long lists go to the page on the phone (FR-PAGE). |
| Any public CA is fine for API calls | All HTTPS goes through `net/HttpClients` (Conscrypt + bundled Mozilla roots). Let's Encrypt, which the 2017 system store rejects, is fine there. |
| Audio streams are the exception | `MediaPlayer` uses the *system* store: an HTTPS stream signed by Let's Encrypt fails on this tablet. Streams must be plain HTTP or signed by a 2017-era root (DigiCert, GlobalSign, Sectigo…). |
| Small responses | Snapdragon 400, 1.4 GB. Parsing a 2 MB JSON on the answer path is a visible pause; ask the API for the fields and rows needed. |
| JSON, XML/RSS or iCal only | No HTML scraping: it breaks without notice and costs CPU. |
| Local intents first | A new feature is matched in French by `assistant/Intents` before any provider is called, exactly like the weather, so it works with no key and cannot be talked out of by a model. |
| Keys stay out of the repository | A keyed service gets its key from `config.json` on the device (pushed with `scripts/push-config.sh`), never from the code. |
| Quotas are counted | The LLM gateway already counts requests per provider per day; a keyed tool with a daily limit must do the same, and say so instead of failing silently. |

## 3. What adding a service costs in this codebase

The weather tool is the template, and it is small:

- `assistant/Intents.kt` — one `Intent` subclass and one French regex block (accent-stripped
  matching, the original text kept for what is spoken back).
- `tools/<Service>.kt` — one class: build the URL, `ToolHttp.getText()` (one retry on a blip),
  parse with `org.json`, cache what is worth caching (the weather caches the geocoding).
- `assistant/ToolReplies.kt` — the spoken French sentences, pure and unit tested; nothing to be
  spoken is built inline.
- `assistant/Router.kt` — one `when` branch, with the offline check (`isOnline()`) where the
  answer needs the network.
- A unit test for the parser on a saved real response, and one for the intent.

A key-free JSON service is therefore **half a day** including the tests on the tablet. A keyed
service adds the config entry (`core/ConfigIo`, masked in exports), a daily counter and a settings
line. A service that streams audio adds a `MediaPlayer` path, the "stop" intent and the
barge-in rules, which is a phase of its own, not a tool.

## 4. The shortlist

Ranked by what a household gets for the cost. "Verified" means the real call was made today and
the spoken sentence in §5–6 comes from the real response.

**Tier 1 — no account, verified, half a day each (§3).**

| # | Feature, as Bello would say it | Service | Verified |
|---|---|---|---|
| 1 | « Qui a gagné hier soir ? », « le prix du gazole aujourd'hui » — **questions that need today's web** (closes FR-TOOL-07) | Gemini 2.5 Flash with `google_search`, native endpoint, the key already on the tablet; 500 a day | ✅ 3.2 s, French, cited |
| 2 | « L'air est bon ? », « il y a du pollen ? », « l'eau est à combien ? », « le soleil se couche à quelle heure ? » | Open-Meteo air quality, marine, sun and moon — extends the weather tool | ✅ 0.13–0.16 s, < 1 KB |
| 3 | « Où est le gazole le moins cher ? » | data.economie.gouv.fr, 8 km around the house | ✅ 0.17 s, 547 B — **built, Phase 11** |
| 4 | « Les vacances, c'est quand ? », « c'est férié demain ? » | data.education.gouv.fr, calendrier.api.gouv.fr | ✅ 465 B and 318 B — **built, Phase 10** |
| 5 | « Demain est bleu ou rouge ? » (Tempo contracts only) | api-couleur-tempo.fr, RTE backup | ✅ 80 B |
| 6 | « Qui est Marie Curie ? », « c'est quoi Grasse ? », « que s'est-il passé un 18 septembre ? », « ça veut dire quoi, … ? » | French Wikipedia and Wiktionary | ✅ 0.06–0.36 s — Wikipedia **built, Phase 11**; the dictionary is not |
| 7 | « La mairie est ouverte quand ? », « combien d'habitants à Grasse ? » | Annuaire de l'administration, geo.api.gouv.fr | ✅ 659 B, 496 B |
| 8 | « Combien de calories dans le Nutella ? » | Open Food Facts search | ✅ 0.20 s (main host flaky, search host fine) |
| 9 | « Raconte-moi une blague » | JokeAPI, French, with a local blocklist | ✅ 395 B — **built, Phase 11** |
| 10 | « Qu'est-ce qu'on fait ce week-end ? » — with the list on the phone | OpenAgenda legacy export | ✅ 53 events in Grasse |
| 11 | « Cent dollars en euros ? » | Frankfurter | ✅ 71 B |
| 12 | « Qui chante ça ? », a 30-second preview | iTunes Search | ✅ preview on a root the tablet trusts |
| 13 | « Demain, j'ai quoi ? » — the household calendar | Google Calendar secret iCal URL (the URL is the credential) | ✅ on a public calendar |

**Tier 2 — a free sign-up, no card, worth doing.**

| # | Feature | Service | Why the sign-up |
|---|---|---|---|
| 14 | « Le prochain train pour Cannes ? » | api.sncf.com, 5 000/day | the key-free GTFS-RT path works but weighs 1.5 MB per question |
| 15 | « Quand joue Nice ? », « le classement de Ligue 1 » | football-data.org free tier | the key-free alternative only shows home matches |
| 16 | « Qu'est-ce qui sort au cinéma ? » | TMDB, French metadata | no key-free source has French film data |
| 17 | A second live-web source, when Gemini's day is used up | Tavily, 1 000 credits/month | the only searcher with a monthly free plan and no card |
| 18 | « Il y a une vigilance ? » | Météo-France DPVigilance | no key-free live source; 600 KB twice a day, tolerable |
| 19 | The recogniser's fallback (FR-STT-03, already planned) | Groq Whisper turbo, 8 h of audio a day | the key is already on the tablet |

**Tier 3 — a feature, not a tool: Bello plays.** Six French stations and Radio France's podcasts
are verified to play on this tablet over plain HTTP (§5.3). It needs a `MediaPlayer` path, "stop",
barge-in, the wake word coexisting with the speaker, and the night rules: a phase, and the one
with a real risk (the microphone hears the radio; see §8).

**Later, or never.** Gemini's own TTS (works, 5.7 s per sentence), Todoist, La Poste, Overpass
places (cached weekly), the Zou! buses, Nominis, MyMemory, the ISS and NASA's picture, TV listings.
Dropped for cause: everything needing a card (Brave, Google Cloud TTS), paid despite the name
(Dicolink, OpenRouter search, Serper after the first 2 500), English-only (Wolfram, OMDb, TheMealDB,
Groq TTS), dead or dying (Wikinews, LibreTranslate mirrors, Edge TTS, SCARE cinema data, the Deezer
app programme), or non-existent (pharmacie de garde, lottery results, cinema showtimes).

Two things to do first, whichever features are chosen: give `net/HttpClients` a **speaking
`User-Agent`** (`Bello/1.0 (+contact)`) — the app sends OkHttp's default today, and Wikimedia,
Open Food Facts, Radio Browser and Overpass all require a real one and may block without it — and
add the **attribution lines** to the settings screen (Wikipedia CC BY-SA, Open Food Facts ODbL,
Open-Meteo CC BY 4.0, "Source : service-public.gouv.fr", and TMDB's sentence if it is used).

## 5. Findings by area

### 5.1 Daily life at home

| Service | Needs | Quota (official) | French | Verdict |
|---|---|---|---|---|
| Open-Meteo Air Quality (European AQI, pollen) | none | < 10 000/day, non-commercial, CC BY 4.0 | numbers | ✅ **Recommend** — same family as the weather |
| Open-Meteo Marine (sea temperature, waves off Cannes) | none | same | numbers | ✅ Recommend |
| Fuel prices — data.economie.gouv.fr (DGCCRF, Licence Ouverte 2.0) | none | not stated on the dataset page | yes | ✅ **Recommend** |
| School holidays — data.education.gouv.fr (zone B, Nice) | none | not stated | yes | ✅ Recommend |
| Public holidays — calendrier.api.gouv.fr | none | none stated; static files | yes | ✅ Recommend (see §5.2) |
| EDF Tempo colour — api-couleur-tempo.fr, RTE `tempoLight` as backup | none | none stated | yes | ✅ Recommend, **if the house is on a Tempo contract** |
| Calendar — a Google Calendar "secret address in iCal format" | the secret URL (kept like a key) | none | yes | ✅ Recommend |
| Trains from Grasse — SNCF GTFS + GTFS-RT on transport.data.gouv.fr | none | none stated; ODbL | yes | 🔶 Maybe — works, but 1.5 MB of protobuf per call and a 54 MB timetable |
| Trains from Grasse — api.sncf.com | free token (name, e-mail; no card) | 5 000 requests/day | yes | ✅ Recommend if a sign-up is acceptable: tiny JSON, real time |
| Zou! buses (650, 651, 660, 662 from the gare) — GTFS + GTFS-RT | none | none; Licence Ouverte 2.0 | yes | 🔶 Maybe — 3.2 MB per real-time call; the timetable belongs on the phone page |
| Météo-France vigilance (weather warnings, 06) | free account, key or 1-hour token | ≈ 60 requests/min | yes | 🔶 Maybe — ≈ 600 KB of JSON, no key-free live source exists |
| RTE Ecowatt (grid alerts) | free account + OAuth2 client | one call per 15 min | yes | 🔶 Maybe — winter only |
| BAN geocoding (api-adresse.data.gouv.fr) + geo.api.gouv.fr | none | not stated | yes | ✅ Recommend as helpers |
| OpenStreetMap Overpass (nearest pharmacy, bakery, opening hours) | none (`User-Agent` required) | < 10 000 queries/day; ODbL | OSM names and hours | 🔶 Maybe — one 504 and two time-outs in the tests; cache the 30 nearest places weekly, never query live |
| Todoist (shopping list sync) | free personal token | 1 000 partial syncs per 15 min | yes | 🔶 Maybe — only if the household already uses it; Bello's own list in `BelloDb` on the phone page needs no account |
| La Poste Suivi (parcel tracking) | free key (Okapi account) | **unverified** — the portal is JavaScript only | yes | 🔶 Maybe |
| Frankfurter, Open Food Facts | none | see §5.2 | — | ✅ Recommend (see §5.2) |
| Pharmacie de garde | — | — | — | ❌ Drop — **no API exists** (confirmed on the data.gouv forum, April 2026): Bello should say « appelez le 3237 » |
| TheMealDB (recipes) | test key `1`, development only | 100 results | **no French content** | ❌ Drop — recipes stay with the model and the `[détails]` page |

**Air and pollen** — `https://air-quality-api.open-meteo.com/v1/air-quality?latitude=43.658&longitude=6.926&current=european_aqi,pm10,pm2_5,ozone,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen&timezone=Europe%2FParis`
(HTTP 200, 0.13 s, 710 B) → « La qualité de l'air à Grasse est moyenne, indice 47 ; le polluant
principal est l'ozone. » / « Presque pas de pollen aujourd'hui : un peu d'armoise. » Pollen is
Europe-only and seasonal (CAMS, 4-day forecast). `daily=european_aqi_max` answers 400: use
`hourly` with `forecast_days=1` and take the maximum on the tablet.

**The sea** — `https://marine-api.open-meteo.com/v1/marine?latitude=43.54&longitude=7.02&current=wave_height,sea_surface_temperature&daily=wave_height_max,sea_surface_temperature_max&forecast_days=2&timezone=Europe%2FParis`
(HTTP 200, 0.16 s, 777 B; the grid point is 9 km off Cannes) → « La mer est à 26 degrés au large de
Cannes, avec des vagues de 40 centimètres : elle est calme. »

**Fuel** — dataset `prix-des-carburants-en-france-flux-instantane-v2` (9 805 stations, updated
during the day), with a distance filter around the house and a freshness filter:
`https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records?where=within_distance(geom,geom'POINT(6.926 43.658)',8km) AND e10_prix IS NOT NULL AND e10_maj>="2026-09-11"&select=adresse,ville,e10_prix,e10_maj,gazole_prix,distance(geom,geom'POINT(6.926 43.658)') as dist&order_by=e10_prix&limit=3`
(HTTP 200, 0.17 s, 547 B) → « L'E10 le moins cher près de Grasse est à 1,99 € à la station du
quartier Moulin de Brun, sur la RD 4, à un kilomètre et demi. » There is **no brand field**, so the
station is named by its address; stale rows exist (a Valbonne station last updated three weeks
ago), so the `*_maj` filter is not optional. The list of stations belongs on the phone page.

**School holidays** — `https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/fr-en-calendrier-scolaire/records?where=zones="Zone B" AND location="Nice" AND start_date>="2026-09-18"&select=description,start_date,end_date,population&order_by=start_date&limit=3`
(HTTP 200, 0.13 s, 465 B) → « Les prochaines vacances, la Toussaint, commencent samedi 17 octobre ;
les cours reprennent lundi 2 novembre. » Dates are UTC midnights (22:00Z is the next Paris day);
summer has an "Enseignants" row to drop.

**Tempo** — `https://www.api-couleur-tempo.fr/api/jourTempo/tomorrow` (HTTP 200, 0.11 s, **80 B**)
→ « Demain est un jour bleu Tempo, comme aujourd'hui. » It is one volunteer's cache of RTE's daily
publication (around 11:00); RTE's own undocumented `https://www.services-rte.com/cms/open_data/v1/tempoLight`
(HTTP 200, 112 B) is the backup. EDF's old endpoint is dead (404). Ecowatt has no key-free live
source: the RTE portal needs a free account and an OAuth2 client, one call per quarter hour.

**The calendar** — a Google Calendar's "secret address in iCal format" is a plain HTTPS GET, no
OAuth. Verified on the public French holidays calendar
(`https://calendar.google.com/calendar/ical/fr.french%23holiday%40group.v.calendar.google.com/public/basic.ics`,
HTTP 200, 0.24 s, 82 KB, 209 events) → « Demain, vous avez "Dentiste" à 10 h 30. » The URL *is*
the credential: it goes in `config.json`, masked in exports like a key. A personal calendar carries
its whole past (hundreds of KB): fetch hourly at most, parse line by line, handle folded lines and
the three `DTSTART` forms; recurring events can be ignored at first. Google's CalDAV needs OAuth;
iCloud and Nextcloud CalDAV would work with a password but were not tested.

**Trains** — the key-free path works: the SNCF GTFS on transport.data.gouv.fr (3.9 MB zip, daily)
plus the GTFS-RT trip updates (`https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates`,
HTTP 200, 2.7 s, **1.5 MB** of protobuf, refreshed every 2 min) gave the next departures from Grasse
(stop `87757724`) at 17:21, 17:38, 17:53 and 18:08, on time → « Le prochain train part de Grasse à
17 h 21 pour Cannes et Nice, à l'heure. » But 1.5 MB per question and a 54 MB `stop_times` file are
heavy for this tablet: the day's timetable would have to be precomputed (on the Mac, or nightly)
and the real-time feed read with a hand-written protobuf reader. The `export-ter-gtfs-last.zip`
feed is stale (dated 2025). **api.sncf.com** is the practical route: free token, 5 000 requests a
day, one small JSON per question (`/v1/coverage/sncf/stop_areas/stop_area:SNCF:87757724/departures`),
not tested for want of a token. The Zou! buses have the same shape: a 7.9 MB GTFS and a 3.2 MB
real-time feed (delays of 0–8 min at the gare today); the static timetable is cheap and fits the
phone page, the real time does not fit the tablet.

**Places** — BAN (`https://api-adresse.data.gouv.fr/search/?q=place+aux+aires+grasse&limit=1`,
HTTP 200, 0.16 s, 569 B) turns a spoken place into coordinates for the fuel and place queries.
Overpass gave the pharmacies around the house with their hours (« La pharmacie du Jeu de Ballon est
ouverte jusqu'à 19 heures ») but in 6.9 s from a mirror after the main server answered 504, and the
supermarket query never succeeded in four tries: usable only as a weekly cached list. The on-duty
pharmacy has no database at all: the honest answer is the 3237 number.

**Pharmacie de garde, parcels, lists** — no API for the first. La Poste's tracking API exists and
is free with an account, but its quota could not be read (JavaScript-only portal), and a tracking
number is nothing anyone wants to say aloud. Todoist's API is free with a personal token; Bello's
own list in `BelloDb`, shown on the phone page, costs no account and no quota.

### 5.2 Knowledge and language

Bello's providers already answer general-knowledge questions, translate and define words. A service
earns its place here only when it adds something a model cannot give: an exact figure, today's
value, or the certainty of not having invented anything.

| Service | Needs | Quota (official) | French | Adds over the model | Verdict |
|---|---|---|---|---|---|
| French Wikipedia — search + summary | none (a descriptive `User-Agent`) | "no hard speed limit" on reads; be sequential | yes | exact dates and figures, current pages, no invention | ✅ **Recommend** |
| French Wikipedia — "ce jour-là" (`selected`) | none | same | yes | curated, dated events; 29 KB (`all` is 1.1 MB: never) | ✅ **Recommend** |
| French Wiktionary (`prop=extracts`) | none | same | yes | a real definition with gender and pronunciation | ✅ Recommend |
| geo.api.gouv.fr (communes) | none | 50 calls/s/IP, Licence Ouverte 2.0 | yes | official INSEE population, postcodes, a French geocoder | ✅ Recommend |
| calendrier.api.gouv.fr (jours fériés) | none | not stated, Licence Ouverte 2.0 | yes | the exact list, 318 B per year | ✅ Recommend (cache yearly) |
| Annuaire de l'administration (mairie) | none | not stated, Licence Ouverte 2.0 | yes | the real opening hours and phone of the mairie | ✅ Recommend |
| Frankfurter (currency) | none | "no quotas" | n/a | today's ECB rate | ✅ Recommend |
| Open Food Facts (search) | none (`User-Agent` mandatory) | 10 searches/min/IP | yes | the label's Nutri-Score and kcal | ✅ Recommend |
| Open-Meteo sun and moon | none, same call as the weather | — | n/a | sunrise, sunset, moon phase | ✅ Recommend, no new service |
| Nominis (saint du jour) | none; a link back asked | none stated | yes | a ready French paragraph on the saint | 🔶 Maybe: the first names differ from the La Poste calendar |
| MyMemory (translation) | none (an e-mail lifts the limit) | 5 000 chars/day anonymous (third-party figure) | yes | a keyless fallback only | 🔶 Maybe |
| DeepL API | free key, no card | plan changed: 1 M characters **in total**, not per month | yes | the best translation quality, once | 🔶 Maybe |
| Wolfram Alpha Short Answers | free key | 2 000 calls/month | **English only** | exact maths, but needs two translations | ❌ Drop |
| Open Library | none | 3 req/s | partly | nothing over Wikipedia, 6–8 s per call | ❌ Drop |
| Google Books | free key (Cloud project) | keyless: HTTP 429 "quota 0" today | partly | — | ❌ Drop |
| Dicolink | **paid** (10 €/month) | — | yes | — | ❌ Drop |
| LibreTranslate | key on `.com`, no keyless mirror alive | — | yes | — | ❌ Drop |
| French quotes / trivia APIs | — | — | — | nothing free and stable exists; the model does it | ❌ Drop |
| Académie française, CNRTL | — | — | yes | HTML only, no API | ❌ Drop |

**French Wikipedia** — the "no invention" source for people, places, works and dates.
`https://fr.wikipedia.org/w/api.php?action=query&list=search&srsearch=tour%20eiffel&format=json&srlimit=3`
(HTTP 200, 0.36 s, 1.6 KB) resolves what was said to a title, then
`https://fr.wikipedia.org/api/rest_v1/page/summary/Grasse` (HTTP 200, 0.24 s, 2.3 KB) gives a
one-line `description` and an `extract`. "Qui a écrit L'Étranger ?" → « L'Étranger est le premier
roman publié d'Albert Camus, paru en 1942. » "C'est quoi Grasse ?" → « Grasse est une commune
française des Alpes-Maritimes, en région Provence-Alpes-Côte d'Azur. » Handle
`type: "disambiguation"` (search first), strip the phonetics between dashes in `extract`, and send a
`User-Agent` in the Wikimedia format (`Bello/1.0 (contact)`) — generic agents "may be blocked
without notice". Content is CC BY-SA: a line in the settings screen. "Que s'est-il passé un
18 septembre ?" → `feed/onthisday/selected/09/18` (0.06 s, 29 KB) → « Le 18 septembre 1981,
l'Assemblée nationale votait la loi sur l'abolition de la peine de mort. »

**French Wiktionary** — the REST `page/definition` route is English-only (HTTP 501 on
`fr.wiktionary`); the plaintext extract works:
`https://fr.wiktionary.org/w/api.php?action=query&prop=extracts&explaintext=1&exchars=1200&exlimit=1&titles=maison&format=json&redirects=1`
(HTTP 200, 0.18 s, 1.4 KB). The first line after the first `=== Nom commun ===` (or `Verbe`,
`Adjectif`) heading is the definition: « Maison, nom féminin : bâtiment servant de logis,
d'habitation, de demeure. » A missing word comes back as `"missing": ""`.

**geo.api.gouv.fr** — `https://geo.api.gouv.fr/communes?nom=Grasse&fields=nom,code,codesPostaux,population,departement,region&boost=population&limit=2`
(HTTP 200, 0.23 s, 496 B) → « Grasse compte 50 970 habitants, code postal 06130, dans les
Alpes-Maritimes. » With `centre` it is also a free French geocoder for the weather ("à Vence").

**Jours fériés** — `https://calendrier.api.gouv.fr/jours-feries/metropole/2026.json` (HTTP 200,
0.08 s, 318 B): « Le prochain jour férié est la Toussaint, dimanche 1er novembre. » Fetched once a
year and kept in `BelloDb`; it also lets the alarms say "c'est férié demain".

**Annuaire de l'administration** — `https://api-lannuaire.service-public.gouv.fr/api/explore/v2.1/catalog/datasets/api-lannuaire-administration/records?where=code_insee_commune%3D%2206069%22%20AND%20pivot%20LIKE%20%22mairie%22&select=nom,telephone,plage_ouverture&limit=1`
(HTTP 200, 0.16 s, 659 B) → « La mairie de Grasse est ouverte du lundi au vendredi de 8 h 15 à
16 h 30, et le samedi matin pour les passeports et cartes d'identité. Son numéro est le
04 97 05 50 00. » The hours and phone are JSON inside a string (decode twice). A model invents
opening hours; this is the official record.

**Frankfurter** — `https://api.frankfurter.dev/v1/latest?base=USD&symbols=EUR&amount=100`
(HTTP 200, 0.31 s, **71 B**) → « Cent dollars font 87 euros et 26 centimes, au cours du
18 septembre. » No key, "no quotas", ECB rates. The old `api.frankfurter.app` host redirects.

**Open Food Facts** — `https://search.openfoodfacts.org/search?q=nutella&langs=fr&page_size=2&fields=product_name,brands,nutriscore_grade,nutriments,quantity`
(HTTP 200, 0.20 s, 1.3 KB) → « Le Nutella a un Nutri-Score E : 544 kilocalories et 57 grammes de
sucre pour 100 grammes. » The main host answered 503 for three calls out of four this afternoon;
the search host is the one to use, with a plain « je n'ai pas trouvé » when it fails. ODbL:
attribution in the settings screen. A `User-Agent` with a contact is mandatory.

**Sun and moon** — no new service: Open-Meteo's daily `sunrise,sunset,moonrise,moonset,moon_phase`
come with the weather call (`moon_phase` is a 0–1 fraction: 0 nouvelle lune, 0.5 pleine lune). For
Grasse today: sunset 19:37, moonrise 15:10, phase 0.236 → « Le soleil se couche à 19 h 37. La lune
est en premier croissant. » Eight labels in `ToolReplies`.

**Nominis** — `https://nominis.cef.fr/json/nominis.php` (HTTP 200, 0.09 s, 7.9 KB) gives the
saint of the day with a French paragraph. But its "prénoms majeurs" for 18 September are Moana,
Océane and Richarde, while the calendar on every French wall says **Nadège** (Nominis lists her
under `saints.autres`). For « c'est la fête de qui ? » the honest answer is a bundled 365-line
table of the La Poste calendar, no network at all; Nominis stays useful for « raconte-moi le saint du
jour ». The data.gouv "saints et fêtes" file was checked too: no Nadège either, and mojibake.

**Translation** — the providers already translate well. MyMemory
(`https://api.mymemory.translated.net/get?q=…&langpair=fr|en`, HTTP 200, 0.8–1.0 s, 1.4 KB) works
without a key, but its top `responseData` is a fuzzy memory match that dropped "s'il vous plaît":
take the `matches[]` entry marked `created-by: "MT!"`. DeepL's free API plan no longer exists as a
monthly allowance: the 2026 "Developer" plan is one million characters **in total**, with no card
(years of spoken sentences, but a one-off budget). Neither is worth a tool on its own; MyMemory is
a reasonable fallback if every provider is in cooldown.

### 5.3 Entertainment

**What the tablet can play.** The tablet's own CA store was pulled for this study
(`/system/etc/security/cacerts`, 157 roots, the newest from 2013). Present: DigiCert Global Root
G2 and CA, GlobalSign, Starfield Services Root G2, Baltimore, GeoTrust. **Absent: ISRG Root X1
(Let's Encrypt), USERTrust (Sectigo, Gandi), GTS Root R4.** Every stream below is given with the
scheme that `MediaPlayer` can open on this tablet; the API calls themselves go through OkHttp and do
not care.

| Service | Needs | Quota (official) | French | Verdict |
|---|---|---|---|---|
| Radio: Radio France streams (`icecast.radiofrance.fr`), RTL, Nostalgie, Radio Classique | none | — | yes | ✅ **Recommend** — six stations resolved, MP3 128 k, all playable over **http** |
| Radio Browser (directory) | none (a speaking `User-Agent`) | none stated | FR stations | ✅ Recommend as the fallback lookup when a hard-coded URL dies |
| Radio France podcasts (RSS) | none | — | yes | ✅ Recommend — the enclosure plays over http |
| JokeAPI (`lang=fr`) | none | 120 requests/min | 999 French jokes, one category | ✅ Recommend, with a small local blocklist |
| OpenAgenda legacy export (`openagenda.com/agendas/<id>/events.json`) | none | "legacy", 20 per page | yes: 53 events in Grasse this weekend | ✅ Recommend — with the official API (free account) as its successor |
| iTunes Search API | none | ≈ 20 calls/min | FR store, 30-s previews over https (DigiCert) | ✅ Recommend for « qui chante… », « ça ressemble à quoi » |
| TMDB | free key (account + a form; no card) | ≈ 40 requests/s | `language=fr-FR&region=FR` | ✅ Recommend for the cinema releases — needs the sign-up |
| football-data.org | free key (e-mail; no card) | 10 calls/min, 12 competitions **including Ligue 1**, scores delayed | fixtures, results, tables | ✅ Recommend — not tested (key) |
| TheSportsDB (test key `123`) | none | 30 req/min; **home matches only** on the free key | Ligue 1 | 🔶 Maybe — tested, but half the fixtures are invisible |
| Deezer public API | none today | undocumented; new app tokens no longer issued | yes | 🔶 Maybe — works, but unregistered use of an API that can no longer be registered |
| lyrics.ovh | none | none stated | yes | 🔶 Maybe — unofficial lyrics; quote two lines at most |
| Blagues API | free token via a **Discord login** | not documented | 2 786 French jokes, six categories | 🔶 Maybe — the better corpus, if a Discord account is acceptable |
| Radio France open API (metadata: what is playing) | free account, "restreint" | 1 000 requests/day | yes | 🔶 Maybe — not tested |
| NASA APOD (`DEMO_KEY`) | none (a free key lifts the limit) | 30/hour, 50/day per IP | English; the picture is on a Let's Encrypt host | 🔶 Maybe — the face could show it, via the app as proxy |
| open-notify ISS position | none, **http only** (port 443 closed) | none | n/a | 🔶 Maybe — fun, needs a coarse "over which ocean" table |
| xmltvfr.fr TV programme | none | one 1.25 MB gzip a day | yes | 🔶 Maybe — unofficial data, legal grey |
| API-Football | free key | 100 requests/day | yes | 🔶 Maybe — redundant |
| Podcast Index | free key + secret | undocumented | yes | 🔶 Maybe — Apple search plus RSS already cover it |
| OMDb | free key | 1 000/day | **English only** | ❌ Drop |
| Cinema showtimes (Allociné, SCARE open data) | — | — | — | ❌ Drop — no API; the open dataset is dead; no cinema in Grasse in it |
| Digital3D TV API | key | **2 requests/day** | yes | ❌ Drop |
| open-notify crew list | none | — | stale (the 2024 roster) | ❌ Drop |
| FDJ lottery results | — | — | — | ❌ Drop — no open data, the unofficial endpoint answers 400 |

**Radio** — the six streams, checked with a `HEAD` from the Mac and the pulled CA store:

| Station | URL that plays on the tablet | Codec |
|---|---|---|
| France Inter | `http://icecast.radiofrance.fr/franceinter-midfi.mp3` | MP3 128 k (`-hifi.aac` 192 k, `-lofi.mp3` 32 k) |
| franceinfo | `http://icecast.radiofrance.fr/franceinfo-midfi.mp3` | MP3 128 k |
| FIP | `http://icecast.radiofrance.fr/fip-midfi.mp3` | MP3 128 k |
| RTL | `http://icecast.rtl.fr/rtl-1-44-128` → 302 → `streaming-ice.audiomeans.fr` (https fine too: Starfield) | MP3 128 k |
| Nostalgie | `http://streaming.nrjaudio.fm/oug7girb92oc` (https fine too: DigiCert G2; the URL carries tracking parameters) | MP3 128 k |
| Radio Classique | `http://radioclassique.ice.infomaniak.ch/radioclassique-high.mp3` (https root USERTrust: **missing**) | MP3 128 k |

Radio France's https endpoints are Let's Encrypt: **http only** for them. The Radio Browser
directory (`https://de1.api.radio-browser.info/json/stations/search?countrycode=FR&order=clickcount&reverse=true&limit=5`,
HTTP 200, 0.46 s, 1.2 KB per station) is the lookup when a URL moves (RTL moved to audiomeans);
its rules: resolve `all.api.radio-browser.info` by DNS, send a speaking `User-Agent`, report a
click. Avoid its HLS entries (`.m3u8`) on Android 5; take the icecast MP3 ones. « Mets France
Inter » → « Je mets France Inter. » — and "stop", the barge-in and the wake word have to know that
the speaker is busy: this is the phase-sized feature of §3.

**Podcasts** — `https://radiofrance-podcast.net/podcast09/rss_10078.xml` (Les pieds sur terre,
HTTP 200, 0.26 s, 199 KB — fetch the first 30 KB with a `Range` header, the newest item is at the
top). The enclosure is on a Let's Encrypt host, but its http form redirects to
`http://media.radiofrance-podcast.net/…m4a` (HTTP 200, `audio/mp4`, 43 MB): playable. « Le dernier
épisode des Pieds sur terre » → « Le dernier épisode est sorti aujourd'hui. Je le lance ? »

**JokeAPI** — `https://v2.jokeapi.dev/joke/Any?lang=fr&safe-mode` (HTTP 200, 0.07 s, 395 B):
« Comment appelle-t-on un chien qui a des lunettes ? … Un optichien ! » 999 French jokes, all
marked safe, one category. In 13 samples, "safe" was loose (one Hitler joke, two "blondes"): keep a
local blocklist of ids and words, and let the face do `[happy]`.

**OpenAgenda** — the official v2 API needs a key even to read (free account); the legacy export
works without one: `https://openagenda.com/agendas.json?search=Grasse` (HTTP 200, 0.16 s, 17 KB,
42 agendas: Théâtre de Grasse, Médiathèques de Grasse, Journées du patrimoine PACA…) then
`https://openagenda.com/agendas/2476341/events.json?city=Grasse&limit=3` (HTTP 200, ≈ 1.0 s,
30 KB, 53 events). « Qu'est-ce qu'on fait ce week-end à Grasse ? » → « Ce sont les Journées du
patrimoine : samedi à 15 h, "Grasse au temps de Charles Nègre" à la Maison du Patrimoine. Je
t'envoie la liste sur ton téléphone ? » — the list is exactly what the page on the phone is for.
About 10 KB per event: always `limit=3`. The export calls itself "legacy": budget the free account
for the day it goes.

**Music** — iTunes Search (`https://itunes.apple.com/search?term=Stromae%20Alors%20on%20danse&country=FR&media=music&limit=3`,
HTTP 200, 0.27 s, 4.8 KB) identifies a song and gives a 30-second AAC preview on a DigiCert host
(https plays). Apple's terms want promotional content next to a store badge and "not used for
independent entertainment value": identification and a short preview, no more. Deezer's public
endpoints (`/search`, `/chart/0/tracks?limit=5`) still answer with no key, and its previews play
over http; but Deezer no longer issues app tokens and its FAQ says the "simple API" needs a login
to accept the terms, so it is not something to build on.

**Sport** — football-data.org's free tier is the real thing (Ligue 1, Champions League, fixtures,
results and tables, scores delayed, 10 calls a minute) but needs a key; the unauthenticated call
answered 403. TheSportsDB answers with the public test key today: « Quand joue Nice ? » → « Nice
reçoit Lille dimanche 20 septembre à 15 h 15 à l'Allianz Riviera » and « Le 5 septembre, Nice et
Le Mans, un partout » — but the free key only shows **home** fixtures, so the "next match" is wrong
half the time. Fallback only.

**Cinema** — TMDB gives French titles and overviews for « qu'est-ce qui sort cette semaine ? »
(`/movie/now_playing?language=fr-FR&region=FR`) at no cost; it wants an account, a short form and
the attribution line "This product uses the TMDB API but is not endorsed or certified by TMDB". Not
called (no test key exists). Showtimes are out of reach: Allociné has no API and the independent
cinemas' open dataset is broken.

**The rest** — open-notify's ISS position (`http://api.open-notify.org/iss-now.json`, HTTP 200,
112 B, port 443 closed) is a nice « où est la station spatiale ? » with a coarse ocean table; its
crew list is the 2024 roster. NASA's picture of the day works with `DEMO_KEY` (HTTP 200, 1.4 KB,
English explanation, picture on a Let's Encrypt host: the face WebView would need the app to fetch
it). TV listings exist only as an unofficial daily XMLTV file. Lottery results have no open data.

## 6. Fresh information from the web, and the voice

### 6.1 The open requirement: questions that need today's web (FR-TOOL-07)

Two attempts had failed while building: Gemini's OpenAI-compatible endpoint refused the
`google_search` tool, and Groq's compound model "refused the request". Both are explained now.

| Service | Needs | Quota (official) | French | Verdict |
|---|---|---|---|---|
| **Gemini native endpoint + `google_search`**, model `gemini-2.5-flash` | the Gemini key already on the tablet | "Free of charge, up to 500 RPD" for 2.5 Flash and Flash-Lite ([pricing](https://ai.google.dev/gemini-api/docs/pricing), 2026-09-02). Gemini 3.x: grounding "not available" on the free tier | yes | ✅ **Recommend** — tested, it works |
| Tavily search | free key, "no credit card required" | 1 000 credits/month, a basic search is 1 credit ([docs](https://docs.tavily.com/documentation/api-credits)) | results follow the query; `country: france`, `topic: news` | ✅ Recommend as the second source |
| Google News RSS (`news.google.com/rss/search?q=…&hl=fr&gl=FR&ceid=FR:fr`) | none | undocumented; the feed's own copyright line allows "personal, non-commercial use" in a personal reader | yes | ✅ Recommend for « quoi de neuf sur X ? » (135 KB per query: keep 10 items) |
| Bing News RSS (`bing.com/news/search?q=…&format=rss&setlang=fr-fr&cc=fr`) | none | undocumented | yes, with snippets | 🔶 Maybe — lighter (12 KB), the real URL is in the link |
| Jina Reader (`r.jina.ai/<url>`) | none | 20 requests/min keyless; a free key gives 500/min and 10 M tokens once ([jina.ai/reader](https://jina.ai/reader/)) | yes | ✅ Recommend to read one article (9 KB with `X-Target-Selector: article`) |
| You.com Search API | free key, no card | 100 queries/day ([pricing](https://you.com/pricing)) | `country: FR`, `language: FR` | 🔶 Maybe |
| SerpApi | free key (card: not stated) | 250 searches/month | `gl=fr&hl=fr`, Google answer boxes | 🔶 Maybe — 8 a day |
| Exa | free key | $10 of credits a month ≈ 1 400 searches | no language parameter | 🔶 Maybe |
| DuckDuckGo Instant Answer | none | undocumented | French Wikipedia abstracts | 🔶 Maybe — nothing live; empty for "OGC Nice" |
| Groq compound (`groq/compound-mini`) | the Groq key | 250 requests/day, 70 000 tokens/min on the free tier (response headers) | yes | ❌ Drop — see below |
| Brave Search API | free key **+ credit card** ("anti-fraud measure") | $5 of credits a month | yes | ❌ Drop |
| Serper | free key | 2 500 queries **once** | yes | ❌ Drop |
| OpenRouter `:online` | key | "will incur extra costs, even with free models" | — | ❌ Drop |
| fr.wikinews | none | — | — | ❌ Drop — locked since 4 May 2026 |
| GDELT | none | one request every 5 s per IP | yes | ❌ Drop — HTTP 429 on both calls |

**Gemini grounding, tested with the project key.** The native endpoint,
`POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent` with
`"tools": [{"google_search": {}}]`, answered « En une phrase : quelle est la principale actualité en
France aujourd'hui, 18 septembre 2026 ? » in **3.2 s** with a French sentence about the day's
fuel-price record, three search queries and two cited sources (`groundingMetadata`), 465 tokens in
all. Why the earlier attempts failed: the OpenAI-compatible layer only supports grounding on
Gemini 3 and newer, and Gemini 3 has no free grounding — so the free path is the native API with a
2.5 model. The same call on `gemini-3.6-flash` answered 429 *without* the tool too: that was
today's daily quota, used up by the tablet's normal day. Each model has its own bucket, so a second
model on the same key is also a fallback. Two risks: the 2.5 models have "no shutdown date
announced" but are the older generation, and Google's terms expect the "search suggestions" chips
to be displayed — the page card on the face can carry them.

**Groq compound, tested.** The open news question ended in HTTP 413 "Request Entity Too Large" after
4–10 s: the pages the model fetched overflow the free tier's 70 000 tokens per minute. Two narrow
questions (the last OGC Nice score, the weather in Grasse) came back in 0.5 s with *no* tool
executed and « je ne dispose pas des résultats ». Not a live-information source on the free tier.

**The design that follows.** A "fresh" route in the Router, matched locally like the tools
(« aujourd'hui », « hier », « dernier », « résultat », « score », « prix », « actualité », « quoi de
neuf sur »): first Gemini 2.5 Flash with grounding, as a new provider type in the gateway so that
cooldowns, counters and fallback apply as everywhere; then Tavily with `include_answer` when a key
is configured; then the headlines route already used by the news tool, fed by Google News RSS for the
subject and summarised by the current provider. About 500 free grounded answers a day against a
household's twenty.

### 6.2 The voice

Bello's voice today is Android's Google TTS, pitched up, with no network and no latency. Every free
hosted voice below adds a round trip; the question is whether one is good enough to be worth it.

| Service | Needs | Quota (official) | French | Verdict |
|---|---|---|---|---|
| **Gemini TTS** (`gemini-2.5-flash-preview-tts`) | the Gemini key | free tier "free of charge"; daily limit only visible in AI Studio | yes, 30 voices, a style can be asked for in the prompt | 🔶 Maybe — tested: works, but **5.7 s** for a 7-second sentence |
| Groq Whisper `whisper-large-v3-turbo` (STT fallback, FR-STT-03) | the Groq key | 20 requests/min, 2 000/day, 8 hours of audio a day ([rate limits](https://console.groq.com/docs/rate-limits)) | yes | ✅ Recommend, as planned |
| Gemini audio input as STT | the Gemini key | free; 32 tokens per second of audio | yes | 🔶 Maybe — a third STT fallback |
| Microsoft Edge TTS (unofficial endpoint) | none | none; needs a signed `Sec-MS-GEC` header and a Chrome version string that must be bumped | the best free French voices (`fr-FR-DeniseNeural`, `HenriNeural`…) | ❌ Drop — HTTP 503 since 21 July 2026 in the reference client, two earlier cut-offs; nothing to build on |
| Mistral Voxtral TTS / Voxtral Mini Transcribe | free key | "free mode" limits only shown after login | yes (9 languages) | 🔶 Maybe — worth a look with an account |
| Deepgram Aura-2 (`aura-2-agathe-fr`, `hector-fr`) / Nova-3 | free key, no card | $200 of credit **once** (≈ 6.6 M characters) | yes | 🔶 Maybe — years of use, then it stops |
| ElevenLabs | free key | 10 000 credits/month ≈ 10 minutes of speech | yes | ❌ Drop |
| Google Cloud TTS | billing account (card) | 1–4 M chars/month | yes | ❌ Drop |
| Groq TTS | key | — | English and Arabic only | ❌ Drop |
| OpenAI TTS / Piper on-device | — | paid / impossible on 32-bit ARM with 1.4 GB | — | ❌ Drop |

**Gemini TTS, tested with the project key.** « Bello ! Aujourd'hui à Grasse, il fait vingt-quatre
degrés, ciel dégagé. Banana ! », voice Puck, asked for a cheerful childlike tone: HTTP 200 in
**5.7 s**, 321 KB of raw PCM (24 kHz, 16-bit, 6.7 s of audio), 167 audio tokens. The sample is at
`scratchpad/area-e/gemini-tts-puck-fr.wav` for listening. At that latency it cannot replace the
local voice on the answer path (an answer today takes 0.6–2 s end to end); it could pre-render
the sentences Bello says every day — greetings, the timer ring, the offer of the page — and cache
them, if the voice is judged better than the pitched Google one. A `MediaPlayer` path for PCM
wrapped in a WAV header is small; it would also serve any radio or podcast feature.

## 7. What could not be verified

- **Anything behind an account** was read from the documentation, not called: api.sncf.com (and
  its CGU pages answered 404), football-data.org, TMDB (the form's fields come from community
  reports), Tavily, You.com, SerpApi (card not stated), Météo-France, RTE Ecowatt, La Poste
  (quota unreadable), Todoist (card not stated), DeepL, Wolfram, the Radio France metadata API,
  Podcast Index, Blagues API, the official OpenAgenda API.
- **Free-tier figures behind a login**: Gemini's per-model daily limits (chat, grounding, TTS) are
  only shown in AI Studio; Mistral's "free mode" limits likewise. The 500-a-day grounding figure is
  the pricing page's.
- **Third-party figures**: MyMemory's 5 000/50 000 characters a day and DeepL's "one million
  characters in total" come from 2026 third-party pages and a search snippet, the official pages
  refusing non-browser fetches.
- **Opendatasoft anonymous quotas** (fuel, school calendar, annuaire) and calendrier.api.gouv.fr:
  "non communiqué".
- **Playback on the tablet itself**: the stream verdicts come from the pulled CA store and `HEAD`
  requests from the Mac, not from a `MediaPlayer` test; HLS on Android 5 was avoided, not tested.
- **Overpass reliability** (one 504, two time-outs, one query never answered), Open Food Facts'
  main host (503 three times out of four), and whether Google Books' keyless 429 is permanent.
- **Gemini TTS voice quality in French**: the sample was saved, not listened to.
- **Google News link decoding** (the RSS links are redirect tokens) and Jina's keyless tier
  stability (a token header shows metering already exists).

## 8. Proposed next steps

A Phase 9 in three steps, each ending on the tablet like the others, smallest risk first.

1. **9a — Bello knows today** (no sign-up; the two keys already on the tablet): the "fresh" route
   with Gemini 2.5 Flash grounding as a gateway provider type, Google News RSS as its fallback;
   air, pollen, sea, sun and moon folded into the weather tool; fuel prices; school and public
   holidays; Tempo. Plus the `User-Agent` and the attribution lines. Done when « qui a gagné hier
   soir ? » and « où est le gazole le moins cher ? » are answered in the room, and the day's
   grounded questions are counted like the providers'.
2. **9b — Bello knows things**: Wikipedia and Wiktionary, the mairie, Open Food Facts, currency,
   jokes, the weekend's events on the phone page, the household calendar by secret URL. Then the
   free sign-ups, one at a time as they are wanted: SNCF, football-data, TMDB, Tavily,
   Météo-France — each is a config entry, a daily counter and a settings line (§3).
3. **9c — Bello plays** (a phase on its own): radio and podcasts over HTTP with `MediaPlayer`.
   The risk to measure first, in a one-hour spike before any code: the microphone hears the
   speaker. The wake-word energy gate would open on the music and feed Vosk continuously (CPU),
   and a station saying "bello" would wake Bello. The likely design is to pause the wake word
   while audio plays and rely on the tap, or duck the volume every few seconds for a listening
   window; both are measurable with `scripts/wake-live.sh room 30` and a station on.

What this needs from you: nothing for 9a; for 9b, the Google Calendar secret address if the
calendar feature is wanted, and whether the house is on a Tempo contract; for the Tier 2 services,
the free sign-ups (SNCF, football-data.org, TMDB, Tavily, Météo-France), each a form with an
e-mail and no card.

Raw responses of every call made for this study are kept, git-ignored, in
`spikes/results/free-services/` (the multi-megabyte GTFS and protobuf files excluded).
