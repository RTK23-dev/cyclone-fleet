# GOAL — Cyclone V4 Stage 4 ONLY: Cyclone One V4 glass

Branch `grok/cyclone-v4-s4-one` from Stage 3 tip `73c08d7` (Fast Path + Session Kernel + Skill Compiler on 3.9.12 lineage). Read `docs/V4_BUILD_PLAN.md`, Stage 1–3 handoff docs. **Stage 4 only.** Do not start Stage 5 / do not cut `v4.0.0`.

## USE SUBAGENTS (required)
Parallelize independent work: merge/port One 0.2.1 JPEG+handoff into `apps/pc-companion` (or current One surface), session tile/events UI, MCP `session_id` hard requirement + gateway wiring, tests/docs. One coherent PR on this branch.

## WHY
Cyclone One is **glass/MCP**, not a second brain. Phone owns Fast Path / Session Kernel / Skill Compiler. PC shows sessions, streams JPEG live, hands control, and routes MCP with exact `session_id` (+ `display_id` when applicable). Never invent a second navigator on the PC that bypasses mobile executor.

## REFERENCE
- One 0.2.1 already shipped JPEG live + AI handoff: branch `grok/cyclone-one-v0.2.1-beta` tip `7b0e77c` (worktree may exist at `C:\Users\Agent\Cyclone-one-v0.2.1`). Cherry-pick / merge / port those changes into this V4 stack — do not regress JPEG-first live or handoff.
- Stages 1–3 already expose `sessionId`+`displayId` end-to-end and compiled skills bound to session/display.

## REQUIRED
1. **Merge/port One 0.2.1** JPEG live view + human/AI handoff into the V4 PC companion path used by this repo.
2. **Session fabric UI**: session.added / session.removed (and equivalent) so the glass can tile multiple phone sessions; tiles bind to `session_id` (and display when present).
3. **MCP**: require `session_id` on observe/act (and related phone tools that mutate or observe UI); reject/mismatch cleanly (align with Stage 2 `SESSION_DISPLAY_MISMATCH` / named-workspace rules). Forward `display_id` when provided. No silent display-0 rewrite for named workspaces.
4. PC remains glass only — no second PhoneToolExecutor / Fast Path brain on Windows.
5. Preserve mobile Fast Path + Session Kernel + Skill Compiler + 3.9.12 Take control/GATE.
6. Tests for MCP session_id requirement, session events/tiles wiring where feasible, no-regression guards; `docs/V4_STAGE4_ONE_GLASS.md`; mark Stage 4 DONE in `V4_BUILD_PLAN.md` with handoff to Stage 5 release lane.
7. Version bump: mobile/companion as appropriate for alpha (e.g. **4.0.0-alpha.4** / One prerelease bump if you version the companion); physical UNVERIFIED.
8. Open PR on `grok/cyclone-v4-s4-one` stacking on Stage 3 branch `grok/cyclone-v4-s3-skills`.

## OUT OF SCOPE
`v4.0.0` / One 1.0 final tag (Stage 5), Magisk, claiming 20 concurrent VLMs, rewriting Stage 1–3.

## SUCCESS
PR open with One 0.2.1 live+handoff on V4 stack, session tiles/events, MCP `session_id` required, tests/docs, clear Stage 5 handoff. Investigate yourself; start now.
