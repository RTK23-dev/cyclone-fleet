# Mobile orchestrator — STATUS

## Current state (2026-09-23) — alpha.4 dev1 in PR (alpha.3 merged)

**Published developer prerelease:** [`v5.0.0-alpha.2.dev3`](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.2.dev3) — Mobile `5.0.0-alpha.2.dev3` (versionCode 143) + Glass `1.6.0-alpha.2`, source `a896b5da`. Mobile + Glass CI passed; physical phone / Glass acceptance **waived by the owner, not passed**. Signed with the historical development key, which is exposed in repository history (**security debt**; rotation work parked in #168).

**Integration:** #169 merged into `v5/integration` (`df306096`) so integration equals the released source plus its release automation. #167 closed as superseded (its commits are in #169).

**Merged:** alpha.3 dev1 (one-button mapping, #171) into `v5/integration` (`51b52150`); not published separately.

**Next candidate:** `5.0.0-alpha.4.dev1` (versionCode 145) + Glass `1.6.0-alpha.4` on `claude/cyclone-v5-handoff-review-9qrs40` — the sentence is law, the Atlas as an Ask hint, and Ask from Glass.

| Capability | State |
|---|---|
| Vault + Secrets Card, `needs-secret` on login walls (password slot) | implemented, CI-tested, device-unproven |
| Durable Atlas, Follow Me → Atlas, `atlas.places/get/diff` | implemented, CI-tested, device-unproven |
| Canonical Places + Chrome origin privacy, Settings mounts | implemented, CI-tested, device-unproven |
| **Autonomous mapping** (driver, backtracking, mapping-persona Atlas port, notification Stop, secret pause/resume) | **new in alpha.3.dev1**, JVM-tested against a fake app with the real walker/controller/journal/AtlasStore; device-unproven |
| Phone App Maps: Map an app / Pause / Resume / Stop / rooms / structural report | **new in alpha.3.dev1** |
| Glass Maps: Start/Pause/Stop, live cursor, cards appear from `atlas.diff` | **new in alpha.3.dev1** (Glass side) |
| Sentence is law: account words no longer become "Checking … login status"; stages keep the user's verb | **new in alpha.4.dev1**, JVM-tested |
| Ask reads the Atlas as a hint (`atlasSketch`: rooms, doors, you-are-here, suggested route) | **new in alpha.4.dev1**; plumbing/privacy tested; effect on model choices unproven |
| Glass Ask (`ask.start`, `ask.status`, live HUD mirror) | **new in alpha.4.dev1**, phone + gateway + Glass tested; device-unproven |
| People memory, fact-slot reading, capability index | not started |
| Chrome-origin mapping, VD-plane mapping, dummy sign-up, freshness | not started |

Physical Pixel 8 status stays **UNVERIFIED** for every row until a named pass exists.

## Alpha.2 exit candidate in validation

The `codex/v5-alpha2-exit` branch carries the `5.0.0-alpha.2.dev2` candidate (Android versionCode **142**) based on `v5/integration@7462e40578e3dea3c548439b09452546f993d283`. It adds canonical native and observed Chrome-origin Places, binds live/mapping secret walls to the proven Place, redacts Chrome address-bar URLs from Gateway evidence, and mounts App Maps and Vault in root Settings. The Glass version contract now follows V5 metadata. Publication remains disabled.

The first dev2 source (`8fc2fde326ff11441fae037f6179f2e89d76e933`) passed Mobile CI #1115/#1116 and PC/Glass CI #565. Its protected signing run #5 failed before signing because all five Android secrets are absent from `mobile-release-approval`. The signing workflow now has a follow-up correction for its Android SDK bootstrap, API-33 coverage and lineage export; this correction needs CI on a new exact source SHA before another signing attempt. The Pixel 8 was identified in a prior connected environment with the 4.8.0 installation, but there is no signed dev2 candidate or named physical acceptance record. Follow Me → real Glass demonstration remains **UNVERIFIED**. See [physical acceptance return](returns/RETURN-V5-ALPHA2-PHYSICAL.md). The frozen `alpha.2.dev1` release branch is unchanged.

## Prior validated checkpoint

**Wave:** `5.0.0-alpha.2.dev1` consolidated checkpoint **MERGED**  
**Integration merge:** `v5/integration@a7f03728fdca16064e03f977499c8d1790029cff`  
**Release branch:** `release/cyclone-mobile-v5.0.0-alpha.2.dev1@a7f03728fdca16064e03f977499c8d1790029cff`  
**Validated candidate head:** `4d4fa4dfb055894ab70be4b7d4a989e8949aef7f`  
**Original Mobile baseline:** `release/cyclone-mobile-v4.8.0@97f81cb692893896b500f2372068fb1cd67d85ed`

## Validation

- **Cyclone Mobile CI #1114: SUCCESS** — product/security guards, Gateway/MCP contracts, Android unit tests, lint, unsigned release assembly, provenance, candidate upload.
- **Cyclone PC Companion CI #564: SUCCESS** — exact source checkout, Glass/release metadata, Gateway/MCP contracts, companion tests/build, sidecars, NSIS installer, installed-candidate acceptance, provenance/artifact upload.
- Physical Pixel/device acceptance remains **UNVERIFIED**.
- Publication remains **disabled**.

## Mobile integration board

| ID | Work | Integration state |
|---|---|---|
| 001 | protocol + `needs-secret` | merged |
| 002 | Vault + Secrets Card | merged |
| 003 | durable Atlas + Follow Me | absorbed/corrected in PR #165; source PR #146 closed as superseded |
| 004 | mapping session + protocol | absorbed in PR #165; source PR #152 closed as superseded |
| 005 | safe mapper walker | absorbed/repaired in PR #165; source PR #151 closed as superseded |
| 006 | canonical Place/Chrome + Settings | implemented on `codex/v5-alpha2-exit`; local/CI and physical exit pending |

PR #165 is the authoritative integration path for 003–005. Their stale branch histories were not merged directly; their owned code was consolidated on the latest shared integration line and revalidated together with Glass.

## V5 foundation now integrated

- [x] distinct nonterminal `needs-secret`;
- [x] Atlas + Secrets schemas;
- [x] phone-local encrypted Vault and Secrets Card;
- [x] one-shot secret fill through `PhoneToolExecutor`;
- [x] metadata-only secret presence on the wire;
- [x] durable phone-local AtlasStore;
- [x] Follow Me → same Atlas;
- [x] production phone-owned `atlas.get` / `atlas.places`;
- [x] truthful `unmapped | partial | mapped | stale | blocked`;
- [x] structural/content privacy boundary;
- [x] live/mapping persona isolation;
- [x] phone-owned mapping session state machine;
- [x] `atlas.diff`;
- [x] Android ops `mapping.start | pause | stop | status`;
- [x] foreground / named-VD / Layer-2 authority reuse;
- [x] safe mapper walker physics: fresh observe → one safe mutation → fresh observe → verify;
- [x] dangerous pay/send/delete/logout-all/GRANT boundaries are recorded and never crossed autonomously;
- [x] dangerous-only exploration stops safely with Atlas `partial`;
- [x] mapping writes remain `persona=mapping`.

## Important integration gap

The mapping **control plane** and `SafeMapperWalker` are both integrated and individually/collectively tested, but the production runtime that launches and advances the walker from a `mapping.start` job is not wired yet.

Therefore this checkpoint does **not** claim working autonomous alpha.3 mapping. `mapping.start` owns the phone mapping job and authority state; the walker owns the safe step physics; the session → walker driver remains the next integration seam.

## Glass on the same integration lineage

Glass Runs 1–3 are already present:

- Ask / Maps / Vault shell;
- honest live-vs-demo Maps source;
- full read-only Maps board and inspector;
- phone-owned Vault slot presence;
- focused phone + session binding;
- foreground vs named-VD plane labels;
- English edge inspector and dark-door filter;
- Take control → Phone;
- honest Ask samples and redacted HUD log export.

The largest previous read-only blocker is now removed in code: Mobile has a real phone-owned Atlas provider. The named real-device Follow Me → Glass rendering acceptance is still outstanding.

## Version

- Mobile: `5.0.0-alpha.2.dev1`
- Android versionCode: **141**
- Device Gateway: `5.0.0-alpha.2.dev1`
- MCP packages: `5.0.0-alpha.2.dev1`
- Glass/PC companion: `1.6.0-alpha.1`

## Required before calling the next checkpoint alpha.2 exit

1. Agent 006 canonical Place resolver, including `chrome:<origin>`;
2. root Settings integration for App Maps + Vault;
3. named physical Pixel pass;
4. Follow Me one real app and render that real phone Atlas in Glass;
5. operator can explain the learned rooms from Glass without reading logs.

## Alpha.3 / later V5 work

- production mapping-session → `SafeMapperWalker` driver;
- Glass live `mapping.start` / status / `atlas.diff` cursor UI;
- Chrome-host autonomous mapping;
- Ask compiler that preserves the user mission;
- People memory / Louella path;
- stale/remap freshness system;
- encrypted Glass secret entry or explicit phone-card-only V1 path.

**Physical Pixel status:** UNVERIFIED.
