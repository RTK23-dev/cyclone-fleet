# Cyclone V4 Stage 5 — release lane

**Identity:** mobile `4.0.0` / versionCode `71`; Cyclone One `1.0.0`; gateway / MCP `4.0.0`  
**Base:** Stage 4 tip `4ad2667` / PR #62 (One glass on Skill Compiler + Session Kernel + Fast Path + published `v3.9.12`)  
**Physical Pixel 8:** **UNVERIFIED** — this lane packages identity and the operator cut path. Do not treat a green CI build as device evidence.

Stage 5 is the V4 Session OS packaging handoff. It does **not** rewrite Stages 1–4, cut Magisk, or invent a physical pass. Product notes live in [`RELEASE_V4.md`](RELEASE_V4.md); this file is merge order + operator commands only.

`publication_authorized` remains **false** until a signed artifact and physical evidence exist. Do not claim the GitHub tag `v4.0.0` already exists.

## Merge order

Do not rewrite stack history. Merge in this order:

1. **#59** Fast Path (`grok/cyclone-v4-s1-fastpath`)
2. **#60** Session Kernel (`grok/cyclone-v4-s2-session`)
3. **#61** Skill Compiler (`grok/cyclone-v4-s3-skills`)
4. **#62** One glass (`grok/cyclone-v4-s4-one`)
5. **this Stage 5 PR** (`grok/cyclone-v4-s5-release` base `grok/cyclone-v4-s4-one`)

After the stack lands, cut `v4.0.0` from the merged SHA. This PR does not create the tag.

## Operator cut path

1. After stack merge, create and push `release/cyclone-mobile-v4.0.0` from the merged SHA so the Mobile CI **push** run fires (`on.push.branches` includes `release/cyclone-mobile-v*`).
2. Wait for [`.github/workflows/mobile-ci.yml`](../.github/workflows/mobile-ci.yml) success on that push (not a pull_request run). Record the run id and unsigned artifact name.
3. Dispatch [`.github/workflows/mobile-release.yml`](../.github/workflows/mobile-release.yml) with that run id + artifact name (environment `mobile-release-approval`).
4. Dispatch [`.github/workflows/pc-companion-release.yml`](../.github/workflows/pc-companion-release.yml) for the Cyclone One **1.0.0** NSIS candidate.
5. Dry-run: `python scripts/ci/cut_v4_release.py`
6. Tag only when CI is green and no `v4.0.0` tag already exists:

```bash
gh release create v4.0.0 --title "Cyclone 4.0.0 + One 1.0.0" --notes-file docs/RELEASE_V4.md
```

Never force-push. Never replace an existing `v4.0.0`.

## Physical Pixel 8

Physical Pixel 8 remains **UNVERIFIED**. Use the checklist in [`RELEASE_V4.md`](RELEASE_V4.md) (USB / Shizuku / JPEG live / take-control / skill replay). Do not check those boxes from CI.

## What Stage 5 does not do

- Restart or rewrite Stages 1–4
- Magisk / root / a second mutation engine
- 20 concurrent VLMs or 20 hot LLM agents
- Merge other PRs
- Invent device results or mark Pixel 8 verified
- Claim the GitHub tag already exists
- Flip `publication_authorized` without a signed artifact and physical evidence
