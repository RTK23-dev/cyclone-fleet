# HANDOFF-008 — vault-live-slots

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 2 / alpha.2 pipe  
**Branch:** `v5/glass/vault-live-slots` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
`apps/pc-companion/src/pages/vaultPage.ts`  
`apps/pc-companion/src/vault.css`  
`apps/pc-companion/src/ui/secretsCard.ts` (waiting / request banner only; still **no** password `<input>`)  
`apps/pc-companion/src/core/fleet.ts` (slot mapping helpers only — do not change Ask fixture titles 005 already fixed)  
`apps/pc-companion/tests/ask-vault.test.mjs` and/or new `tests/vault-live.test.mjs`  
`Cyclone V5 plan/orchestrators/glass/returns/RETURN-008-vault-live-slots.md`

**Do not touch:** `app.ts`, `mapsPage.ts`, `askPage.ts`, `atlasClient.ts` (call through options, don’t fork), `livePhoneController`, `apps/mobile/**`, `version.toml`

## Total picture (read first)

1. [`05-secrets-vault.md`](../../../05-secrets-vault.md) — **G3 path 1 only** (phone card)
2. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
3. [`agents/run-002/RUN-002-SHARED.md`](run-002/RUN-002-SHARED.md)
4. `pages/vaultPage.ts`, `ui/secretsCard.ts`
5. Run 1 `RETURN-001` + `RETURN-003` (`secrets.slots` booleans, `secrets.request` → waiting)

## Your individual task

1. Implement frozen `createVaultPage` options: `loadSlots`, `previewSlots`, `onRequestSlot`, honor `mobileVersion`.
2. **Live path:** `loadSlots()` maps `secrets.slots` `{ [slotName]: boolean }` → `GlassVaultSlot[]` (place label + slot name + set). **Never a value field.**
3. **Request:** clicking a missing slot calls `onRequestSlot(slotId)` if provided. Glass shows the existing waiting card (“Type it on the phone overlay”). No PC text field. No `localStorage`.
4. **Version gate:** < 5.0 / unknown → update-the-phone, no fixture pretending to be this phone. `previewSlots === true` is the only sample inventory when a version was passed.
5. Keep the privacy line. Tests: no `type=password`, no `<input>`, fixture not used as live when `mobileVersion` is `4.8.0`, boolean `password: true` allowed, string value rejected if it ever appears on a slot object you render.
6. Persona: if you show Live vs Dummy, they are two inventories, never mixed. Default live. Don’t invent a third persona.

## Out of scope

Encrypted Glass fill (G3 path 2). Maps. Ask composer. `app.ts` wiring (009). Mapper. Keystore on PC.

## Success

Vault can render phone-owned slot **presence**. A 4.8 phone does not see dummy Facebook/Gmail slots labeled as live. Operator still cannot type a secret on Glass.
