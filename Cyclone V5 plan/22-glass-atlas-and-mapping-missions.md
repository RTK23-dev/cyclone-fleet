# 22 — Glass Atlas and mapping missions: the final build plan (alpha.38)

**Status:** built in 5.0.0-alpha.38.dev1 (see `docs/RELEASE_5.0.0-alpha.38.dev1.md`); final plan, 2026-09-26. Supersedes the Glass parts of plan 20 §7 and moves plan 21 (Hands) to alpha.39.
**Inputs:**
- the owner's three mockup sheets: fleet dashboard, app map with scenarios and zones, zone detail, place and door
  inspector, door replay;
- another agent's architecture essay;
- this repository as it is at alpha.37: code read, and Glass rendered in Chromium against fixtures.

The goal in the owner's words:
- *Dedicated mapping missions from Glass.* Map with your own account (look only) or a test account, with a budget of
  10 minutes, 30 minutes or 2 hours.
- The mission maps level by level, shows a live board and ends with a report.
- It never sends, posts, pays, deletes or changes security settings.
- Glass shows the map and the runs the way a serious developer platform would.

---

## 1. What already exists (so we build on it, not beside it)

| Layer | Exists at alpha.37 | Evidence |
|---|---|---|
| Mapping missions | `mapping.start / pause / stop / status` with personas, budgets up to 2 h (`maxElapsedMs ≤ 7 200 000`), progress counters, `needs-secret` and `human-control` states, resume | `v5_contract.py` `MAPPING_JOB_KEYS`, `_validate_budget`; `GatewayV5MappingAdapter.kt` |
| The walker | One mutation per step through `PhoneToolExecutor`. Doors are ranked **tabs → menus → drawers → settings → account → search → list sample**, so it already works roughly level by level. Content rows are never tapped. It stops at a secret wall (Secrets Card), yields to the human and has a budget | `SafeMapperWalker.kt` `StructuralDoorPolicy`, `chooseSafeDoor` |
| Safety | Pay, send, delete, grant and log-out doors are refused and recorded as dangerous in the Atlas | `ExistingGateMappingSafetyPort` |
| Live map | `atlas.diff` stream, a live mapping watcher and a cursor on the board | `maps/mappingWatcher.ts`, `appPage.ts` |
| Fleet | `apps.list` returns **every launchable app**, mapped or not, with versions and `needsRemap` | `GatewayV5AppsAdapter.kt` |
| Glass | Apps list; app page with Map / Screens / Scenarios / Versions / Runs / Issues; room-and-door board; run inspector with cause of death, route on the map and last-good comparison; Learn | rendered screenshots, `pages/*.ts` |

So alpha.38 is **not** a new mapper or a new graph. It closes specific gaps between this base and the mockups.

## 2. Gaps found (the evidence that shapes the build)

1. **Safety gap.** Tapping a *toggle* changes a setting, and the walker does not know toggles. `ExistingGateMappingSafetyPort`
   only classifies pay / send / delete / grant / log-out. List rows sampled in a settings RecyclerView (`isSampleShape`)
   can be a row whose tap flips *Private account* or *Two-factor*. State-changing social verbs (Follow, Like, Join,
   Block, Report, Install, Subscribe) and security areas (password, two-factor, passkeys, logged-in devices) are not
   blocked either. **The owner's promise "never changes security settings" is not true today.** This is fix #1.
2. **Identity is not a choice.** `persona` (live / mapping) decides where knowledge is written, not *how* the mapper
   behaves. There is no "look only" mode for the owner's own account, and no explicit test-account mode.
3. **Budgets are counted in rooms, not time** ("Quick · 12 rooms"). The owner thinks in minutes.
4. **The map is one flat board.** Regions come from a purpose lookup table (`regionForPurpose`: Inbox, Chat, Feed…).
   That is not how people think about an app. They think in *base pages*: Home, Search, Reels, DMs, Profile,
   Settings. There are no scenario lanes (signed out → sign in → signed in), no zones and no semantic zoom.
5. **Coverage is shown as counts plus "dark".** There is no per-zone confidence, freshness or blocked-door view. A
   "% mapped" would be dishonest, because the true size of an app is unknown.
6. **The inspector says "screenshots arrive in a later alpha."** Doors show no evidence: which runs used them, whether
   they worked, and how confident Cyclone is.
7. **The fleet table** has no coverage bar, frontier, activity or *Start mapping* for unmapped apps.

## 3. Checking the other agent's plan

| Claim | Verdict | Why |
|---|---|---|
| Separate Evidence (immutable) → Atlas (derived) → Routes/Skills → Glass projection | **Agree; already the shape.** | Trails and mission journals are the evidence (alpha.36). Learn is a derivation and idempotent. The Atlas is structure. Keep it. |
| Places and Doors are semantic, not coordinates or screenshots | **Agree; already true.** | Doors are selectors and structural keys, re-resolved live. `go_to` re-reads after every move (alpha.37). |
| Place families (`Profile(user)`, `Thread(person)`) | **Agree, with a correction.** | The walker already never taps content rows and samples one list row (`STRUCTURAL_SAMPLE`), so families emerge structurally. Parameterised door labels in the UI are the next step, not a new model. |
| Semantic zoom: Scenario → Zone → Place → Door; never the raw graph by default | **Agree. This is the core of the build.** | It matches all three mockup sheets. Zones are derived deterministically in Glass from the existing Atlas (§4.2). There is no new phone model. |
| "Fully mapped %" is the wrong metric; show confidence, freshness, frontier | **Agree.** | Coverage shows *confidence*, *current-version share*, *unconfirmed* and *blocked*. It never shows "X% of the app". |
| Mapper account scope and identity, credentials never in the graph | **Agree.** | Credentials stay in the Vault and Secrets Card as today. What was missing is the *mode* (look only vs test account). Built in §4.1. |
| Frontier planner by information gain | **Partly already there.** | Door ranking is tabs-first. A real information-gain planner needs a durable per-room frontier, which the phone does not persist yet (dark doors are counted per job in memory). Deferred to alpha.39, and named honestly in the UI as "unconfirmed" rather than "frontier". |
| **React Flow + ELK.js** | **Reject for Glass.** | `scripts/ci/glass_guard.py` fails the build on any runtime dependency. Glass ships as static files and the whole bundle is **194 KB**; ELK.js alone is over 1 MB. Semantic zoom keeps each view to about 5–80 nodes, which a small deterministic layered layout draws well. Revisit only if one zone regularly exceeds ~150 places. |
| Server-side projections per zoom level | **Not yet.** | Budgets cap a pass at 500 new screens. `atlas.get` for one app is small, so client-side projection is cheaper than new contract surface. Add a projection op when one app exceeds ~400 places. |
| Playwright-style replay with before/after screenshots | **Agree on the interaction; disagree on raw screenshots.** | Before/after screenshots of an owner's DMs are private content. Replay shows *structure*: expected vs observed room, the door, map vs model, and the Atlas effect. Real frames are for test-account passes only, later. |
| Minitap parity claims (dependency graph, personas, 100% AndroidWorld) | **Not verified here; not load-bearing.** | We copy the interaction patterns (story map, personas, dependency lanes), not benchmark claims. |
| SQLite on device, no Neo4j | **Agree; already true.** | |

## 4. The build (alpha.38)

### 4.1 Phone: safe mapping missions

**Mapper safety v2.** Before any door is tapped, refuse and record as dangerous:
- **Setting changes:** a checkable or switch / checkbox / radio / seek-bar element, or a row that *contains* one inside
  its bounds.
- **Social and state actions:** follow / unfollow, like, subscribe, join, add friend, accept / decline, block, report,
  mute, save / bookmark, vote, rate, install / uninstall / update, enable / disable, turn on / off, allow / deny.
- **Security areas:** password, two-factor, 2FA, passkey, security, recovery, logged-in devices, sessions, blocked
  accounts, delete or deactivate account.

These add to the existing pay / send / delete / grant / log-out classes. They become Atlas dangers (so the map shows
the blocked door) and are unit-tested with realistic rows.

**Mapping identity** (`mapping.start {identity: "own" | "test"}`, default `own`; returned in `mapping.status`):
- `own`, **"My account — look only"**. The mapper also refuses account and log-in doors. A sign-in wall ends the
  pass politely ("sign-in needed") instead of asking for credentials.
- `test`, **"Test account"**. The mapper may walk entry screens (sign in / sign up / onboarding). Credentials come
  only through the phone's Secrets Card (the existing `needs-secret` pause). Every safety class above still applies,
  and the final submit of an account creation stays the owner's.

Budgets in time come from Glass: 10 min, 30 min and 2 h map to `maxElapsedMs` 600 000 / 1 800 000 / 7 200 000 with
`maxNewScreens` 60 / 200 / 500.

### 4.2 Glass: the Atlas, semantic zoom

**Zones** (a pure, tested function in `maps/zones.ts`):
- The **entry place** is the scenario's entry screen, else the home-purpose screen, else the most-connected screen.
- **Base pages** are the entry plus the places one door away from it (tabs, drawer items, top-level destinations).
  Each base page names a zone.
- Every other place belongs to the zone of the base page on its shortest path from the entry. Places not reachable
  from the entry go to *Elsewhere*.
- **Scenario lanes:** places whose purpose is login / sign-up / onboarding form the *Signed out* and *Sign in* lanes;
  the rest are *Signed in*. Lanes with nothing known are shown empty, which is honest and tells you what to map next.

**Map tab**, a three-pane workspace:
- **Left rail:** Scenarios (with place counts) and Zones (with place counts and a confidence dot).
- **Centre, level 0 "Overview":** scenario lanes left to right, then zone cards (icon, name, places, doors,
  confidence), with the entry zone emphasised and zone-to-zone door bundles drawn between them.
- **Centre, level 1 "Zone":** click a zone and the existing room-and-door board shows only that zone's places, with a
  breadcrumb (*App › Zone*) and *Back to overview*.
- **Right, the inspector:**
  - **Place:** confidence, seen and verified times, purpose, zone, and the **doors from this place** as rows with
    confidence bars and a danger mark.
  - **Door:** clicking a door row shows intent, from → to, confidence, risk, last verified, and **evidence**: the
    runs that walked this door (from the run records' routes), each with *Open replay*.
- The live cursor and route highlighting keep working in both levels.

**Coverage tab** (new), with honest labels:
- **Summary:** scenarios known (x / 3), zones, places, doors, *confidence* (the average the phone reports),
  *on the installed version* (from the versions data), *unconfirmed* (low-confidence places and doors), and *blocked*
  (dangerous doors the mapper refused).
- **Per zone:** places, doors, a confidence bar, unconfirmed and blocked counts.
- A one-line explanation: *"Confidence, not a percentage of the app: Cyclone cannot know how big an app really is."*

### 4.3 Glass: mapping mission control

- **Start mapping sheet**, from the app header or from an unmapped row on the Apps page:
  - identity cards *My account — look only* / *Test account*;
  - budget chips *10 min · 30 min · 2 h*;
  - the promise: never sends, posts, pays, deletes or changes settings or security;
  - for a test account, *"Sign the test account in on the phone; passwords only go through the Secrets Card."*
- **Live board** while a pass runs:
  - elapsed time against the budget, with a bar;
  - counters: new places, verified moves, attempted doors;
  - current place pulsing on the zone overview, and a timeline of discoveries from the `atlas.diff` stream;
  - Pause / Resume / Stop, and the state spelled out (*Waiting for you: sign in on the phone*).
- **End report** when the job completes or stops:
  - places and doors before → after, zones reached and scenario lanes covered;
  - blocked dangerous doors with their reason;
  - why it stopped (budget, finished, sign-in needed, you stopped it);
  - buttons *View map*, *Map again*, *Map deeper (30 min)*.

### 4.4 Glass: fleet dashboard

`#/apps` becomes the fleet table from the mockup:
- **columns:** App, Knowledge (*Routing ready / Needs attention / Partial / Unmapped*), Places · Doors, Confidence
  bar, Freshness (*Current* / *Map from vX*), Activity (*Mapping…* live, last run), and an action (*Start mapping* for
  unmapped apps, *Open* otherwise);
- **filter chips** with counts, and sort by activity, least mapped or name.

"Routing ready" means mapped on the installed version with no failing last run. It is a label derived from facts
the phone already sends; no new signal.

### 4.5 Glass: run replay

The run inspector gets a **Replay** strip:
- **Step N of M** with previous / next and arrow keys, and a filmstrip of step chips.
- Each step card shows **Expected** (the room the map predicted, when the step came from the map), **Action**,
  **Observed** (the room after), **Result** (confirmed, diverged or failed) and **Source** (map or model).
- A *Show on the map* link lights the room.

It has no screenshots, by design (§3).

## 5. Invariants (unchanged, and guarded)

- `PhoneToolExecutor` stays the only mutation path. The mapper taps one door per step with settle and re-observe.
- Never persist passwords, codes, typed values or message content. The Atlas stays structural, and Glass shows
  structure only.
- Glass has no intelligence: zones and coverage are deterministic projections of the phone's Atlas, and Glass never
  decides a route.
- Glass has no runtime dependencies (`glass_guard.py`).

## 6. Order of work, done definition

1. Phone mapper safety v2 + identity (Kotlin, with tests); gateway contract (`identity`), tests.
2. Glass: `zones.ts` + coverage projection (tests) → Map tab with rail, overview and zone view, and the inspector
   with door evidence.
3. Glass: mapping mission sheet, live board and report (tests with a fake gateway).
4. Glass: fleet dashboard; run replay strip.
5. Rendered check in Chromium against fixtures (Instagram-sized fixture); release notes; versions **alpha.38**
   (mobile 5.0.0-alpha.38.dev1, versionCode 180; PC 1.6.0-alpha.38; Glass 1.0.0-alpha.21); all suites; publish.

**Done** means:
- the suites and guards pass;
- Glass is shown rendered with fixture data;
- the safety classes have tests;
- the release is published;
- physical acceptance is stated honestly (UNVERIFIED until the owner runs a pass).

## 7. After alpha.38

| Release | Contents |
|---|---|
| alpha.39 | One map, grounded skills and routines (plan 23). |
| alpha.40 | Hands (plan 21): reliable typing into composers. |
| alpha.41+ | Durable per-room frontier and an information-gain planner; the Changes (version diff) overlay on the map. |
| Later | Test-account frame capture with retention; App Packs. |
