# HANDOFF-010 — maps-operator-board

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** Run 3 / alpha.2 operator table  
**Branch:** `v5/glass/maps-operator-board` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
`apps/pc-companion/src/pages/mapsPage.ts`  
`apps/pc-companion/src/ui/appMapCanvas.ts`  
`apps/pc-companion/src/maps/atlasViewModel.ts`  
`apps/pc-companion/src/maps.css`  
`apps/pc-companion/tests/maps-canvas.test.mjs` (extend) and/or new `tests/maps-operator.test.mjs`  
`Cyclone V5 plan/orchestrators/glass/returns/RETURN-010-maps-operator-board.md`

**Do not touch:** `app.ts`, `askPage.ts`, `vaultPage.ts`, `fleet.ts` CompanionState, `glassRuntime.ts`, `atlasClient.ts` implementation, `livePhoneController`, `apps/mobile/**`, `version.toml`, mapping cursor

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`04-app-maps-canvas.md`](../../../04-app-maps-canvas.md) **G2.2, G2.5, G2.6** (not G2.7)
3. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
4. [`agents/run-003/RUN-003-SHARED.md`](run-003/RUN-003-SHARED.md) — **option names are frozen**
5. `apps/pc-companion/src/pages/mapsPage.ts`, `ui/appMapCanvas.ts`, `maps/atlasViewModel.ts` as on integration
6. Run 2 `RETURN-007`

## Your individual task

Make the Mini-class board an operator table. Honesty rules from 007 stay.

1. **Session plane on the top bar.** Honor existing `sessionId` (currently unused in the UI) plus frozen `sessionPlane?: "foreground" | "session_kernel_vd"`. Copy like `Foreground · session_id default-foreground` or `Session Kernel VD · session_id vd-mail`. Empty/omitted sessionId displays `default-foreground`. Never rewrite a named id.

2. **Take control.** Frozen `onOpenControl?: () => void`. Enabled only when the callback is passed. Same idea as Ask: navigates the operator to Phone live handoff. Missing callback → disabled, `title` explains it is the Phone handoff (not mapping pause). Do **not** call `mapping.pause`.

3. **Edge inspector (G2.5).** Canvas: add `onSelectEdge?: (edgeId: string | null) => void`; click an SVG door. Inspector `kind: "edge"`: English `actionHint` as the headline, from → to room labels, last verified, confidence. **No xpath headline. No password.** Screen inspector stays. Pin / Remap / Never stay **disabled** (`phone alpha.3` / rc).

4. **Dark doors filter (G2.6).** Extend `BoardFilters` with `unmappedDoors?: boolean`. Chip **Dark doors**. When active, keep screens that have at least one outgoing edge with `confidence < DARK_CONFIDENCE`, plus those dark edges. Existing stale/blocked/danger chips still work (AND among themselves as today; dark-doors is an additional chip — if only dark-doors is on, apply that filter; if combined, a screen/edge must match **any** active chip, same as current `applyBoardFilters`).

5. **Capability glyphs on cards (G2.2).** Cards already show landmarks. Add a compact glyph row from `model.capabilities` that apply to the place (place-level is fine) **or** from screen purpose (`login`, `inbox`, `compose`, `payment` → simple text/emoji-free initials, CSS-only). Do not invent Mini QA “passed 8/8”.

6. Hover English on edges already exists — keep it. Start mapping stays disabled.

7. Tests (Node `npm test`):
   - sessionId `vd-mail` + `sessionPlane: "session_kernel_vd"` appears in the Maps DOM; `default-foreground` is **not** substituted
   - omitted sessionId shows `default-foreground`
   - `onOpenControl` click fires; without it Take control is disabled
   - clicking a mock Gmail edge (`open avatar` or similar) renders inspector English, not xpath
   - Dark doors chip hides high-confidence-only rooms (use mock Gmail: live has ≥1 dark screen)
   - Start mapping still disabled
   - no password/email plaintext in inspector output
   - existing maps-canvas + maps-honest tests still pass

## Out of scope

Wiring `app.ts` (012). Ask. Vault. `mapping.start`. Live cursor. Layout persist to the phone. Encrypted fill.

## Success

An operator can pan Gmail (demo or live source), click a door, read what it does in English, see which plane they are on, and Take control — without Glass pretending to crawl.
