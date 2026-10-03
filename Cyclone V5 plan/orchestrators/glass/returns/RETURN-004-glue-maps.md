# RETURN-004 — glue-maps

**Agent:** Glass Run 1 Agent 004 (glue-maps)  
**Handoff:** `agents/HANDOFF-004-glue-maps.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/glue-maps`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/156  
**Starting integration SHA:** `3fa31431ba91fdb1c27ee3068bf22921937f065d`  
**Implementation SHA:** `b883f0d3ad7cb34f4e73a602fb4bcb1aff603e6e`  
**Head SHA:** this return commit on the same branch  
**Commits:**

- `b883f0d3ad7cb34f4e73a602fb4bcb1aff603e6e` feat(glass): mount Maps canvas in the Glass shell
- this return file

## Done

- Imported `createMapsPage` from `./pages/mapsPage.js` in `apps/pc-companion/src/app.ts` next to the other page imports.
- When `this.state.route === "maps"`, the shell now mounts `createMapsPage()` (default mock source). `createMapsPage()` returns `{ element, destroy }` and satisfies `PageHandle`.
- Deleted `createMapsPlaceholderPage` and the “Maps board ships next” copy.
- Did **not** import `atlasClient`. Mock atlas from Agent 002 is the Run 1 board.
- Updated `tests/ask-vault.test.mjs` “Glass shell brands Ask Maps Vault and keeps ChatGPT”:
  - matches `createMapsPage` and `from "./pages/mapsPage`
  - does not match `createMapsPlaceholderPage` or `Maps board ships next`
  - still does not match `atlasClient` in `app.ts`
  - still matches Cyclone Glass / Ask / Maps / Vault / ChatGPT / `createAskPage` / `createVaultPage`

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/156 | Glue-maps PR into `v5/integration` |
| `3fa31431` | Starting `v5/integration` SHA |
| `b883f0d3ad7cb34f4e73a602fb4bcb1aff603e6e` | Implementation: mount `createMapsPage()`, drop placeholder |

## Paths touched

```text
apps/pc-companion/src/app.ts
apps/pc-companion/tests/ask-vault.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-004-glue-maps.md
```

## Tests

```text
cd apps/pc-companion && npm install --no-audit --no-fund && npm test
→ tsc -p tsconfig.test.json && node --test tests/*.test.mjs
→ 148 pass / 0 fail / 0 skipped
  including:
  - Glass shell brands Ask Maps Vault and keeps ChatGPT
  - Glass shell navigates to Ask, Maps and Vault without dropping ChatGPT
  - maps page smoke: rail, empty place, inspector select
```

## Contract

Did this change names/ops? If yes, CONTRACT.md updated in the same PR: n/a

Mount-only. Consumed Agent 002’s `createMapsPage({ source }?)` with the default mock source. No atlas ops, no mapping.start, no live client.

## Not done / blocked

- Did not restyle the canvas.
- Did not wire a live `atlasClient` / `atlas.get`.
- Did not start mapping. Start mapping stays disabled (`phone alpha.3`) inside Agent 002’s page.
- Fixture title (005) and version bump (006) are other glue agents.
- Encrypted PC fill is out of scope.

## Suggested next handoff

- HANDOFF-005: fixture title (copy adapter).
- HANDOFF-006: version bump.
- Later: swap mock source for phone-owned `atlas.get` once live atlas is the default board; still do not start mapping until alpha.3.
