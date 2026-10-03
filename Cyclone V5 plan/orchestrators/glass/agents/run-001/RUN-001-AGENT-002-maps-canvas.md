# RUN 001 — AGENT 002 — MAPS CANVAS

**You are:** Glass implementation Agent 002  
**Shared brief:** `RUN-001-SHARED.md`  
**Branch:** `v5/glass/maps-canvas`  
**PR target:** `v5/integration`  
**Return:** `returns/RETURN-002-maps-canvas.md`

Read the shared brief first. Your task is visual/product architecture, not protocol integration.

---

## Objective

Build the **Minitap-class Maps operator board** using a rich mock atlas and one clean renderer-facing `AtlasViewModel`.

The board must feel like a spatial representation of an app's rooms and doors, not a debug list.

Agent 003 will later replace the mock data source with real `atlas.get` data. Make that possible without rewriting your renderer.

---

## Owned paths

You may create/edit:

- `apps/pc-companion/src/pages/mapsPage.ts`
- `apps/pc-companion/src/ui/appMapCanvas.ts`
- `apps/pc-companion/src/maps.css`
- `apps/pc-companion/src/maps/mockAtlas.ts`
- focused Maps tests/fixtures contained in the Maps lane

If Agent 001 has already merged, consume its Maps mount/route.

If Agent 001 has not merged, keep your module standalone and do **not** take over `app.ts` navigation. The orchestrator will serialize the final mount.

---

## Do not touch

Do not edit:

- `app.ts` navigation except a truly unavoidable one-line mount after 001 is merged and rebased; prefer no edit
- Ask/Vault implementation
- `atlasClient.ts`
- protocol/client transport
- `apps/mobile/**`
- live video stack
- mapping crawler
- `mapping.start`

---

## Core design requirement

Export one explicit renderer input contract named:

`AtlasViewModel`

All board rendering should consume that model.

Agent 003 must be able to transform real `atlas.get` output into `AtlasViewModel` without changing canvas internals.

Do not bind visual components directly to mock-only fields.

---

## Deliverables

### A. Three-region Maps layout

Implement:

1. **Places rail** on the left
2. **Infinite/spatial board** in the center
3. **Inspector** on the right

The page should read as an operator table.

### B. Places rail

Use mock entries including:

- Gmail
- Chrome · facebook.com
- one unmapped place

Selecting a place changes the displayed graph.

An unmapped place should have an intentional empty state with a clear Start/Map card, not a dead blank canvas.

### C. Board

Implement:

- dotted spatial background;
- click-and-drag pan;
- zoom controls / wheel zoom;
- Fit All;
- stable transforms that never become NaN/Infinity;
- card positioning;
- region clusters or clear region labels;
- visible edges between screens;
- directional/meaningful edge labels or hover text.

Fit All should produce a sensible initial view on open.

### D. Gmail-shaped house

The Gmail fixture must be rich enough that someone can infer the app structure from the board.

Include meaningful rooms such as, at minimum:

- Account / identity
- Inbox
- Message
- Compose
- Settings

Use meaningful doors such as:

- open avatar
- open message
- compose
- settings navigation

Include at least one identity/fact slot and represent it masked/redacted.

Do not put an actual password or secret-like sample value in the fixture.

### E. Card semantics

Cards should communicate more than a title.

Use the spec's concepts where appropriate:

- purpose;
- region;
- mapped / stale / blocked / danger-like state;
- coverage status;
- last proof / redacted-frame placeholder;
- available fact slots.

Avoid QA-test language such as “8/8 passed.”

These are product screens and doors, not test cases.

### F. Inspector

Clicking a screen/card opens details including:

- purpose;
- redacted frame placeholder;
- fact slots, masked;
- connected doors;
- status/proof details;
- actions such as pin/remap/never may be visible but disabled where the backing behavior is not live.

The inspector should make the selected room understandable without exposing raw secrets.

### G. Operator controls

Provide top-level board controls for:

- persona selector as defined by the spec/model;
- coverage counts;
- filters;
- fit/zoom;
- Start Mapping shown as unavailable/disabled for this run with honest copy indicating the mobile mapper belongs to a later alpha.

Do not implement live `mapping.start`.

### H. Filters

At minimum support a useful subset such as:

- region;
- mapped/stale/blocked status;
- danger;
- Chrome vs native.

Filtering must not corrupt the graph transform.

---

## Tests required

Add focused tests for:

1. mock Gmail graph renders the expected number of nodes;
2. Fit All / zoom calculations never produce NaN or Infinity;
3. selecting a card opens inspector state;
4. empty/unmapped place produces intentional empty UI;
5. fixture does not contain password/secret plaintext;
6. `AtlasViewModel` can be supplied independently of the mock fixture.

Run normal companion test/typecheck commands.

---

## PR evidence

If practical, include a screenshot of the Maps board in the PR description.

If the environment cannot produce a screenshot, say that explicitly; do not block the PR only for that.

---

## Acceptance check

Your task is complete when a reviewer can look at the mock Gmail Maps board and answer:

- What are the major rooms?
- How do I move between them?
- Which room contains identity/account context?
- Which parts are mapped vs incomplete?
- What does clicking a room reveal?

If the page is primarily a package list or table, keep working.
