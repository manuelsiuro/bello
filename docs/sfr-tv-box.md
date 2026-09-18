# Controlling the SFR TV box from Bello — a study

| | |
|---|---|
| Status | Done — protocol verified on the box in the house; key names beyond `mute` await ten minutes in front of the television (§7) |
| Date | 2026-09-18 |
| Branch | `research/sfr-tv-box` |
| Question | Can Bello drive the SFR TV decoder the way the "SFR TV" Android app's remote does, and how? |
| Answer | **Yes.** The decoder in the house is an SFR STB8 (SFR Box 8 TV). It exposes an unauthenticated JSON-over-WebSocket remote on port 7682, the one the SFR TV app uses. Verified from the Mac: status and versions read, a key accepted, 10 ms round trip. |

## 1. What is in the house

Found on the home network on 2026-09-18 from the Mac (ARP after a ping sweep, SSDP, mDNS):

| Host | Address | What it is |
|---|---|---|
| `gen8` | 192.168.1.1 | the SFR Box 8 router (MiniUPnPd on OpenEmbedded) |
| `stb` | 192.168.1.216 | **the TV decoder**: UPnP `friendlyName` `SFR_STB8_39DC`, manufacturer SFR, device types `urn:neufboxtv-org:device:SetTopBox:1` and `MultiroomDevice:1`, Linux 4.9.132 — a proprietary Linux box, not Android TV |
| `android-49aacd84325969c2` | 192.168.1.199 | the Galaxy Tab 4, Bello's tablet (same MAC as `wlan0`) |

The decoder advertises two things: a UPnP "Resources" service on port 49153 (one action, `getUsage`,
bitrates for multiroom — not a remote), and an mDNS service **`STB8._ws._tcp.local.` → `STB8.local.:7682`**.
The router's DNS also resolves the plain name **`stb`** to the decoder. Only ports **7682, 7684** and
49153 answer among the usual suspects (22, 80, 443, 5555, 6466, 6467, 7686, 7688, 7742, 8080,
8443… are closed).

## 2. The remote protocol, as verified on the box

Source of the message format: the open-source project
[GehDoc/sfr-tv-box-remote](https://github.com/GehDoc/sfr-tv-box-remote) (MIT, created 2026-01-13,
last push 2026-02-02), which documents a transport "identified by reverse-engineering the SFR TV
APK" for three generations (LaBox, STB7, STB8) and implements the STB8 one. Everything below was
then **checked against the decoder in the house** with a hand-written WebSocket client
(`spikes/results/sfr-tv-box/wsprobe.py`).

### 2.1 Transport

- Plain WebSocket, no TLS, no pairing, no token: `ws://<box>:7682/ws` (the path `/` is accepted
  too). Handshake: `HTTP/1.1 101 Switching Protocols` in **23 ms** from the Mac. The sub-protocol
  `lws-bidirectional-protocol` that every STB7 client sends is echoed back when offered and not
  required. **Port 7684 is a second, identical endpoint** (same handshake, answers `getStatus`) —
  the notification port of the older LaBox; on the STB8 nothing arrived on it in 8 s of listening.
- Text frames carrying one JSON object each. Every request carries `action`, a free-form
  `deviceId` (any string identifies the client; `"bello"` will do) and a `requestId` (the app
  uses the epoch in milliseconds; the box echoes it, which is how a reply is matched).
- The box keeps an idle connection open: **90 s of silence, no ping from either side, and the
  next request still answered**. So either a persistent connection or one per command works;
  connecting costs 23 ms.
- Round trip for a request: **8–12 ms**.

### 2.2 Requests and replies

Read the state — the box was in standby during the tests:

```json
>> {"action": "getStatus", "deviceId": "bello", "requestId": 1789745933063}
<< {"action": "getStatus", "data": {"power": "powerOff"}, "deviceId": "bello",
    "message": "", "remoteResponseCode": "OK", "requestId": 1789745933063}
```

Identify the box:

```json
>> {"action": "getVersions", "deviceId": "bello", "requestId": 1789745937118,
    "params": {"deviceName": "bello"}}
<< {"action": "getVersions", "data": {"boxName": "SFR_STB8_39DC", "boxType": "STB8",
    "macAddress": "6035C0EE39DC", "remoteControlVersion": "1.2.0"}, "deviceId": "bello",
    "message": "", "remoteResponseCode": "OK", "requestId": 1789745937118}
```

Press a key:

```json
>> {"action": "buttonEvent", "deviceId": "bello", "requestId": 1789745977404,
    "params": {"key": "mute"}}
<< {"action": "buttonEvent", "data": {}, "deviceId": "bello", "message": "",
    "remoteResponseCode": "OK", "requestId": 1789745977404}
```

`mute` was chosen because the box was in standby: it was accepted, and the box stayed in standby
(`getStatus` before and after). Six minutes later, on the last read of the session, `getStatus`
answered `powerOn`: no `power` key had been sent, so someone in the house most likely switched the
television on at that moment — but a delayed wake from the `mute` key cannot be excluded, and the
tests stopped there. Sending a key to a box in standby is one of the things to do with you present. The other values are `powerOn` for `power`, and the community
documentation also reports unsolicited notifications `{"data": {"status": "powerOn" | "powerOff"}}`
when the state changes (not observed here: nothing changed during the tests).

**`OK` means "received", not "valid".** A made-up key (`bogusKeyXYZ`) and a made-up action
(`noSuchAction`) both came back `remoteResponseCode: "OK"` with an empty `message`. So a key name
can only be validated by watching the television, never from the reply. `KO` is the documented
failure code.

### 2.3 The keys of the STB8

String values in `params.key`, from the community driver (its list is what the APK maps; every
name below except `mute` is **unverified on the television** — see §7):

| Group | Keys |
|---|---|
| Power, home | `power` (a toggle: read `getStatus` first), `home`, `back` |
| Volume | `volUp`, `volDown`, `mute` ✅ |
| Channels | `channelUp`, `channelDown`, digits `0`…`9` |
| Navigation | `up`, `down`, `left`, `right`, `ok` |
| Playback | `playPause`, `stop`, `fastForward`, `fastBackward`, `record` |

Keys the physical remote has and this list does not (guide, info, menu/options, the coloured keys,
Netflix, "replay", "VOD", "TV", "mosaic") are the first thing to try with the television on.

### 2.4 Discovery

Three ways to find the box, in the order Bello should try them:

1. A configured address: `stb`, `STB8.local` or `192.168.1.216` in `config.json`. The router's DHCP
   gives the decoder a stable lease in practice, and the name `stb` is resolved by the router.
2. mDNS: browse `_ws._tcp` and take the instance whose name starts with `STB8` (`STB7` and
   `ws_server` are the older boxes). Android's `NsdManager` exists since API 16, so it works on
   this tablet, with its usual quirks (resolve one service at a time, retry on `ERROR`).
3. `getVersions` on the candidate confirms `boxType` before any key is sent.

The community discovery spec also mentions a router call (`http://192.168.1.1/lan.getHostsList`);
on this SFR Box 8 it answers 404.

## 3. What Bello would do with it

Spoken French, matched locally like every other tool (§5), one WebSocket message each:

| Said to Bello | What happens |
|---|---|
| « Allume la télé », « éteins la télé » | `getStatus`; `power` only if the state differs from the wish; « C'est allumé. » |
| « Mets la 3 », « mets France 2 » | digits `3`, or the number from a small channel table in the config (`France 2` → `2`, `TF1` → `1`, `Arte` → `7`…), then `ok` if the box needs it (to check) |
| « Chaîne suivante », « chaîne précédente » | `channelUp`, `channelDown` |
| « Monte le son », « baisse le son », « coupe le son » | `volUp` × n, `volDown` × n, `mute` — if the decoder's volume is what the household uses; if the television's is, this goes through HDMI-CEC (§7) |
| « Pause », « lecture », « stop » | `playPause`, `stop` — but « stop » already means "stop talking" to Bello, so the TV needs « stop la télé » |
| « Retour », « menu », « accueil » | `back`, `home` |
| « La télé est allumée ? » | `getStatus` |

Timing: 10 ms per key means « mets la 12 » is two keys in 30 ms; nothing to wait for, and
NFR-PERF-03 (local intents ≤ 1.5 s) is met by a wide margin. Bello's answer should be short
(« Voilà. ») and the face `[happy]`.

## 4. The landscape: other SFR boxes, the official app, Android TV

Bello only has to drive the STB8 in the house, but the same port has carried three generations
of protocol, the household could change box one day, and the study was asked "how the SFR TV app
does it". What the research found, with its sources and dates:

| Decoder | Years | Maker / OS | LAN control path | Confidence |
|---|---|---|---|---|
| Numericable **LaBox** (V1 2012 → "La Box THD 4K" 2015) | 2012–2016 | Sagemcom, proprietary Linux | **WSS 7682** (commands) + **7684** (notifications), sub-protocol `lws-bidirectional-protocol`, **client TLS certificate** taken from the app's `.p12`; hostname `websocket.labox` | High for the protocol ([labox-tv](https://github.com/RemyJeancolas/labox-tv), 2016–2019; an nmap on lafibre.info, July 2018: 7682, 7684, 7686, 7688, 7742); unknown whether alive in 2026 |
| SFR **Décodeur Evolution** | 2010–2017 | Sagemcom, proprietary | nothing documented beyond IPTV/config endpoints | Low |
| **Décodeur Plus / TV Plus / "Box 7 TV" / STB7** | Jan 2017 → still in homes | Sagemcom, proprietary Linux (libwebsockets), Bluetooth remote. **Not Android TV** | **plain WS 7682**, path `/`, sub-protocol `lws-bidirectional-protocol`, JSON wrapped in `Params` with `"Token": "LAN"` and PascalCase keys; mDNS `STB7.local`; needs firmware ≥ 12.1.11 | **High**: five independent implementations, reported working from 2018 to March 2024 |
| **SFR Box 8 TV / STB8** — *the one in the house* | Aug 2019 → **still shipped in 2026** with the Box 10+ | Sagemcom "Video Soundbox" (Broadcom 7271), proprietary platform, "OK SFR" voice. **Not Android TV** | **plain WS 7682** (and 7684), camelCase JSON, three actions: `buttonEvent`, `getStatus`, `getVersions`; no "what is on screen" query | **Verified here (§2)** — before this study, only the port and the action names were known (HA forum, June 2022), and the JSON came from reading the APK, never from a box |
| **SFR Connect TV V1 / V2 / V3** | 2018 / 2021 / Feb 2023 → sold until 2026, dropped from bundles Aug 2026 | SDMC or Sagemcom, **Android TV** 7 → 12 (operator tier), Chromecast built-in, Google Assistant, HDMI-CEC toggle | the Android TV paths — see §4.3 | Medium for the hardware and OS; low for what is actually enabled |
| A 2024–2026 decoder | — | — | **none exists**: the Box 10+ (July 2025) ships with the Box 8 TV | High |

### 4.1 What the official "SFR TV" app does

Package `com.sfr.android.gen8` (the Android TV flavour is `com.sfr.androidtv.gen8`, the RED one
`com.sfr.android.redtv`). SFR's conditions for its "télécommande": the phone on the box's own
Wi-Fi (not SFR WiFi or a hotspot), the decoder on or in standby, at least the Décodeur Plus with
software 12.1.11 — the "Classic" decoder is excluded, and so are games, text entry and third-party
apps (Netflix, MyTF1, Canal+…). **No pairing code and no account step** are mentioned anywhere, and
the tests here confirm it: the box accepts any client on the LAN.

Every community client for the STB7 sends identity fields plainly lifted from a capture of the iOS
app (`"DeviceModel": "iPhone"`, `"DeviceSoftVersion": "11.2.2"`, `"Token": "LAN"`), and the STB8
command set was read out of the APK's Kotlin. So the app talks **directly to the decoder on
WebSocket 7682**, not through SFR's cloud — which is why it needs the same Wi-Fi.

### 4.2 The STB7 protocol, for the record

Same port, older dialect. `ws://<ip>:7682/` with `Sec-WebSocket-Protocol: lws-bidirectional-protocol`:

```json
{"Params": {"Token": "LAN", "DeviceSoftVersion": "11.2.2", "DeviceModel": "iPhone",
            "Action": "ButtonEvent", "Press": [303]}}
{"Params": {..., "Action": "CustomEvent", "Event": "GotoLive", "Params": ["12", "zapdigit"]}}
{"Params": {..., "Action": "GotoApp", "AppName": "Epg"}}
{"Params": {..., "Action": "SetVolume", "IsMute": false, "Level": "12"}}
{"Params": {..., "Action": "GetSessionsStatus"}}
```

Integer key codes: `13` OK, `19` stop, `27` back, `290`/`291` P+/P−, `292` home, `293`/`222`/`297`/`294`
left/right/up/down, `300` VOD, `301` info/options, `302` mute, `303` power, `304`–`306` rewind,
forward, play/pause, `307`/`308` volume −/+, `309` record, `124`–`128` Netflix, Prime, Disney,
Canal, Replay; digits as their ASCII codes (`49` = "1"). Apps: `Mosaic`, `Epg`, `Vod`, `Replay`,
`Pvr`, `MediaCenter`, `Settings`. `GetSessionsStatus` returns the current application
(`"En Veille"` in standby) — the one thing the STB8 lost: it has no "what is on" query at all.
Sources: [sfrtvctl](https://github.com/dragouf/sfrtvctl) (2018),
[home-assistant-SFR-Decoder-7](https://github.com/ThierryBourbon/home-assistant-SFR-Decoder-7)
(2018–2022), [sfr_decoder_ha](https://github.com/thierry-rhone/sfr_decoder_ha) (Node-RED, 2022),
the Jeedom thread [22486](https://community.jeedom.com/t/script-pour-piloter-decodeur-tv-plus-sfr/22486)
(2020 → April 2026).

### 4.3 The Connect TV (Android TV) route

Not the box in the house, so for the record only. If a Connect TV ever replaces the STB8, the
SFR WebSocket is not confirmed on it by anyone, and the routes are Android's own:

| Route | On a Connect TV | Feasible from this tablet | Verdict |
|---|---|---|---|
| **Android TV Remote protocol v2** — mDNS `_androidtvremote2._tcp`, TLS on 6467 (pairing) and 6466 (remote), protobuf with a varint length prefix, a 6-character code shown on the screen once, the client's self-signed certificate bound by that pairing | Probable, **unverified**: the Connect TV is a certified Android TV device, but no report of the Google TV app or Home Assistant driving one was found | Yes: Conscrypt supports API 21, a 2048-bit software RSA key and a hand-written self-signed certificate carry the pairing hash, and the two protos are small enough for a hand-written encoder (`protobuf-javalite` no longer supports Lollipop) | the route to try first — `dns-sd -B _androidtvremote2._tcp` on the Mac tells in a second whether the service is advertised |
| **ADB over TCP** (port 5555; `input keyevent`, `am start`) | **Blocked**: since firmware 11.3.55 the Connect TV asks a password to open the developer options (RED community, August 2024 → May 2025, apparently pushed by the operator) | Yes (pure-Java ADB clients exist) | not viable |
| **Google Cast** (TLS on 8009) | Present ("Chromecast intégré") | Yes with a native Cast v2 client (Play services stopped updating on Lollipop in July 2024) | launches receiver apps only; no keys, no channel |
| **HDMI-CEC** | the television's business; Android's CEC API is system-only | not applicable | note only |

Key facts of the remote protocol, from `tronikos/androidtvremote2` (the Home Assistant library,
last commit 2026-09-07): key codes are Android's (`POWER` 26, `CHANNEL_UP` 166, `CHANNEL_DOWN`
167, digits 7–16, `DPAD_CENTER` 23, `VOLUME_UP` 24, `MUTE` 91); the TV pings every 5 s and drops
the client after three unanswered pings; `remote_start.started` gives the power state;
`remote_app_link_launch_request` opens a deep link. Whether SFR's live-TV app honours channel and
digit keys is unknown. Sources: the library's `polo.proto` and `remotemessage.proto`,
[Aymkdn's protocol write-up](https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-(aka-Android-TV)-Remote-Control-(v2))
(2025-08-17), the [Home Assistant integration](https://www.home-assistant.io/integrations/androidtv_remote/),
the RED community thread on the [developer-options lock](https://communaute.red-by-sfr.fr/t5/Box-d%C3%A9codeur-TV/Activer-le-mode-d%C3%A9veloppeur-sur-Le-d%C3%A9codeur-Connect-TV-Android/td-p/607757).

### 4.4 HDMI-CEC

The Box 8 TV has an "HDMI-CEC" setting that powers the television and the box off together, and
a "télécommande universelle" mode where the physical remote drives the television directly (a
Bluetooth pairing with keys 7 + 9). CEC carries power and volume, not channels, and it is the
television that obeys it — so whether « allume la télé » lights the screen depends on the CEC
setting in the box menu, which is one of the things to check with the television on (§7).

## 5. Implementing it in this codebase

- **`tools/TvBox.kt`** — an OkHttp `WebSocket` (OkHttp 4.12, already in the app; `ws://` needs
  no TLS and none of the Conscrypt/CA machinery) opened on first use and kept, reopened on failure;
  `send(action, params)` writes one JSON object and matches the reply on `requestId` with a
  short timeout (500 ms is ten times the measured round trip). `getStatus` before `power`.
- **`assistant/Intents.kt`** — a `TvBox` intent family (power on/off, channel by number or name,
  channel up/down, volume, mute, playback, back, home, status), French regexes in the style of the
  timers, with the channel names from the config.
- **`assistant/ToolReplies.kt`** — the spoken confirmations, pure, unit tested.
- **`assistant/Router.kt`** — one branch; offline is not a question here (the box is on the LAN),
  but "the box did not answer" is: « Je n'arrive pas à joindre le décodeur. »
- **`core/AppConfig.kt`** / `config.json` — `tvBox: {"host": "stb", "port": 7682, "channels":
  {"tf1": 1, "france 2": 2, …}}`; no secret in it.
- **`scripts/tv.sh`** on the Mac — `status | key <name> | channel <n>` against the box directly
  (the probe script already does it), and through Bello with `scripts/ask.sh "mets la 3"`.
- A unit test for the message builder and the reply matcher on the saved real replies; the
  tablet test is the usual `scripts/ask.sh`, with the television on.

Cost: a day, most of it the intents and the channel table. It is a tool, not a phase.

## 6. The bigger picture

- **This is the easiest tool Bello will ever get.** No key, no quota, no TLS, no cloud, 10 ms, a
  protocol of three JSON messages, and a box that is on the same LAN as the tablet. The only
  work is the French: which sentences mean which keys, and the channel names of the household.
- **The protocol is unauthenticated by design.** Anyone on the Wi-Fi can drive the decoder, which
  is how SFR's own app works; Bello adds nothing to that surface. Bello itself should still refuse
  to act on a page request or anything not spoken or typed in the room.
- **The STB8 says nothing about what is on screen.** `getStatus` is power only. Bello can zap and
  press, but « qu'est-ce qu'il y a à la télé ? » stays a question for the EPG on the phone page
  (a future free-services item), not for the box.
- **What the box answers is not what it does.** `OK` acknowledges receipt of anything. Every key
  name in §2.3 beyond `mute` needs one look at the television, and the study could not do that
  without someone in the room.
- **Same port, three dialects.** If the household ever moves to a Décodeur Plus (STB7) the
  transport stays and the messages change (§4.2); a Connect TV is another world (§4.3). The
  `getVersions` reply (`boxType`) tells Bello which dialect to speak.

## 7. What could not be verified, and what needs you in the room

- **Every key except `mute`.** The box answers `OK` to anything; the television has to be on and
  watched. Ten minutes with `scripts/tv.sh key <name>` settle the whole table in §2.3 plus the
  guesses (`guide`, `info`, `menu`, `netflix`, `red`…).
- **Whether `power` also wakes the television** (HDMI-CEC from the decoder) or only the decoder.
- **Channel entry**: digits alone, or digits then `ok`; and how the box takes two digits.
- **Unsolicited notifications** on power changes (documented by the community, not observed here
  because nothing changed).
- **Volume**: whether the household's volume lives on the decoder or on the television.
- **mDNS from the tablet** (`NsdManager` on Android 5) — the configured name `stb` is the
  fallback that needs no discovery at all.
- **Whether the decoder listens on 7682 in deep standby** — it did in ordinary standby.

## 8. Next steps

1. **Ten minutes with you in front of the television**: `scripts/tv.sh` (or the probe script)
   sending each key of §2.3 and the guesses (`guide`, `info`, `menu`, `netflix`, `red`…), noting
   what the screen does; two-digit channels with and without `ok`; `power` from standby with the
   CEC setting on and off; whether the television's or the decoder's volume moves. That
   settles every open question in §7 except deep standby, which needs a night.
2. **Then the tool** as laid out in §5: `tools/TvBox.kt`, the intents, the replies, the config with
   the household's channel numbers, `scripts/tv.sh`, the unit tests on the saved replies. One day.
3. **Not now**: the EPG ("what is on"), which the STB8 cannot tell; the Connect TV route, which
   is not the box in the house.

What this needs from you: the ten minutes above, and the channel list you actually use
(« la 3 » is easy; « mets Arte » needs the number).

Raw captures — UPnP descriptions, mDNS listings, every WebSocket exchange — are kept, git-ignored,
in `spikes/results/sfr-tv-box/`.
