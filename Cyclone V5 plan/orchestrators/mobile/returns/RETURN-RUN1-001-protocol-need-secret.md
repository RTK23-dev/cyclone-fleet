# RETURN-RUN1-001 — protocol + needs-secret

**Agent:** 001 — Protocol + needs-secret  
**Handoff:** `RUN-1-SHARED.md` + `AGENT-001-PROTOCOL-NEEDS-SECRET.md`  
**Date:** 2026-09-22  
**Branch:** `v5/mobile/protocol-need-secret`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/144  
**Run-1 seed:** `0e14ab9892f429d4006591af7b4804a1b8009f92`  
**PR base:** `v5/integration@805e4b11bcbcf6abefb42d291fb88dbd5289c2a8`  
**Validated Android implementation SHA:** `5b85b0998964a6972c560dde222077ba7ebc2268`  
**Latest code/test SHA before this return:** `60fc655ec1f2e2d014d6164c080992452051fc36`

## Done

Agent 001 now provides the shared Run-1 type system and pipe without implementing Vault storage or Atlas persistence.

- Added Draft 2020-12 schemas:
  - `protocol/cyclone-atlas-v1.schema.json`
  - `protocol/cyclone-secrets-v1.schema.json`
- Extended the existing authoritative task/presentation model with a typed secret interruption instead of creating a second state machine.
- Preserved policy `GateClass` exactly as `pay | send | delete | grant`; secret waiting is not a fifth approval gate.
- Registered and dispatched the four frozen Run-1 Android operations:
  - `atlas.places`
  - `atlas.get`
  - `secrets.slots`
  - `secrets.request`
- Added explicit phone-owned source seams for the later owners:
  - `GatewayV5ContractSources.installSecrets(...)`
  - `GatewayV5ContractSources.installAtlas(...)`
- Added constrained PC gateway routes that forward to the authenticated Android authority. No PC Atlas/Vault persistence was added.
- Added fail-closed request rejection before forwarding and fail-closed Android-response validation before data can enter PC/model context.
- Added contract/schema tests and a readonly MCP regression proving `mapping.start` is not exposed.
- Kept mapping and live personas distinct.

## Exact consumer state contract

`TaskConsumerState.wireValue` is:

```text
working
action-needed
needs-secret
done
failed
```

Authoritative implementation:

- Secret waiting is represented by `TaskInterruptionKind.NEEDS_SECRET`.
- `TaskInterruption.needsSecret()` creates the bounded interruption.
- While the existing task is `HUMAN`, `PAUSED`, or `REVIEW`, `TaskPresentationProjector` projects that interruption as `TaskConsumerState.NEEDS_SECRET`.
- `TaskConsumerState.NEEDS_SECRET.wireValue == "needs-secret"`.
- It is not terminal, is not `FAILED`, and does not add a policy `GateClass`.
- Agent 002 should use this state for the Secrets Card rather than inventing another run-state name.

## Exact gateway result shapes

### `atlas.places`

Empty/default before Agent 003 installs a durable source:

```json
{
  "places": []
}
```

When Agent 003 installs a source, each entry must satisfy the Atlas `placeSummary` schema: `place`, `persona`, `mapStatus`, `confidence`, `lastObservedAt`, `lastVerifiedAt`.

### `atlas.get(placeId, persona)`

Default/unmapped package response:

```json
{
  "place": {
    "placeId": "package:com.example.app",
    "kind": "package",
    "label": "app",
    "packageName": "com.example.app"
  },
  "persona": "live",
  "mapStatus": "unmapped",
  "screens": [],
  "edges": [],
  "capabilities": [],
  "confidence": 0.0,
  "lastObservedAt": null,
  "lastVerifiedAt": null
}
```

Chrome identity uses the same document shape with:

```json
{
  "place": {
    "placeId": "chrome:https://example.com",
    "kind": "chrome-origin",
    "label": "example.com",
    "origin": "https://example.com"
  }
}
```

`persona` is always exactly `live` or `mapping`. Agent 003 must not merge those identities.

### `secrets.slots(placeId, persona)`

Default before Agent 002 installs a Vault metadata source:

```json
{
  "placeId": "package:com.example.app",
  "persona": "live",
  "slots": {}
}
```

Allowed populated form is presence metadata only:

```json
{
  "placeId": "package:com.example.app",
  "persona": "live",
  "slots": {
    "password": true,
    "otp": false
  }
}
```

Slot names may describe secret kinds. Slot values are booleans only.

### `secrets.request`

Request args are exactly safe metadata:

```json
{
  "placeId": "package:com.example.app",
  "persona": "live",
  "slot": "password",
  "reason": "Login required"
}
```

Acknowledgement:

```json
{
  "state": "needs-secret",
  "request": {
    "placeId": "package:com.example.app",
    "persona": "live",
    "slot": "password",
    "reason": "Login required"
  }
}
```

There is intentionally no secret-value field in the request, acknowledgement, schemas, source interfaces, or PC forwarding service.

## Phone-owned integration hooks

### Agent 002 — Vault / Secrets Card

Implement the phone-owned metadata source and install it during Vault runtime initialization:

```text
GatewayV5ContractSources.installSecrets(source)
```

The source contract is:

```text
slots(placeId, persona) -> Map<String, Boolean>
request(GatewayV5SecretRequestMetadata)
```

It has no secret-value parameter. Secret lease/fill remains Agent 002-owned and must stay off gateway JSON.

When execution reaches a credential wall, keep the user's original task and move the existing task to an appropriate human boundary (`REVIEW`, `PAUSED`, or `HUMAN`) with `TaskInterruption.needsSecret()`. Do not convert that wall into `FAILED`.

### Agent 003 — durable Atlas

Implement the phone-owned durable source and install it during Atlas runtime initialization:

```text
GatewayV5ContractSources.installAtlas(source)
```

The source contract is:

```text
places() -> List<JSONObject>
get(placeId, persona) -> JSONObject?
```

Return schema-shaped summaries/documents only. Do not serialize raw legacy graph `dynamic_json`, screenshot paths, typed text, credentials, or secret fact slots. A null `get` result intentionally falls back to the canonical unmapped document.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/144 | Agent 001 PR targeting `v5/integration` |
| `5b85b0998964a6972c560dde222077ba7ebc2268` | Validated implementation head; Mobile CI passed gateway/MCP contracts, Android unit tests, lint, and release assemble |
| `60fc655ec1f2e2d014d6164c080992452051fc36` | Test-only fixture cleanup; final-head gateway/MCP contract suite passed |

## Paths touched

```text
apps/device-gateway/cyclone_device_gateway/api/stream_api.py
apps/device-gateway/cyclone_device_gateway/api/v5_contract_api.py
apps/device-gateway/cyclone_device_gateway/cyclone_bridge/protocol.py
apps/device-gateway/cyclone_device_gateway/desktop_runtime/v5_contract.py
apps/device-gateway/pyproject.toml
apps/device-gateway/tests/test_v5_contract.py
apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayProtocol.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayRuntime.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayV5ContractAdapter.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/TaskHarnessState.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/TaskPresentationSnapshot.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceProgressActivity.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39BrainPage.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/TaskGlassPresentation.kt
apps/mobile/app/src/test/java/com/cyclone/mobile/gateway/GatewayDesktopRuntimeV1Test.kt
apps/mobile/app/src/test/java/com/cyclone/mobile/gateway/GatewayV5ContractAdapterTest.kt
apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/V5NeedsSecretContractTest.kt
protocol/cyclone-atlas-v1.schema.json
protocol/cyclone-secrets-v1.schema.json
tools/codex-phone-mcp/tests/test_v5_readonly_contract.py
```

## Tests

GitHub Actions Mobile CI run `35663445809` validated implementation SHA `5b85b0998964a6972c560dde222077ba7ebc2268` in the PR merge context:

```text
python3 -m pytest apps/device-gateway/tests -q
python3 -m unittest discover -s tools/codex-phone-mcp/tests -v
→ SUCCESS (combined "Verify PC Gateway and MCP contracts" step)
→ MCP unittest: Ran 187 tests in 2.696s — OK

./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --stacktrace
→ BUILD SUCCESSFUL in 8m 21s
→ 154 actionable tasks: 154 executed

repository/security guards
→ SUCCESS
```

The same Mobile CI also packaged and uploaded the unsigned release candidate successfully.

GitHub Actions PC Companion CI run `35663445415` on the same implementation head:

```text
Test gateway and MCP contracts
→ SUCCESS

Test and compile PC Companion
→ SUCCESS
```

After the fixture-only commit `60fc655ec1f2e2d014d6164c080992452051fc36`, Mobile CI run `35664477712` again completed `Verify PC Gateway and MCP contracts` successfully. No Android production source changed after the validated Android implementation SHA.

Static schema/contract audit also confirmed:

```text
Draft 2020-12 Atlas schema: yes
Draft 2020-12 Secrets schema: yes
persona live|mapping in both: yes
Atlas document minimum fields: yes
layout x/y: yes
Secrets schema generic value property: absent
additionalProperties:false on secret request/presence: yes
needs-secret acknowledgement constant: yes
mapping.start in readonly MCP surface: no
```

## Architecture invariants checked

- Phone remains source of truth for task state, Atlas, and Vault metadata.
- PC gateway forwards/validates only; no PC Atlas/Vault store was added.
- Secrets are rejected rather than sanitized-and-forwarded.
- Android/PC response guards prevent a buggy source response from introducing secret-bearing context.
- `secrets.slots` is the one intentional exception for secret *slot names*, with strict boolean presence values.
- `needs-secret` is nonterminal and not a fifth policy `GateClass`.
- Mapping/live persona identities remain distinct.
- `mapping.start` was not added to Run 1 or readonly MCP.
- No generic phone mutation was introduced by the V5 contract.
- Atlas remains descriptive route knowledge; no saved-hop executor was added.

## Contract

Frozen Run-1 names/ops were implemented as specified. `CONTRACT.md` did not need a vocabulary change.

Version metadata remains at the 4.8 seed values intentionally. The V5 protocol plan requires mobile/gateway/MCP/Glass lockstep **when alpha.1 is cut**; Run 1 is a foundation PR and does not publish or cut alpha.1. The release/version bump belongs to the release/integration cut, not this Agent-001 feature branch.

## Not done / intentionally delegated

- Vault storage / Android Keystore: Agent 002.
- Secret leasing, revocation, or fill: Agent 002.
- Secrets Card UI: Agent 002.
- Durable AtlasStore / PlaceCatalog / Graph-v2 promotion: Agent 003.
- Follow Me → Atlas writes: Agent 003.
- Atlas/Secrets Settings mounting: owning later integration pass.
- Autonomous Mapper / `mapping.start`: not Run 1.
- Glass UI/client implementation: Glass team.
- Physical Pixel/device acceptance: **UNVERIFIED**. No physical-device run was performed by Agent 001.

## Suggested next handoff

1. Merge/review PR #144 into `v5/integration`.
2. Agent 002 rebase onto that integration head and wire its phone Vault metadata source through `installSecrets(...)`.
3. Agent 003 rebase after Agent 001 (and Agent 002 if already merged) and wire the durable phone Atlas source through `installAtlas(...)`.
4. Keep `needs-secret`, place identity, persona, and the four gateway op names unchanged unless `orchestrators/CONTRACT.md` changes in the same PR.
