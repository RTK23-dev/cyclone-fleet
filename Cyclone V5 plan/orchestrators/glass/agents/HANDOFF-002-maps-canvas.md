# HANDOFF-002 — maps-canvas

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.2 look-and-feel (**Glass 1.0 exit criterion**)  
**Branch:** `v5/glass/maps-canvas` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):** `apps/pc-companion/src/pages/mapsPage.ts`, `ui/appMapCanvas.ts`, `maps.css`, a `maps/mockAtlas.ts` fixture (Gmail-shaped house). 001 may have left a placeholder route — replace it, do not fight nav.  
**Do not touch:** `app.ts` beyond mounting if 001 is not merged (prefer rebase). `atlasClient` (003). `apps/mobile/**`. Live video stack.

## Total picture (read first)

1. [`04-app-maps-canvas.md`](../../../04-app-maps-canvas.md) — **this is the spec. Meet it.**
2. [`03-glass-v1.md`](../../../03-glass-v1.md) G2
3. [`06-atlas-and-mapper.md`](../../../06-atlas-and-mapper.md) — what a node is
4. MiniTest feel: dotted board, cards, edges, zoom/fit, inspector, filters, coverage. Nodes are **screens and doors**, not QA stories. No “8/8 passed.”

## Your individual task

Build the operator table:

1. Left rail: places (mock: Gmail, Chrome · facebook.com, an unmapped app).
2. Center: dotted infinite board, pan, zoom, fit. Region clusters. Cards + edges with English hover. Fit-all on open.
3. Inspector: purpose, redacted-frame placeholder, fact slots (masked), doors, pin/remap/never **disabled but visible** if data isn’t live.
4. Top: persona Live/Dummy, coverage counts, Start mapping **disabled** with honest “mapper is mobile alpha.3”, filters.
5. Empty place: one Start card, not a blank grid.
6. Mock graph must be rich enough that an operator can **explain Gmail’s rooms from the canvas alone** (Account, Inbox, identity slot). That is the alpha.2 exit test, mock allowed until 003.
7. Export a single `AtlasViewModel` so 003 can swap mock → `atlas.get` without rewriting the renderer.
8. Tests: zoom/fit doesn’t NaN; fixture renders N nodes; inspector opens; no password string in fixture.

## Required

PR + `returns/RETURN-002-maps-canvas.md` with GitHub evidence. Screenshot in the PR description if you can (not required if CI is headless — then say so).

## Out of scope

Live mapping cursor, `mapping.start`, encrypted secrets, fleet-wide atlas.

## Success

Looks like Mini’s demo. Means Cyclone screens. Mock is swappable.
