# RUN 001 — AGENT 003 — ATLAS / SECRETS CLIENT

**You are:** Glass implementation Agent 003  
**Shared brief:** `RUN-001-SHARED.md`  
**Branch:** `v5/glass/atlas-client`  
**PR target:** `v5/integration`  
**Return:** `returns/RETURN-003-atlas-client.md`

Read the shared brief first.

This task is **dependency-gated**. Do not invent missing protocol/schema details to start early.

---

## Objective

Wire Glass's new Ask/Maps/Vault surfaces to the real Mobile/Gateway V5 contract while keeping the PC a typed, fail-closed client.

You bridge:

- Mobile atlas → Agent 002's `AtlasViewModel`
- Mobile secret-slot booleans → Vault
- Mobile Ask presentation / `needs-secret` → Agent 001's UI

You do not build the atlas, vault, or Ask engine yourself.

---

## Start gate

Before implementing transport, confirm on the current `v5/integration`:

1. Mobile-owned V5 schemas exist:
   - `protocol/cyclone-atlas-v1.schema.json`
   - `protocol/cyclone-secrets-v1.schema.json`
2. contract vocabulary still matches `orchestrators/CONTRACT.md`;
3. Agent 001's Ask/Vault surface exists or its PR is available to rebase onto;
4. Agent 002's `AtlasViewModel` exists or its PR is available to rebase onto.

If the schemas are missing or materially disagree with CONTRACT, stop that portion and report the blocker. Do not create competing names.

---

## Owned paths

Primary ownership:

- `apps/pc-companion/src/services/atlasClient.ts`
- client-side types/adapters directly supporting that service
- focused client/contract tests
- minimum wiring seams from:
  - Maps page → client/view-model adapter
  - Vault page → `secrets.slots`
  - Ask page → real presentation/`needs-secret`

You may touch 001/002 files only at narrow integration seams after rebasing their completed work.

Do not redesign their UI.

---

## Do not touch

Do not edit:

- canvas renderer internals or visual layout from Agent 002
- shell/nav architecture from Agent 001
- `apps/mobile/**`
- Mobile gateway operation handlers
- protocol schema names unless coordinated through CONTRACT/Mobile
- live video controller
- mapping crawler
- encrypted PC secret-fill behavior

---

## Deliverables

### A. Typed V5 client

Build a typed client from the Mobile-owned schemas/contract.

Consume the canonical operations, including where present:

- `atlas.places`
- `atlas.get(placeId, persona)`
- `secrets.slots`
- `secrets.request`
- `ask.start`
- `ask.status`

Do not hard-code alternate endpoint/op names because an expected one is inconvenient.

### B. Atlas adapter

Translate real atlas graph data into Agent 002's single `AtlasViewModel`.

The renderer should not know whether the source was:

- real Mobile atlas;
- optional demo fixture.

Keep the demo graph behind an explicit **demo** control/path.

For a compatible Mobile 5 phone, default behavior should prefer real data and should not silently pretend the mock is live.

### C. Places

Populate Maps place selection from the real source of truth when available.

Support the canonical place model from CONTRACT, including native package and Chrome-origin forms as represented by the schema.

Do not turn places into a fleet-global merged atlas.

### D. Vault slots

Wire Vault to `secrets.slots` booleans only.

Allowed data is slot identity/display metadata and set/missing state.

Never request or render raw values.

`secrets.request` may initiate the phone-side card/wait flow only.

### E. Ask secret state

Map real Ask presentation/status into Agent 001's `needs-secret` UI.

The result must remain a wait state, not a generic failure.

Do not add a PC password field.

### F. Session identity

Scoped operations must carry the correct `session_id`.

Preserve existing named-workspace/display rules.

Explicitly fail closed for:

- absent required session identity;
- session/display mismatch;
- `HUMAN_HAS_CONTROL`;
- incompatible Mobile version/capability.

Do not silently retry against `default-foreground` when a named session fails.

### G. Mobile compatibility

For phones below the V5 capability/version threshold:

- do not call unsupported atlas/Vault ops as if they exist;
- return an explicit compatibility state for UI;
- do not substitute the demo graph without labeling it demo.

### H. Secret leakage defense

Add a defensive client boundary so unexpected secret-bearing payloads are not persisted/logged/rendered.

Do not implement “strip plaintext and continue” as a normal success path if the contract forbids the payload. Fail closed and surface a safe error.

No request/response debug logging may contain secret values.

---

## Tests required

Add focused coverage for:

1. schema/fixture → typed client parsing;
2. real atlas payload → `AtlasViewModel` adapter;
3. `session_id` required on scoped calls;
4. named session is not rewritten to default foreground;
5. Mobile < 5 compatibility behavior;
6. demo graph is explicit rather than silently live;
7. Vault receives booleans only;
8. Ask `needs-secret` maps to the wait UI;
9. secret-looking unexpected payload fails closed and is not logged.

Run normal companion tests/typecheck plus any contract tests available for this client seam.

---

## Dependency handling

If Agent 001 or Agent 002 is still in PR rather than merged:

- rebase/cherry-pick only when the orchestrator has designated the integration order;
- avoid creating competing UI implementations;
- keep your changes adapter-focused.

If Mobile's `atlas.get` is not yet implemented but schema/types are stable, you may finish the client and adapter with contract fixtures while keeping runtime UI honest about unavailable live data.

---

## Acceptance check

Your PR is complete when:

- a real Mobile atlas graph can flow into Maps through `AtlasViewModel`;
- Vault shows real slot presence without values;
- Ask shows real `needs-secret`;
- every scoped call respects `session_id`;
- incompatible phones fail honestly;
- demo data cannot be mistaken for live data;
- no PC secret persistence exists.
