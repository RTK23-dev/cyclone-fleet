# RETURN-007 — maps-honest-source

**Agent:** 007 — maps-honest-source  
**Handoff:** `agents/HANDOFF-007-maps-honest-source.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/maps-honest-source`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/161  
**Starting integration SHA:** `1c13c9ff0cd48ab7231474655771974db48d32b0`  
**Implementation SHA:** `0df266b88067df7780a62293c4eec0b8af7ec28f`  
**Head SHA:** this return commit on the same branch  
**Commits:**
- `0df266b8` feat(glass): honest Maps source — demo flag, version gate, live loadSource
- this return file — docs(v5 glass): return Run 2 Agent 007 maps-honest-source

## Done

Maps is honest. `createMapsPage()` no longer always mounts mock Gmail as if it were the phone.

Frozen options (do not bikeshed; 009 will call these):

```ts
createMapsPage({
  source?: MapsDataSource;
  loadSource?: () => Promise<MapsDataSource>;
  phoneVersion?: string | null;
  demo?: boolean;
  sessionId?: string;
})
```

Board rules implemented:

| Inputs | Board |
|---|---|
| `demo === true` | Mock Mini house + visible **(demo)** banner (`glass-compat-banner`). Start mapping stays disabled. |
| `"phoneVersion" in options` (via `Object.prototype.hasOwnProperty.call`) and not demo and `phoneSupportsGlassAtlas(phoneVersion \|\| "") === false` | Update-the-phone banner using `GLASS_UPDATE_PHONE_TITLE` / `GLASS_UPDATE_PHONE_COPY`. **No mock Gmail.** Explicit `null` is update-the-phone; omitted key is not. |
| phone ≥ 5.0 and `loadSource` | Loading overlay, then `await loadSource()`. Empty catalog → “No atlas from this phone yet”. Unmapped place → existing Start mapping card, disabled, `title="phone alpha.3"` (canvas empty action still `mapper is phone alpha.3`). |
| phone ≥ 5.0, no `loadSource` | Honest empty. Not mock. |
| demo not set, `phoneVersion` omitted, no `loadSource` | Today’s mock default so 007 can merge before 009. |

Errors from `loadSource` (`PHONE_VERSION_UNSUPPORTED`, `SESSION_REQUIRED`, `HUMAN_HAS_CONTROL`, network / `Failed to fetch`) render named English on the board. Not a blank crash.

`partial` stays `partial` in the view-model and the rail chip. A non-empty graph is not coerced to `mapped`.

`apps/pc-companion/src/maps/phoneAtlasSource.ts` is the optional load + empty helper. It re-exports `loadMapsDataSourceFromClient` / `toMapsDocument` from Agent 005. Secret values are not re-stripped here; the adapter already drops them.

Start mapping stays disabled. No `mapping.start`, no cursor, no `atlas.diff`. CSS for empty / loading / demo / update-phone lives in `maps.css` only and reuses `glass-compat-banner`.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/161 | Agent 007 PR targeting `v5/integration` |
| `1c13c9ff0cd48ab7231474655771974db48d32b0` | Starting `v5/integration` SHA |
| `0df266b88067df7780a62293c4eec0b8af7ec28f` | Implementation commit |
| this return commit | Return MD on the same branch |

## Paths touched

```text
apps/pc-companion/src/pages/mapsPage.ts
apps/pc-companion/src/maps.css
apps/pc-companion/src/maps/phoneAtlasSource.ts
apps/pc-companion/tests/maps-honest.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-007-maps-honest-source.md
```

`src/maps/**/*.ts` is already in `tsconfig.test.json`, so the new helper compiles without a tsconfig edit. No `app.ts`, `askPage.ts`, `vaultPage.ts`, `fleet.ts`, `atlasClient.ts` rewrite, `apps/mobile/**`, `version.toml`, or `package-lock.json`.

## Tests

```text
cd apps/pc-companion && npm install && npm test
→ tsc -p tsconfig.test.json && node --test tests/*.test.mjs
→ 167 pass / 0 fail / 0 skipped
```

Focused maps-honest coverage:

- `demo: true` → mock Gmail rooms exist AND `(demo)` text in DOM
- `{ phoneVersion: "4.8.0" }` → update-phone copy; Gmail title is not presented as live
- explicit `{ phoneVersion: null }` → update-phone; omitted `phoneVersion` keeps today’s mock default
- `{ phoneVersion: "5.0.0-alpha.1", loadSource: async () => emptySource }` → loading, then honest empty, not a crash or mock Gmail
- 5.0 unmapped place keeps Start mapping disabled (`phone alpha.3` / canvas `mapper is phone alpha.3`)
- loadSource document with `mapStatus: "partial"` stays Partial in UI and `toViewModel`
- loadSource that throws `{ code: "SESSION_REQUIRED" }` or `Error.name = "SESSION_REQUIRED"` shows named copy; `HUMAN_HAS_CONTROL` and network (`Failed to fetch`) also named
- fixtures / adapter output still contain no password plaintext (`hunter2` stripped)
- existing maps-canvas tests still pass (default `createMapsPage()` mock path)

## Contract

Did this change names/ops? **no** / n/a. Consumes existing `atlas.places` / `atlas.get` via Agent 005’s adapter. Maps canvas still speaks `MapsDataSource`. `partial` is not rewritten to `mapped`.

## Not done / blocked

- `app.ts` is **not** wired. Agent 009 must pass `phoneVersion`, `loadSource` (from `loadPhoneAtlasSource` / `loadMapsDataSourceFromClient`), `sessionId`, and `demo` only as an explicit opt-in.
- Start mapping / `mapping.start` / mapping cursor / `atlas.diff` remain out of scope (alpha.3).
- Adapter is still fail-closed on secret **values**; this PR does not reimplement stripping.
- Live `atlas.get` graph quality is still blocked on Mobile #146. Glass now fails honest-empty rather than lying with mock Gmail.

## Suggested next handoff

- HANDOFF-009: mount `createMapsPage({ phoneVersion, loadSource, sessionId, demo })` from `app.ts` / `glassRuntime`. Do not pass mock as the default once a phone version is known. Vault 008 ships the matching `loadSlots` / `previewSlots` contract in parallel.
