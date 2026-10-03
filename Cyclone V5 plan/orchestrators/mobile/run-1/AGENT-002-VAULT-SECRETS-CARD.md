# RUN 1 — AGENT 002 — VAULT + SECRETS CARD

**Read first:** `RUN-1-SHARED.md`  
**Branch:** `v5/mobile/vault-secrets-card`  
**PR target:** `v5/integration`

You are Agent 002. You own the **phone-local secret system and human card**. You do not own protocol names, central task-state typing, or Atlas.

You may develop in parallel, but before final validation **rebase onto Agent 001's merged `v5/integration`** and consume its actual `needs-secret` contract.

## Mission

Turn a credential wall from a dead end into a safe human-assisted boundary:

```text
run reaches secret wall
→ run waits as needs-secret
→ Secrets Card appears
→ user supplies or selects a stored slot
→ one-shot lease fills through PhoneToolExecutor
→ local verification
→ lease revoked
→ run can continue
```

No plaintext secret escapes this boundary.

## You own

- new `apps/mobile/**/secrets/**` package or equivalent
- `SecretsVault`
- secret slot metadata
- lease/revoke mechanism
- `SecretsCardOverlay` / controller
- Vault-specific Settings composables
- secret-specific tests
- integration with Agent 001's `needs-secret` contract after rebase, only where ownership does not collide

## Do not own

Do not edit:

- protocol schemas
- Android GatewayProtocol operation names
- Python gateway contract
- AtlasStore / Graph v2
- Follow Me
- Ask title/compiler heuristics
- Glass
- Mapper
- fleet/camera

Do not turn NEED_SECRET into a policy PAY/SEND/DELETE/GRANT class.

## Implementation requirements

### A. Vault storage

Use Android Keystore.

Preferred:

```text
StrongBox-backed non-exportable key
```

Fallback when StrongBox is unavailable:

```text
non-exportable Android Keystore key
```

StrongBox absence must not make the feature unusable.

Identity key:

```text
(placeId, persona, slotName)
```

Persona must remain `live` or `mapping`.

Do not store plaintext in SharedPreferences, SQLite, Graph, Brain, files, logs or diagnostics.

### B. Slot API

Your internal/provider API must expose only safe metadata:

- slot exists?
- slot name
- place
- persona
- created/updated timestamps if useful

Never return plaintext through a general getter that another subsystem could casually log.

Design explicit narrow methods for lease/fill/delete/replace.

### C. One-shot lease

The lease is single-use and bounded.

Required lifecycle:

1. authorize requested slot;
2. decrypt only inside the lease boundary;
3. fill through the existing `PhoneToolExecutor` input path;
4. locally verify without exporting plaintext;
5. revoke/zero references;
6. never retry from retained RAM after revoke.

Verification may compare inside the lease or confirm field-filled state. It must not copy the secret into Page Cards, observations, traces, assertion messages or consumer UI.

### D. Secrets Card

Build a sibling surface to Ask, using existing Cyclone glass language rather than a new visual system.

The card must:

- clearly say which safe slot is needed;
- allow entering/replacing a value;
- allow using an existing stored slot;
- allow Skip/Cancel;
- remain hittable while host-app taps still respect overlay yield;
- never display a stored password after save;
- never put the raw value into task status text.

Skip/Cancel leaves the task/place blocked/waiting; it must not crash Cyclone.

### E. Settings

Create Vault-specific Settings composables showing:

- place/persona
- slot names
- present / missing
- replace
- delete

Never show values.

During parallel development, avoid editing the root Settings navigation file if Agent 003 could touch it. Provide a mountable composable and document the mount hook in your return if needed.

### F. Agent-001 integration

After rebasing, consume the actual Run-1 `needs-secret` representation.

If wiring requires editing a central Agent-001-owned file, prefer the smallest possible hook. If that would create a conflict or architectural mess, leave a precise hook request in the return rather than duplicating state logic.

### G. Tests

Required tests:

1. save → slot present;
2. delete → slot absent;
3. live and mapping personas do not collide;
4. StrongBox fallback works in the testable abstraction;
5. lease is one-shot;
6. lease revokes after success and failure;
7. no plaintext appears in fake diagnostics/log export;
8. Secrets Card can receive input while overlay host-yield semantics remain correct;
9. Skip/Cancel is non-terminal;
10. stored value is never rendered back into Settings/card UI.

## Return file

Write:

`Cyclone V5 plan/orchestrators/mobile/returns/RETURN-RUN1-002-vault-secrets-card.md`

Include the exact public Kotlin interfaces Agent 001/Glass-facing adapters can safely call, but never a sample real secret.

## Done means

A real secret wall can stop safely, ask the human for exactly the missing slot, use a one-shot secret lease, and continue without the password appearing anywhere outside the Vault boundary.
