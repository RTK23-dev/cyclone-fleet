# RETURN-009 — glass-runtime-wire

**Agent:** Glass Run 2 Agent 009 (glass-runtime-wire)  
**Handoff:** `agents/HANDOFF-009-glass-runtime-wire.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/glass-runtime-wire`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/160  
**Starting integration SHA:** `1c13c9ff0cd48ab7231474655771974db48d32b0`  
**Implementation SHA:** `1024f4bf8d226bd2c053f65b647a6e1b1232e1c5`  
**Head SHA:** this return commit on the same branch  
**Commits:**
- `1024f4bf` feat(glass): wire atlasClient into Ask/Maps/Vault constructors
- this return file

## Done

Mount only. 007/008 own page internals. `app.ts` is the only place that knows gateway URL, bearer, device id, and session.

- `apps/pc-companion/src/services/glassRuntime.ts` — factory, not a second HTTP stack.
  - `createGlassRuntime({ httpBase, getBearer, getDeviceId, getSessionId, getPhoneVersion })` → `createAtlasClient({ …, useDemoGraph: false })`.
  - `loadMapsSource(persona)` → `loadMapsDataSourceFromClient`.
  - `loadVaultSlots(placeId, persona, placeLabel)` maps `secrets.slots` Record<string,boolean> → `GlassVaultSlot[]` (`id` `${placeId}:${name}`, `slotLabel: name`, `set: boolean`). Non-boolean values dropped.
  - `requestSecret(placeId, persona, slot, reason)` → `atlas.secretsRequest`. Body has no `session_id` (client already omits it). Never sends secret values.
  - `resolveGlassSessionId(focused?)`: named non-empty id preserved; empty → `default-foreground`. Never rewrites `vd-mail` to display 0 / default-foreground.
  - Blank `getSessionId` → `SESSION_REQUIRED`, fetch is not called.
- Phone version: optional `DesktopDevice.mobileVersion`. `readDeviceMobileVersion` / `applyDeviceMobileVersion` map fleet JSON `mobileVersion` → `appVersion` → `version`. Absent ⇒ undefined, **fail closed** (not 5.x). No new gateway op.
- `HttpDesktopService.glassGateway`: `{ httpBase, getBearer }`. `getBearer` returns the real token only for Authorization (same as existing `request()`). Never prints the bearer; last-4 is not this getter. Mock service omits `glassGateway`. Mock devices stay version-less.
- `app.ts` constructors (frozen names; type-asserted because 007/008 options are not on this worktree):
  - Ask: `{ devices, mobileVersion, onOpenControl }`. Send stays disabled. `ask.start` not wired.
  - Maps: `{ phoneVersion, loadSource, demo: this.service.mode === "mock", sessionId }`. Real (`mode === "real"`) never auto-demo. `loadSource` only when atlas-ready (version ≥ 5) **and** session + device + bearer + httpBase; otherwise omitted so 007 shows update-phone.
  - Vault: `{ devices, mobileVersion, loadSlots, onRequestSlot, previewSlots }`. Version always passed when a focused/real device exists (`"4.8.0"` or explicit `null` if unknown). Mock: `previewSlots: true` so fixtures are not “Presence from the connected phone.” `onRequestSlot` calls `requestSecret` with reason `operator-request`; errors swallowed without logging payloads.
- Settings: one doctor card titled **Cyclone Glass atlas**. Copy: Maps / Ask atlas / Vault need Mobile 5.0. Installer path may still say Cyclone One. MCP tunnel card not rewritten.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/160 | Agent 009 PR targeting `v5/integration` |
| `1c13c9ff0cd48ab7231474655771974db48d32b0` | Starting `v5/integration` SHA |
| `1024f4bf8d226bd2c053f65b647a6e1b1232e1c5` | Implementation |

## Paths touched

```text
apps/pc-companion/src/services/glassRuntime.ts
apps/pc-companion/src/app.ts
apps/pc-companion/src/services/types.ts
apps/pc-companion/src/services/httpDesktopService.ts
apps/pc-companion/src/pages/settingsPage.ts
apps/pc-companion/tests/glass-runtime.test.mjs
apps/pc-companion/tsconfig.test.json
Cyclone V5 plan/orchestrators/glass/returns/RETURN-009-glass-runtime-wire.md
```

Did **not** edit `mapsPage.ts`, `vaultPage.ts`, `appMapCanvas.ts`, `mockAtlas.ts`, `atlasClient.ts` implementation, `apps/mobile/**`, or `version.toml`.

## Tests

```text
cd apps/pc-companion && npm test
→ 169 pass, 0 fail

npx tsc -p tsconfig.json --noEmit → clean

Focused glass-runtime coverage:
- resolveGlassSessionId: named id preserved; empty → default-foreground; never rewrites vd-mail
- missing session → loadMapsSource does not fetch
- slotsFromPresence / loadVaultSlots: { password: true } allowed; string value dropped; no secret string in result JSON
- useDemoGraph is false
- requestSecret body omits session_id
- app.ts source contains loadSource, mobileVersion, createGlassRuntime / glassRuntime
- settingsPage source mentions Mobile 5.0 / Glass atlas
- 4.8.0 is not atlas-ready
- fleet JSON appVersion → mobileVersion; absent fails closed
- HttpDesktopService has glassGateway; mock omits it and leaves version unset
```

`package-lock.json` was not changed.

## Contract

Did this change names/ops? **no**. Consumes existing `atlas.places` / `atlas.get` / `secrets.slots` / `secrets.request`. Frozen option names from `RUN-002-SHARED.md`.

## Not done / blocked

- 007 Maps empty/update-phone/demo banner internals are not on this branch. Extra Maps options are passed via `as MapsPageOptions` so this PR stays `tsc`-green alone. After orch merges 007 then 009, the page will read them.
- 008 Vault `loadSlots` / `previewSlots` / `onRequestSlot` internals likewise asserted. Mock `previewSlots: true` is ready for 008.
- Companion state has no focused VD session id on Ask/Maps/Vault routes. Mount uses `resolveGlassSessionId()` → `default-foreground` unless a named id is supplied later. Named ids are never rewritten.
- Live `atlas.get` house still depends on Mobile durable atlas. Empty-valid is success; demo is explicit mock-only.
- `ask.start` / Send remain off (`ask.start` is not in `V5_OPS`).
- `mapping.start` not implemented.
- Gateway fleet JSON today does not emit `appVersion`. Field is optional; absent is fail closed, not invented 5.x.

## Secret values

No secret values were added to fixtures, logs, or source. The string `hunter2` appears only inside `glass-runtime.test.mjs` as a dropped/rejected presence-map assertion and is asserted **not** to appear in result JSON or in `glassRuntime.ts`.

## Suggested next handoff

- Orch merge order: **007 + 008 first**, then 009, so Maps/Vault option types replace the assertions.
- After merge: open Maps on a 4.8 fleet — must not be unlabeled mock Gmail. Open Maps on 5.0 / empty atlas — empty-valid or unmapped, not a crash.
- Gateway: emit Mobile `appVersion` on `/v1/fleet` when known so Glass can stop treating current 4.8 phones as “unknown.”
EOF
