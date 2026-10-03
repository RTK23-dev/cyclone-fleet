# RETURN-005 — glue-copy-adapter

**Agent:** 005 — glue-copy-adapter  
**Handoff:** `agents/HANDOFF-005-glue-copy-adapter.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/glue-copy-adapter`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/158  
**Starting integration SHA:** `3fa31431ba91fdb1c27ee3068bf22921937f065d`  
**Implementation SHA:** `c9c6a85f2211883fa0f0751ae9c6af749abd5b17`  
**Head SHA:** this return commit on the same branch  
**Commits:**
- `c9c6a85f` feat(glass): honest needs-secret copy and atlas→Maps adapter
- this return file — docs(v5 glass): return Run 1 Agent 005 glue-copy-adapter

## Done

Honest Ask wait copy and a typed atlas.get → MapsDataSource adapter. Canvas stays sync. Adapter is not mounted.

- `ASK_NEEDS_SECRET_FIXTURE.title` is now `"Facebook needs a password"` (was `"Checking Facebook login status"`). State stays `needs-secret`. `slotLabel` stays `"Facebook password"`. `needsSecretWaitTitle` still yields `"Needs you — Facebook password"`. No secret values in the fixture.
- `apps/pc-companion/src/maps/atlasClientAdapter.ts`
  - `toMapsDocument(doc)` copies required `services/atlasTypes.AtlasDocument` fields into `maps/atlasViewModel.AtlasDocument`. Unknown extras are dropped. Fact slots stay `name` / `factType` / `required` / `description` only.
  - Fail closed via `secretGuards`: secret-looking keys and `looksLikeSecretValue` strings are not copied onto the Maps document.
  - `loadMapsDataSourceFromClient(client, persona)` fetches `places()` then `get()` per placeId and returns a **sync** `MapsDataSource` cache (`listSummaries` / `getDocument`).
- Does **not** auto-enable demo graph. Does **not** call `mapping.start`. Does **not** mount from `app.ts`.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/158 | Agent 005 PR targeting `v5/integration` |
| `3fa31431ba91fdb1c27ee3068bf22921937f065d` | Starting `v5/integration` SHA |
| `c9c6a85f2211883fa0f0751ae9c6af749abd5b17` | Implementation commit |
| this return commit | Return MD on the same branch |

## Paths touched

```text
apps/pc-companion/src/core/fleet.ts
apps/pc-companion/src/maps/atlasClientAdapter.ts
apps/pc-companion/tests/atlas-adapter.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-005-glue-copy-adapter.md
```

`src/maps/**/*.ts` is already in `tsconfig.test.json`, so the adapter compiles without a tsconfig edit. No `app.ts`, `mapsPage.ts`, `appMapCanvas.ts`, `atlasClient.ts` rewrite, Ask/Vault pages, `package.json`, `tauri.conf.json`, `version.toml`, or `apps/mobile/**`.

## Tests

```text
cd apps/pc-companion && npm install --no-audit --no-fund && npm test
→ 152 pass, 0 fail

Focused atlas-adapter coverage:
- needs-secret fixture title is "Facebook needs a password" and does not match /login status/i
- toMapsDocument maps a minimal empty-screens atlas document
- adapter does not copy a password STRING value onto the maps document
- loadMapsDataSourceFromClient builds a sync source from a fake client
```

## Contract

Did this change names/ops? **no** / n/a. Adapter consumes existing `atlas.places` / `atlas.get` names. Maps canvas still speaks `MapsDataSource`.

## Not done / blocked

- Adapter is **not** mounted from `app.ts`. Agent 004 owns Maps page mount (mock source). Later orch/003 glue can pass `createMapsPage({ source })`.
- `ASK_SAMPLE_SNAPSHOTS.done` title remains `"Signed-in state checked"` (not the login-status wait-title anti-pattern; left as-is).
- `mapping.start` not implemented (out of scope).
- Demo graph not auto-enabled.

## Suggested next handoff

- After 004 merge: optionally pass `loadMapsDataSourceFromClient(createAtlasClient(…), persona)` into `createMapsPage({ source })` instead of `mockMapsDataSource`.
- Ask HUD already uses `needsSecretWaitTitle(slotLabel)`; keep the fixture title as the honest password-wall copy, not destination-regex login-status.
