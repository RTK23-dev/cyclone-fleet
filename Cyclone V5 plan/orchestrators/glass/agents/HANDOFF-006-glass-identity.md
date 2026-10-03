# HANDOFF-006 — glass-identity

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 1 glue / identity  
**Branch:** `v5/glass/glass-identity` off `v5/integration`  
**PR into:** `v5/integration`  
**Worktree:** `/tmp/glass-006` (already created; do not create another)  
**Code paths (only these):**
- `apps/pc-companion/package.json` (`version`, `description`)
- `apps/pc-companion/src-tauri/tauri.conf.json` (`version`, window `title` only)
- `apps/pc-companion/src-tauri/Cargo.toml` (`version`)
- `release/version.toml` (`pc_companion` only)
- `apps/pc-companion/tests/glass-identity.test.mjs` (NEW)

**Do not touch:** `app.ts`, pages, maps, atlas client, `fleet.ts`, `productName` in tauri.conf.json (installer path stays **Cyclone One**), `mobile` / `device_gateway` / `mcp` versions, `apps/mobile/**`, Android versionCode, `publication_authorized`

## Total picture (read first)

1. `Cyclone V5 plan/03-glass-v1.md` G0 Identity
2. `Cyclone V5 plan/orchestrators/CONTRACT.md` Version lockstep (read, then follow the exception below)
3. Current: One 1.5.5 / Mobile 4.8.0

## Your individual task

Run 1 identity for Glass. HUD already says “Cyclone Glass” in `app.ts` (do not edit it).

Set:

| File | Field | Value |
|---|---|---|
| `apps/pc-companion/package.json` | `version` | `1.6.0-alpha.1` |
| `apps/pc-companion/package.json` | `description` | `Cyclone Glass` |
| `apps/pc-companion/src-tauri/tauri.conf.json` | `version` | `1.6.0-alpha.1` |
| `apps/pc-companion/src-tauri/tauri.conf.json` | `app.windows[0].title` | `Cyclone Glass` |
| `apps/pc-companion/src-tauri/tauri.conf.json` | `productName` | **unchanged** `Cyclone One` |
| `apps/pc-companion/src-tauri/Cargo.toml` | `version` | `1.6.0-alpha.1` |
| `release/version.toml` | `[components].pc_companion` | `1.6.0-alpha.1` |

Leave `product_version` / `mobile` at `4.8.0` and gateway/mcp at `4.1.0`. Mobile orch owns the 5.0.0-alpha.N pipe bump. Glass doctor already refuses atlas on phones < 5.0.

Add `tests/glass-identity.test.mjs` that reads those files and asserts:
- package version `1.6.0-alpha.1`
- description `Cyclone Glass`
- tauri window title `Cyclone Glass`
- tauri `productName` still `Cyclone One`
- Cargo.toml version `1.6.0-alpha.1`
- `version.toml` pc_companion `1.6.0-alpha.1` and mobile still `4.8.0`

## Required

- `cd apps/pc-companion && npm test` green
- PR + `returns/RETURN-006-glass-identity.md`

## Out of scope

GitHub release/tag (orch after merge). Mapping cursor. Installer path rename. Bumping Mobile.

## Success

Glass reports 1.6.0-alpha.1 / window title Cyclone Glass, while the One installer identity is preserved.
