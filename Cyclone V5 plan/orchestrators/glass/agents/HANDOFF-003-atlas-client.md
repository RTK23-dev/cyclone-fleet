# HANDOFF-003 — atlas-client

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.2 pipe  
**Branch:** `v5/glass/atlas-client` off `v5/integration` (rebase onto 001 + 002)  
**PR into:** `v5/integration`  
**Code paths (only these):** `apps/pc-companion/src/services/atlasClient.ts` (and types), wire Maps `AtlasViewModel` to `atlas.places` / `atlas.get`, Ask/Vault to `secrets.slots` + `needs-secret` snapshot, session_id on calls, fail-closed for mobile < 5.0 and `HUMAN_HAS_CONTROL`  
**Do not touch:** canvas renderer internals (002), overlay video, `apps/mobile/**` handlers (comment on Mobile PR if schema is wrong)

## Total picture (read first)

1. [`08-protocol-gateway.md`](../../../08-protocol-gateway.md)
2. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
3. Mobile 001 schemas on `v5/integration` — **consume, do not fork**
4. Existing `httpDesktopService.ts` / session client patterns (`session_id`)

## Your individual task

1. `atlasClient` typed from `protocol/cyclone-atlas-v1.schema.json` and secrets schema. If schemas are missing, **block** and write a return that points at Mobile 001 — do not invent names.
2. Maps: real `atlas.get` when the phone has a place; keep mock as fallback behind an explicit “demo graph” toggle, default off when phone ≥ 5.0.
3. `secrets.slots` booleans on Vault. `secrets.request` only triggers the **waiting** card (password still on phone).
4. Ask status: map `needs-secret` to 001’s UI.
5. All mutating/observe calls carry `session_id`. Named workspace rules unchanged.
6. Reject/ignore any payload with secret-looking values; do not write them to disk.
7. Tests: types vs fixture; session_id required; demo toggle; no password in client logs.

## Required

PR + `returns/RETURN-003-atlas-client.md` with GitHub evidence.

## Out of scope

`mapping.start` live crawl. Encrypted fill from PC keyboard.

## Success

Glass Maps draws a Follow Me house from the phone when Mobile 003 is present. Without it, honest empty + optional demo graph. Vault shows slot booleans. Zero secrets on the PC.
