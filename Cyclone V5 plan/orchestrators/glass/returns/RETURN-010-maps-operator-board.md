# RETURN-010 — maps-operator-board

**Agent:** 010 — maps-operator-board  
**Handoff:** `agents/HANDOFF-010-maps-operator-board.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/maps-operator-board`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/164  
**Starting integration SHA:** `3578e6c2667853d5cc79ba4ddb9801eeec2842a5`  
**Implementation SHA:** `23dbf1e15ac80e4b2af51da43288651058ab5353`  
**Head SHA:** this return commit on the same branch  
**Commits:**
- `23dbf1e1` feat(glass): Run 3 Agent 010 — Maps operator bar and edge inspector
- this return file — docs(v5 glass): return Run 3 Agent 010 maps-operator-board

## Done

Mini-class Maps board is an operator table on the Run 2 honest pipe. Frozen `createMapsPage` additions:

```ts
createMapsPage({
  source?: MapsDataSource;
  loadSource?: () => Promise<MapsDataSource>;
  phoneVersion?: string | null;
  demo?: boolean;
  sessionId?: string;
  sessionPlane?: "foreground" | "session_kernel_vd";
  onOpenControl?: () => void;
})
```

| Surface | Behavior |
|---|---|
| Session plane | Top-bar copy `Foreground · session_id …` / `Session Kernel VD · session_id …`. Empty/omitted `sessionId` displays `default-foreground`. Named id `vd-mail` is shown as-is and is never rewritten. |
| Take control | Enabled only when `onOpenControl` is passed; click calls it. Missing callback → disabled, `title="Phone live handoff, not mapping pause."` Does not call `mapping.pause` / `mapping.start`. |
| Edge inspector (G2.5) | Canvas `onSelectEdge`. SVG paths have `data-edge-id`. `InspectorState.kind` includes `"edge"`. Headline is English `actionHint` (e.g. `open avatar`), plus from → to labels, last verified, confidence. No xpath headline. No password. Pin / Remap / Never stay disabled (`phone alpha.3`). Screen inspector still works. |
| Dark doors (G2.6) | `BoardFilters.unmappedDoors?: boolean`. Chip **Dark doors**. Only-that-chip keeps screens with an outgoing edge `confidence < DARK_CONFIDENCE` (and the dark-edge destination so the door can render) plus those dark edges. Combined with stale/blocked/danger is any-of. Early-return no longer skips `unmappedDoors`. |
| Capability glyphs (G2.2) | Compact CSS initials on cards from purpose (`IN` / `CO` / `LG` / `PY`) plus abbreviated place capabilities. No Mini “passed 8/8”. |
| Start mapping | Still disabled (`phone alpha.3`). Empty-board Start mapping still disabled. |

Honesty from Agent 007 is unchanged: demo banner, 4.8 update-the-phone, empty atlas, `partial` stays `partial`.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/164 | Agent 010 operator bar |
| `3578e6c2667853d5cc79ba4ddb9801eeec2842a5` | Starting `v5/integration` SHA |
| `23dbf1e15ac80e4b2af51da43288651058ab5353` | Implementation commit |
| this return commit | Return MD on the same branch |

## Paths touched

```text
apps/pc-companion/src/pages/mapsPage.ts
apps/pc-companion/src/ui/appMapCanvas.ts
apps/pc-companion/src/maps/atlasViewModel.ts
apps/pc-companion/src/maps.css
apps/pc-companion/tests/maps-operator.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-010-maps-operator-board.md
```

No `app.ts`, `askPage.ts`, `vaultPage.ts`, `fleet.ts`, `glassRuntime.ts`, `atlasClient.ts` rewrite, `livePhoneController`, `apps/mobile/**`, `version.toml`, or `package-lock.json`. `tsconfig.test.json` did not need an include edit (`src/pages/mapsPage.ts` and `src/maps/**` were already listed).

## Tests

```text
cd apps/pc-companion && npm test
→ tsc -p tsconfig.test.json && node --test tests/*.test.mjs
→ 201 pass / 0 fail / 0 skipped
```

Was 190 on integration. Focused operator coverage:

- `sessionId: "vd-mail"` + `sessionPlane: "session_kernel_vd"` appears; `default-foreground` is not substituted
- omitted / empty `sessionId` shows `default-foreground`
- `onOpenControl` click fires; without it Take control is disabled
- clicking mock Gmail `open avatar` renders inspector English, not xpath
- screen inspector still works after an edge select
- Dark doors chip hides high-confidence-only rooms (Compose) and keeps Inbox + Search
- Start mapping still disabled (bar + empty board)
- no password/email plaintext in inspector output
- capability glyphs are CSS initials, not “passed 8/8”
- `applyBoardFilters({ unmappedDoors: true })` keeps only dark doors; no-chip path unchanged
- existing maps-canvas + maps-honest tests still pass

## Contract

Did this change names/ops? **no**. Consumed existing atlas view-model + session-plane labels (`Foreground` / `Session Kernel VD`). No new gateway ops. `mapping.start` remains unimplemented.

## Not done / blocked

- Wiring `app.ts` / `sessionId` / `sessionPlane` / `onOpenControl` from the shell is Agent 012. 010 only exported the frozen options.
- `mapping.start`, live cursor, `atlas.diff`, layout persist to the phone, encrypted fill — out of scope.
- Pin / Remap / Never stay disabled until phone alpha.3 / rc.
- Dark-doors destination screens are kept so the door can draw; the chip still hides high-confidence-only rooms.

## Suggested next handoff

- Merge **010 + 011 first**, then **012** mounts `sessionPlane` / `onOpenControl` on Maps (and Ask) from the focused session tile. 012 should type-assert the new options only if 010/011 are not on its worktree.
- Do not enable Start mapping in 012.
