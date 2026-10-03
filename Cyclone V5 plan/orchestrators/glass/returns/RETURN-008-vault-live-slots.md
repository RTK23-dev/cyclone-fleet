# RETURN-008 — vault-live-slots

**Agent:** Glass 008 — live Vault slot presence  
**Handoff:** `agents/HANDOFF-008-vault-live-slots.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/vault-live-slots`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/159  
**Starting integration SHA:** `1c13c9ff0cd48ab7231474655771974db48d32b0`  
**Implementation SHA:** `b351a383e42663f955ba717097490111a1b3c440`  
**Head SHA:** docs return on `v5/glass/vault-live-slots` (PR #159)  
**Commits:**
- `b351a383` feat(glass): live Vault slot presence and phone-card request
- docs(v5 glass): return Run 2 Agent 008 vault-live-slots

## Done

Vault renders **phone-owned slot presence** (booleans only) and no longer lies that sample Facebook/Gmail rows are this phone.

- Frozen `createVaultPage` options from RUN-002-SHARED:
  - `devices?`, `mobileVersion?: string | null`, `slots?`
  - `loadSlots?: () => Promise<readonly GlassVaultSlot[]>`
  - `previewSlots?: boolean`
  - `onRequestSlot?: (slotId: string) => void`
- Version gate uses `hasOwnProperty` / `"mobileVersion" in options` so an **omitted** version keeps today’s fixture default (merge-safe before 009). Once `mobileVersion` is passed, fixtures are never live.
- `mobileVersion: "4.8.0"` (not atlas-ready): update-the-phone banner, **empty list**. No fixture Facebook/Gmail rows. `previewSlots === true` is the only sample inventory (labeled “Sample inventory — not live”).
- Atlas-ready + `loadSlots`: loading copy, then rows from the promise. Empty array → “No vault slots to show.” Live note: “Presence from the connected phone. Values stay in Android Keystore.”
- Missing slots with `onRequestSlot` are requestable rows. Click fires `onRequestSlot(slot.id)` and shows the existing secrets card (“Type it on the phone overlay”). **No `<input>`**, no `type=password`, no `localStorage`.
- `slotsFromPresence(placeLabel, slots)` in `fleet.ts` maps `secrets.slots` `{ [name]: boolean }` → `GlassVaultSlot[]`. Non-boolean values are dropped (fail closed). Slot names such as `password` are allowed as **keys**. Extra fields (`value` / otp / cookie strings) are ignored at render time and never copied onto the row.
- Privacy line stays. `ASK_NEEDS_SECRET_FIXTURE.title` is unchanged (`"Facebook needs a password"`).
- No Live vs Dummy toggle (009 will pass already-scoped `loadSlots`). Two inventories are never mixed: preview and live paths are exclusive.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/159 | Agent 008 PR targeting `v5/integration` |
| `1c13c9ff0cd48ab7231474655771974db48d32b0` | Starting `v5/integration` SHA |
| `b351a383e42663f955ba717097490111a1b3c440` | Implementation |
| docs return on PR #159 | Return MD on the same branch |

## Paths touched

```text
apps/pc-companion/src/pages/vaultPage.ts
apps/pc-companion/src/vault.css
apps/pc-companion/src/core/fleet.ts
apps/pc-companion/tests/vault-live.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-008-vault-live-slots.md
```

Did not touch `app.ts`, `mapsPage.ts`, `askPage.ts`, `atlasClient.ts` implementation, `livePhoneController`, `apps/mobile/**`, or `version.toml`. `secretsCard.ts` is consumed as-is (waiting UI only).

## Tests

```text
cd apps/pc-companion && npm install && npm test
→ 166 pass, 0 fail

Focused vault-live coverage:
- slotsFromPresence keeps boolean password keys and drops non-booleans
- mobileVersion 4.8.0 without previewSlots → update copy, fixture Facebook/Gmail rows NOT claimed live
- previewSlots: true → fixture rows + sample wording
- mobileVersion 5.0.0-alpha.1 + loadSlots set:true password slot → row shows set; JSON of slot has no value/otp/cookie
- clicking a missing slot with onRequestSlot fires the slot id and shows the phone wait card
- still no password input / no createElement("input") / no localStorage in vaultPage and secretsCard
- malformed slot string values (value/otp/cookie) are ignored and never rendered
- omitted mobileVersion keeps today's sample fixture default
- Agent 005 Ask fixture title is unchanged
```

Physical UNVERIFIED (no packaged companion / phone run in this environment). Accidental root `package-lock.json` from a misplaced `npm install` was discarded; companion lockfile was not committed.

## Contract

Did this change names/ops? **no**. Consumed frozen `secrets.slots` booleans and `secrets.request` → waiting card (G3 path 1). Glass still never sees, stores, or displays secret values.

## Not done / blocked

- `app.ts` does not pass `loadSlots` / `onRequestSlot` / `mobileVersion` — Agent 009 mounts.
- Encrypted Glass fill (G3 path 2) — out of scope.
- Persona Live vs Dummy toggle not added; 009 should pass already-scoped `loadSlots` (default live, never mix inventories).
- `atlasClient.ts` not forked; 009 calls `secretsSlots` / `secretsRequest` through options.

## Suggested next handoff

Agent 009: `createVaultPage({ devices, mobileVersion, loadSlots, onRequestSlot })` with `loadSlots` mapping `atlasClient.secretsSlots` through `slotsFromPresence`. Request body remains `{ placeId, persona, slot, reason }` — no secret value, no `session_id` on that JSON body.
