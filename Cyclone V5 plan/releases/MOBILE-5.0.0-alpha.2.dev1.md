# Cyclone Mobile 5.0.0-alpha.2.dev1

This is a **merged development checkpoint**, not the alpha.2 exit and not a public-production release.

Authoritative integration merge:

`a7f03728fdca16064e03f977499c8d1790029cff`

Pinned release branch:

`release/cyclone-mobile-v5.0.0-alpha.2.dev1`

The release branch points to the exact integration merge above and is intentionally left unchanged after the cut.

## CI evidence

Validated candidate head: `4d4fa4dfb055894ab70be4b7d4a989e8949aef7f`

- **Cyclone Mobile CI #1114 — SUCCESS**: repository/product/security guards, Gateway/MCP contracts, Android unit tests, lint, unsigned release assembly, provenance and candidate upload.
- **Cyclone PC Companion CI #564 — SUCCESS**: exact head checkout, release metadata, Gateway/MCP contracts, Glass tests/build, Windows sidecars, NSIS installer, installed-candidate acceptance and provenance/artifact upload.
- Physical Pixel/device acceptance: **UNVERIFIED**.

## What is now in one build line

### V5 alpha.1 foundation

- distinct nonterminal `needs-secret` presentation state;
- Atlas and Secrets schemas;
- Android-owned V5 gateway operations;
- StrongBox-preferred / Android-Keystore fallback Vault;
- phone Secrets Card;
- one-shot secure fill through `PhoneToolExecutor`;
- metadata-only slot presence on the wire;
- no secret values in Atlas / Brain / gateway / Glass fixtures.

### Atlas / alpha.2 foundation

- durable phone-local `AtlasStore`;
- Follow Me writes into the same Atlas;
- Graph-v2 reuse rather than a second graph;
- production phone-owned `atlas.get` / `atlas.places` source;
- live/mapping persona split;
- truthful `partial` map state;
- structural-only Atlas privacy boundary;
- data-only retrieval hints;
- App Maps mobile composable.

### Mapping / alpha.3 foundation pulled forward

- phone mapping-session state machine;
- `atlas.diff`;
- Android `mapping.start | pause | stop | status` control plane;
- foreground / named VD / Layer-2 authority binding;
- bounded mapper budgets;
- nonterminal secret/human boundaries;
- safe mapper walker with observe → one mutation → observe → verify;
- dangerous pay/send/delete/logout-all/GRANT boundaries are never crossed autonomously;
- dangerous-only exploration exits safely with Atlas `partial`;
- mapping writes isolated to `persona=mapping`.

**Important:** the control plane and safe walker are not yet joined by a production session → walker driver. This checkpoint therefore does not claim autonomous alpha.3 mapping.

### Glass already on the same integration lineage

- Cyclone Glass shell with Ask, Maps and Vault;
- full read-only Maps board and inspector;
- honest live-vs-demo source behavior;
- phone-owned Vault slot presence;
- focused device/session binding;
- foreground vs named-VD plane labels;
- Take control path;
- dark-door filter and English edge inspector;
- Ask sample honesty and redacted HUD-log export.

## Why this is alpha.2.dev1, not alpha.2

Still missing for the alpha.2 exit:

1. Agent 006 canonical Place resolver, including `chrome:<origin>`;
2. root Settings integration for App Maps + Vault;
3. named physical Pixel pass;
4. proof that Glass renders a real learned phone Atlas in a named acceptance run;
5. alpha.2 exit demo: Follow Me one real app → explain the learned house on Glass without logs.

Still later / alpha.3+:

- production mapping-session → `SafeMapperWalker` driver;
- Glass mapping start/status/diff cursor controls;
- Chrome-host autonomous mapping;
- Ask compiler / People memory / Louella path;
- freshness/remap system;
- encrypted Glass secret entry or a documented phone-card-only V1 path.

## Release safety

- publication disabled;
- Android versionCode **141**;
- physical Pixel status **UNVERIFIED**;
- no GitHub production release/tag was published from this checkpoint.
