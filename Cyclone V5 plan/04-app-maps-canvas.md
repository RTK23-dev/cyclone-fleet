# 04 — App Maps canvas (Glass operator board)

> Updated 2026-09-23 with the owner's Glass charter ([03](03-glass-v1.md)): Glass is a local web app (`apps/glass`), not Cyclone One pages.
> The board below is the **App → Map** tab. This doc adds the **Scenarios** lens and **per-version maps**.

The PC is the **operator table**. The phone is the **hands** and the **brain**.

Glass Maps V1 is a **Minitap-class full mapping view**: dotted board, cards, edges, zoom, filters, inspector. The operator must get a look and feel of every mapped app — not a list of package names.

If they cannot pan the whole Gmail house on a monitor and explain the rooms from the canvas alone, this page is not done.

## Operator bar (copy Mini’s *feel*, not their QA objects)

MiniTest’s demo worked because you could **see the house**: dotted board, cards, edges, zoom, click a room, know if it was healthy. Glass must do that for **screens and doors**.

| Mini (miniTest demo) | Cyclone Glass Maps |
|---|---|
| App switcher (Demo App) | Place switcher: Gmail, Chrome · facebook.com, … |
| Tabs: SCENARIOS / PERSONAS | **Map** (rooms/doors) / **Scenarios** (routes to end results) / **Live vs mapping** |
| Journey cards (“Register a New Account”) | **Scenarios lens:** journey cards (“Sign in”, “DM a person”). **Map lens:** room cards (purpose, coverage, stale/blocked) |
| Edges = story order | Edges = **doors** (“open avatar”, “Create account”) |
| Passed / warning / critical | Rooms: mapped / stale / blocked (secret) / danger (pay). Scenarios: passing / warning / critical from real runs |
| Click → recording + acceptance criteria | Room → redacted frame + slots + doors + last proof. Scenario → route on the map + runs that used it → [run inspector](11-run-inspector.md) |
| Mini orb while authoring | Live **mapping cursor** on the node being walked |
| Filters | Region, status, danger, Chrome vs native |
| 4/12 bar | Doors mapped / still dark |
| “Watch Mini author one now” | Start mapping from the board; cards spawn live |

## Layout

```text
┌──────────┬──────────────────────────────────────┬─────────────────┐
│ Places   │  Board (dotted grid, pan / zoom / fit)│ Inspector      │
│ catalog  │                                      │ (sheet)        │
│          │   [Account] [Inbox] [Chat] [Settings] │ purpose        │
│ Gmail    │      ●────────●────────●              │ redacted frame │
│ Chrome · │                                      │ slots, doors   │
│  fb.com  │   coverage: 18 screens · 41 doors    │ pin / remap    │
│ …        │                                      │                │
└──────────┴──────────────────────────────────────┴─────────────────┘
 Top: place name | Live/Dummy | Start mapping | filters | Take control
```

### Left rail — All places

- Every launcher app on **this** phone + Chrome origins.
- Status chip: Not mapped · Mapping… · Mapped · Stale · Blocked.
- Native missing: row still exists as `Chrome · facebook.com`.
- Search. Mapped-only filter.
- One tap focuses that atlas on the board.
- Device picker stays the existing fleet control: the board is **this phone’s** atlas. No merged-fleet graph in V1.

### Center — the board

- Infinite dotted grid. Pan, scroll/pinch zoom, `+` `−` fit.
- Cards clustered by region: Account · Inbox · Compose · Chat · Settings · Danger.
- Card shows: purpose title, 2–3 landmark labels, status color, capability glyphs.
- Edge lines (bezier or ortho) with English on hover: *open account photo*. Confidence as opacity.
- Fit-all on open so the operator gets the shape in one glance.
- Empty place: one card **Start mapping** (not a blank grid).
- Operator may drag to tidy. Layout persists **on the phone atlas** so phone and Glass agree.

### Right / sheet — inspector (Mini’s scenario drawer)

Open a **screen**:

- Purpose (account-switcher, dm-list, login, signup, …)
- Last redacted snapshot (vault fields stripped on the phone before the JPEG leaves)
- Fact slots: *signed-in email (which row is current)* — no live address in the operator UI unless they expand a **masked** value
- Doors: unmapped ones marked dark
- Last verified, confidence, app version
- Actions: **Pin** (“this is DMs”), **Remap this room**, **Never** (payment)

Open an **edge**: last success/fail, English action, hint target (text / id). No password as the headline. No xpath as the headline.

### Top bar

- Place name + persona toggle **Live / Dummy** (two atlases, never mixed on one board)
- Coverage: `18 screens · 41 doors · 3 dark`
- **Start mapping** / Pause / Take control
- Filters: stale, blocked-secret, danger, unmapped doors, Chrome vs native
- View toggle: Screens (default) · Capabilities (index of what this place is good for)

## Scenarios lens

The owner's picture: Instagram is a house. You arrive by **registering**, **signing in** or being **already signed in**; you land on the home
screen; from there doors lead to the DM list, DM search, a person's thread. A scenario is one known route to one **end result**.

- **Card** = a scenario: title in plain English (*DM a person*, *Search DMs*, *Sign in to an existing account*), the room it starts from,
  the end-result room, the number of steps, health (passing / warning / critical) from the last runs that used it, last verified, app version.
- **Layout** = left to right like Minitap: entry scenarios (register / sign in / already signed in) → landing → destination scenarios.
  Edges mean "this scenario continues from that one's end room".
- **Click** = the route lights up on the Map lens, room by room, with every run that used it; a failing run opens the [run inspector](11-run-inspector.md).
- **Where scenarios come from** (phone-side, never Glass): successful Ask runs whose path followed Atlas doors, mapping runs that reached a
  recognised end room (sign-in, DM list, search), and developer pins in Glass ("this route is *DM a person*"). Parameterised ends
  (*DM **someone***) keep the person out of the scenario; the person comes from the sentence and People memory.
- **Why it matters**: with a known route, the phone takes the next door without asking the model when the screen matches the expected room,
  and checks the room after every door. That is how Cyclone gets near-instant decisions without losing its eyes (law 1).

Scenario health is **coverage of the engine**, not product QA: "critical" means Cyclone currently cannot get there reliably.

## Versions

Every door already stores the app version it was verified on. Glass shows:

- which versions of the app have a map, and which is installed now;
- **needs remap** when the installed version has no verified map;
- per version: rooms and doors added, removed or failing compared with the previous version;
- Remap for this version (phone mapper; the older version's map stays for comparison until forgotten).

## Live mapping theater

JPEG live stays available on **Phone**. Maps board **animates**:

- Current node pulses (`mapping.status.nodeId`)
- New cards spawn, new edges draw from `atlas.diff`
- Blocked node (needs secret): board dims, **Secrets card** on Glass, crawl resumes, node flips mapped
- Operator can sit on Maps and *feel* the crawl — Mini’s “watch it author one now” beat

Pause / Take control uses the existing One handoff (`HUMAN_HAS_CONTROL`). Teach from the desk: take the mouse, tap the account header, pin “signed-in email,” give back.

## Phone vs Glass

| Surface | Canvas |
|---|---|
| Mobile Settings → App Maps | Catalog + **small** graph + Start / peek chip |
| Glass → Maps | **Full board.** This is the look-and-feel product |

Do not duplicate the monitor on a phone. Do not ship Glass Maps as a settings list.

## Sync (60fps pan, phone is truth)

```text
atlas.places
atlas.get(placeId, persona)     → full graph + layout + redacted thumbs
atlas.diff(placeId, since)      → live crawl
mapping.status                  → current node id (cursor)
mapping.start | pause | stop
```

Glass holds a local replica for pan/zoom. Diffs apply in place so the board does not rebuild from scratch every edge.

4.8 phone: Maps shows **Update Cyclone on the phone**, not a fake graph.

## Build tasks (G2)

| ID | Task | Ship |
|---|---|---|
| G2.1 | Board engine | Port the prototype `mapsPage.ts` + `appMapCanvas.ts` into `apps/glass`: pan/zoom/fit, dotted grid, region layout |
| G2.2 | Node cards + edges | Purpose, status color, capability icons, hover English, confidence opacity |
| G2.3 | Layout | Seed by region; persist operator drags on the phone atlas |
| G2.4 | Atlas sync | `atlasClient.ts` replica + diff apply |
| G2.5 | Inspector | Redacted still, slots, doors, proofs as English, pin/remap/never |
| G2.6 | Catalog + filters | Places, search, Chrome vs native, personas, status |
| G2.7 | Live mapping | Cursor, spawn, secrets interrupt, Take control on the same bar |
| G2.8 | Empty / loading / stale | Honest copy, one primary action |
| G2.9 | Phone small canvas | List + mini-graph only — must not block Glass |
| G2.10 | Scenarios lens (✅ cards + health + route highlight in Glass alpha.5; board layout next) | Scenario cards, entry → landing → destinations layout, health from runs, route highlight on the Map lens |
| G2.11 | Versions (✅ Glass alpha.5; per-version remap is the normal Remap for now) | Mapped versions per app, needs-remap, per-version diff, remap for this version |

## Exit tests

**alpha.2 (read-only board):** operator opens Glass → Maps → Gmail (after a Follow Me or first atlas) and can **explain the app’s rooms from the canvas alone** (where identity lives, where inbox is, what’s unmapped). If they still need logs, the canvas failed.

**alpha.3:** Start mapping from the board; live cursor; diffs spawn cards; secrets card pauses then resumes.

**rc:** Chrome places, persona split, coverage bar, pin/remap room.

**Glass 1.0:** Encrypted secrets resume on the same board; stale/refresh in the catalog.

## What not to copy from Mini

- Acceptance criteria **as room nodes**. Rooms are screens; proofs live in the inspector. (Scenario cards are wanted — see the Scenarios lens.)
- Scenario health presented as product QA of the app. It measures whether **Cyclone** can get there.
- A Mini orb that *is* the agent. Cyclone’s agent is on the phone; the pulse is just the cursor.
- Replaying doors without checking the room after each one. Map steps are fast, never blind.
- Storing dummy emails as the signed-in identity of the live persona.

The board is how **you** see the atlas. The phone still **looks** when it walks.
