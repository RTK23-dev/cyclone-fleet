# RETURN — Run 1 Agent 003 — durable Atlas + Follow Me

**Source PR:** #146  
**Source branch:** `v5/mobile/atlas-follow-me`  
**Source corrected head included in combined candidate:** `c50aa7f895ff0b95faec5c935e0e5fda300e8b21`  
**Combined candidate:** `v5/mobile/alpha2-preview1-combined`

## Delivered

- durable phone-local AtlasStore with restart persistence;
- Graph-v2 Atlas model and conservative legacy App Graph projection;
- live/mapping persona isolation;
- Follow Me promotion into the same durable Atlas;
- structural screen/control privacy classifier;
- content/person labels are reduced to safe structural categories and raw content is not serialized;
- truthful `partial` wire status;
- real production `AtlasRuntime.provider` install through `GatewayV5ContractSources.installAtlas(...)`;
- `atlas.get` / `atlas.places` now have a real phone-owned provider;
- hint-only Atlas retrieval; no AppGraphExecutor macro execution;
- mountable App Maps Settings section.

## Validation history

The source PR's last standalone Mobile CI failed one privacy test because the test expected the generic fallback while the classifier safely reduced the content-derived screen to the structural category `Messages`. The assertion was corrected without weakening the raw-content exclusion. Final acceptance is the combined alpha.2.dev1 CI, not the stale source run.

## Deliberate limits

- no autonomous mapper in this return;
- no Chrome origin resolver;
- no People memory;
- no Ask compiler;
- no physical-device claim.

**Physical Pixel status:** UNVERIFIED.
