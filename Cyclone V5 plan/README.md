# Cyclone V5 + Glass 1.0

**Status:** generation plan (not current product)  
**Baseline to leave behind:** Mobile **4.8.0** (versionCode 140) · Cyclone One **1.5.5** · Gateway/MCP **4.1.0**  
**Target:** Mobile **5.0** · Cyclone Glass **1.0** · Gateway/MCP **5.0** as one contract  
**Written:** 2026-09-21

V5 is not a feature dump on 4.8. The phone grows an **atlas** and a **vault**. The PC gets **Cyclone Glass** — a local browser dashboard that is the developer's eyes on the phone's engine, not a second brain.

> **Owner correction, 2026-09-23.** Glass is a **local web app in the browser** (`apps/glass`), not the Cyclone One Tauri app and not built on Artemis. It shows what Cyclone knows (apps, maps per version, scenarios), what it did (every run, with a step-by-step autopsy of failures) and lets you control the phone. It has no intelligence of its own. Read [03](03-glass-v1.md) and [11](11-run-inspector.md) first; where older docs or orchestrator briefs disagree, **03 wins**.

> One button to learn the house. A vault for the keys. A Minitap-class board on the PC so the operator can *see* the house. Eyes every time the agent walks through a door.

## Orchestrators (start here to build)

Two team leads, GitHub-only, first handoffs already written:

| Orchestrator | Prompt | First brief |
|---|---|---|
| Mobile 5.0 | [`orchestrators/mobile/ORCHESTRATOR.md`](orchestrators/mobile/ORCHESTRATOR.md) | [`HANDOFF-000-start.md`](orchestrators/mobile/HANDOFF-000-start.md) |
| Glass 1.0 | [`orchestrators/glass/ORCHESTRATOR.md`](orchestrators/glass/ORCHESTRATOR.md) | [`HANDOFF-000-start.md`](orchestrators/glass/HANDOFF-000-start.md) |

How they run, branch names, and the pipe: [`orchestrators/README.md`](orchestrators/README.md) · [`CONTRACT.md`](orchestrators/CONTRACT.md)

Each orch issues **three** agent handoffs (drafted under `orchestrators/<team>/agents/`). Agents return under `returns/` with PR URLs.

## Read in this order

| # | Doc | What it is |
|---|---|---|
| 0 | [Overview](00-overview.md) | Picture, Louella demo, what 4.8 already is |
| 1 | [Headset and laws](01-headset-and-laws.md) | Why this is a headset, not a harness |
| 2 | [Mobile 5.0](02-mobile-v5.md) | Workstreams M0–M6 |
| 3 | [Glass 1.0](03-glass-v1.md) | **Owner charter.** Local web dev dashboard: Apps, Map, Scenarios, Versions, Runs, Knowledge, Phone |
| 4 | [App Maps canvas](04-app-maps-canvas.md) | **Map board** + Scenarios lens + per-version maps, Minitap-class |
| 5 | [Secrets and vault](05-secrets-vault.md) | The card, Keystore, no values on the wire |
| 6 | [Atlas and mapper](06-atlas-and-mapper.md) | One-button crawl, dummy vs live, never-pay |
| 7 | [Ask compiler](07-ask-compiler.md) | Sentence stays law; atlas whispers |
| 8 | [Protocol and gateway](08-protocol-gateway.md) | `atlas.*` `mapping.*` `secrets.*` `ask.*` |
| 9 | [Cuts and milestones](09-cuts-and-milestones.md) | Alpha → RC → 5.0 / Glass 1.0 |
| 10 | [Reuse and refusals](10-reuse-and-refusals.md) | Steal 4.8. Do not rebuild. What we will not ship |
| 11 | [Run inspector](11-run-inspector.md) | The autopsy: every run step by step, cause of death, fix |
| 16 | [Cyclone Mind](16-cyclone-mind.md) | One model, one conversation, one mission |
| 17 | [Structure](17-structure.md) | Task Kit, Owner Moments, the building blocks |
| 18 | [Cyclone Lab](18-cyclone-lab.md) | Measured missions on the real phone, A/B |
| 19 | [Marketplace](19-marketplace.md) | Recipes, connections, and the road to a community store |
| 20 | [App mapping build plan](20-app-mapping-build-plan.md) | **Final build plan alpha.36–39**: Learn (36), map-guided runs (37, built), mapping missions |
| 21 | [Mind hands and desk](21-mind-hands-and-desk.md) | **Hands built (alpha.41)**: reliable text delivery; mission desk scratchpad (after Drive) |
| 22 | [Glass Atlas and mapping missions](22-glass-atlas-and-mapping-missions.md) | **Built in alpha.38**: semantic-zoom map, coverage, mission control, safe mapping identities |
| 23 | [One map, grounded skills and routines](23-one-map-grounded-skills.md) | **Built in alpha.39**: mapping passes teach runs, skills and routines anchored on the map, skills on Glass's map |
| 24 | [Cyclone Drive: voice assistant](24-cyclone-drive-voice.md) | **Plan**: driver mode AI button, Siri-like AI mode, voice pipeline on OpenRouter (alpha.49–50, after Planes, Hands, Background always, the Glass overlay, stable background, the web-only PC and parallel sessions) |
| 25 | [Planes: background and foreground](25-planes-background-foreground.md) | **First step built (alpha.40)**: the Mind in the background, one-tap switch pill, transactional plane switches, automatic plane choice, self-healing background |
| 26 | [Background that always works](26-background-always.md) | **Built (alpha.42)**: switch it on once, start from Recents, background parity, start preference, app leases for the same-app case, tier 0, see-to-approve, Lab planes suite; stabilised in plan 28, parallel sessions alpha.48 |
| 27 | [The overlay, redesigned](27-overlay-redesign.md) | **Built (alpha.43)**: tilt-lit glass, the one-word plane pill above card and island, four heights (card, island, notification only, idle), app logos, a background switch that goes home and is never silent, notification Show and Android 16 step segments |
| 28 | [Background that stays working](28-background-stable.md) | **Built (alpha.44)**: an audit of the whole background path and its fixes (interrupts no longer close background screens, a lock no longer pauses them for good, approvals work in the background, only steps that need the screen move the task, apps with several tasks, dialogs from other apps, a second route for gestures) and the one-tap Background Check |
| 29 | [Direct first, then the background screen](29-one-tap-engine.md) | **Layer 1 built (alpha.45)**: calendar, contacts, timers and alarms with no screen on every phone, access asked by Android's own dialog. **Layer 2**: stays on Shizuku, no Cyclone engine (decided) |
| 30 | [Setup cards](30-setup-cards.md) | **Built (alpha.46)**: a short guided setup in plain words, one Tilt Glass card per important setting (Shizuku included), with ⓘ in Settings to see a card again |
| 31 | [Web-only PC](31-web-only-pc.md) | **Built (alpha.47)**: one-line install, type `cyclone`, Glass in the browser with Remote MCP and ChatGPT Attach; the Cyclone One desktop window is retired |
| 32 | [Cyclone Drive build](32-cyclone-drive-build.md) | **D1 (alpha.49), D2 (alpha.50), "faster" (alpha.52) and D3 (alpha.53) built**: Driver mode, the voice orb and AI mode; questions, details and sends by voice with a word-for-word readback; newest speech-to-text, instant commands, JEV watching; message announcements, the car's Bluetooth microphone. Next: the owner's car test, then the Lab voice suite and JEV promotion if earned |
| 33 | [Command Center](33-command-center.md) | **Final plan; C0–C3 built (alpha.51–56), C5 Pages and the workspace redesign built (alpha.61), the AI project manager with OpenRouter in the dashboard (alpha.62)**: one dashboard for accounts, tasks, routines, phones, results and approvals; zero-knowledge vault with sealed one-use delivery to a phone; MCP connections (Higgsfield first); an AI coordinator that can't see secrets or approve. Releases C0–C6. **C0 built in alpha.51, C1 (vault) in alpha.54, C2 (sealed delivery) in alpha.55, C3 (connections, Higgsfield, pre-authorised leases) in alpha.56** |
| 34 | [Connector maker](34-connector-maker.md) | **M1+M2 built (alpha.57), M3+M4 built (alpha.58)**: connect any MCP yourself: paste an address, a server config or an API description; Cyclone finds the transport and sign-in, sorts tools into reads and changes, and pins them. Local MCP servers on the PC are allowed behind a short plain-words setup card; read and write; results come back from outside services, chain into the next step and reach a phone as quoted data; connectors export and import as cards without keys. Releases M1–M4 |
| 35 | [Everything left, run by run](35-roadmap-to-5.0.md) | **Roadmap after alpha.65 built**: about 21 build runs to 5.0 — connector maker, parallel sessions, the mission workspace (plan 37), fleet health, coordinator, smarter routines, the complete vault, mission desk, Pages, hosted, then RC and 5.0 — plus the owner's tests that gate each |
| 36 | [The App Manual](36-app-manual.md) | **App dictionary built (alpha.59); the rest planned (M1–M3)**: a smart mapper that learns apps by itself from generic UI patterns, safe experiments and a self-quiz. It writes every screen, panel, list and category in the app's own words (never your content); lists by how they work (order, groups, how to find one), categories and sub-categories found automatically; an index of abilities with verified paths so an agent walks there with no model call; JEV watches close calls. Model picker on the PC (default: the phone's model), screenshots switch |
| 37 | [The mission workspace](37-mission-workspace.md) | **W1+W2 built (alpha.66), W3 built as memory v2 (alpha.67); W4 planned; revised for flexibility**: guidance not gates, no new tools, nothing scrapped; every model call built from eight owned modules (rules, brief, your world, a journal of app stays, the current stay, a live state, the app's section of map and manual, one full screen) at about 25–30k tokens however long the run; `switch_app` with a journal block per app; each action states what it expects and the harness checks it; a definition of done checked at finish; diversions shown and asked when they change who, how, money or what is public; the owner's encrypted People & notes; recipes and traps that compound. Behind a setting and promoted only when the Lab shows it wins every suite |
| 38 | [Steer, queue, parallel](38-steer-queue-parallel.md) | **Built in alpha.68**: while Cyclone works, send shows Steer / Queue / Parallel and an empty bar gives Pause and a two-tap Stop; a steer is a goal version and forces a re-plan; the model decides diversions on its own, and only serious actions confirm; plan versions drawn as a branch on the plan card; a Lab divert suite |
| 39 | [Handoff: the remaining builds](39-handoff-next-builds.md) | **Working brief (2026-09-29, at alpha.70)**: how to ship a build, the laws, and per build (recipes and traps, fleet health, coordinator, routines, vault, mission desk, hosted, RC) the code to start from, what to build, tests, traps and the owner decisions needed |
| 40 | [Profiles as a workhorse](40-profiles-workhorse.md) | **P1 and P2 built in alpha.75** (Recently deleted for 7 days, automatic backup before delete, Cyclone Carry with people memory labelled by profile); deep dive and plan (2026-09-29, at alpha.73): how root profiles work today, what doesn't follow you, Cyclone Carry (hub and spoke, shared / per profile / never, an encrypted bundle, merge), friendly lifecycle with Remove → Recently removed → backup before delete, what makes it great, P1–P5 and the owner decisions |
| 41 | [Fast mode: decision models](41-fast-mode-decisions.md) | **Parallel Pilot built in alpha.76** (Fast mode in Settings, off by default); research plan (2026-09-29, at alpha.75): OpenAI's Decisions API (and JEV) for very fast runs; the lessons Fast mode must keep; three builds compared (Referee, **Pilot** recommended, Routes and forks); the question catalogue, images and privacy gate, Settings, proof and phases F0–F4 |
| 42 | [Cyclone Modes and Live voice](42-modes-instant-flash-mind.md) | **Built in alpha.77 (M1–M4, most of M5, Flash as a quick Mind mission)**; final build plan for "Cyclone Live" (2026-09-29): smart mode routing; Instant (local grammar and multi-question decision boxes acting with Cyclone tools in 0.05–0.6 s); Live voice (on-device partials, early commit, prewarm, silent success, 8 s ping-pong, answers within 10 s); Flash (fast planner + the parallel Pilot); the Mind; promotion with a baton; M1–M9 |
| 43 | [Tables, action buttons and Account Setup](43-tables-actions-account-setup.md) | **T1 (Tables core) built in alpha.79; T2 (relations, rollups, formulas, timeline and calendar) in alpha.80; T5 + T6 (Accounts rebuilt, sign-up mapping; no profiles) in alpha.81; T7 (Create accounts) in alpha.82; T3 (action buttons) in alpha.83; T4 (profiles from the PC) in alpha.84; mapping fix in alpha.85**; build plan (2026-09-30): Notion-grade tables in the Command Center, cross-references, action buttons for you and agents, phones › profiles, Accounts as phones → profiles → apps → accounts, Account Setup mode, and the Verification desk with scoped hand-over over MCP; T1–T9 |

## Identity

| Surface | Today | V5 generation |
|---|---|---|
| Android | Cyclone Mobile 4.8.0 | Cyclone Mobile **5.0** |
| PC (any OS) | Cyclone One 1.5.5 (Windows) | **Cyclone Glass 1.0** — local web app in the browser (`apps/glass`), served by the local gateway. Cyclone One stays a separate Windows console |
| Pipe | Gateway / MCP 4.1.0 | Gateway / MCP **5.0** (lockstep with mobile, not later) |

Invariant that does not move: **the phone thinks and mutates. Glass shows, inspects, and sends commands. GATE still owns pay / send / delete.**

## Folder rule

This folder is the generation plan. It does not describe the shipping 4.8 product. Current-product docs stay in [`docs/`](../docs/). When a V5 cut ships, promote the matching slice into `docs/` and leave this folder as the source plan.

Orchestrators and agents work **inside GitHub** under `Cyclone V5 plan/orchestrators/`. They do not invent a parallel docs tree.
