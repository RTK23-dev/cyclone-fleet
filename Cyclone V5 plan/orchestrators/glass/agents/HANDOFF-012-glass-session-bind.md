# HANDOFF-012 — glass-session-bind

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 3 / alpha.2 operator table  
**Branch:** `v5/glass/glass-session-bind` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
`apps/pc-companion/src/app.ts` (**constructors / session pass-through only** — do not restyle nav)  
`apps/pc-companion/src/core/fleet.ts` (`CompanionState` + reducer only; **do not** change Ask fixture titles)  
`apps/pc-companion/src/services/glassRuntime.ts` (session resolve helpers only)  
`apps/pc-companion/src/pages/focusedPhonePage.ts` (optional `onSessionFocus` callback only — **do not** rewrite JPEG / `livePhoneController`)  
`apps/pc-companion/src/pages/settingsPage.ts` — **one extra line** on the existing Cyclone Glass atlas doctor card (session plane)  
`apps/pc-companion/tests/fleet.test.mjs` (extend reducer)  
`apps/pc-companion/tests/glass-runtime.test.mjs` (extend)  
`Cyclone V5 plan/orchestrators/glass/returns/RETURN-012-glass-session-bind.md`

**Do not touch:** `mapsPage.ts` internals, `askPage.ts` internals, `vaultPage.ts`, `appMapCanvas.ts`, `atlasClient.ts` implementation, `livePhoneController`, camera, ChatGPT Attach, `apps/mobile/**`, `version.toml`

## Total picture (read first)

1. [`03-glass-v1.md`](../../../03-glass-v1.md) G0, G4
2. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
3. [`agents/run-003/RUN-003-SHARED.md`](run-003/RUN-003-SHARED.md) — **call those option names**
4. `src/app.ts` `glassContext()` / Maps / Ask / Vault constructors
5. `src/core/sessionTiles.ts` — `DEFAULT_FOREGROUND_SESSION_ID`, named VD rules
6. Run 2 `RETURN-009`

## Your individual task

You are the **mount**. 010/011 own page internals. You pass plane + session + Take control.

1. **CompanionState** (`fleet.ts`):
   - Add `focusedSessionId: string | null` (default `null`).
   - Action `{ type: "focus_session"; sessionId: string | null }` — trim; empty → `null`. Never rewrite a named id to `default-foreground` here (display default happens at `resolveGlassSessionId`).
   - `navigate` to `ask` | `maps` | `vault` **keeps** `focusedDeviceId` and `focusedSessionId` (Glass pages are per-phone). Navigate to `home` | `fleet` | `automations` | `connections` | `chatgpt` | `settings` may keep current “leave focused phone view” behavior (`focusedDeviceId: null`) **and** clear `focusedSessionId`.
   - `back_to_fleet` / lost device still clears both.
   - `ASK_NEEDS_SECRET_FIXTURE.title` **unchanged**.

2. **`app.ts`**
   - Remove leftover `as MapsPageOptions` / `as VaultPageOptions` once types exist; if 010/011 are not on this worktree, type-assert the **new** fields the same way 009 did, using the frozen names.
   - `glassContext()`: `sessionId = resolveGlassSessionId(this.state.focusedSessionId)`. Named preserved. Empty → `default-foreground`.
   - `sessionPlane`: `"session_kernel_vd"` when the resolved id is **not** `default-foreground`; else `"foreground"`. Do not invent `layer2` on Maps/Ask.
   - Ask: `{ devices, mobileVersion, onOpenControl, sessionId, sessionPlane, previewSnapshots: demo }` — mock companion may pass `previewSnapshots: true`. Real backend (`mode === "real"`) must **not** auto-preview samples.
   - Maps: existing fields plus `sessionId`, `sessionPlane`, `onOpenControl: () => this.navigate("fleet")` (same as Ask — Phone live).
   - Vault: unchanged Run 2 mount (no new options).
   - Type assertions only if needed for parallel merge.

3. **Phone page (optional, light):** if `createFocusedPhonePage` can take `onSessionFocus?: (sessionId: string) => void` without rewriting video, fire it when the operator selects a session tile. `app.ts` dispatches `focus_session`. If that callback is too invasive, document leftover: Maps/Ask stay on `default-foreground` until Phone selection is wired. Prefer wiring it.

4. **Settings doctor:** one extra sentence on the existing **Cyclone Glass atlas** card: Ask/Maps declare Foreground (`default-foreground`) or the named Session Kernel VD; named ids are never rewritten to display 0.

5. Tests:
   - `resolveGlassSessionId("vd-mail")` → `vd-mail`; empty → `default-foreground`
   - focus device then navigate to `maps` keeps `focusedDeviceId`
   - navigate to `home` clears focus session
   - `app.ts` source contains `sessionPlane`, `onOpenControl`, `previewSnapshots`, `focusedSessionId`
   - type assertions: if you can compile without `as MapsPageOptions`, do so
   - no secret values; no `ask.start`; Start mapping not enabled from here
   - existing fleet + glass-runtime tests pass

## Out of scope

Maps inspector (010). Ask HUD internals (011). `ask.start`. Mapping. Encrypted fill. Gateway `/v1/fleet` `appVersion` (still absent — fail closed). Version bump / release.

## Success

`app.ts` is the only place that knows which phone and which `session_id`. Maps/Ask receive plane + Take control. Navigating Glass no longer forgets which phone was focused.
