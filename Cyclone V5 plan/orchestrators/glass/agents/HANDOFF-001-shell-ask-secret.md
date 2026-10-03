# HANDOFF-001 — shell-ask-secret

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.1  
**Branch:** `v5/glass/shell-ask-secret` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):** `apps/pc-companion/src/app.ts` (nav), new `pages/askPage.ts`, `pages/vaultPage.ts` (slot inventory stub), `ui/secretsCard.ts` (waiting / phone-card banner; no PC vault), styles as needed, doctor/settings one-liner “Maps/Ask atlas need Mobile 5”  
**Do not touch:** `mapsPage` / canvas (002), `atlasClient` (003), `livePhoneController`, ChatGPT Attach, camera, `apps/mobile/**`

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`03-glass-v1.md`](../../../03-glass-v1.md) G0–G1, G3 path 1, G4
3. [`05-secrets-vault.md`](../../../05-secrets-vault.md)
4. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
5. `apps/pc-companion/src/app.ts` — today’s Home / Control / Tasks / Connections / ChatGPT

## Your individual task

1. Primary nav becomes: **Phone** (existing focused live), **Ask**, **Maps** (mount a placeholder page titled Maps — “board in 002”), **Vault**, keep Tasks / Connections / Settings.
2. Brand label **Cyclone Glass** in the top bar. Installer path can stay One.
3. **Ask page:** composer → `ask.start` **if** the gateway op exists; otherwise bind to the existing phone Ask/status snapshot the companion already uses. HUD stages from the same presentation snapshot as mobile 4.8. Show `needs-secret` as **Needs you — [slot]** with a waiting card (G3 path 1: password is entered on the phone). Take control still works (existing handoff).
4. **Vault page:** slot booleans only (“Facebook password: set”). Empty until 003. Never an input that stores a secret on the PC.
5. Mobile < 5.0: Ask/Maps/Vault show update-the-phone, not a fake atlas.
6. Tests: nav routes; Ask renders needs-secret fixture; Vault has no password field in the DOM.

## Required

PR + `returns/RETURN-001-shell-ask-secret.md` with GitHub evidence.

## Out of scope

Full Maps canvas, atlas sync, encrypted PC fill.

## Success

Operator can open Ask on the desk and see a password wall as a wait state, not a crash. Maps is in the nav, even if the board is a placeholder.
