# RUN 1 — AGENT 001 — PROTOCOL + NEEDS-SECRET

**Read first:** `RUN-1-SHARED.md`  
**Branch:** `v5/mobile/protocol-need-secret`  
**PR target:** `v5/integration`

You are Agent 001. You own the **shared type system and pipe** for Run 1. Do not implement Vault or Atlas persistence.

## Mission

Make `needs-secret` and the V5 read contracts real enough that Agents 002/003 and Glass can build against them without guessing.

A credential wall must be representable as **waiting for secret input, not failed**.

## You own

1. `protocol/cyclone-atlas-v1.schema.json`
2. `protocol/cyclone-secrets-v1.schema.json`
3. authoritative Android gateway registration/dispatch for:
   - `atlas.places`
   - `atlas.get`
   - `secrets.slots`
   - `secrets.request`
4. task/run-state + presentation typing needed to expose `needs-secret`
5. Python `apps/device-gateway` forwarding/validation for those ops
6. secret-bearing payload rejection before forwarding/logging
7. contract tests

## Do not own

Do not edit or implement:

- Vault storage
- Android Keystore
- Secrets Card UI
- secret lease/fill
- AtlasStore
- PlaceCatalog persistence
- Follow Me graph writes
- App Maps Settings UI
- Glass
- Mapper
- Ask compiler/title heuristics

## Implementation requirements

### A. Schemas

Create Draft 2020-12 JSON schemas following the repo's existing protocol conventions.

Atlas schema must express the shared Run-1 vocabulary from `RUN-1-SHARED.md`. It must support an empty/unmapped document without inventing fake rooms.

Secrets schema must model:

- place/persona
- slot-name metadata
- boolean slot presence
- safe secret request metadata

It must have **no property capable or intended to carry a password/OTP/token value**.

Use `additionalProperties: false` where practical so accidental secret fields fail validation.

### B. needs-secret

Find the authoritative existing task model. Extend it; do not create a second state machine.

Consumer presentation must distinguish:

```text
working
action-needed
needs-secret
done
failed
```

or the exact equivalent that preserves `needs-secret` as an unambiguous wire/consumer value.

A secret wall must not be projected to FAILED merely because the agent cannot continue automatically.

Do **not** add NEED_SECRET to policy `GateClass` unless the existing architecture proves GateClass has changed meaning. Today it represents PAY/SEND/DELETE/GRANT approval risk.

### C. Android gateway

Android remains authority.

Register the four ops in the Android protocol and dispatch them to a bounded Run-1 adapter.

For this Agent-001 PR, Atlas and Vault implementations do not exist yet, so return **empty-but-valid** data, not fake data.

Examples:

- `atlas.places` → empty place list
- `atlas.get` → valid unmapped document for requested place/persona
- `secrets.slots` → known-safe boolean map, initially empty/false
- `secrets.request` → acknowledge a safe slot request without accepting a secret value

Keep them read/coordination surfaces. Do not expose generic phone mutation.

### D. Python gateway

Forward to Android. Do not create SQLite/JSON/local PC truth for Atlas or secrets.

Add validation that rejects secret-bearing keys/values before logging or forwarding.

At minimum reject obvious keys such as:

```text
password passcode passwd pin otp token secret api_key authorization cookie cvv credential typed_text typed_value
```

Reuse `SkillSecrets` concepts where appropriate, but do not weaken existing redaction.

### E. Tests

Required tests:

1. valid empty Atlas document validates;
2. valid secrets slot-presence document validates;
3. schema rejects a password/value field;
4. `needs-secret` is not a terminal failed state;
5. Android bridge advertises and dispatches all four ops;
6. Python gateway forwards to phone authority;
7. a secret-looking payload is rejected, not stripped-and-forwarded;
8. readonly MCP does not gain `mapping.start`.

## Return file

Write:

`Cyclone V5 plan/orchestrators/mobile/returns/RETURN-RUN1-001-protocol-need-secret.md`

Your return must include the exact consumer-state shape and exact gateway result shapes so 002/003 can rebase and wire without inference.

## Done means

Agent 002 can hang a Secrets Card on a stable `needs-secret` representation, Agent 003 knows the Atlas schema it must satisfy, and Glass can consume the same contract without inventing names.
