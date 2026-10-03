# HANDOFF-007 — maps-honest-source

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 2 / alpha.2 pipe  
**Branch:** `v5/glass/maps-honest-source` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
`apps/pc-companion/src/pages/mapsPage.ts`  
`apps/pc-companion/src/maps.css` (empty / loading / demo / update-phone states only)  
`apps/pc-companion/src/maps/atlasClientAdapter.ts` (extend if needed; do not rewrite)  
new `apps/pc-companion/src/maps/phoneAtlasSource.ts` (optional; load + cache helper)  
`apps/pc-companion/tsconfig.test.json` (include only if you add a new src module)  
`apps/pc-companion/tests/maps-*.test.mjs` (extend or add `tests/maps-honest.test.mjs`)  
`Cyclone V5 plan/orchestrators/glass/returns/RETURN-007-maps-honest-source.md`

**Do not touch:** `app.ts`, `askPage.ts`, `vaultPage.ts`, `fleet.ts`, `atlasClient.ts` (consume only), `livePhoneController`, `apps/mobile/**`, `version.toml`, mapping cursor

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`04-app-maps-canvas.md`](../../../04-app-maps-canvas.md) G2.4 + **G2.8** (not G2.7)
3. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
4. [`agents/run-002/RUN-002-SHARED.md`](run-002/RUN-002-SHARED.md) — **option names are frozen**
5. `apps/pc-companion/src/pages/mapsPage.ts` as it is on integration
6. Run 1 `RETURN-002` + `RETURN-005`

## Your individual task

1. Implement the frozen `createMapsPage` options from the shared brief (`loadSource`, `phoneVersion`, `demo`, `sessionId`).
2. **G2.8 honest board:**
   - loading
   - empty catalog
   - unmapped place (existing Start mapping card stays **disabled**, `phone alpha.3`)
   - `partial` coverage is labeled partial, never upgraded to mapped
   - error from `atlas.get` (including `PHONE_VERSION_UNSUPPORTED`, `SESSION_REQUIRED`, `HUMAN_HAS_CONTROL`) is named, not a blank crash
3. **4.8 / unknown version:** update-the-phone copy. **Do not show mock Gmail as the phone.**
4. **`demo === true`:** today’s mock atlas, with a visible `(demo)` banner. This is how operators still pan the Mini house without a 5.0 phone.
5. **Phone ≥ 5.0:** `loadSource()` → `MapsDataSource`. Reuse `loadMapsDataSourceFromClient` / `toMapsDocument` from Agent 005. Extra keys dropped. Secret **values** never copied onto slots.
6. Keep pan/zoom/inspector/Live-vs-Dummy. Do not enable Start mapping. Do not subscribe to `mapping.status`.
7. Tests: version gate hides mock; demo shows mock + `(demo)`; loadSource empty catalog; `partial` stays partial; no password string in mapped docs.

## Out of scope

Wiring `app.ts` (009). Vault. Ask Send. `atlas.diff`. Mapping start/cursor. Encrypted fill.

## Success

A 4.8 phone cannot be shown a fake Gmail house by this page. A 5.0 empty atlas is an honest empty/unmapped board. The Mini mock still exists behind an explicit demo flag.
