# GOAL — Cyclone V4 Stage 2 ONLY: Session Kernel

Worktree branch `grok/cyclone-v4-s2-session` from Stage 1 tip `0a0c323` (Fast Path on 3.9.12). Read `docs/V4_BUILD_PLAN.md` and `docs/V4_STAGE1_FASTPATH.md`. **Stage 2 only** — do not start Stage 3–5.

## USE SUBAGENTS (required)
Parallelize with Grok subagents: e.g. one on Android workspace/display inject, one on gateway/MCP session_id contract, one on tests/docs. Merge into one coherent PR. Do not serialize everything in a single thread when work is independent.

## WHY
3.9.12 already has one Shizuku background workspace + Take control/Continue. Stage 2 hardens **display-scoped session identity** so Fast Path actions never cross displays, and so Cyclone One / MCP can bind work to `session_id` later (Stage 4). Pattern from Ruto: `launchDisplayId` + `InputEvent.setDisplayId`; prefer TRUSTED|OWN_DISPLAY_GROUP style flags where the codebase creates virtual displays (OEM escape is real on ColorOS).

## REQUIRED
1. Ensure every observe/act path that can run in a workspace carries **session_id + displayId** end-to-end (executor, gateway, MCP stubs as present in-tree).
2. Harden virtual display creation / task launch affinity used by 3.9.12 background intelligence; document OEM limits.
3. Design for **N≥2 sessions** in types/APIs even if product still hot-gates to one BG task; do not silently fall back to display 0.
4. Preserve Stage 1 Fast Path settle/fingerprint/nav isolation and 3.9.12 Take control / GATE.
5. Tests for display-scoped inject / no cross-session action; `docs/V4_STAGE2_SESSION_KERNEL.md`; update Stage 2 checkbox in `docs/V4_BUILD_PLAN.md`.
6. Version: advance alpha line coherently (e.g. 4.0.0-alpha.2) with honest UNVERIFIED physical.
7. Open PR on `grok/cyclone-v4-s2-session`.

## OUT OF SCOPE
Skill compiler (S3), Cyclone One Windows installer/tiles (S4), Magisk, claiming 20 concurrent VDs work on device.

## SUCCESS
PR with session/display affinity + tests + docs; Fast Path + Take control not regressed; handoff note for Stage 3.

Investigate yourself; start now.
