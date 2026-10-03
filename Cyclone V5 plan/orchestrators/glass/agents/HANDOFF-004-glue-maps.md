# HANDOFF-004 — glue-maps

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 1 glue  
**Branch:** `v5/glass/glue-maps` off `v5/integration`  
**PR into:** `v5/integration`  
**Worktree:** `/tmp/glass-004` (already created; do not create another)  
**Code paths (only these):**
- `apps/pc-companion/src/app.ts`
- `apps/pc-companion/tests/ask-vault.test.mjs`

**Do not touch:** `fleet.ts`, `mapsPage.ts`, `appMapCanvas.ts`, `atlasClient.ts`, `package.json`, `tauri.conf.json`, `version.toml`, `Cargo.toml`, `apps/mobile/**`, `livePhoneController`

## Total picture (read first)

1. `Cyclone V5 plan/03-glass-v1.md` G0–G2
2. `Cyclone V5 plan/04-app-maps-canvas.md`
3. `Cyclone V5 plan/orchestrators/CONTRACT.md`
4. `apps/pc-companion/src/pages/mapsPage.ts` — `createMapsPage({ source }?)` already exists
5. `apps/pc-companion/src/app.ts` still mounts `createMapsPlaceholderPage()`

## Your individual task

Maps canvas (Agent 002) is on integration as a **standalone module**. Agent 001 left a placeholder. Mount the real board.

1. Import `createMapsPage` from `./pages/mapsPage.js`.
2. When `this.state.route === "maps"`, set `this.currentPage = createMapsPage();` (default mock source is correct for Run 1). Do **not** import `atlasClient`.
3. Delete `createMapsPlaceholderPage` and any “Maps board ships next” copy.
4. `createMapsPage()` returns `{ element, destroy }` — that satisfies `PageHandle`.
5. Update `tests/ask-vault.test.mjs` test **“Glass shell brands Ask Maps Vault and keeps ChatGPT”**:
   - MUST match `createMapsPage` and `from "./pages/mapsPage`
   - MUST NOT match `createMapsPlaceholderPage` or `Maps board ships next`
   - MUST still NOT match `atlasClient` in `app.ts`
6. Do not restyle the canvas. Do not wire a live client. Do not start mapping.

## Required

- `cd apps/pc-companion && npm test` green
- PR into `v5/integration`
- `returns/RETURN-004-glue-maps.md` using `orchestrators/TEMPLATES/AGENT-RETURN.md`

## Out of scope

Fixture title (005). Version bump (006). Encrypted PC fill. Mapping cursor.

## Success

Opening Maps in the Glass shell renders the Minitap-class board (Gmail house), not a placeholder.
