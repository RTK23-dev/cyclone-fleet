# Cyclone One 1.1 Stage A2 — Layer 2 on PC/MCP/glass

**Status:** DONE (code + docs; physical UNVERIFIED)  
**Identity:** mobile `4.0.4` / versionCode `75` (unchanged); Cyclone One `1.1.0-alpha.2`; device gateway / MCP `4.1.0-alpha.2`  
**Branch:** `grok/one-1.1-s2-layer2` from A1 tip  
**Physical Pixel 8:** **UNVERIFIED** — do not run `doctor` against a live phone as a merge gate. A green CI or unit path is not device evidence.

Stage A2 makes One/MCP speak the mobile 4.0.3+ Layer 2 workspace protocol. It does **not** add named VD session tiles, the operator pack, a GitHub release cut, a mobile APK, Magisk, or a local compile. The A1 tooling seam (persisted bearer, token-free locator, doctor-without-scrape) stays intact.

## Why

Installed One **1.0.0** MCP had no `phone_workspace`. Mobile **4.0.3+** already shipped Layer 2 (`workspace.list/register/switch/pause/release/arm/next`) with a time-sliced global mutate lock on display 0. PC glass and MCP could not list, switch, or fail closed with the phone.

## What landed

| Surface | Behavior |
|---|---|
| Gateway | Routes `workspace.list/register/switch/pause/release/arm/next` and GET/POST `/v1/devices/{id}/workspaces` under protocol `cyclone.one.layer2.v1`. |
| MCP | Tool `phone_workspace`. After switch, mutating `phone_act.params` carry `workspaceId` + `workspaceGeneration`. |
| Glass | Layer 2 strip: registered workspaces, lock owner, pause/release, armed goal, generation. Distinct from VD session tiles. |
| Fail closed | Stale generation, wrong package (`TARGET_MISMATCH`), pending GATE, named session mix. |
| A1 seam | Persisted bearer, token-free locator, and doctor-without-scrape remain the attach path. |

Transport success is still not task success. Do not require a live Pixel run to merge this stage.

## Planes

Do not collapse these. Full contract: [`SESSION_CONTRACT.md`](SESSION_CONTRACT.md).

| Plane | Identity | Display | Mutation model |
|---|---|---|---|
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock; switch verifies package/user |

One glass must never draw a Layer 2 workspace as a VD tile or vice versa. PC is glass only; `PhoneToolExecutor` on Android is the only mutator.

Physical Pixel remain **UNVERIFIED**. USB / a11y / `phone_status` on hardware is operator evidence, not a merge gate.

## Out of scope

- **A3** named VD session tiles
- **A4** operator MCP pack
- **A5** One 1.1.0 release cut
- Mobile APK (`4.0.4` / versionCode `75` stays)
- Magisk
- Local compile

## Handoff to A3

Next stage: `docs/ONE_1.1_BUILD_PLAN.md` Stage **A3** on `grok/one-1.1-s3-sessions` from this A2 tip. Goal: named Shizuku VD session tiles, distinct from Layer 2. Do not start A3 in this change.
