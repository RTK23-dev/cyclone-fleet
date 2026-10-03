# GOAL — Cyclone V4 Stage 3 ONLY: Skill Compiler

Branch `grok/cyclone-v4-s3-skills` from Stage 2 tip `4d270ae` (Fast Path + Session Kernel on 3.9.12). Read `docs/V4_BUILD_PLAN.md`, `docs/V4_STAGE1_FASTPATH.md`, `docs/V4_STAGE2_SESSION_KERNEL.md`. **Stage 3 only.**

## USE SUBAGENTS (required)
Parallelize: e.g. hint store / condenser, compiler→deterministic route, executor integration, tests/docs. One coherent PR.

## WHY (Sanna + Cyclone moat)
Sanna learns NL playbooks per package but still pays an LLM every UI run. Cyclone’s wedge: **learn like Sanna, then compile stable paths into deterministic PhoneToolExecutor routes** so many sessions stay cheap. Vision/Fast Path LLM only on miss or unlabeled canvas.

## REQUIRED
1. Per-package **NL playbook / hint store** (persist, merge after successful Fast Path runs; user override merge if easy).
2. **Compiler**: promote stable hint sequences / known transitions into deterministic routes (selectors + session/display binding from Stage 2).
3. **Replay path** in executor: try compiled skill first; on miss fall back to Fast Path LLM/UI sub-agent loop.
4. Preserve Fast Path settle/fingerprint/nav isolation and Session Kernel display affinity + 3.9.12 Take control/GATE.
5. Tests for store/compile/replay/miss-escalate; `docs/V4_STAGE3_SKILL_COMPILER.md`; mark Stage 3 done in plan.
6. Version bump e.g. **4.0.0-alpha.3** / code +1; physical UNVERIFIED.
7. Open PR on `grok/cyclone-v4-s3-skills` (stack on s2 branch or note base).

## OUT OF SCOPE
Cyclone One Windows tiles/installer (Stage 4), Magisk, claiming 20 hot LLM agents.

## SUCCESS
PR with learn→compile→replay working in tests; Fast Path+Session Kernel not regressed; handoff for Stage 4 One glass.

Investigate yourself; start now.
