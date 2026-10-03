# RETURN-003 — atlas-client

**Agent:** 003 — atlas/secrets client  
**Handoff:** `agents/run-001/RUN-001-AGENT-003-atlas-client.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/atlas-client`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/154  
**Starting integration SHA:** `c8c9e62946fb0028528f30358bb9f246b057baf6`  
**Implementation SHA:** `25bcfe891c736b1d2b2facb4c1499d6e550f27a2`  
**Head SHA:** `34c8150ceb73570c609445564aa782f609db07bb`  
**Commits:**
- `25bcfe89` feat(glass): add typed atlas/secrets client
- `34c8150c` docs(v5 glass): return Run 1 Agent 003 atlas-client

Worktree originally checked out `f47afade` tracking `origin/v5/integration`; fast-forwarded one docs commit (`c8c9e629`) before implementation.

## Done

Typed Glass client that consumes Mobile-owned V5 schemas (PR #144) without forking names.

- `atlasTypes.ts` — Persona, Place/PlaceId (`package:…` / `chrome:https://…`), AtlasDocument, PlaceCatalog, SecretsSlots boolean presence, SecretRequest / SecretRequestAck.
- `secretGuards.ts` — fail closed on password/otp/cookie/bearer-like keys or values. Does not strip-and-forward. Does not write `localStorage`. Slot names such as `password` are allowed only as **boolean** keys on `secrets.slots`.
- `atlasClient.ts` — `createAtlasClient({ baseUrl, getBearer, getSessionId, getDeviceId })` with CONTRACT ops:
  - `places()` → `atlas.places`
  - `get(placeId, persona)` → `atlas.get`
  - `secretsSlots(placeId, persona)` → `secrets.slots`
  - `secretsRequest(placeId, persona, slot, reason)` → `secrets.request`
- Gateway HTTP paths taken from `apps/device-gateway/cyclone_device_gateway/api/v5_contract_api.py` (not invented):
  - `GET /v1/devices/{device_id}/atlas/places`
  - `GET /v1/devices/{device_id}/atlas?placeId=&persona=`
  - `GET /v1/devices/{device_id}/secrets/slots?placeId=&persona=`
  - `POST /v1/devices/{device_id}/secrets/request`
- Every scoped call requires `session_id`. Blank → `SESSION_REQUIRED`. Never rewritten to display 0 / `default-foreground`. Sent as `session_id` query + `X-Cyclone-Session-Id` header. **Not** placed on the `secrets.request` JSON body (gateway rejects unexpected fields on that object).
- `secrets.request` schema ack `{ state: "needs-secret", request }` maps to Glass `{ status: "waiting" }`.
- Empty-but-valid atlas documents succeed. 4xx mapped honestly (`HUMAN_HAS_CONTROL` stays named).
- `supportsGlassAtlas("4.8.0") === false`; `supportsGlassAtlas("5.0.0-alpha.1") === true`. Incompatible phones fail with `PHONE_VERSION_UNSUPPORTED` (“Update the phone…”) rather than a fake atlas.
- `DEMO_ATLAS_DISABLED_WHEN_PHONE_V5 = true`. `useDemoGraph` default false; demo labels are explicit `(demo)` / `DemoGraph`.
- No `mapping.start`.

`getDeviceId` is an extra factory getter because the real gateway routes are device-scoped. It is not a new protocol name.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/154 | Agent 003 PR targeting `v5/integration` |
| `c8c9e62946fb0028528f30358bb9f246b057baf6` | Starting `v5/integration` SHA |
| `25bcfe891c736b1d2b2facb4c1499d6e550f27a2` | Implementation head before this return |
| `34c8150ceb73570c609445564aa782f609db07bb` | Return MD on the same branch |

## Paths touched

```text
apps/pc-companion/src/services/atlasClient.ts
apps/pc-companion/src/services/atlasTypes.ts
apps/pc-companion/src/services/secretGuards.ts
apps/pc-companion/tests/atlas-client.test.mjs
apps/pc-companion/tests/fixtures/atlas-empty-valid.json
apps/pc-companion/tsconfig.test.json
Cyclone V5 plan/orchestrators/glass/returns/RETURN-003-atlas-client.md
```

`tsconfig.test.json` is a compile seam so `npm test` emits the new services into `.test-dist`. No `app.ts`, Maps canvas, Ask/Vault pages, Mobile, protocol schemas, or `version.toml`.

## Tests

```text
cd apps/pc-companion && npm test
→ 131 pass, 0 fail

Focused atlas-client coverage:
- fixture atlas document satisfies required keys
- get() on empty valid document succeeds
- session_id omitted → SESSION_REQUIRED
- named session_id is not rewritten to default-foreground
- payload with password value rejected and not logged
- secrets.slots { password: true } allowed
- secrets.slots string value rejected
- supportsGlassAtlas version gate
- Mobile < 5 fails closed (no demo substitution)
- demo graph explicit / default off
- chrome-origin placeId accepted
- 4xx / HUMAN_HAS_CONTROL mapped honestly
- secrets.request maps needs-secret → waiting; session_id not in JSON body
- no mapping.start
```

Also ran `npx tsc -p tsconfig.json` (noEmit) → clean.

## Contract

Did this change names/ops? **no**. Client consumes existing Mobile schema/op names.

Noted mismatches (not forked; documented for orch):

1. CONTRACT table says Place = `package` **or** `chrome|origin`. Schema/wire identity is `package:com.example.app` and `chrome:https://example.com`. Client uses the schema forms.
2. `secrets.request` schema ack is `{ state: "needs-secret", request }`. The Glass method returns `{ status: "waiting" | "filled" | "skipped" | "cancelled" }` with `needs-secret` → `waiting`. filled/skipped/cancelled are later `secrets.lease.ack` states from plan/05, not a second request op.
3. Current `v5_contract_api.py` does not yet read `session_id` on atlas/secrets HTTP routes. Glass still **sends** it (fail closed if blank). Gateway should start requiring it on those observe paths.
4. `ask.start` / `ask.status` are in CONTRACT but not in `V5_OPS` / v5_contract_api. Not invented here.

## Not done / blocked

- Maps `AtlasViewModel` adapter: Agent 002 canvas is not on this branch; orch said glue after 001+002 merge. Do not wire Maps/Ask pages from this PR.
- Vault page / Ask `needs-secret` UI wiring: Agent 001 surface not merged here.
- Live `atlas.get` house from Follow Me still depends on Mobile 003 durable atlas (`GatewayV5ContractSources.installAtlas`). Until then empty-but-valid documents are success; demo graph is explicit opt-in only.
- `ask.start` / `ask.status` client methods: no gateway route yet. Stopped rather than inventing `/v1/ask`.
- `getDeviceId` required by existing device-scoped gateway paths. Not in the original factory sketch `{ baseUrl, getBearer, getSessionId }` but required to hit real routes.

## Secret values

No secret values were added to fixtures, logs, or source. The string `hunter2` appears only inside `atlas-client.test.mjs` as a rejected payload assertion and is asserted **not** to be logged.

## Suggested next handoff

- After 001+002 merge: wire Maps to `atlas.places` / `atlas.get` → `AtlasViewModel`, Vault to `secretsSlots`, Ask wait-state to `needs-secret` / `secretsRequest`.
- Gateway: accept/require `session_id` on the four V5 atlas/secrets HTTP routes.
- Mobile 003: durable Follow Me graph so `atlas.get` returns a real house.
