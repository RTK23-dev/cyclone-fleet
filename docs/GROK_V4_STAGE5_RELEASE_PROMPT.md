# GOAL — Cyclone V4 Stage 5 ONLY: V4 release lane

Branch `grok/cyclone-v4-s5-release` from Stage 4 tip `4ad2667` (Fast Path + Session Kernel + Skill Compiler + One glass). Read `docs/V4_BUILD_PLAN.md` and Stage 1–4 handoff docs. **Stage 5 only** — ship the V4 release packaging. Do not restart Stages 1–4.

## USE SUBAGENTS (required)
Parallelize: version/release.toml + mobile/companion bumps, release notes, physical checklist, CI/tag/release workflow, packaging docs. One coherent PR on this branch.

## STACK CONTEXT
Open stacked PRs (do not rewrite their history):
- #59 Stage 1 Fast Path (`grok/cyclone-v4-s1-fastpath`)
- #60 Stage 2 Session Kernel (`grok/cyclone-v4-s2-session`)
- #61 Stage 3 Skill Compiler (`grok/cyclone-v4-s3-skills`)
- #62 Stage 4 One glass (`grok/cyclone-v4-s4-one`) ← your base tip

This branch stacks on Stage 4. Prepare everything so after the stack merges (or via this release PR onto the Stage 4 head), Cyclone can cut **`v4.0.0`** paired with Cyclone One **1.0** (or honest 1.0.0-rc if installer/CI cannot go final — prefer real `v4.0.0` / One `1.0.0` if packaging is ready).

## REQUIRED
1. **Version bump** to mobile **`4.0.0`** / versionCode +1 from alpha.4 (70→71 or next), and Cyclone One companion to **`1.0.0`** (or documented pairing version in `release/version.toml` + packaging). Drop `-alpha.N` for the release cut.
2. **Release notes** `docs/RELEASE_V4.md` (or `docs/releases/v4.0.0.md`): Session OS story — Fast Path, Session Kernel, Skill Compiler, One glass/MCP `session_id`, JPEG live + handoff; what is UNVERIFIED (physical Pixel); upgrade notes from 3.9.12 / One 0.2.1.
3. **Physical checklist** honest: Pixel 8 USB / Shizuku / live JPEG / take-control / skill replay — mark unchecked/UNVERIFIED unless you actually verify on device.
4. **CI / tags**: ensure release workflow or documented `gh release` steps for tag `v4.0.0` (+ One installer artifact if present). Do not force-push. Creating the GitHub Release + tag is OK if CI green on this branch and notes are ready; otherwise open the PR with tag/release instructions and a script, and leave cutting to CI or a final `gh release create` when checks pass.
5. Mark Stage 5 DONE in `V4_BUILD_PLAN.md`; add `docs/V4_STAGE5_RELEASE.md` handoff summarizing merge order (#59→#62 then this) and release commands.
6. Open PR on `grok/cyclone-v4-s5-release` base `grok/cyclone-v4-s4-one`.

## OUT OF SCOPE
Rewriting Stages 1–4, Magisk, claiming 20 concurrent VLMs, inventing physical pass results, merging other people’s PRs without CI.

## SUCCESS
PR open that completes the V4 release lane (version 4.0.0 + One 1.0 pairing, notes, checklist, CI/tag path). Prefer actually publishing `v4.0.0` if safe; otherwise PR + clear publish steps. Investigate yourself; start now.
