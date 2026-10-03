# HANDOFF-011 — ask-hud-honesty

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 3 / alpha.2 operator table  
**Branch:** `v5/glass/ask-hud-honesty` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
`apps/pc-companion/src/pages/askPage.ts`  
`apps/pc-companion/src/ask.css`  
`apps/pc-companion/tests/ask-vault.test.mjs` (extend) and/or new `tests/ask-hud.test.mjs`  
`Cyclone V5 plan/orchestrators/glass/returns/RETURN-011-ask-hud-honesty.md`

**Do not touch:** `app.ts`, `mapsPage.ts`, `vaultPage.ts`, `fleet.ts` (Ask fixture **titles** stay; do not change `ASK_NEEDS_SECRET_FIXTURE.title`), `glassRuntime.ts`, `livePhoneController`, `apps/mobile/**`, `version.toml`

If you need a tiny helper, put it in `askPage.ts` or a new `apps/pc-companion/src/pages/askHudLog.ts` (include in `tsconfig.test.json` only if you add a new src module).

## Total picture (read first)

1. [`03-glass-v1.md`](../../../03-glass-v1.md) G0, G4
2. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
3. [`agents/run-003/RUN-003-SHARED.md`](run-003/RUN-003-SHARED.md) — **option names are frozen**
4. `apps/pc-companion/src/pages/askPage.ts` as on integration
5. Run 1 `RETURN-001` + Run 2 `RETURN-009`

## Your individual task

Ask is a replica HUD. Samples must not lie. Send stays off.

1. **Frozen options** on `createAskPage`:

```ts
previewSnapshots?: boolean;
sessionId?: string;
sessionPlane?: "foreground" | "session_kernel_vd";
```

Keep `devices`, `mobileVersion`, `previewNeedsSecret`, `onOpenControl`.

2. **Honesty (same hasOwnProperty pattern as Maps 007 / Vault 008):**
   - `previewSnapshots === true` **or** `previewNeedsSecret === true` → today's sample dropdown, visibly labeled **(sample)** / “Sample snapshot — not a live phone run.”
   - `"mobileVersion" in options` (including `4.8.0` or a 5.x string) and **not** preview → **do not show the sample dropdown as if it were this phone.** Empty HUD: “No Ask run yet” plus existing update-phone banner when version is not atlas-ready. 5.x empty: “Send stays off until the phone Ask transport is connected.”
   - version **omitted** and no preview flag → keep today's sample dropdown (merge-safe default before 012).

3. **Session plane.** Display `sessionPlane` + `sessionId` on the Ask header/HUD kicker. Empty sessionId → `default-foreground`. Named id (e.g. `vd-mail`) must appear unchanged.

4. **HUD log download.** A control **Download HUD log** appears when a snapshot is showing. It produces a `.txt` (Blob + `<a download>` in browser; in Node tests, export `formatAskHudLog(snapshot)` and assert the string). Contents: state, title, milestones, session_id. **Strip / refuse** password, otp, cookie, token, typed secret values. Slot **labels** like `Facebook password` are allowed (that string is already the fixture title). The downloaded text must not contain `hunter2` or any invented secret value.

5. Send remains disabled. Form submit still does not execute on the PC. Take control unchanged.

6. Tests:
   - omitted version → sample dropdown still exists
   - `{ mobileVersion: "5.0.0-alpha.1" }` without preview → no “Sample snapshot (no phone required)” live-looking bar; Gmail/Facebook sample titles not presented as current run
   - `{ mobileVersion: "4.8.0" }` → update-phone copy; samples not live
   - `previewSnapshots: true` with a 5.x version → samples + **(sample)** wording
   - `sessionId: "vd-mail"` appears; not rewritten
   - `formatAskHudLog` on needs-secret fixture contains the title and `needs-secret`, contains `default-foreground` or the provided session, does **not** match a secret-value regex (`hunter2`, `otp=`, `cookie=`)
   - Send button still disabled
   - existing ask-vault tests still pass
   - `ASK_NEEDS_SECRET_FIXTURE.title` remains `"Facebook needs a password"`

## Out of scope

`ask.start`. Wiring `app.ts` (012). Maps. Vault. Encrypted fill. Louella compiler.

## Success

A 5.0 phone’s Ask page is an empty honest HUD with a plane label, not a fake “Opening Facebook” run. Samples still exist behind an explicit preview flag.
