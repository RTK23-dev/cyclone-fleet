# RETURN-002 — maps-canvas

**Agent:** Glass implementation Agent 002  
**Handoff:** `agents/run-001/RUN-001-AGENT-002-maps-canvas.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/maps-canvas`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/155  
**Starting integration SHA:** `c8c9e62946fb0028528f30358bb9f246b057baf6`  
**Implementation SHA:** `b3f7dff68f21d1e332f23507c622b1ee1ae99888`  
**Head SHA:** this return commit on the same branch  
**Commits:**

- `b3f7dff6` feat(glass): add Minitap-class Maps canvas as a standalone module
- this return file

## Done

- Standalone Maps operator board: left places rail, center dotted canvas, right inspector.
- Exported `createMapsPage(): { element, destroy }` for Agent 001 / orch to mount. Did **not** edit `app.ts`.
- One renderer input `AtlasViewModel`. Canvas does not bind to mock-only fields. Optional `createMapsPage({ source })` so Agent 003 can swap in `atlas.get`.
- Mock atlas (protocol-valid, `additionalProperties: false`):
  - Gmail `package:com.google.android.gm` — Account, Inbox, Message, Compose, Settings, Search (dark), Add account, Storage offer (danger). Doors: open avatar, open message, compose, settings, search.
  - Chrome Facebook `chrome:https://m.facebook.com` — Login / Feed / DMs; live `mapStatus: blocked`.
  - Unmapped YouTube with an intentional **Start mapping** card (disabled).
  - Live vs Dummy (mapping persona) are two documents, never mixed on one board.
- Canvas: pan (pointer drag), wheel zoom, +/−, Fit all (on open and on place change). Region clusters. Bezier edges with English `actionHint` hover. Confidence as opacity. Danger / blocked / stale / dark color. Card click → inspector.
- Inspector: purpose, redacted-frame placeholder, masked fact slots (`signed-in-email` is how-to-read, never an address), outgoing doors, Pin / Remap / Never disabled with `title="phone alpha.3"`.
- Top bar: Live/Dummy, Screens/Capabilities, coverage `N screens · N doors · N dark`, Start mapping disabled (`phone alpha.3`), filters stale / blocked / danger.
- CSS self-loads from the page module; `main.ts` was not touched.
- Focused tests plus existing companion suite green.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/155 | Maps canvas PR into `v5/integration` |
| `b3f7dff6` | Implementation |

## Paths touched

```text
apps/pc-companion/src/maps/atlasViewModel.ts
apps/pc-companion/src/maps/mockAtlas.ts
apps/pc-companion/src/pages/mapsPage.ts
apps/pc-companion/src/ui/appMapCanvas.ts
apps/pc-companion/src/maps.css
apps/pc-companion/tests/maps-canvas.test.mjs
apps/pc-companion/tests/helpers/mini-dom.mjs
apps/pc-companion/tsconfig.test.json
Cyclone V5 plan/orchestrators/glass/returns/RETURN-002-maps-canvas.md
```

`tsconfig.test.json` is a test compile seam so Maps modules emit into `.test-dist`. No product behavior change.

## Tests

```text
cd apps/pc-companion && npm test
→ tsc -p tsconfig.test.json && node --test tests/*.test.mjs
→ 126 pass / 0 fail / 0 skipped
```

Maps coverage: schema/required fields, Gmail node count + rooms, no password/email plaintext, coverage math, independent AtlasViewModel, inspector select, empty unmapped, fit/zoom finite, DOM smoke (rail / empty card / Account inspector).

`./node_modules/.bin/tsc -p tsconfig.json` also clean.

Screenshot: environment cannot render the Tauri UI. DOM smoke asserts the operator path instead.

## Contract

Did this change names/ops? **no**. Consumed `protocol/cyclone-atlas-v1.schema.json` as-is.

Protocol `screen` has no `region` field (`additionalProperties: false`). Region is derived in `toViewModel` from `purpose` (Account / Inbox / Compose / Settings / Chat / Feed / Danger). Compatible with `actionHint`, `layout`, `factSlots`, `risk.danger`.

## Not done / blocked

- Page is not mounted in the shell (`app.ts` is Agent 001). Orch should `createMapsPage()` after 001 merges.
- No `atlasClient` / live `atlas.get` (Agent 003). Mock is the data source until then.
- `mapping.start` not implemented; Start mapping is disabled with honest alpha.3 copy.
- Pin / Remap / Never are visible and disabled (`phone alpha.3`).
- Layout drags are not persisted to the phone atlas (phone is truth; persist is a later atlas write).
- No JPEG redacted thumbs yet — inspector shows a redacted-frame placeholder.

## Suggested next handoff

- HANDOFF-003 / Agent 003: replace `mockMapsDataSource` with phone-owned `atlas.get(placeId, persona)` and `atlas.places`. Keep `AtlasViewModel` as the renderer contract.
- Orch: after 001, mount Maps nav to `createMapsPage()`. Optionally `import "./maps.css"` from `main.ts`; the page already self-links the stylesheet.
- alpha.3: enable Start mapping → `mapping.start` and live cursor from `mapping.status`.

## Secret boundary

No secret values were added to fixtures, logs, inspector payloads, or tests. Fact slots render as `Masked`. Facebook live is blocked without a credential payload. Scanner test rejects password/passcode/otp/cookie/credential-like strings and email addresses in mock documents.
