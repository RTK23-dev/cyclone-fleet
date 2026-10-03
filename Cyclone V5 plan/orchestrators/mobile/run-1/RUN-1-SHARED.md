# RUN 1 — SHARED AGENT BRIEF

**Product:** Cyclone Mobile 5.0  
**Run:** 1 — protocol/secret boundary + Vault + durable Atlas foundation  
**Integration branch:** `v5/integration`  
**Run-1 seed:** `0e14ab9892f429d4006591af7b4804a1b8009f92`  
**4.8 code base:** `release/cyclone-mobile-v4.8.0@97f81cb692893896b500f2372068fb1cd67d85ed`

This file is mandatory for **all three Run-1 implementation agents**. Read this file first, then read **only your own specific Run-1 agent file**. Your specific file narrows your ownership; it never relaxes the laws in this shared brief.

## 1. What Run 1 is proving

Run 1 is not “build all of V5.” It establishes three foundations that later V5 work must be able to trust:

1. A credential wall becomes a first-class **`needs-secret`** run state instead of “Couldn't finish.”
2. Cyclone can hold and lease credentials without putting plaintext secrets into Brain, Atlas, diagnostics, the gateway, Glass, or logs.
3. Follow Me knowledge can become a **durable phone-owned Atlas** that survives restart and can later be drawn by Glass.

The run is successful only when these three foundations fit the same architecture. Do not optimize one by violating another.

## 2. Required reading

After this file, read:

1. `Cyclone V5 plan/README.md`
2. `Cyclone V5 plan/01-headset-and-laws.md`
3. `Cyclone V5 plan/05-secrets-vault.md`
4. `Cyclone V5 plan/06-atlas-and-mapper.md`
5. `Cyclone V5 plan/08-protocol-gateway.md`
6. `Cyclone V5 plan/orchestrators/CONTRACT.md`
7. `docs/ARCHITECTURE.md`
8. `AGENTS.md`
9. your one Run-1 agent file

Use the live 4.8 code as implementation truth when old prose differs.

## 3. Non-negotiable architecture laws

### Sentence is law
The user's mission remains the mission. Login, cookie, permission, and secret walls are intermediate conditions, not replacement goals.

### Phone is truth
Android owns perception, execution state, Atlas, Vault and the real gateway data. The PC gateway forwards/validates. Glass renders and commands. Do not build a PC-owned Atlas or secret store.

### Atlas is a sketch, not an executor
A known route is a hint. Runtime remains:

```text
retrieve sketch
→ observe current page
→ choose one mutation
→ execute through PhoneToolExecutor
→ settle
→ re-observe
→ verify
→ continue/recover
```

Do not make `AppGraphExecutor` rapid saved-hop replay the V5 Atlas execution path.

### Secrets never become context
Passwords, OTPs, cookies, API keys, bearer tokens, card secrets and raw typed secret values must not enter:

- Atlas / Graph
- Brain / routines / skill parameters
- diagnostics
- task presentation
- accessibility/Page Card exports
- gateway JSON
- MCP traces
- Glass/localStorage
- fixtures or assertion messages

Reject a secret-bearing wire payload. Do not “sanitize and continue.”

### Dummy is not live
`persona = live | mapping` is mandatory Atlas identity. Never merge mapping/demo observations into live user knowledge.

### Glass is not a second executor
Do not add phone mutation logic to `apps/pc-companion`.

### Evidence honesty
Passing unit tests is not physical-device verification. Pixel/device acceptance stays **UNVERIFIED** until actually performed.

## 4. Frozen Run-1 vocabulary

These names are shared. Do not invent aliases without changing `orchestrators/CONTRACT.md` in the same PR.

### Run state

Consumer/wire value:

```text
needs-secret
```

It is **not a failure** and is **not** a fifth policy `GateClass`. Existing policy GateClass remains:

```text
pay | send | delete | grant
```

The authoritative task model may implement `needs-secret` as a dedicated phase, interruption kind, or typed projection, but the consumer presentation must expose an unambiguous `needs-secret` state.

Minimal safe secret request metadata:

```json
{
  "placeId": "string",
  "persona": "live|mapping",
  "slot": "string",
  "reason": "bounded-safe-label"
}
```

Never include the secret value.

### Place identity

```text
package:<android.package.name>
chrome:<origin>
```

Place kind:

```text
package | chrome-origin
```

### Persona

```text
live | mapping
```

### Run-1 gateway operations

```text
atlas.places
atlas.get
secrets.slots
secrets.request
```

`mapping.start` is **not** part of Run 1.

### Secrets wire rule

`secrets.slots` exposes presence only. Example shape:

```json
{
  "placeId": "package:com.example",
  "persona": "live",
  "slots": {
    "username": true,
    "password": true,
    "otp": false
  }
}
```

Slot names are metadata. Values never cross the wire.

### Atlas wire minimum

The schemas authored in Run 1 must be able to represent:

- place id/kind/label
- package name or Chrome origin
- persona
- map status
- screens
- edges
- screen purpose
- fact-slot definitions
- danger/risk metadata
- confidence
- last verified/observed time
- stable layout coordinates for Glass

Run 1 does **not** require autonomous Mapper output.

## 5. Existing 4.8 facts you must preserve

- `PhoneToolExecutor` is the canonical mutation engine.
- `TaskPhase` currently has no secret-specific state.
- `TaskPresentationProjector` currently folds human input into generic `ACTION_NEEDED`.
- `GateClass` currently means approval risk: PAY/SEND/DELETE/GRANT.
- Android `GatewayProtocol` + `GatewayDispatcher` own phone operation registration/dispatch.
- Python `apps/device-gateway` is a constrained bridge, not phone truth.
- Legacy App Graph is persisted in SQLite by `AppKnowledgeStore`.
- Graph v2 currently exposes an `InMemoryTemporalGraphStore`; that alone is not durable enough for Atlas.
- `LegacyAppGraphV2Adapter` already exists. Reuse it instead of creating another graph universe.
- Follow Me intentionally ignores typed text events and sensitive fields. Preserve this.
- Existing graph records may contain `dynamic_json` and screenshot paths. Never blindly serialize those into `atlas.get`.

## 6. Parallel work and merge order

Development may start in parallel, but integration is ordered:

```text
Agent 001 → Agent 002 → Agent 003
```

Before final PR validation:

- Agent 001 validates against the Run-1 seed.
- Agent 002 rebases onto the latest `v5/integration` after Agent 001 merges.
- Agent 003 rebases onto the latest `v5/integration` after Agent 001 merges; if Agent 002 has merged too, take it as well.

Do not resolve an overlap by editing another agent's owned files. If your final hook requires an owned file, document the exact hook in your return and keep your feature internally callable/testable.

## 7. Central-file ownership for Run 1

| Surface | Owner |
|---|---|
| `protocol/cyclone-atlas-v1.schema.json` | Agent 001 |
| `protocol/cyclone-secrets-v1.schema.json` | Agent 001 |
| Android gateway op registration/dispatcher | Agent 001 |
| task-state / task-presentation `needs-secret` typing | Agent 001 |
| Python gateway forwarding/secret-payload rejection | Agent 001 |
| new Vault implementation | Agent 002 |
| Secrets Card overlay implementation | Agent 002 |
| secret lease/revoke implementation | Agent 002 |
| Vault-specific Settings composables | Agent 002 |
| durable AtlasStore / PlaceCatalog | Agent 003 |
| Graph-v2 Atlas model promotion | Agent 003 |
| Follow Me → Atlas write path | Agent 003 |
| Atlas-specific Settings composables | Agent 003 |

### Shared UI collision rule

Agents 002 and 003 may create their own Settings composables/files, but **must not both edit the root Settings navigation file during parallel development**. Mounting both new sections can be done after rebase/integration.

## 8. Prohibited Run-1 work

Do not implement:

- autonomous Mapper crawl
- People memory
- Louella/relationship memory
- full Ask compiler rewrite
- destination-title cleanup unrelated to `needs-secret`
- Chrome mapper
- Glass Maps canvas
- encrypted PC secret fill
- fleet/camera changes
- Magisk/root work
- unrelated visual redesign
- release publication

## 9. Validation standard

Run the tests for the code you touch. At minimum, the team collectively needs:

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest

python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp
python -m pytest apps/device-gateway/tests -q
python -m unittest discover -s tools/codex-phone-mcp/tests -v
```

Also run contract/schema tests added by Agent 001.

Never claim physical Pixel/device verification unless it actually happened.

## 10. Return contract

Every agent must create exactly one return file under:

```text
Cyclone V5 plan/orchestrators/mobile/returns/
```

Include:

- branch
- PR URL
- base SHA
- head SHA
- files changed
- tests run + exact results
- architecture invariants checked
- anything intentionally not wired because another agent owns the central file
- follow-up hook, if any
- physical-device status: VERIFIED or UNVERIFIED, with evidence if verified

Do not declare success with only screenshots or prose. The PR and tests are the evidence.

## 11. Stop conditions

Stop and report instead of improvising if:

- the required implementation would put a secret value on the wire;
- a solution requires making PC/Glass source of truth;
- you would need to create a second task state machine;
- you would need to merge mapping persona into live;
- you would need to make Atlas execute saved macros without live verification;
- the CONTRACT names no longer fit the implementation.

If the contract is wrong, change `orchestrators/CONTRACT.md` in the same PR and explain why.
