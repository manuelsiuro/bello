# A picture on the details page — a study

| | |
|---|---|
| Status | Built (Phase 13): one picture per page, written into the page by the page's own author, drawn by a free service, served by the tablet |
| Date | 2026-09-22 |
| Branch | `feature/page-images` |
| Question | Which free online model can draw a picture that fits a recipe, a how-to or a list, and how should it be asked? |
| Requirement | FR-PAGE-07 |

## 1. What happens

1. The owner says « oui » to the page offer (FR-PAGE-02).
2. The page's author (a provider, `ToolReplies.PAGE_SYSTEM`) writes the Markdown **and** a last
   line `[image: …]`: an English description of a picture, in the style of the page's kind. It is
   one call, so the picture costs no second trip to a chat provider.
3. `images/ImagePrompt` takes the line out of the page and cleans it: one line, at most 600
   characters, and a fixed tail, `Clean wordless image, blank surfaces, high detail.` If the line is
   missing (a page cut short), the title gives a calm flat illustration instead.
4. `images/ImageGateway` asks the services of `config.json` in order (Cloudflare, then
   Pollinations). A service that fails is cooled down, as the chat providers are. The whole chain
   has 25 s.
5. `images/PageImages` brings the picture down to 900 px wide (JPEG q82) when it is bigger. The
   tablet keeps it in memory with its page and serves it at `/r/<id>/img`. The page's CSP allows
   `img-src 'self'`, and nothing else.
6. No picture in time → the page is published without one. A picture is never a reason to fail a
   page.

The phone never talks to the service, and the key never leaves the tablet (FR-PAGE-04).

## 2. The services (tested for real, 2026-09-22)

| Service | Needs | Free quota | Measured | Verdict |
|---|---|---|---|---|
| **Cloudflare Workers AI**, `@cf/black-forest-labs/flux-1-schnell` | Free account + API token (Workers AI), **no card** | 10 000 neurons/day ≈ a couple of hundred pictures | 1.5–2.3 s from the Mac (five styles), 1.9 s from the tablet; 300–900 KB JPEG, shrunk to 105–127 KB | **First.** Real FLUX, and the pictures are good. POST, JSON with a base64 JPEG, always 1024 × 1024 (the page crops it to 4:3). The body takes `prompt` and `steps` **only**: a `seed`, shown in Cloudflare's own example, is refused (400, code 5006) |
| **Pollinations, with a free key** (`gen.pollinations.ai`) | Free key at enter.pollinations.ai, no card | A small daily grant of "pollen"; a `flux` picture costs 0.002 | *to measure once the key is in* | **Second.** FLUX; GET, the answer is the JPEG |
| Pollinations, no key (`image.pollinations.ai`) | nothing | about 1 request / 15 s / IP, then queued | 2–40 s; on the tablet 8.2 s and 15.4 s; once a 500 (upstream 429) | Last resort. Every model is swapped for `sana` (SD 1.5 LCM): a "ratatouille" came out as a pot and two lemons. **Stamps a pollinations.ai logo** even with `nologo=true` |
| Gemini image models, with the AI Studio key | the key already on the tablet | **0** — `gemini-2.5-flash-image` answers 429 "limit: 0"; Imagen 404 | — | Out (image output needs billing) |
| Together AI FLUX.1-schnell-Free | key | removed 2025-12-23 | — | Out |
| OpenRouter | key | no free image model | — | Out |
| AI Horde | anonymous key | volunteer queue | 31 s to ~13 min | Out: too slow while someone waits |
| Hugging Face Inference | token | ~$0.10/month of credits | — | Out: too little |
| TheMealDB, Wikimedia Commons | nothing | — | 0.1–1.4 s | Real photos, but recipes only and not always the right dish; not used |

`scripts/image.sh "<prompt>" [cloudflare|pollinations|anonymous]` draws one picture from the Mac
with the keys of `config/bello.local.json`, saves it under `.cache/images/` and prints its size and
latency. It is the way to try a prompt before Bello does.

## 3. How to ask — what the prompt research found

**Which models.** FLUX (Cloudflare, Pollinations) is the target. The rules below also work for
Gemini's image models if a free tier comes back. SDXL, which the rules do not target, would need
keywords and a negative prompt.

| | FLUX.1 schnell | Gemini 2.5 Flash Image | SDXL |
|---|---|---|---|
| Form | Full sentences, like a caption (a T5 encoder reads grammar) | "Describe the scene, don't list keywords" | Keywords work |
| Length | ~256 tokens on schnell; 40–120 words is best | one rich paragraph | ~77 tokens |
| Negatives | **None** (distilled). Say what is there | Say it positively ("an empty street") | Supported |
| Language | English; lens and light words are only reliable in English | French not in 2.5 Flash's best list | English only |

Sources:
- FLUX: deAPI's schnell guide, imagetoprompt.dev's FLUX guide, the FLUX.1-dev discussion on Hugging Face.
- Gemini: Google's "How to prompt Gemini 2.5 Flash Image" and the image-generation docs.
- SDXL: stable-diffusion-art.com.

**Hence the rules in `PAGE_IMAGE_RULE`:**
- One English paragraph of 40–90 words, in full sentences.
- **Start with the type of image.** DALL-E 3's own rewriter does this, and it fixes the style
  before anything else.
- Then, in this order: one clear subject, the setting and props, the light, the angle, the
  palette, the mood.
- **Wordless.** Image models garble text, and the page's HTML title is the only writing needed.
  Words that invite text ("poster", "infographic", "sign") are avoided, and the fixed tail says
  "blank surfaces".
- **No close-up people, faces or hands** (still FLUX schnell's usual defect). At most small
  distant figures.
- No real persons, brands or artists' names ("3D animated-film style", never a studio's name).
- Say what is there, never "no X": FLUX has no negative prompt.
- Pollinations' `enhance` stays off: the prompt is already written, and rewriting it twice drifts.

## 4. The style map

| Page | Starts with | Details that work |
|---|---|---|
| Recipe | "Editorial food photograph of …" | 45° angle (overhead for flat dishes: tart, pizza, salad), natural side light from a window, shallow depth of field, texture words (glossy, crispy, caramelised), rustic props cut by the frame |
| How-to, DIY, tech | "Isometric 3D vector illustration of …" | one main object plus a few tools, soft pastel palette, plain light background, subtle shadows |
| Places, travel, itinerary | "Wide-angle editorial travel photograph of …" | golden hour, sense of place; for a "top 5", the first place only |
| Advice (sleep, health, money, organisation) | "Soft watercolor illustration of …" | a metaphor with objects rather than people, calm palette, empty space |
| Children, games, parties | "Colorful 3D animated-film style render of …" | rounded shapes, bright colours, soft studio light |

Other presets that stay consistent, kept for later:
- "flat vector illustration, solid colors, pastel palette" — the fallback for a page without its line.
- "minimalist line art" — very abstract topics.
- "3D clay render" — friendly objects.
- "studio product photograph" — tools, shopping lists.
- "vintage travel poster" — invites text, so avoided.

**Example prompts** (from the research, in the rule's shape):
- *Recipe:* Editorial food photograph of a rustic French ratatouille served in a cast-iron skillet,
  glossy roasted courgette, aubergine and red pepper slices arranged in a spiral, fresh thyme on
  top. Shot from a 45-degree angle on a weathered oak table with a crumpled linen napkin partly out
  of frame. Soft natural window light from the left, shallow depth of field, warm earthy tones.
- *How-to:* Isometric 3D vector illustration of a small workbench where a wooden bookshelf is being
  assembled: flat-pack panels, an Allen key, a cordless screwdriver and a small box of screws neatly
  laid out. Clean geometry, soft pastel palette of sage green and warm beige, plain light cream
  background.
- *Advice:* Soft watercolor illustration of a cozy bedroom at night: a crescent moon through an open
  window, a steaming cup of chamomile tea on a bedside table, a closed book and a dim warm lamp.
  Calm palette of deep indigo, lavender and warm amber, lots of quiet empty space.

## 5. Size

- **What is asked:** 1024 × 768 (4:3), which gives more height on a phone than 16:9 without pushing
  the text below the fold. Cloudflare's FLUX ignores this and answers 1024 × 1024. The page crops
  both with `aspect-ratio: 4/3; object-fit: cover`, so a centred subject matters more than the
  exact size.
- **What is kept:** 900 px wide at most, JPEG q82, which is about 40–200 KB. Ten pages of two hours
  stay under 2 MB of heap.
- **Per-step pictures:** considered and left out. Each one is a call, a quota and seconds of waiting
  for somebody standing in the kitchen.

## 6. Settings

- `config.json`, block `images`:
  - `width`, `height`, `budgetMs`;
  - `providers`, in order: `cloudflare` (`accountId`, `key`), `pollinations` (`key`, optional).
  - See `config/bello.example.json`.
  - The keys are masked by `settings.sh export`, like the chat keys, and kept on import when masked.
- The switch « Une image sur la page » (`pageImages`, on by default). It is in the « Images »
  section of the settings screen, in the export, and in `scripts/page.sh images on|off`.
- The same section shows each service's state and has one switch per service. A switch writes
  `enabled` into its `images.providers` entry and rebuilds everything through
  `MainActivity.reloadEverything()`. Cloudflare without a key or an `accountId` is shown but cannot
  be switched on. Pollinations can always be switched on: without a key it gets the weak model and
  the logo.
- `scripts/page.sh images` shows each service's state: ok and failed counts, today's use, and any
  cooldown.
