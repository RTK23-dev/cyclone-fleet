# RETURN-001 — shell-ask-secret

**Agent:** Glass 001 — shell / Ask / secret wait  
**Handoff:** `agents/HANDOFF-001-shell-ask-secret.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/shell-ask-secret`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/153  
**Starting integration SHA:** `c8c9e62946fb0028528f30358bb9f246b057baf6`  
**Head SHA:** `25f5f3d3497ee129a90a880e90d7f88d37430e7f`  
**Commits:**
- `b3743687662986417a685e377c41a2e88063fb56` feat(glass): add Ask, Maps, Vault shell and needs-secret wait
- `25f5f3d3497ee129a90a880e90d7f88d37430e7f` docs(glass): record Agent 001 shell-ask-secret return

## Done

- Brand the PC companion **Cyclone Glass** in the top bar (installer path still One).
- Primary nav: Home, Control, Ask, Maps, Vault, Tasks, Connections, ChatGPT. Settings stays in the profile menu.
- `AppRoute` extended with `"ask" | "maps" | "vault"`. Existing home/fleet/focused/automations/connections/chatgpt/settings preserved.
- Ask page: goal composer (send disabled; no PC executor), HUD for `working | action-needed | needs-secret | done | failed`, sample snapshot so `needs-secret` renders without a phone.
- `needs-secret` wait card: **Needs you — Facebook password**. Copy tells the operator to type on the phone. Path 1 only. No password input, no capture buttons. Privacy copy: Cyclone will not keep this in chat or logs.
- Vault page: slot-boolean inventory only (`Facebook password: set / missing`). Local fixture until Agent 003. Zero password/OTP fields.
- Maps route: inline placeholder `"Maps board ships next"`. Did not create `mapsPage.ts` or `atlasClient`.
- Compatibility: `phoneSupportsGlassAtlas(version)` is true for 5.x, false for 4.x. Ask/Vault show **Update Cyclone on the phone** when Mobile is not 5.x.
- Tests for new routes, needs-secret wait (not failed), Vault with no `input[type=password]` and no secret values in the fixture.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/153 | Agent 001 PR targeting `v5/integration` |
| `c8c9e62946fb0028528f30358bb9f246b057baf6` | Starting `v5/integration` SHA |
| `b3743687662986417a685e377c41a2e88063fb56` | Implementation |
| `25f5f3d3497ee129a90a880e90d7f88d37430e7f` | Return MD |

## Paths touched

```text
apps/pc-companion/src/app.ts
apps/pc-companion/src/core/fleet.ts
apps/pc-companion/src/pages/askPage.ts
apps/pc-companion/src/pages/vaultPage.ts
apps/pc-companion/src/ui/secretsCard.ts
apps/pc-companion/src/ask.css
apps/pc-companion/src/vault.css
apps/pc-companion/tests/fleet.test.mjs
apps/pc-companion/tests/ask-vault.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-001-shell-ask-secret.md
```

## Tests

```text
cd apps/pc-companion && npm test
→ 123 passed, 0 failed
  including:
  - Glass shell navigates to Ask, Maps and Vault without dropping ChatGPT
  - phoneSupportsGlassAtlas is true for 5.x and false for 4.x
  - Ask needs-secret fixture is a wait state, not failed
  - Ask page renders the needs-secret wait card from the sample snapshot
  - Vault lists slot booleans only and has no password input
  - existing focused-live / session / ChatGPT tests remain green
```

Physical UNVERIFIED (no packaged companion / phone run in this environment).

## Contract

Did this change names/ops? If yes, CONTRACT.md updated in the same PR: n/a

Consumed existing frozen names only: run state `needs-secret`, vault slot booleans, Mobile ≥ 5.0 fail-closed. Did not invent `ask.start` client/transport. Composer stays honestly disabled until Agent 003.

No CONTRACT mismatch discovered. Secret values were **not** added to fixtures, logs, DOM inputs, or source.

## Not done / blocked

- Maps canvas / `mapsPage.ts` — Agent 002.
- `atlasClient`, live `ask.start` / `ask.status` / `secrets.slots` — Agent 003.
- Desktop password fill (G3 path 2) — out of scope; Run 001 is phone-card path 1 only.
- Device objects still have no mobile version field (`types.ts` not owned). Ask/Vault therefore treat missing version as not-5.x and expose a sample snapshot toggle.
- Settings/doctor one-liner not edited (`settingsPage.ts` not in this agent's allowed paths). Compatibility copy lives on Ask/Vault themselves.

## Suggested next handoff

- HANDOFF-002: Maps board on the `maps` route placeholder this PR mounted.
- HANDOFF-003: Wire Ask HUD + Vault rows to phone-owned `ask.status` / `secrets.slots`; pass a real mobile version into `phoneSupportsGlassAtlas`.
