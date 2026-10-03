# HANDOFF-009 — glass-runtime-wire

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 2 / alpha.2 pipe  
**Branch:** `v5/glass/glass-runtime-wire` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
`apps/pc-companion/src/app.ts` (**constructors / version pass-through only** — do not restyle nav)  
new `apps/pc-companion/src/services/glassRuntime.ts` (factory: baseUrl, bearer, session_id, deviceId, phone version → `AtlasClient` + loaders)  
`apps/pc-companion/src/services/httpDesktopService.ts` / `mockDesktopService.ts` / `types.ts` **only** to surface optional `mobileVersion` / `appVersion` already on the fleet payload — do not add a new gateway op  
`apps/pc-companion/src/pages/settingsPage.ts` — **one** Glass doctor card (“Maps / Ask atlas need Mobile 5.0”)  
`apps/pc-companion/src/pages/askPage.ts` **only if** you must accept a live snapshot callback already shaped as `GlassAskSnapshot` — prefer passing `mobileVersion` from `app.ts` and leave HUD as-is  
`apps/pc-companion/tsconfig.test.json` include for `glassRuntime.ts`  
`apps/pc-companion/tests/glass-runtime.test.mjs` (new)  
`Cyclone V5 plan/orchestrators/glass/returns/RETURN-009-glass-runtime-wire.md`

**Do not touch:** `mapsPage.ts` internals, `appMapCanvas.ts`, `mockAtlas.ts`, `atlasClient.ts` implementation, mapping, ChatGPT Attach, camera, `livePhoneController`, `apps/mobile/**`, `version.toml` identity bump

## Total picture (read first)

1. [`03-glass-v1.md`](../../../03-glass-v1.md) G0, G4
2. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
3. [`agents/run-002/RUN-002-SHARED.md`](run-002/RUN-002-SHARED.md) — **call those option names**
4. `src/app.ts` Maps/Ask/Vault constructors
5. `src/services/httpDesktopService.ts` (`httpBase`, bearer `this.token`, `/v1/fleet`)
6. `src/services/atlasClient.ts` `createAtlasClient`
7. `src/core/sessionTiles.ts` — `session_id` rules

## Your individual task

You are the **mount**. 007/008 own page behavior. You pass phone reality in.

1. **`glassRuntime.ts`:** given focused device + `HttpDesktopService` (or duck-typed `{ httpBase, token }`), build `createAtlasClient({ baseUrl, getBearer, getSessionId, getDeviceId, getPhoneVersion, useDemoGraph: false })`.  
   - `getSessionId`: focused named session if the operator is on a VD tile; else `default-foreground`. **Never** rewrite a named session to display 0. Blank → do not call atlas (pages show SESSION_REQUIRED / update-phone, not a guessed id).  
   - `useDemoGraph` default **false**.
2. **Phone version:** map existing fleet JSON (`appVersion` on gateway hello / device payload) onto optional `DesktopDevice.mobileVersion`. If absent, treat as **not** 5.x (fail closed). Do **not** invent `atlas.version`. Mock service may stay version-less (honest banner) unless a test fixture sets `mobileVersion: "5.0.0-alpha.1"`.
3. **`app.ts`:**
   - `createAskPage({ devices, mobileVersion, onOpenControl })` — pass version. Send stays disabled.
   - `createMapsPage({ phoneVersion, loadSource, demo: false })` — `loadSource` uses 007’s contract + 005 adapter. On mock-mode companion (`service.mode === "mock"`), you **may** pass `demo: true` so the Mini house remains for UI development, and it **must** still say `(demo)`. Real backend never auto-demo.
   - `createVaultPage({ devices, mobileVersion, loadSlots, onRequestSlot })` — `loadSlots` / request go through `atlasClient.secretsSlots` / `secretsRequest`. Request only sends `{ placeId, persona, slot, reason }`. No secret value, no `session_id` on that JSON body.
4. **Settings:** one diagnostic card: Glass atlas/Vault require Mobile ≥ 5.0; installer path may still say Cyclone One.
5. Tests: mock backend → Maps demo flag or update-phone, never unlabeled fake live; missing session → no fetch; `mobileVersion` `4.8.0` → update copy; optional `5.0.0-alpha.1` fixture → loaders called; no password in runtime module.

If 007/008 are not merged yet, still **write calls using the frozen option names**. Keep a tiny fallback (`createMapsPage()` / `createVaultPage({ devices })`) only behind a type-narrowing comment if tsc cannot see the options; prefer depending on the published option contract so the orch merge order (007+008 then 009) is clean.

## Out of scope

Implementing Maps empty states (007) or Vault row mapping (008). `ask.start`. Mapping start. Encrypted fill. Version bump / GitHub release.

## Success

`app.ts` is the only place that knows gateway URL, bearer, device id, and session. Pages receive version + loaders. A real 4.8 fleet cannot be shown unlabeled mock Gmail as “this phone.”
