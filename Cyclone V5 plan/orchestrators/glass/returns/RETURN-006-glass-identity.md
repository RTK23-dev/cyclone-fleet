# RETURN-006 — glass-identity

**Agent:** Glass Run 1 Agent 006 (glass-identity)  
**Handoff:** `agents/HANDOFF-006-glass-identity.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/glass-identity`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/157  
**Starting integration SHA:** `3fa31431ba91fdb1c27ee3068bf22921937f065d`  
**Implementation SHA:** `181453302cac71653e9da6df438ca8aeb66664ac`  
**Head SHA:** this return commit on the same branch  
**Commits:**

- `18145330` feat(glass): Glass 1.6.0-alpha.1 identity (window title Cyclone Glass)
- this return file

## Done

Glass 1.0 identity cut on the existing Tauri companion. HUD already said “Cyclone Glass” in `app.ts` (untouched).

| File | Field | Value |
|---|---|---|
| `apps/pc-companion/package.json` | `version` | `1.6.0-alpha.1` |
| `apps/pc-companion/package.json` | `description` | `Cyclone Glass` |
| `apps/pc-companion/src-tauri/tauri.conf.json` | `version` | `1.6.0-alpha.1` |
| `apps/pc-companion/src-tauri/tauri.conf.json` | `app.windows[0].title` | `Cyclone Glass` |
| `apps/pc-companion/src-tauri/tauri.conf.json` | `productName` | **unchanged** `Cyclone One` |
| `apps/pc-companion/src-tauri/Cargo.toml` | `version` | `1.6.0-alpha.1` |
| `release/version.toml` | `[components].pc_companion` | `1.6.0-alpha.1` |

Left in place:

- `product_version` = `4.8.0`
- `mobile` = `4.8.0`
- `device_gateway` = `4.1.0`
- `mcp` = `4.1.0`
- `android_version_code` = `140`
- `publication_authorized` = `true`
- Cargo package `description` = `Cyclone One` (version-only edit)

New `apps/pc-companion/tests/glass-identity.test.mjs` reads those files with `fs` and asserts the Glass version/title plus installer `productName` still `Cyclone One` and mobile still `4.8.0`.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/157 | Agent 006 PR targeting `v5/integration` |
| `3fa31431ba91fdb1c27ee3068bf22921937f065d` | Starting `v5/integration` SHA |
| `181453302cac71653e9da6df438ca8aeb66664ac` | Implementation |

## Paths touched

```text
apps/pc-companion/package.json
apps/pc-companion/src-tauri/tauri.conf.json
apps/pc-companion/src-tauri/Cargo.toml
apps/pc-companion/tests/glass-identity.test.mjs
release/version.toml
Cyclone V5 plan/orchestrators/glass/returns/RETURN-006-glass-identity.md
```

No `app.ts`, pages, maps, atlas client, `fleet.ts`, Mobile, gateway, MCP, Android versionCode, or installer `productName`.

## Tests

```text
cd apps/pc-companion && npm install --no-audit --no-fund && npm test
→ tsc -p tsconfig.test.json && node --test tests/*.test.mjs
→ 152 pass / 0 fail / 0 skipped

New glass-identity coverage (4):
- package.json is Glass 1.6.0-alpha.1
- tauri window title is Cyclone Glass; installer productName stays Cyclone One
- Cargo.toml crate version is 1.6.0-alpha.1
- version.toml pc_companion is Glass; mobile/gateway stay 4.x
```

## Contract

Did this change names/ops? **no** / n/a.

CONTRACT.md Version lockstep says Glass/mobile/gateway/mcp move together for V5 alphas. Handoff exception followed: Glass alone to `1.6.0-alpha.1`; Mobile orch owns the 5.0.0-alpha.N pipe bump. Glass doctor already refuses atlas on phones < 5.0.

## Not done / blocked

- GitHub release/tag (orch after merge). Did not create a release.
- Installer path / `productName` still `Cyclone One` on purpose (one cut so MCP paths do not fork).
- `package-lock.json` still records `1.5.5` after `npm install` (not in the allowed-file list; restored).
- Mapping cursor. Mobile/gateway/mcp version bumps.

## Suggested next handoff

- Orch: merge #157 after 004/005 glue if those PRs are ready; then tag Glass 1.6.0-alpha.1.
- Mobile orch: 5.0.0-alpha.N pipe bump (`mobile` / `device_gateway` / `mcp` / `product_version`).
- Later cut: rename installer `productName` / `%LOCALAPPDATA%\Cyclone One` if Glass should stop sharing the One install path.
