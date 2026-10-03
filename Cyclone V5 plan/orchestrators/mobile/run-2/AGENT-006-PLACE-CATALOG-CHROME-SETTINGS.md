# RUN 2 — AGENT 006 — PLACE CATALOG + CHROME + SETTINGS

**Read first:** `RUN-2-SHARED.md`  
**Branch (issued now):** `v5/mobile/place-catalog-chrome-settings`  
**PR target:** `v5/integration`

You own canonical **Place resolution** and the Run-1 Settings mounting closeout. You do not own the mapper walker.

## Mission

Make “Facebook in Chrome” a real phone Place instead of `package:com.android.chrome`, and give the user one honest Settings entry point for the Run-1 Atlas/Vault surfaces.

## Owned paths

- new `apps/mobile/**/places/**`
- bounded browser/origin resolver hooks
- the single root Settings integration file for this run
- minimal hooks in Run-1 secret-wall/place consumers
- place/catalog/Settings tests

Do not create another AtlasStore or PlaceCatalog.

## Required behavior

### 1. Canonical PlaceResolver

Provide one reusable phone API that resolves current destination to either:

```text
package:<package>
chrome:<origin>
```

For Chrome-like browsers, origin must be based on observed/browser authority. Never guess an origin from the task string alone.

Canonicalize:
- lower-case scheme/host as appropriate;
- explicit non-default port when present;
- no path/query/fragment;
- reject non-http(s) for V5 Chrome Places.

### 2. Browser authority

Use the strongest existing browser URL/origin evidence available in the mobile runtime.

If current origin cannot be proven:
- return unresolved;
- do not fall back to `package:com.android.chrome` for a website Place;
- let caller remain blocked/unknown.

### 3. Integrate Run-1 secret wall

Replace Agent 002's deliberate Chrome exclusion with the canonical resolver.

A Chrome credential wall can request:

```text
placeId = chrome:https://example.com
persona = live|mapping
slot = password
```

only when the origin is actually proven.

No secret value enters the resolver.

### 4. Place catalog population

Reuse the existing Atlas `PlaceCatalog`.

Support:
- native launcher/package places;
- observed Chrome origins;
- operator-added valid Chrome origins if an existing Settings flow naturally supports it.

Do not copy Atlas data into a second catalog database.

### 5. Settings integration

Run 1 intentionally left two mountable sections unmounted to avoid parallel conflicts. In this run you are the **only root Settings owner**.

Mount the existing:
- App Maps mini/catalog section;
- Vault slot section.

Keep them small/mobile-appropriate. Glass remains the full board.

Autonomous mapping controls should only become active if Agent 004's real session surface is present; otherwise show honest unavailable/coming state. Do not fake Start.

### 6. Privacy

Catalog labels may contain app names and host names, not page content/person names.

Do not persist browser path/query values.

### 7. Tests

Required:

1. native package resolves canonical package Place;
2. Chrome URL resolves origin-only Place;
3. path/query/fragment are removed;
4. default vs explicit port canonicalization is correct;
5. unresolved browser origin does not become Chrome package Place;
6. Chrome secret request uses canonical origin;
7. mapping/live persona still remain separate downstream;
8. invalid scheme/origin rejected;
9. App Maps and Vault sections are reachable from Settings;
10. Settings never renders stored secret values.

## Return

`RETURN-RUN2-006-place-catalog-chrome-settings.md`

Document the exact authority source used to prove Chrome origin and any browser variants intentionally unsupported.
