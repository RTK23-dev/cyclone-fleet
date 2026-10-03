# GOAL — Cyclone V4 Stage 1 ONLY: Fast Path harness

You are implementing **Stage 1 of Cyclone V4 Session OS** on branch `grok/cyclone-v4-s1-fastpath` in this worktree.

**Base:** tag `v3.9.12` / SHA `c9ed77a` (Background Intelligence already exists — one Shizuku workspace, Take control/Continue, GATE confirm, exact-session progress). **Do not rip out 3.9.12 workspace/Take control.** Build Fast Path *into* the existing PhoneToolExecutor / gateway / MCP stack.

Read `artifacts/V4_BUILD_PLAN.md` first. This session is **Stage 1 only**. Do not start Stage 2–5.

# WHY
Cyclone navigation with frontier models is slow and flaky vs ClosePaw on weaker models. Root cause: harness (over-verify, vague observe, multi-check), not model quality. Stage 1 copies ClosePaw’s loop economics while keeping Cyclone’s product surface.

# REQUIRED (implement + test)
1. **A11y-first observation** with stable element indices for agent/MCP act path; screenshot/vision only as escalate when tree useless.
2. **Fast Path loop helpers**: post-action settle ~300ms + fingerprint/UI-change detect; retry ladder ~500ms then ~1000ms; if Unchanged, return `verified=false` warning — **do not** auto-fallback to a second click channel (avoid double-tap).
3. **Nav isolation**: one screen-changing mutation per agent decision turn (form field batching allowed when non-nav).
4. **MCP / tool surface clarity**: document or implement planner vs UI-subagent tool split (open_app/intent/wait/finish vs get_tree + index click/type/swipe). Keep `phone.click` / soft-success lessons from One 0.2.x.
5. **Prefer intent/deep-link / open_app** before icon hunting; integrate with 3.9.12 Ask→workspace routing when present.
6. **Docs:** `docs/V4_STAGE1_FASTPATH.md` + update `artifacts/V4_BUILD_PLAN.md` Stage 1 checkbox status.
7. **Versioning:** bump mobile identity toward **4.0.0-alpha.1** / versionCode appropriately for an alpha line *or* keep 3.9.13-dev with clear V4 Stage1 flags — pick one coherent scheme and document. Do **not** claim physical VERIFIED.
8. **PR** on `grok/cyclone-v4-s1-fastpath` with summary + test plan. Run relevant unit tests.

# CONSTRAINTS
- Phone remains authority for pay/send/delete/GATE
- No Magisk / multi-AI / 20-VD claims in this stage
- No Cyclone One Windows installer rewrite (Stage 4)
- No full App Learner compiler (Stage 3)
- Prefer existing executor over inventing a second engine
- Gateway loopback-only

# SUCCESS
- PR open with Fast Path settle/fingerprint/nav-isolation covered by tests
- 3.9.12 Take control / workspace not regressed in code paths you touch
- Clear handoff note for Stage 2 Session Kernel

Investigate the repo yourself; treat symptoms as acceptance, not a line-by-line patch list. Start now.
