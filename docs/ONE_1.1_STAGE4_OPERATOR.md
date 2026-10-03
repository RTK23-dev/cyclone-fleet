# Cyclone One 1.1 Stage A4 — Operator MCP pack

**Status:** DONE (code + docs; physical UNVERIFIED)  
**Identity:** mobile `4.0.4` / versionCode `75` (unchanged); Cyclone One `1.1.0-alpha.4`; device gateway / MCP `4.1.0-alpha.4`  
**Branch:** `grok/one-1.1-s4-operator` from A3 tip  
**Physical Pixel 8:** **UNVERIFIED** — not a merge gate. Tests are the source of truth. Do not claim a live Pixel run.

Stage A4 makes the operator MCP pack boringly reliable. It does **not** add a GitHub release cut, a mobile APK, Magisk, a local compile, or a claim of a live Pixel run. The A1 tooling seam, A2 `phone_workspace` / Layer 2 glass, and A3 named VD tiles stay intact.

## Why

Installed One **1.0.0** browse was fragile: `phone.open_app` mixed app name / `packageName`, HTTP `/v1/observe` empty-422'd, `phone.home` already-on-home could false-negative as `VERIFICATION_FAILED`, and operators expected an OpenRouter key. `session_id=default-foreground` was implicit on glass.

## What landed

| Surface | Behavior |
|---|---|
| `phone.open_app` | `params.package` required. App name / `packageName` errors are distinct. Chrome is `com.android.chrome`. |
| HTTP observe | Legacy compact `POST /v1/observe` accepts an empty body (no empty-422). MCP uses `POST /v1/capabilities/observe`. |
| `phone.home` | Already-on-home is not `VERIFICATION_FAILED`. |
| Browse | Typed MCP `phone_status` → `phone_observe` → `phone_locate` → `phone.home` → `phone.open_app` Chrome. No OpenRouter key. |
| Copy | `session_id=default-foreground` named in One UI + doctor. |
| Seams | A1 persisted bearer / token-free locator / doctor-without-scrape, A2 `phone_workspace` / Layer 2 glass, and A3 named VD tiles remain the attach, workspace, and session path. |
| Tests | Source of truth. Operator pack suite + schema / home / observe / doctor / UI copy tests. Timings live in the operator pack test dict — not a live Pixel run. |

Transport success is still not task success. Do not require a live Pixel run to merge this stage.

## Planes

Do not collapse these. Full contract: [`SESSION_CONTRACT.md`](SESSION_CONTRACT.md). No fourth plane.

| Plane | Identity | Display | Mutation model |
|---|---|---|---|
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock; switch verifies package/user |

One glass must never draw a Layer 2 workspace as a VD tile or vice versa. PC is glass only; `PhoneToolExecutor` on Android is the only mutator.

Operator browse stays on Foreground (`session_id=default-foreground`, display 0). Physical Pixel remain **UNVERIFIED**. USB / a11y / `phone_status` on hardware is operator evidence, not a merge gate.

## Out of scope

- **A5** One 1.1.0 release cut
- Mobile APK (`4.0.4` / versionCode `75` stays)
- Magisk
- Local compile
- Claiming Pixel verified

## Handoff to A5

Next stage: `docs/ONE_1.1_BUILD_PLAN.md` Stage **A5** on `grok/one-1.1-s5-release` from this A4 tip. Goal: drop alphas, CI Setup.exe, `docs/RELEASE_ONE_1.1.md`. Do not start A5 in this change.
