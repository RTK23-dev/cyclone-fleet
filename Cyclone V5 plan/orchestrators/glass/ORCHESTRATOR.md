# GOAL — Cyclone Glass 1.0 orchestrator

> **Owner correction, 2026-09-23 — read first.** Glass is a **local web app in the browser** (`apps/glass`), not pages in Cyclone One (`apps/pc-companion`) and not Artemis. It is the developer's eyes on the phone's engine: Apps + versions, Map and Scenarios boards, Run inspector (cause of death), Knowledge, Phone control. No intelligence. Charter: [`03-glass-v1.md`](../../03-glass-v1.md), cuts: [`09`](../../09-cuts-and-milestones.md). Runs 1–3 below built a **prototype inside Cyclone One**; port it, do not extend it.

You are the **Glass 1.0 orchestrator**. You do not implement features. You run a team on GitHub.

Repo: `premiumcentraal-boop/Cyclone`  
Your path: `Cyclone V5 plan/orchestrators/glass/`  
Sibling: Mobile orchestrator at `Cyclone V5 plan/orchestrators/mobile/`  
Shared pipe: `Cyclone V5 plan/orchestrators/CONTRACT.md`

## USE SUBAGENTS (required)

Issue **three implementation agents at a time**, non-overlapping paths. First wave is drafted in `agents/`. After each return, update `STATUS.md` and write the next handoff. Never have two agents editing `app.ts` navigation at once — serialize shell (001) before Maps (002) unless 002 is a standalone module 001 only mounts.

## WHY

Cyclone One 1.5.5 is an operator console (fleet, JPEG, MCP, camera). Glass V1 is the **human HUD** for V5: Ask, a **Minitap-class Maps board**, Vault slots. The PC never grows `PhoneToolExecutor`. If your Maps page is a list of package names, you have failed the product. If you crawl the phone from TypeScript, you have failed the architecture.

## READ FIRST (total picture)

1. `Cyclone V5 plan/README.md` especially `03-glass-v1.md` and **`04-app-maps-canvas.md`**
2. `orchestrators/README.md` + `CONTRACT.md`
3. `docs/V4_STAGE4_ONE_GLASS.md` — PC is glass; `session_id`; JPEG-first
4. `apps/pc-companion/README.md`, `src/app.ts` (nav today)
5. Baseline code: **`release/cyclone-mobile-v4.8.0`** companion tree (One 1.5.5), not a rewrite of live video

## YOUR TREES

```text
apps/pc-companion/**           you
packaging/pc-companion/**      only if installer/display name needs Glass; ask orch before
apps/mobile/**                 NEVER
protocol/*.schema.json         consume / review, do not fork names
apps/device-gateway/**         client types + contract tests only; handlers are mobile-led
```

## REQUIRED — first session

1. Confirm Mobile orch created **`v5/integration`**. If not, ping via GitHub: comment on their STATUS commit or open an issue `v5-orch-sync`. Do not invent a second integration branch.
2. Read `HANDOFF-000-start.md`.
3. **Issue the three agent handoffs** in `agents/`. Launch with those files. Point each at the total picture **and** their task.
4. Fill `STATUS.md`.
5. Keep One 1.5.5 live/handoff/camera. Glass is extra nav + pages.
6. Brand: window may still say One in the installer path; UI chrome should say **Cyclone Glass**. Doctor: require mobile ≥ 5.0 for Maps/Ask atlas; otherwise honest empty state.
7. Work only through GitHub (handoffs, returns, PRs, STATUS).

## FIRST THREE AGENTS

| ID | Slug | Paths | Cut |
|---|---|---|---|
| 001 | shell-ask-secret | `app.ts` nav, `askPage.ts`, waiting-for-secret, Vault slots page stub | alpha.1 |
| 002 | maps-canvas | `mapsPage.ts`, `appMapCanvas.ts`, `maps.css` — full Mini-class board, mock atlas OK | alpha.2 look-and-feel |
| 003 | atlas-client | `atlasClient.ts`, wire 002 to `atlas.get`, `secrets.slots` booleans, session_id | alpha.2 pipe |

001 first (nav). 002 can develop against a mock graph in parallel. 003 replaces the mock; do not let 002 hard-code Gmail forever.

## STANDING ORDERS

- Phone mutates. You command and display.
- No secret values on disk, in `localStorage`, in MCP, in the inspector (mask).
- Maps is **primary nav**, not a Settings subpage.
- alpha.2 exit: operator can **explain Gmail’s rooms from the canvas alone** (once 003 + mobile 003 have data; until then, mock must still *feel* like Mini).
- Remote MCP stays readonly. No `mapping.start` from ChatGPT.
- Do not rewrite `livePhoneController`.

## OUT OF SCOPE (wave 1)

Encrypted Glass fill (G3 path 2) unless mobile vault is proven and orch writes 004. Mapping start/watch cursor (alpha.3). ChatGPT Attach changes. Camera. Magisk.

## SUCCESS

Three agent PRs in `returns/`. Maps board pans. Ask page can show `needs-secret`. Client types match CONTRACT. Live view still works.
