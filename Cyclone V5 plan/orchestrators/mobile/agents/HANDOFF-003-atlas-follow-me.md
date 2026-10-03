# HANDOFF-003 — atlas-follow-me

**From:** Mobile 5.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.2 start  
**Branch:** `v5/mobile/atlas-follow-me` off `v5/integration` (needs 001 schemas merged)  
**PR into:** `v5/integration`  
**Code paths (only these):** `apps/mobile/**/applearner/**`, `apps/mobile/**/brain/graphv2/**`, new phone-local `AtlasStore` / `PlaceCatalog`, Settings App Maps **mini** row, Follow Me write path into atlas, Android `apps/mobile/**/gateway/**` atlas adapter that fills 001's registered `atlas.places` / `atlas.get` ops  
**Do not touch:** `apps/pc-companion/**` (Glass draws the big board), mapper crawl (later), Ask compiler, vault (002)

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`06-atlas-and-mapper.md`](../../../06-atlas-and-mapper.md)
3. [`04-app-maps-canvas.md`](../../../04-app-maps-canvas.md) — you supply data; you do **not** build the Minitap board
4. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
5. Code: `AppGraphEngine.kt`, `GraphV2Contracts.kt`, Follow Me learner

Follow Me already learns screens. Promote that to **the** atlas. Dummy ≠ live (persona on the graph). Fact slots are **how to read**, not mapping-day emails.

## Your individual task

1. Durable phone-local `AtlasStore` + `PlaceCatalog` (packages + chrome origins stub: origin field exists even if unused). Persona split. Maps must survive process death/restart; the current Graph v2 `InMemoryTemporalGraphStore` is not sufficient as the V5 store by itself.
2. Promote Graph v2: purpose, fact slots, danger, confidence, lastVerified, layout coords (so Glass can persist operator drags later). Reuse the existing Graph v2 and `LegacyAppGraphV2Adapter`; do not fork a second graph. Import/project existing persistent `AppKnowledgeStore` knowledge rather than abandoning it. The current `AppGraphExecutor` rapid-replays saved hops — **do not make that the V5 atlas execution path**. Atlas retrieval is a sketch/hint only.
3. Follow Me writes the same store (teach path). Keep its existing typed-text/sensitive-field exclusion intact.
4. Settings → App Maps: catalog list, status, **small** graph or “open on Glass”, Start button **disabled / labeled coming** if mapper is not this PR. Do not fake a crawl.
5. Fill the Android gateway `atlas.places` and `atlas.get` adapters from the phone store (empty valid document if unmapped). 001 owns op registration/PC forwarding; do not add a second Python-side atlas store. Serialize only atlas-safe fields: no raw typed values, no legacy `dynamic_json` dump, and no unredacted screenshot path/frame. If a thumbnail is exposed, it must be redacted on-phone before it crosses the gateway.
6. Retriever: capability+slot query sketch is allowed; **do not** execute macros from it.
7. Tests: Follow Me → atlas node; atlas survives store reopen/process-style reinitialization; dummy persona not mixed; `atlas.get` matches schema; no raw password/typed value/dynamic payload on a node; any exported thumbnail is demonstrably redacted.

## Required

PR + `returns/RETURN-003-atlas-follow-me.md` with GitHub evidence.

## Out of scope

Autonomous mapper, Glass canvas, Chrome facebook.com crawl, People memory.

## Success

After a Follow Me of Gmail, `atlas.get` returns a house Glass 002 can draw. Phone Settings shows the place as Mapped (teach), not a blank.
