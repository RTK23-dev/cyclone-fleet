# RUN 1 — AGENT 003 — DURABLE ATLAS + FOLLOW ME

**Read first:** `RUN-1-SHARED.md`  
**Branch:** `v5/mobile/atlas-follow-me`  
**PR target:** `v5/integration`

You are Agent 003. You own the **durable phone Atlas foundation and Follow Me promotion path**. You do not own autonomous Mapper, Glass Maps, Vault, or the central gateway operation registry.

You may develop in parallel, but before final validation **rebase onto Agent 001's merged `v5/integration`** so your serialized Atlas matches the actual schema.

## Mission

Promote existing learned phone knowledge into one durable Atlas without throwing away 4.8's working App Graph.

After a Follow Me session, Cyclone must have a phone-owned house that survives restart and can later be returned by `atlas.get`.

## You own

- durable `AtlasStore`
- `PlaceCatalog`
- Atlas model additions in `brain/graphv2/**`
- safe projection/import from existing `AppKnowledgeStore`
- Follow Me → Atlas write path
- Atlas retrieval/query primitives
- Atlas-specific Settings composables
- Atlas serialization/provider that Agent 001's gateway adapter can call after rebase
- tests for durability/persona/privacy

## Do not own

Do not implement:

- autonomous Mapper crawl
- protocol schema filenames/names
- central Android GatewayProtocol operation registration
- Python gateway
- Vault
- Secrets Card
- Glass Maps
- Ask compiler
- People memory
- macro replay as V5 execution

## Implementation requirements

### A. Durable store

Graph v2's current `InMemoryTemporalGraphStore` is not sufficient by itself.

Create a durable phone-local Atlas store that survives:

- object recreation;
- app process death/restart equivalent;
- reopening the database/store.

Reuse Graph v2 contracts rather than creating a second unrelated graph model.

### B. Legacy knowledge reuse

The current App Graph is SQLite-backed and valuable.

Use `LegacyAppGraphV2Adapter` or extend the projection path so existing learned:

- apps
- screens
- semantic selectors
- transitions
- confidence/evidence

can appear in Atlas conservatively.

Do not claim old legacy VERIFIED records are physical V5 verification if evidence does not prove that.

Do not delete or destructively migrate the legacy database in Run 1.

### C. Atlas model

Add the V5 fields required by the shared Atlas schema:

- place identity
- persona
- purpose
- capability/fact-slot metadata
- danger/risk
- confidence
- observed/verified time
- stable layout x/y
- screens and edges

Fact slots describe **how/where to read a fact**, not the fact value captured during mapping.

Examples of acceptable slot meaning:

```text
signed_in_email
latest_order_status
profile_name
```

Do not store the observed email/order/profile value as the slot definition.

### D. Persona split

Every Atlas graph/write/read must be scoped by:

```text
live | mapping
```

A mapping/dummy crawl must never upgrade live knowledge.

Tests must make this impossible to accidentally merge.

### E. Follow Me promotion

Keep Follow Me's existing privacy behavior:

- ignore typed text contents;
- skip sensitive fields;
- learn semantic navigation.

Make its stable page/action/transition observations update the same durable Atlas.

Do not create a separate “Follow Me graph” and later copy it manually.

### F. Atlas is not a macro executor

The existing `AppGraphExecutor` can rapidly replay stored clicks. Do not wire V5 Atlas to that behavior.

Atlas retrieval can return:

- likely destination screen
- candidate path
- capability match
- fact-slot location
- confidence/danger

The actual runtime still has to observe and verify each mutation through the canonical executor.

### G. Safe serialization

After rebasing onto Agent 001, satisfy its actual Atlas schema.

Never serialize:

- raw typed values
- legacy `dynamic_json` wholesale
- passwords/OTP/tokens
- unredacted screenshot paths or raw frames

If you expose a thumbnail, it must be redacted on-phone before export. If redaction is not implemented in Run 1, omit thumbnails rather than leaking them.

Expose a narrow Atlas read/provider API that the Agent-001 Android gateway adapter can use.

### H. Settings

Create an App Maps Settings composable showing at minimum:

- place label
- package/origin
- persona
- unmapped/partial/mapped/stale
- screen/edge count
- last observed/verified
- “Open on Glass” or a small local graph affordance

Autonomous Start Mapping must be disabled/coming-soon in Run 1.

Avoid root Settings navigation collisions during parallel development; document the mount hook if necessary.

### I. Tests

Required tests:

1. Follow Me observation creates/updates an Atlas screen;
2. demonstrated transition becomes an Atlas edge;
3. store close/reopen retains place/screens/edges/layout;
4. existing legacy App Graph can be projected/imported safely;
5. mapping persona never mutates live persona;
6. fact-slot definition contains no captured user fact value;
7. no password/raw typed value/`dynamic_json` dump appears in serialized Atlas;
8. `atlas.get` provider output validates against Agent 001's schema after rebase;
9. unmapped place produces a valid empty graph;
10. Atlas retrieval returns hints only and does not execute `PhoneToolExecutor` actions.

## Return file

Write:

`Cyclone V5 plan/orchestrators/mobile/returns/RETURN-RUN1-003-atlas-follow-me.md`

Include the exact durable-store choice, migration/projection strategy, and provider interface for gateway wiring.

## Done means

Teach/Follow Me creates a persistent live-persona house, old 4.8 graph knowledge is reused safely, dummy knowledge stays separate, and the result is ready for `atlas.get`/Glass without becoming a blind macro system.
