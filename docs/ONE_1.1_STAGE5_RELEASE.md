# Cyclone One 1.1 Stage A5 — release lane

**Identity:** Cyclone One `1.1.0`; gateway / MCP `4.1.0`; mobile on this tree stays `4.0.4` / versionCode `75`  
**Base:** A4 tip `4d75911` / PR #72 (Operator MCP pack on Sessions + Layer 2 + Tooling + published `v4.0.4`)  
**Physical Pixel 8:** **UNVERIFIED** — this lane packages identity and the operator cut path. Do not treat a green CI build as device evidence.

Stage A5 is the One 1.1.0 packaging handoff. It does **not** rewrite A1–A4, cut Magisk, invent a physical pass, or cut mobile 4.1.0. Product notes live in [`RELEASE_ONE_1.1.md`](RELEASE_ONE_1.1.md); this file is merge order + operator commands only.

`publication_authorized` remains **false** until a signed/CI installer artifact exists. Do not claim the GitHub tag `one-1.1.0` already exists. This PR does not create the tag.

## Merge order

Do not rewrite stack history. Merge in this order:

1. **#66** Tooling (`grok/one-1.1-s1-tooling`) base `release/cyclone-mobile-v4.0.4` / `v4.0.4`
2. **#68** Layer 2 (`grok/one-1.1-s2-layer2`)
3. **#70** Sessions (`grok/one-1.1-s3-sessions`)
4. **#72** Operator (`grok/one-1.1-s4-operator`)
5. **this A5 PR** (`grok/one-1.1-s5-release` base `grok/one-1.1-s4-operator`)

After the stack lands, cut `one-1.1.0` from the merged SHA. This PR does not create the tag.

## Operator cut path

Setup.exe via existing CI, **not** a new workflow:

1. After stack merge, create and push `release/cyclone-one-v1.1.0` from the merged SHA for provenance. Do **not** push `release/cyclone-mobile-v*` for this One-only cut (that would fire Mobile CI for an already-published 4.0.4 APK).
2. Do **not** dispatch [`.github/workflows/mobile-ci.yml`](../.github/workflows/mobile-ci.yml) or [`.github/workflows/mobile-release.yml`](../.github/workflows/mobile-release.yml) as part of this One 1.1.0 cut. Mobile 4.0.4 is already published (tag `v4.0.4`). Mobile 4.1.0 is PR #73 / B5, out of scope. Do not merge other PRs.
3. Dispatch [`.github/workflows/pc-companion-release.yml`](../.github/workflows/pc-companion-release.yml) (`gh workflow run pc-companion-release.yml --ref release/cyclone-one-v1.1.0`). That Windows job validates the PC stack, builds NSIS, stages `Cyclone-PC-Companion-1.1.0-Setup.exe` (name comes from `apps/pc-companion/package.json` version), optional Authenticode if secrets exist (`windows_signing` remains `CI_UNSIGNED` if they do not).
4. Wait for that workflow success. Download the candidate artifact `cyclone-pc-release-candidate-<sha>`. Copy the exact Setup.exe name from the artifact; do not invent it (typically `Cyclone-PC-Companion-1.1.0-Setup.exe` if `package.json` is `1.1.0`).
5. Dry-run: `python scripts/ci/cut_one_1_1_release.py`
6. Tag only when CI is green and no `one-1.1.0` tag already exists:

```bash
gh release create one-1.1.0 --title "Cyclone One 1.1.0" --notes-file docs/RELEASE_ONE_1.1.md
```

attaching the Setup.exe if present. Never force-push. Never replace an existing `one-1.1.0`.

The helper refuses leftover `1.1.0-alpha.4` / `4.1.0-alpha.4`. `--execute` is for the operator after CI; this session does not run it.

## Pairing

One 1.1.0 pairs with mobile ≥ `4.0.4`; ideally `4.1.0` for Layer 2 MCP / dual-plane contract. Full Layer 2 MCP needs the phone that already speaks `workspace.*` (`4.0.4+`).

Uninstall legacy Cyclone PC Companion **3.8.1** so doctor / install-path stop flipping.

## Physical Pixel 8

Physical Pixel 8 remains **UNVERIFIED**. The Pixel / doctor checklist is in [`RELEASE_ONE_1.1.md`](RELEASE_ONE_1.1.md) and stays **UNVERIFIED**. Do not check those boxes from CI.

## What A5 does not do

- Restart or rewrite A1–A4
- Magisk / root / a second mutation engine
- Merge other PRs
- Invent device results or mark Pixel 8 verified
- Claim the GitHub tag `one-1.1.0` already exists
- Flip `publication_authorized` without a CI installer artifact
- Cut mobile 4.1.0 (PR #73)
