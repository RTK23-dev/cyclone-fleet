# Cyclone V4 — Multi-stage Session OS build

Base line: **published mobile `v3.9.12`** (`c9ed77a`, versionCode 66) — Background Intelligence (one Shizuku workspace, Take control / Continue, GATE confirm, exact-session progress frames). PC companion in that tag is unchanged; Cyclone One **0.2.1** already has JPEG live + handoff on a separate branch and will be pulled in Stage 4.

## Product north star
**Cyclone Session OS**: reliable, low-token phone control that can scale toward many sessions.
- ClosePaw-shaped Fast Path (a11y JSON + smallest act + fingerprint settle)
- Ruto-shaped display-scoped sessions (build on 3.9.12 workspace, not replace)
- Sanna-shaped planner + tiny UI sub-agent + NL playbooks → **compile** to deterministic skills (Cyclone moat)
- Cyclone One as glass/MCP, not a second brain
- Never: 20 concurrent VLMs; yes: mostly compiled skills + few hot LLM agents

## Grok budget (week of 2026-09-05 → 12 Sep ~10:21 CEST)
- At plan start: ~**67%** weekly pool left (Build ~25% used). Target **≤12–15% Build per stage**.
- **Do not** attempt V4 in one headless run.
- After each stage: PR + CI green + short written handoff before next stage.

## Stages (strict order — do not skip)

### Stage 1 — Fast Path harness (DONE — PR #59)
Branch: `grok/cyclone-v4-s1-fastpath` from `v3.9.12`.
Goal: Make ordinary navigation feel like ClosePaw — mid-model capable.
Deliverables:
1. Primary observation = structured a11y / Page Card with stable `element_index` (vision escalate only when tree empty/custom canvas).
2. Control loop: perceive → **one** screen-changing act (or batched form fills) → settle 300ms → fingerprint → optional +500/+1000; Unchanged ≠ auto double-channel retry.
3. Planner/UI split surface for MCP: planner tools (open_app/intent/wait/finish) vs UI sub-agent tools (get_tree, click/type/swipe by index).
4. Kill extra LLM “verify” rounds for ordinary taps; local fingerprint is authority.
5. Intent/deep-link landing before icon hunting (keep 3.9.12 workspace routing).
6. Tests + `docs/V4_STAGE1_FASTPATH.md` + version identity toward **4.0.0-alpha.1** *module* flags (do not publish full v4 APK yet unless smoke green).
Acceptance: unit/integration tests for settle/fingerprint/nav-isolation; honest UNVERIFIED for physical; PR open.
Out of scope: multi-VD scale-out, skill compiler, One V4 packaging, Magisk.

### Stage 2 — Session Kernel (DONE — PR #60)
Branch: `grok/cyclone-v4-s2-session` from Stage 1.
Goal: Display-scoped session identity so Fast Path never crosses displays; MCP/Cyclone One can bind `session_id` later (Stage 4).
Deliverables:
1. `sessionId` + `displayId` on observe/act end-to-end (executor workspace routing kept; gateway no longer force-foregrounds observe/act; MCP stubs accept/forward `session_id` + `display_id`).
2. Virtual display flags: `TRUSTED|OWN_DISPLAY_GROUP|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED|DESTROY_CONTENT_ON_REMOVAL|OWN_CONTENT_ONLY|PRESENTATION`. `PUBLIC` omitted (ColorOS escape / incompatible with `OWN_DISPLAY_GROUP`). Fail closed if `OWN_DISPLAY_GROUP` cannot be resolved. Launch via `am start --display`; inject via `input -d`. Never inject on display 0 for a named workspace.
3. Types/APIs designed for N≥2 sessions. Product still hot-gates to 1 concurrent background Ask task (`WorkspaceTasks`). Do not claim 20 concurrent VDs.
4. Fast Path settle/fingerprint/nav isolation preserved. Take control / Continue / GATE preserved. Handoff to display 0 is intentional human take-over, not a silent fallback.
5. No silent fallback to display 0 for a named workspace. Unknown session rejected. Display mismatch rejected. Cross-session observation cannot authorize an action.
6. Tests + `docs/V4_STAGE2_SESSION_KERNEL.md` + version identity **4.0.0-alpha.2** / versionCode 68. Physical Pixel 8 = UNVERIFIED.
Acceptance: unit tests for display-scoped inject, flags, no cross-session action, gateway/MCP forwarding, N≥2 types, `ExecutionRequestScope` no display-0 rewrite; honest UNVERIFIED for physical; PR open.
Out of scope: Skill compiler (S3), Cyclone One tiles/installer (S4), Magisk, requiring MCP `session_id` (Stage 4), claiming 20 concurrent VDs.
Handoff: Stage 3 Skill Compiler is DONE in the follow-on PR; compile playbooks per package **and** per session/display. Do not restart Stage 2.

### Stage 3 — Skill Compiler (DONE — PR #61)
Branch: `grok/cyclone-v4-s3-skills` from Stage 2.
Goal: Learn NL playbooks per package like Sanna, then compile stable Fast Path paths into deterministic PhoneToolExecutor routes so ordinary UI runs skip the LLM. Vision / Fast Path LLM only on miss.
Deliverables:
1. Per-package NL playbook / hint store (`PlaybookHintStore`): persist, merge after successful Fast Path runs, user-override merge. Secrets and coordinate-only steps never write. Named workspace playbooks cannot bind display 0.
2. Compiler (`SkillRouteCompiler`): promote stable sequences (2+ successes, or a user override) with semantic selectors and SAFE tools into deterministic routes bound to `sessionId` + `displayId`.
3. Replay (`CompiledSkillReplay`): try compiled skill first; miss → Fast Path LLM/UI sub-agent. Vision only on miss (empty tree / `perceptionMode=vision_escalate`). Unchanged is not a second click.
4. Existing `automation.skill.SkillCompiler` (MCP draft capsules / `phone_skill_save`) is unchanged. Stage 3 is `com.cyclone.mobile.skills` runtime learn→compile→replay.
5. Fast Path settle/fingerprint/nav isolation and Session Kernel + 3.9.12 Take control/GATE preserved. `PhoneToolExecutor` remains the only mutation engine.
6. Tests (`PlaybookHintStoreTest`, `SkillRouteCompilerTest`, `CompiledSkillReplayTest`, `SkillRuntimeTest`) + `docs/V4_STAGE3_SKILL_COMPILER.md` + version identity **4.0.0-alpha.3** / versionCode 69. Physical Pixel 8 = UNVERIFIED.
Acceptance: unit tests for store/compile/replay/miss-escalate; honest UNVERIFIED for physical; PR open.
Out of scope: Cyclone One tiles/installer (S4), Magisk, 20 hot LLM agents, requiring MCP `session_id` (Stage 4).
Handoff: Stage 4 Cyclone One glass. MCP may require `session_id`; compiled skills already carry session/display. Do not start Stage 4 in this PR.

### Stage 4 — Cyclone One V4 glass (DONE — PR #62)
Branch: `grok/cyclone-v4-s4-one` from Stage 3.
Goal: Cyclone One is glass/MCP, not a second brain. Phone owns Fast Path / Session Kernel / Skill Compiler.
Deliverables:
1. Port One 0.2.1 JPEG live view + human/AI handoff into the V4 PC companion / gateway path (JPEG/adb-screenshot first; Give control to AI / Take control; locked phone not stolen).
2. Session fabric: `session.added` / `session.removed`; tiles bound to `session_id` (+ `displayId` when present). Named workspace never rewritten to display 0.
3. MCP requires `session_id` on observe/act and related UI tools; `SESSION_REQUIRED` / `SESSION_DISPLAY_MISMATCH`; forward `display_id` when provided.
4. PC remains glass — no second PhoneToolExecutor / Fast Path on Windows.
5. Preserve mobile Fast Path + Session Kernel + Skill Compiler + 3.9.12 Take control/GATE.
6. Tests + `docs/V4_STAGE4_ONE_GLASS.md` + version identity **4.0.0-alpha.4** / versionCode 70. Physical Pixel 8 = UNVERIFIED.
Acceptance: unit/contract tests for JPEG-first, handoff, session tiles/events, MCP session_id requirement; honest UNVERIFIED for physical; PR open.
Out of scope: `v4.0.0` / One 1.0 (Stage 5), Magisk, 20 concurrent VLMs, rewriting Stage 1–3.
Handoff: Stage 5 V4 release lane. Do not start Stage 5 in this PR.

### Stage 5 — V4 release lane (DONE — THIS PR)
Branch: `grok/cyclone-v4-s5-release` from Stage 4 tip `4ad2667`.
Goal: Cut the V4 Session OS packaging identity — mobile **4.0.0** / versionCode **71** paired with Cyclone One **1.0.0** — without rewriting Stages 1–4.
Deliverables:
1. Identity: mobile **4.0.0** / versionCode **71**; Cyclone One **1.0.0**; gateway/MCP **4.0.0**. Drop `-alpha.N`.
2. Release notes `docs/RELEASE_V4.md` (Session OS story, upgrade notes, physical checklist). Handoff `docs/V4_STAGE5_RELEASE.md` (merge order + operator cut path). Do not duplicate the notes in this plan.
3. Physical Pixel 8 checklist remains **UNVERIFIED** (USB / Shizuku / JPEG live / take-control / skill replay). CI green is not device evidence.
4. CI/tag path: after stack merge, push `release/cyclone-mobile-v4.0.0` so Mobile CI fires; wait for `.github/workflows/mobile-ci.yml` success; dispatch `.github/workflows/mobile-release.yml` with that run id + artifact name; dispatch `.github/workflows/pc-companion-release.yml` for One 1.0.0 NSIS; dry-run `python scripts/ci/cut_v4_release.py`; create GitHub Release `v4.0.0` only when CI is green. Never force-push; never replace an existing `v4.0.0`. Do not claim the GitHub tag already exists.
5. `publication_authorized` stays **false** until a signed artifact and physical evidence exist.
Acceptance: identity coherent; notes + handoff present; physical UNVERIFIED stated honestly; operator cut path documented; tag not claimed in this PR.
Out of scope: Magisk, 20 concurrent VLMs, inventing a physical pass, rewriting Stages 1–4, merging other PRs.

## Coordination rule
Each Grok session reads `artifacts/V4_BUILD_PLAN.md` + previous stage PR. Parent orchestrator only starts next stage after PR exists.

## Orchestrator coordination (user 2026-09-07)
- Check progress about **every 30 minutes**; advance **one stage at a time** through to a **V4 release**.
- **Stage 5** is this PR (V4 release lane). Do not restart Stages 1-4. Stage 5 Grok MUST use **subagents**. After merge + `v4.0.0` cut, the 30m V4 push routine can retire. Do not claim the GitHub tag already exists.
