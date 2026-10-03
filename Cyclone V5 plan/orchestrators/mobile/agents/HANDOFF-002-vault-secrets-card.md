# HANDOFF-002 — vault-secrets-card

**From:** Mobile 5.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.1  
**Branch:** `v5/mobile/vault-secrets-card` off `v5/integration` (rebase onto 001 once merged, or onto 001’s branch if orch says so in STATUS)  
**PR into:** `v5/integration`  
**Code paths (only these):** new `apps/mobile/**/secrets/` (or equivalent), overlay secrets card, GATE fill/lease hookup, Settings vault slot list (booleans), `SkillSecrets` remains the log sieve  
**Do not touch:** `apps/pc-companion/**`, protocol schemas (001), AtlasStore (003), Ask compiler titles, fleet

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`05-secrets-vault.md`](../../../05-secrets-vault.md)
3. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
4. Existing: `automation/skill/SkillSecrets.kt`, overlay yield from 4.8, GATE pay/send/delete

The card is the gate. Mapping and Facebook login both die without it.

## Your individual task

1. `SecretsVault` in Android Keystore, using StrongBox when the device supports it and a non-exportable Android Keystore key otherwise. Per place, per persona (`live` | `mapping`). Slots only. **Never** write values into Graph, Brain, diagnostics, or SharedPreferences plaintext. StrongBox absence must not make the card unusable.
2. `SecretsCardOverlay` — sibling of Ask, same glass language. Pause the run. User types in the card **or** the host field. Overlay stays hittable for the card; host yield still applies to agent taps.
3. Lease: one fill via existing `PhoneToolExecutor` input path → local verification → revoke. Verification may check filled/non-empty state or compare inside the lease boundary, but **must not emit the plaintext read-back into observations, Page Cards, Brain, diagnostics, test failure messages, or logs**. No retry from RAM after revoke.
4. Secret boundary: consume 001’s `needs-secret` task/interruption state. Preserve the existing PAY/SEND/DELETE/GRANT policy GATE semantics; do not turn a password request into a financial/approval risk class. Skip / cancel leaves the place blocked, not crashed.
5. Settings: list which slots exist; delete/replace; no values on screen.
6. Tests: strip/redact; lease revoke; overlay does not swallow the card; no password in a fake diagnostic dump.
7. Demo path: Ask that hits a password field must show the card, not “Couldn’t finish.”

## Required

PR + `returns/RETURN-002-vault-secrets-card.md` with GitHub evidence.

## Out of scope

Glass keyboard fill (G3 path 2). Dummy account creation crawl. People memory.

## Success

A login wall is a card. Vault has a slot boolean. Logs do not contain the password.
