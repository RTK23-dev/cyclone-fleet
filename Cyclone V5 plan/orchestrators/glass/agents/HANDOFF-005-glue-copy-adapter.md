# HANDOFF-005 — glue-copy-adapter

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 1 glue  
**Branch:** `v5/glass/glue-copy-adapter` off `v5/integration`  
**PR into:** `v5/integration`  
**Worktree:** `/tmp/glass-005` (already created; do not create another)  
**Code paths (only these):**
- `apps/pc-companion/src/core/fleet.ts`
- `apps/pc-companion/src/maps/atlasClientAdapter.ts` (NEW)
- `apps/pc-companion/tests/atlas-adapter.test.mjs` (NEW)
- `apps/pc-companion/tests/ask-vault.test.mjs` **only if** you add assertions for the fixture title — prefer putting title asserts in `atlas-adapter.test.mjs` or a tiny `fleet-copy.test.mjs` so you do not fight 004’s maps assertions
- `apps/pc-companion/tsconfig.test.json` include the new adapter if `npm test` needs it compiled

**Do not touch:** `app.ts`, `mapsPage.ts`, `appMapCanvas.ts`, `atlasClient.ts` (consume, do not rewrite), `askPage.ts`, `vaultPage.ts`, `package.json`, `tauri.conf.json`, `version.toml`, `apps/mobile/**`

## Total picture (read first)

1. `Cyclone V5 plan/05-secrets-vault.md`
2. `Cyclone V5 plan/orchestrators/CONTRACT.md`
3. `apps/pc-companion/src/core/fleet.ts` — `ASK_NEEDS_SECRET_FIXTURE`
4. `apps/pc-companion/src/maps/mockAtlas.ts` — `MapsDataSource`
5. `apps/pc-companion/src/services/atlasClient.ts` + `atlasTypes.ts`

## Your individual task

### 1. Honest needs-secret copy

`ASK_NEEDS_SECRET_FIXTURE.title` is currently `"Checking Facebook login status"` — that is the 4.8 anti-pattern (login-status as the wait title).

Change it to **`"Facebook needs a password"`**.

Keep:
- `state: "needs-secret"`
- `slotLabel: "Facebook password"`
- `needsSecretWaitTitle` → `"Needs you — Facebook password"`
- no secret **values** in the fixture (no hunter2, otp, cookie, token payloads)

Add a test that the fixture title is **not** `/login status/i`.

### 2. atlasClient → MapsDataSource adapter

Maps canvas is sync (`MapsDataSource.listSummaries` / `getDocument`). Atlas client is async. Do **not** make `mapsPage.ts` async.

New file `apps/pc-companion/src/maps/atlasClientAdapter.ts`:

- Map `services/atlasTypes.AtlasDocument` → `maps/atlasViewModel.AtlasDocument` (structural; names already match CONTRACT).
- `export async function loadMapsDataSourceFromClient(client, persona): Promise<MapsDataSource>` that fetches `places()` then `get()` per place and returns a **sync** cache `MapsDataSource`.
- If a document has extra unknown keys, still map required fields; do not invent screens.
- Fail closed: never put secret values into the view model. Fact slots stay name/type/required/description. Inspector already masks.
- Do **not** auto-enable demo graph. Do **not** call `mapping.start`.
- Do **not** mount this from `app.ts` (004 mounts mock). Adapter exists so later orch/003 glue can pass `createMapsPage({ source })`.

### Tests

`cd apps/pc-companion && npm test` green. Cover:
- fixture title
- type mapping of a minimal atlas document (empty screens allowed)
- adapter does not copy a `password` string value onto the maps document

## Required

PR + `returns/RETURN-005-glue-copy-adapter.md`

## Out of scope

Mounting Maps in `app.ts`. Version identity. Mapping cursor. PC password inputs.

## Success

Ask wait copy names the password wall honestly. There is a typed adapter from `atlas.get` into the Maps board without the canvas knowing about HTTP.
