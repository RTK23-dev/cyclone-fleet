# 03 — Cyclone Glass 1.0

> **Owner charter, 2026-09-23. This replaces the earlier Glass plan.**
> Cyclone Glass is the developer's eyes on the engine that runs in the Cyclone Mobile app.
> It runs **locally in a browser** on the PC. It shows what Cyclone **knows**, what it **did** and where it can **improve**,
> and it can **control the phone**. It has **no intelligence of its own**.

## What Glass is

| Glass is | Glass is not |
|---|---|
| A local website on the PC (`http://127.0.0.1:…`), opened in any browser, on Windows, macOS or Linux | A Windows desktop app, a Tauri app, or pages added to Cyclone One |
| A developer dashboard: see the maps, the runs and the knowledge of the phone's engine, and improve them | An end-user HUD |
| A remote control for the phone (live view, take control, give back, send a goal) | A second brain: no agent loop, no planner, no model calls, no LLM key |
| A replica of phone data for fast panning, zooming and browsing | A source of truth: the phone owns the Atlas, the runs and the vault |
| Inspired by Minitap's product (apps, scenario maps, run replays, triage) | Built on Artemis (see *Decision record* below) |

The intelligence runs in **Cyclone Mobile**. Glass asks the phone for data and sends the phone commands. The goal is simple: developers can
map apps, look inside every run, and fix what Cyclone does not yet know, so Cyclone makes almost instant decisions because it already knows where to go.

## The house, in Glass

The V5 house analogy is the product:

- An **app is a house** (Instagram, Gmail, Chrome · facebook.com).
- **Rooms** are screens with a purpose: sign-in, home feed, DM list, a person's DM thread, search.
- **Doors** are the actions between rooms: "open DMs", "type in DM search", "open Louella's thread".
- A **scenario** is a known route through the house to an **end result**: *Sign in to an existing account*, *DM a person*, *Search DMs*.
  Entry scenarios cover how you arrive: register a new account, sign in, or already signed in.
- The **map** is what Cyclone knows. With a mapped route, Cyclone knows at every step which room it is in and which door leads to the end result, so it does not have to think its way through the dark.

Glass makes that knowledge visible and editable, per app and per app version.

## Pages

| Page | Job |
|---|---|
| **Apps** (home) | Every app and web place on the phone. Per app: map status, installed version vs mapped versions, rooms / doors / scenarios, scenario health, last run, open issues. Actions: Map, Remap, Forget a version |
| **App** (one per app) | Tabs: **Map** (rooms and doors board) · **Scenarios** (Minitap-style journey board) · **Screens** (table) · **Versions** (maps per app version, what changed) · **Runs** (runs that touched this app) · **Issues** |
| **Runs** | Every Cyclone run: Ask from the phone, Ask from Glass, mapping runs, automations. Outcome, duration, app(s), how many steps came from the map vs the model. Filter: failed, needs-secret, stopped, this app |
| **Run inspector** | The autopsy. Step-by-step timeline of one run with the **cause of death** of a failed run. Spec: [11-run-inspector.md](11-run-inspector.md) |
| **Knowledge** | What Cyclone knows beyond maps: People memory, learned skills and routes, place catalog facts, vault slots (set / not set, never values) |
| **Phone** | Live view, Take control / Give back to Cyclone, and an Ask composer that sends the unchanged sentence to the phone's engine (`ask.start`) |
| **Settings** | Pairing, devices, map budgets, never-pay list, data retention |

Multi-phone: every page is per device. No merged fleet atlas in 1.0.

### Apps page (the entry point)

```text
┌ Apps ─────────────────────────────────────────────────────────────────────┐
│ Search apps…   [Mapped] [Needs remap] [Has failures]                      │
│                                                                           │
│ Instagram   v312.0 installed · mapped v311.0, v312.0   42 rooms · 118 doors│
│             9 scenarios · 7 passing · 2 warning       last run 12 min ago │
│ Gmail       v2026.09 installed · mapped v2026.08 ⚠ stale                  │
│ Chrome · facebook.com   web place · 18 rooms           1 critical         │
│ Clock       not mapped                                   [Map]            │
└───────────────────────────────────────────────────────────────────────────┘
```

A map is tied to the app version it was learned on (door evidence already records `versionName` / `versionCode`). When the installed version changes,
the app shows **needs remap** and the Versions tab lists which rooms and doors still verify on the new version.

### App → Map and Scenarios

- **Map** is the rooms-and-doors board already specified in [04-app-maps-canvas.md](04-app-maps-canvas.md): dotted grid, room cards, door edges, inspector, live mapping cursor.
- **Scenarios** is the Minitap-style board: cards are scenarios (routes to an end result), grouped as entry → landing → destinations, like Minitap's
  *Register a new account → Sign in → Browse → Add to cart → Checkout*. Each card shows the end result, the rooms it passes through,
  its health from recent runs (passing / warning / critical) and when it last verified. Click a card: the route lights up on the Map tab,
  with the runs that used it. Details in [04](04-app-maps-canvas.md#scenarios-lens).

## Control

Glass keeps phone control from the PC:

- Live view of the phone (JPEG-first path from Cyclone One, reused through the gateway).
- **Take control** / **Give back to Cyclone** (`HUMAN_HAS_CONTROL` semantics unchanged; a locked phone is never stolen).
- **Ask**: type a goal; the phone's engine runs it; Glass mirrors the stages and then opens the run in the inspector.
- **Start / Pause / Stop mapping** for one app.
- Teach from the desk: take control, open the room, pin "this is the DM list", give back.

Every mutation still happens on the phone through `PhoneToolExecutor`. GATE still owns pay / send / delete / permission / authentication.

## Architecture

```text
Browser (any OS)  ──http/ws 127.0.0.1──►  Cyclone gateway (Python, local)  ──paired channel──►  Cyclone Mobile 5.x
  apps/glass web app                       serves the Glass web bundle                            Atlas, runs, vault,
  (replica for pan/zoom, no logic          + V5 contract ops (atlas.*, mapping.*,                  agent loop, mapper,
   that decides anything)                    runs.*, apps.*, scenarios.*, ask.*, secrets.*)        PhoneToolExecutor
```

- **Code:** new `apps/glass/` (TypeScript web app, no Tauri, no Node at runtime). Built to static files and served by the local gateway.
- **Start:** one command or shortcut (`cyclone-glass`) starts the local gateway if needed and opens the browser. A small Windows start/stop helper is fine; the product itself stays a website.
- **Security:** binds to `127.0.0.1` only; per-launch session secret; strict CSP; bearer tokens never reach `localStorage`; no secret values on the wire, on disk, or in the page.
- **Reuse:** the V5 Glass pages already written as a prototype inside Cyclone One (`apps/pc-companion/src/pages/{mapsPage,askPage,vaultPage}.ts`, `ui/appMapCanvas.ts`, `services/atlasClient.ts`, `maps/mappingWatcher.ts`) are plain TypeScript over HTTP. Port them into `apps/glass`; do not keep extending them inside Cyclone One.
- **One design system** for Glass: one token sheet, one component set. No layered `redesign.css` over old styles.

## Cyclone One

Cyclone One stays what it was: the Windows operator console (fleet, JPEG live, MCP tunnel, ChatGPT Attach, camera). It is **not** Glass.

- Until Glass web reaches parity, the prototype pages can stay inside One so nothing that works is lost.
- After Glass 1.0-alpha covers Apps, Map, Runs and Phone, remove the V5 prototype pages from One, set its window title back to **Cyclone One**, and stop calling it Glass in release notes.
- `release/version.toml` gets a separate `glass` component. `pc_companion` keeps One's numbering.

## Privacy

- Frames shown in Glass are **redacted on the phone** before they leave it (vault fields and typed secrets stripped).
- Run data shows model-visible context, decisions, tool calls, results, verification and recovery. It never contains hidden provider chain-of-thought, passwords, OTPs, API keys or payment data.
- Glass stores nothing sensitive. Closing the tab loses nothing that matters; the phone keeps the truth.

## Decision record (what went wrong before, 2026-09-18 → 23)

1. **2026-09-18/19** — An Artemis-based local web Glass was built on `feature/pc-glass-artemis` (`apps/pc-glass`, 19 commits). It ran as a browser app, but it was Google Artemis with its own agent brain (Flash/Pro profiles, OpenRouter key) steering the phone through the gateway. It was never merged.
2. **2026-09-21** — The V5 plan defined Glass as "the same Tauri app (`apps/pc-companion`), new product surface". In V4, "glass" already meant *Cyclone One as a display-only PC window* (`docs/V4_STAGE4_ONE_GLASS.md`), and the plan read "Cyclone Glass" as a rename of Cyclone One.
3. **2026-09-21 → 23** — Three Glass agent runs and alpha.3–alpha.6 built Maps, Ask and Vault pages into Cyclone One, shipped as "Glass 1.6.0-alpha.x" in the Cyclone One installer.
4. **2026-09-23** — The owner restated the product (this document).

Decisions:

- Glass is a **new local web app**, not Cyclone One and not Artemis.
- From Artemis, keep the **idea** that you can open any run and see exactly why it failed. Do not import Artemis code or its agent.
- From Minitap, take the product shape: apps, scenario maps, run replays, triage.
- The phone-side V5 work (Atlas, mapper, vault, Ask with the Atlas, gateway contract ops) is the engine Glass shows. It stays.

## Exit criteria for Glass 1.0

1. Opens in a browser on the PC from one launcher; works without Cyclone One installed.
2. **Apps** lists every app with mapped versions and needs-remap state from the phone.
3. **Map** pans a real mapped app; the inspector opens rooms and doors; mapping can be started and watched live.
4. **Scenarios** shows at least *sign in* / *already signed in* and one destination scenario for a mapped app, with health from real runs.
5. **Runs** lists phone runs; the **Run inspector** shows a failed run step by step with a cause of death and a suggested fix (for example "door missing: remap this room").
6. **Phone**: live view, take control, give back, Ask from the PC.
7. No model calls, no LLM key and no agent loop exist anywhere in `apps/glass`.
8. Physical-device verification stated honestly.
