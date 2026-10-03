# Cyclone One 1.1 Stage A3 — Session Kernel glass (named VD tiles)

**Status:** DONE (code + docs; physical UNVERIFIED)  
**Identity:** mobile `4.0.4` / versionCode `75` (unchanged); Cyclone One `1.1.0-alpha.3`; device gateway / MCP `4.1.0-alpha.3`  
**Branch:** `grok/one-1.1-s3-sessions` from A2 tip  
**Physical Pixel 8:** **UNVERIFIED** — do not run live-phone smoke as a merge gate. A green CI or unit path is not device evidence.

Stage A3 makes named Shizuku VD sessions first-class on One glass, distinct from Layer 2. It does **not** add the operator MCP pack, a GitHub release cut, a mobile APK, Magisk, 20 concurrent VDs, a local compile, or a claim of parallel input. The A1 tooling seam and A2 `phone_workspace` / Layer 2 glass stay intact.

## Why

Installed One **1.0.0** could stream default-foreground JPEG and hand off HUMAN/AI on display 0. Named Session Kernel VDs (`session_id` + `displayId>0`) were not first-class tiles. Layer 2 workspaces (A2) stay on display 0 and must not be drawn as VD tiles.

## What landed

| Surface | Behavior |
|---|---|
| Tiles | Real `session.added` / `session.removed` tiles with `session_id`, `displayId`, owner HUMAN/AI. |
| JPEG | Per-session JPEG focus. Named VD is never silently rewritten to display 0. |
| Handoff | Pause / Take control / Give to AI per tile. `PHONE_LOCKED` / `HUMAN_HAS_CONTROL` preserved. |
| Copy | Foreground vs Session Kernel VD vs Layer 2 workspace stay distinct. A2 Layer 2 strip is not a VD tile. |
| Seams | A1 persisted bearer / token-free locator / doctor-without-scrape, and A2 `phone_workspace` / Layer 2 glass, remain the attach and workspace path. |
| Tests | Source of truth. MCP observe/act with non-default `session_id` routes to the matching tile in UI wiring tests. |

Transport success is still not task success. Product hot-gate for named VD Ask remains **1**. Types/APIs hold N≥2. Do not claim 20. Do not require a live Pixel run to merge this stage.

## Planes

Do not collapse these. Full contract: [`SESSION_CONTRACT.md`](SESSION_CONTRACT.md). No fourth plane.

| Plane | Identity | Display | Mutation model |
|---|---|---|---|
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock; switch verifies package/user |

One glass must never draw a Layer 2 workspace as a VD tile or vice versa. PC is glass only; `PhoneToolExecutor` on Android is the only mutator.

Physical Pixel remain **UNVERIFIED**. USB / a11y / `phone_status` on hardware is operator evidence, not a merge gate.

## Out of scope

- **A4** operator MCP pack (landed on `grok/one-1.1-s4-operator`; see [`ONE_1.1_STAGE4_OPERATOR.md`](ONE_1.1_STAGE4_OPERATOR.md))
- **A5** One 1.1.0 release cut
- Mobile APK (`4.0.4` / versionCode `75` stays)
- Magisk
- 20 concurrent VDs
- Local compile
- Claiming parallel input

## Handoff to A4

A4 is DONE (code + docs; physical UNVERIFIED) on `grok/one-1.1-s4-operator` from this A3 tip. See [`ONE_1.1_STAGE4_OPERATOR.md`](ONE_1.1_STAGE4_OPERATOR.md). Next stage is **A5** on `grok/one-1.1-s5-release`. Do not start A5 here.
