# Glass orchestrator — STATUS

> **Owner correction, 2026-09-23 — read first.** Glass is a **local web app in the browser** (`apps/glass`), not pages in Cyclone One (`apps/pc-companion`) and not Artemis. It is the developer's eyes on the phone's engine: Apps + versions, Map and Scenarios boards, Run inspector (cause of death), Knowledge, Phone control. No intelligence. Charter: [`03-glass-v1.md`](../../03-glass-v1.md), cuts: [`09`](../../09-cuts-and-milestones.md). Runs 1–3 below built a **prototype inside Cyclone One**; port it, do not extend it.

## Glass web — 1.0.0-alpha.10 (2026-09-24, built by the front-runner session)

Glass restarted as `apps/glass` per the owner charter. Scope: [`SESSION-PLAN-glass-alpha1.md`](../SESSION-PLAN-glass-alpha1.md).

| Capability | State |
|---|---|
| Local web app served by the gateway at `/glass/`, one-tab launch session, `cyclone-device-gateway glass` | built; pytest + launcher smoke + headless Chromium verified |
| Cyclone One → Settings → **Open Cyclone Glass** (One's runtime bundles the Glass build) | built; TS tested, Rust validator unit-tested; Windows build/install verified only by CI |
| Phone `apps.list` (apps, installed + mapped versions, needs-remap) | built; JVM + gateway contract tests |
| Apps page, App → Map board (inspector, start/pause/stop, live cursor), Phone page (live view, take control, Ask) | built; 47 Glass tests; visual check with mocked phone replies |
| **alpha.2:** Runs page + run inspector (steps, metrics, cause of death with the failing step and the fix, events, report download); phone `RunInsight` + `runs.list` / `runs.get`; gateway validation | built; phone JVM 9 + gateway contract 14 + Glass 7 tests; visual check with mocked phone replies |
| **alpha.3:** **Devices** page — connect with a six-digit code + Allow on the phone (heads-up "Connect this PC?"), reconnect, disconnect; Glass opens on Devices when nothing is connected; not-ready pages give the gateway's reason (fixes "Waiting for Cyclone" on a trusted phone whose session was not open) | built; Glass 61 tests, gateway trust/fleet tests, phone match-code fixture shared with the PC; visual check with mocked replies |
| **alpha.4:** run inspector v2 — room / room after / app + version / map-or-model per step, 'From the map' split, Route on the map card, map highlights a run's route, `stale-door` cause | built; phone RunInsight + gateway contract + Glass tests |
| **alpha.5:** App → **Scenarios** (routes from the entry room, health from runs, Show on the map) and **Versions** (versions, needs remap, stale doors); mapper stamps doors with the app version | built; phone knowledge adapter + gateway contract + Glass tests |
| **alpha.6:** App Screens / Runs tabs, Scenarios board, Knowledge page (vault set / not set, skills, automations, totals), mapping passes as runs, Mark as expected (skipped by scenario health), You are here on Phone (`atlas.here`), cause-of-death fix links, Runs filters, last run per app | built; phone + gateway + Glass tests |
| **alpha.7:** mapping depth picker, type + scroll from the PC while you have control, scenario health counts on Apps, `door-missing` (run gave up in a mapped room with no door onward), Teach on the phone (Follow Me) from the map | built; phone + gateway + Glass tests green |
| **alpha.8:** **Home** page (phone at a glance, needs attention, latest runs, knowledge), **Sign in / Already signed in** scenarios (phone), Mapping pass / Your teaching switch on Scenarios, **Never pressed** list (`knowledge.get` `guarded`, counts only) + Safety card in Settings, scenarios a run reached in the inspector, a finished Ask links to its run | built; phone + gateway + Glass 90 tests green; visual check with mocked replies |
| **alpha.9:** Home runs chart + Download report, Runs Goals view, compare with the last good run, Ask again, `wrong-room` (phone, from the Atlas), scenario freshness, Vault state on Sign in, recent sentences on Phone, keyboard shortcuts, `[hidden]` fix; phone Linked PCs with Log out per PC | built; phone + gateway + Glass 99 tests green; visual check with mocked replies |
| **alpha.10:** App Issues tab, live runs, Ask → live run, Never pressed on the map, runs CSV, Screens search, header chips, this PC's name in Devices; phone notice when a linked PC returns | built; phone + gateway + Glass 107 tests green; visual check with mocked replies |
| Redacted frames, expected room recorded by the agent, per-version forget, data retention | next cuts |

Physical phone and Windows install: **UNVERIFIED** until someone runs it.

## Current state (2026-09-23) — alpha.4 dev1 in PR (alpha.3 merged)

**Published developer prerelease:** [`v5.0.0-alpha.2.dev3`](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.2.dev3) — Mobile `5.0.0-alpha.2.dev3` (versionCode 143) + Glass `1.6.0-alpha.2`, source `a896b5da`. Mobile + Glass CI passed; physical phone / Glass acceptance **waived by the owner, not passed**. Signed with the historical development key, which is exposed in repository history (**security debt**; rotation work parked in #168).

**Integration:** #169 merged into `v5/integration` (`df306096`) so integration equals the released source plus its release automation. #167 closed as superseded (its commits are in #169).

**Merged:** alpha.3 dev1 (one-button mapping, #171) into `v5/integration` (`51b52150`); not published separately.

**Next candidate:** `5.0.0-alpha.4.dev1` (versionCode 145) + Glass `1.6.0-alpha.4` on `claude/cyclone-v5-handoff-review-9qrs40` — the sentence is law, the Atlas as an Ask hint, and Ask from Glass.

| Capability | State |
|---|---|
| Vault + Secrets Card, `needs-secret` on login walls (password slot) | implemented, CI-tested, device-unproven |
| Durable Atlas, Follow Me → Atlas, `atlas.places/get/diff` | implemented, CI-tested, device-unproven |
| Canonical Places + Chrome origin privacy, Settings mounts | implemented, CI-tested, device-unproven |
| **Autonomous mapping** (driver, backtracking, mapping-persona Atlas port, notification Stop, secret pause/resume) | **new in alpha.3.dev1**, JVM-tested against a fake app with the real walker/controller/journal/AtlasStore; device-unproven |
| Phone App Maps: Map an app / Pause / Resume / Stop / rooms / structural report | **new in alpha.3.dev1** |
| Glass Maps: Start/Pause/Stop, live cursor, cards appear from `atlas.diff` | **new in alpha.3.dev1** (Glass side) |
| Sentence is law: account words no longer become "Checking … login status"; stages keep the user's verb | **new in alpha.4.dev1**, JVM-tested |
| Ask reads the Atlas as a hint (`atlasSketch`: rooms, doors, you-are-here, suggested route) | **new in alpha.4.dev1**; plumbing/privacy tested; effect on model choices unproven |
| Glass Ask (`ask.start`, `ask.status`, live HUD mirror) | **new in alpha.4.dev1**, phone + gateway + Glass tested; device-unproven |
| People memory, fact-slot reading, capability index | not started |
| Chrome-origin mapping, VD-plane mapping, dummy sign-up, freshness | not started |

Physical Pixel 8 status stays **UNVERIFIED** for every row until a named pass exists.

**Wave:** Run 3 **COMPLETE** (alpha.2 operator table)  
**Integration branch:** `v5/integration` @ `5ec5957b` (plus orch glue on top)  
**Last release:** `glass-1.0.0-alpha.1` (companion `1.6.0-alpha.1`)  
**Orch session:** 2026-09-22 — Run 3 merged; combined companion tests **218 pass / 0 fail**  
**Code base:** One 1.5.5 live path on Mobile 4.8.0; Glass HUD Run 1 + honest pipe Run 2 + operator table Run 3

## Run 1 (complete)

| ID | Agent | PR | State |
|---|---|---|---|
| 001 | shell-ask-secret | [#153](https://github.com/premiumcentraal-boop/Cyclone/pull/153) | merged |
| 002 | maps-canvas | [#155](https://github.com/premiumcentraal-boop/Cyclone/pull/155) | merged |
| 003 | atlas-client | [#154](https://github.com/premiumcentraal-boop/Cyclone/pull/154) | merged |
| 004 | glue-maps | [#156](https://github.com/premiumcentraal-boop/Cyclone/pull/156) | merged |
| 005 | glue-copy-adapter | [#158](https://github.com/premiumcentraal-boop/Cyclone/pull/158) | merged |
| 006 | glass-identity | [#157](https://github.com/premiumcentraal-boop/Cyclone/pull/157) | merged |

## Run 2 (complete) — honest live replica

| ID | Agent | PR | State |
|---|---|---|---|
| 007 | maps-honest-source | [#161](https://github.com/premiumcentraal-boop/Cyclone/pull/161) | merged |
| 008 | vault-live-slots | [#159](https://github.com/premiumcentraal-boop/Cyclone/pull/159) | merged |
| 009 | glass-runtime-wire | [#160](https://github.com/premiumcentraal-boop/Cyclone/pull/160) | merged |

## Run 3 (complete) — operator table

Combined `apps/pc-companion` tests after merge + type-assert cleanup: **218 pass / 0 fail**. `tsc --noEmit` clean.

| ID | Agent | PR | State |
|---|---|---|---|
| 010 | maps-operator-board | [#164](https://github.com/premiumcentraal-boop/Cyclone/pull/164) | merged `1eb5a4d8` |
| 011 | ask-hud-honesty | [#163](https://github.com/premiumcentraal-boop/Cyclone/pull/163) | merged `ac63e757` |
| 012 | glass-session-bind | [#162](https://github.com/premiumcentraal-boop/Cyclone/pull/162) | merged `5ec5957b` |

010: session plane on Maps, Take control → Phone, edge inspector (English doors), Dark doors filter, capability glyphs. Start mapping still disabled.  
011: Ask samples only when preview/omitted version; 5.x without preview is empty honest HUD; redacted HUD log download; Send off.  
012: `focusedSessionId`; Ask/Maps/Vault keep the focused phone; named VD from Phone tiles; mock-only `previewSnapshots`.

## Still blocked / not this cut

Live Gmail house on the board still needs Mobile [#146](https://github.com/premiumcentraal-boop/Cyclone/pull/146) (`atlas.get` Follow Me).  
`mapping.start` / cursor / `atlas.diff` (alpha.3). Encrypted PC fill. `ask.start` / Send. Gateway/MCP 5.0 bump. Glass identity / GitHub prerelease (still `1.6.0-alpha.1` / `glass-1.0.0-alpha.1`). Pixel UNVERIFIED.
