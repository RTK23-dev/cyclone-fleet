# 09 — Cuts and milestones

Do not try to ship maps + Glass board + Louella in one tag.

Gateway/MCP **5.0** ships with **alpha.1**, not later.

Physical Pixel 8 stays **UNVERIFIED** until a named pass. CI is not that pass.

## Cuts

| Tag | Mobile | Glass | Operator can demo |
|---|---|---|---|
| **5.0.0-alpha.1 / Glass 1.0-alpha.1** | Vault + secrets card on Ask. GATE `NEED_SECRET`. Protocol stubs. | Ask page + “waiting for secret.” Pipe 5.0. | Facebook login wall does not *Couldn’t finish* |
| **alpha.2** | Atlas from Follow Me + Settings mini-canvas | **Full read-only Maps board** (G2.1–G2.6). App switcher, zoom, inspector | Walk Gmail once, **see the house on PC**. Exit: explain rooms from the canvas alone |
| **alpha.3** | One-button mapper, never-pay, dummy/live split | Start mapping from the board; live cursor; diffs spawn cards | Settings or Glass → Gmail → Start, watch from the desk |
| **5.0.0-rc** | Chrome places, Ask compiler, People memory | Ask sketches, Louella path, persona split, pin/remap | The sentence: live email → Chrome Facebook → DM |
| **5.0.0 / Glass 1.0** | Freshness + Pixel pass | Encrypted fill (or explicitly ship phone-only card). Stale/refresh in catalog | Secrets fill, one Gmail map, one Chrome-host map, one Ask that uses a live slot, overlay yield, GATE pay-block |

## Glass web cuts (owner charter, 2026-09-23)

Glass restarts as `apps/glass`, a local web app. The V5 pages prototyped inside Cyclone One are ported, not extended.

| Cut | Glass (web) | Phone / gateway work it needs | Developer can |
|---|---|---|---|
| **glass 1.0.0-alpha.1** ✅ built 2026-09-23 | Web shell served by the local gateway, one launcher, one design system. **Apps** page. **Map** board ported. **Phone** live + take control + Ask | `apps.list`; serve static bundle; existing `atlas.*`, `mapping.*`, `ask.*` | Open Glass in a browser, see every app, pan a map, drive the phone |
| **alpha.2** ✅ built 2026-09-23 | **Runs** list + **Run inspector** v1 from today's trace (steps, tools, verification, failure) with cause of death | `runs.list/get` over `AgentTraceStore`; phone-side cause-of-death classifier | Open a failed run and see which step killed it |
| **alpha.3** ✅ built 2026-09-24 (owner request) | **Devices**: connect a phone with a six-digit code + Allow on the phone ("Connect this PC?" notification), list, reconnect, disconnect; Glass opens on Devices when nothing is connected; honest not-ready reasons | trust match code (PC + phone), per-phone trust facts on `/v1/fleet`, `TRUST_REJECTED`, version kept across trust restore | Connect or drop a phone from the browser in seconds, like WhatsApp Web |
| **alpha.4** ✅ built 2026-09-24 | Inspector v2: rooms per step, map vs model steps, route on the map, `stale-door` (redacted frames and expected room still to come) | Run record v2 (room, room after, app + version, decision source) | See where a route broke on the map and jump to fix it |
| **alpha.5** ✅ built 2026-09-24 | **Scenarios** tab + **Versions** tab (cards and table; the Minitap-style left-to-right scenario board is still to come) | `scenarios.list` (routes from the entry room, health from runs) + `atlas.versions`; doors stamped with the app version | See an app's scenarios and their health; see needs-remap after an app update |
| **alpha.6** ✅ built 2026-09-24 | App **Screens** + **Runs** tabs, Scenarios **board** view, **Knowledge** page, mapping passes in Runs, **Mark as expected**, **You are here** on Phone, cause-of-death fix links, Runs filters, last run per app | `knowledge.get`, `atlas.here`, `runs.mark`; mapping passes written to the run trace | Walk from any failure to the room on the map; see what Cyclone knows at a glance |
| **alpha.7** ✅ built 2026-09-24 | Mapping depth (Quick / Standard / Deep), type + scroll on the phone from the PC, scenario health per app on Apps, `door-missing` cause, Teach on the phone from the map | `apps.list` scenario counts; `door-missing` from the Atlas | Choose how deep to map; drive text fields from the PC; spot failing apps at a glance |
| **alpha.8** ✅ built 2026-09-24 | **Home** page (the phone at a glance, what needs attention), **Sign in / Already signed in** scenarios, scenarios per persona, **Never pressed** list (Knowledge) + Safety in Settings, scenarios a run reached in the inspector, a finished Ask links to its run | `scenarios.list` `kind`; `knowledge.get` `guarded` (counts per app and danger, never labels) | See at a glance what needs you; check the login route and the never-pay list for every app |
| **alpha.9** ✅ built 2026-09-24 | Home runs chart + report download, Runs **Goals** view, inspector **compare with the last good run** + **Ask again**, `wrong-room` from the Atlas, scenario freshness, Vault state on Sign in, recent sentences, keyboard shortcuts; phone **Linked PCs** with Log out per PC | `wrong-room` after the run (map door led elsewhere); per-PC trust revoke on the phone | Find where a route broke against the last good run; log one PC out from the phone |
| **alpha.10** ✅ built 2026-09-24 | App **Issues** tab (every open problem with its fix), live run inspector + Runs list, Ask links to the live run, **Never pressed** on the map, Export CSV, Screens search, header chips, this PC's name in Devices, `[hidden]` fix | `knowledge.get` guarded `roomIds`; trust `pcLabel`; phone notice when a linked PC returns | Open one tab and see everything wrong with an app; watch a run as it happens |
| **alpha.12** ✅ built 2026-09-24 | Phone page **Share over Wi‑Fi** (asks the phone; chip when on); live view heals itself and names the USB reason; sidebar shows the Cyclone build | Phone **Wi‑Fi screen share** (MediaProjection → encrypted, mutually authenticated channel with the trust keys; view-only); gateway `share.status` / `share.request`, live view prefers the share and falls back to ADB | See the phone smoothly without depending on ADB screenshots |
| **alpha.13** ✅ built 2026-09-24 (Cyclone 5.0.0-alpha.21) | Run inspector shows each **clause** with its live proof, the run's **masked facts** and the expected room per mapped step; `clause-failed` cause | **Multi-app navigation**: sentences split into clauses proven one by one on the live screen, safe Atlas doors executed and re-checked, run-local task ledger, people memory after a proven thread; learned routes keep the sign-up stop, mapping passes never feed live facts; Teal Matrix v2 phone visuals | Ask a two-app sentence and see which clause passed, with what proof |
| **Cyclone 5.0.0-alpha.23** ✅ built 2026-09-25 | Diagnostics show waits, deferred proofs, model latency, backup route and completion basis | **Reliability**: screens settle on evidence (no lost after-states on launch), deferred proof, action goals need real outcomes, alarm/timer intents with live proof, sentence-true clauses ("check my Gmail and make a Facebook account"), backup model on rate limits, learned wait times | The three alpha.22 runs behave honestly on the phone |
| **Glass 1.0** | Knowledge page, Vault slots, polish, parity; Cyclone One loses the prototype pages | People memory, freshness | Glass exit criteria in [03](03-glass-v1.md) |

## Glass Maps is not optional

alpha.2 is the **look-and-feel** milestone. It ships **before** autonomous crawl. The operator must feel Mini’s demo on *our* atlas first. If the board is late, Glass is late.

## Pixel 8 5.0 gate (honest)

Do not publish 5.0 until Pixel 8 has:

1. Secrets fill (phone card at minimum)
2. One mapping of Gmail
3. One Chrome-host map
4. One Ask that uses a slot **live** (not dummy identity)
5. Overlay yield during mapping taps
6. GATE pay-block during mapping
7. Glass Maps: pan the Gmail house, inspector opens, live cursor on a remap

## Suggested merge order

1. Protocol schemas + GATE `NEED_SECRET` (mobile + gateway together)
2. Vault + overlay card
3. AtlasStore promoted from Graph v2 + Follow Me write path
4. Glass Maps read-only board
5. Mapper session + Glass start/watch
6. Ask compiler + People memory
7. Freshness
8. Encrypted Glass fill (or document phone-only)

Keep parallel agents on non-overlapping paths (`apps/mobile/**` vs `apps/glass/**` vs `apps/device-gateway/**`) as in `AGENTS.md`. `apps/pc-companion/**` is Cyclone One; V5 Glass work no longer lands there.
