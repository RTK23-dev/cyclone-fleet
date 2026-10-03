# HANDOFF-001 — protocol-need-secret

**From:** Mobile 5.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.1  
**Branch:** `v5/mobile/protocol-need-secret` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):** `protocol/`; Android bridge registration/dispatch/adapters under `apps/mobile/**/gateway/**`; GATE / run-state types under `apps/mobile/**` that already own task phase (find the existing enum; extend — do not create a parallel state machine); Ask presentation snapshot so Glass can show `needs-secret`; `apps/device-gateway/**` only as the PC forwarding/validation layer (never atlas authority)  
**Do not touch:** `apps/pc-companion/**`, vault implementation (002), AtlasStore (003), mapper, destination-authority regex titles

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`01-headset-and-laws.md`](../../../01-headset-and-laws.md)
3. [`08-protocol-gateway.md`](../../../08-protocol-gateway.md)
4. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
5. `docs/ARCHITECTURE.md` — phone mutates, PC is glass
6. `AGENTS.md` invariants

4.8 fails a Facebook password wall as **Couldn’t finish**. You add the **type** and the **pipe** so a wall is `needs-secret`, and so Glass/Mobile share names.

## Your individual task

1. Add `protocol/cyclone-atlas-v1.schema.json` and `protocol/cyclone-secrets-v1.schema.json` matching CONTRACT names (Place, persona, ops listed there). Atlas schema must be enough for a Follow Me graph later (screens, edges, purpose, slots, danger, layout). Values for secrets are **not** in the schema.
2. Add the task/run interruption state **`needs-secret`** (or equivalent existing representation — extend the current task state/projection, do not create a parallel state machine). Presentation snapshot Glass consumes must carry it. **Do not overload the existing policy `GateClass` PAY/SEND/DELETE/GRANT enum merely because this boundary is called GATE; those are approval-risk classes.** If a dedicated gate/interruption type exists, extend that instead.
3. Android gateway first: register and dispatch `secrets.slots` (booleans), `secrets.request` (slot/reason only), `atlas.places`, and `atlas.get` on the phone. Until 003 fills the atlas, return empty-but-schema-valid phone-owned documents. Then make `apps/device-gateway` forward/validate those operations. **Do not create a PC-owned atlas or PC truth fixture.** Reject payloads that carry secret-looking keys or values before forwarding/logging.
4. MCP: do not expose secret values; readonly remote mode must not gain `mapping.start`.
5. Tests: schema validate fixtures; `needs-secret` is not `failed`; Android bridge advertises/dispatches the new read-only ops; PC gateway forwards them without becoming source of truth; gateway rejects a password field/value.
6. If CONTRACT names had to move, edit CONTRACT.md **in this PR**.

## Required

1. Only this task.
2. PR + CI you can run (`device-gateway` pytest, mobile unit tests for the state you touched).
3. Write [`../returns/RETURN-001-protocol-need-secret.md`](../returns/RETURN-001-protocol-need-secret.md) with PR URL and SHAs.

## Out of scope

Secrets card UI, Keystore, AtlasStore fill from Follow Me, Maps canvas, Ask composer on PC.

## Success

Glass 001 can type against `needs-secret` without guessing. Glass 003 can generate types from the schemas. 002 can hang a card on the state you added.
