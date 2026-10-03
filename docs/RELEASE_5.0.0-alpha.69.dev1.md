# Cyclone V5 Alpha 69: the AI screen redesign

Developer alpha for owner testing. It builds on Alpha 68 (steer, queue, parallel and plan diversions) and includes it.
- **Mobile:** `5.0.0-alpha.69.dev1` (version code 214).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.69.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

The AI screen is rebuilt from the agreed R3 board (`docs/design/redesign/rounds/R3-ai-screen.md`): the Cyclone rain
behind dark glass that takes the rain's colours, the model selector in the header, and room for suggestions and
recent runs.

## What changed

**1. The Cyclone rain.**
- The teal gradient and breathing dots are gone. The background is the rain: the working overlay's own digits
  (`0–9 A–F · : + /`), lit by a slow dome of rings and spokes that drifts outward and through copper, violet and blue.
  Rain streaks fall through it, brightening the digits they pass.
- About 30 frames a second while the page is on screen, nothing when it is not. With Android animations off it is one
  still frame. If the phone's graphics cannot run it, a dark gradient takes its place.

**2. Dark smoked glass, no teal.**
- Every panel on the page (header chips, model pill, suggestions, recent runs, the Ask bar, the sheets) is real glass:
  a live blur of the rain beneath it, bent at the rim like thick glass, then clearly darkened so words read on it.
  It keeps the colours underneath: copper over copper, violet over violet.
- A thin white highlight runs along each rim, and a soft shine crosses each panel every 6 seconds.
- The Ask bar keeps its fingerprint dots at both ends (silver now) and its shine.
- The task card, island and owner card on this page lose their teal (a new neutral **smoke** palette). The floating
  overlay over other apps stays teal: an overlay cannot blur another app.

**3. The header: burger, model, mark.**
- **Top left, the burger** opens the menu drawer.
- **Middle, the model selector** (for example "Claude Fable 5.1 ⌄") replaces the word "Cyclone". It opens a sheet
  under it: your models with the current one ticked, and the thinking levels the provider offers for it (Low /
  Medium / High and so on, never invented). The + menu's **Model & intelligence** opens the same sheet.
- **Top right, the Cyclone mark** opens the logo panel.

**4. Home: greeting, suggestions, recent runs.**
- The orb dot is gone and the greeting sits right under the header.
- **Suggestions:** Plan my day, Catch me up, Research, New routine. A tap writes it into the Ask bar; nothing is sent
  until you send it.
- **Recent runs:** your last three runs on one glass card, each with a tile in its tone (done, waiting, went wrong)
  and how it ended ("Done · 2h ago"). A tap opens that run in the menu. **See all** opens the menu.
- Runs no longer appear inside the chat thread.

**5. The menu drawer (burger).**
- **New chat** clears this chat (not while a reply is coming).
- **Search runs** filters by goal and summary.
- Runs grouped by **Today**, **Yesterday**, **This week** and **Earlier**. A run opens in place with its summary,
  **Resume** (when it can continue and nothing is running) and **Remove**.
- **Routines**, **Brain** and **Settings** at the bottom.

**6. The logo panel (Cyclone mark).**
- Cyclone's status: "Running on this phone", or what phone control needs.
- **Phone control** (opens its settings), **Driver mode** and **User notes** switches (they work exactly as in
  Settings; Driver mode asks for the microphone first), **Background video**, **Model & API** and **Settings**.

**7. Your own background video.**
- Logo panel › **Background video** lets you pick a video from your phone. It plays muted, looped and cropped to fill
  the screen behind the glass, which blurs it like the rain. It pauses when you leave the app.
- Cyclone keeps only Android's permission to read the file you picked. The video is never copied or uploaded, and no
  video ships inside the app. **Use the rain** switches back and hands the permission back to Android.
- If the file is deleted, the screen falls back to the rain.

## Unchanged

- What you type still goes through the same router (chat, phone task, Up next). Task buttons still go through Task
  Kit. Approval rules, voice rules and privacy rules are unchanged.
- The glass blurs only Cyclone's own background, inside Cyclone's window. No screen pixels are read.
- The bottom tab bar keeps its look (a separate round).

## Tests

- `AskCopyTest` covers the greeting, day groups, run lines, tones, search and header words. `AskBackgroundTest` covers
  the centre-crop.
- `AskScreenR3ContractTest` pins the design's rules:
  - the rain is the backdrop the glass blurs;
  - no teal on the page;
  - the Ask bar keeps its dots;
  - the header order;
  - suggestions only fill the bar;
  - Resume only when resumable;
  - the video is read in place, muted, with the rain as fallback.
- The older AI page contract tests and `mobile_product_guard.py` now describe R3 instead of the dot field and the
  + menu model panel.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone: how smooth the live blur and the rain are, how the glass looks over the
rain's colours, and whether the glass follows a picked video frame by frame.
