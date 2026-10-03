# Cyclone V4 Stage 3 — Skill Compiler

**Identity:** mobile `4.0.0-alpha.3` / versionCode `69`  
**Base:** Stage 2 Session Kernel `4.0.0-alpha.2` (versionCode 68) on Stage 1 Fast Path + published `v3.9.12` (versionCode 66) Background Intelligence  
**Physical Pixel 8:** **UNVERIFIED** — this alpha is unit/CI only. Do not treat a green build as device evidence.

Stage 3 compiles stable Fast Path paths into deterministic `PhoneToolExecutor` routes on the existing Cyclone product: one mutation engine, 3.9.12 workspace / Take control / GATE, Stage 1 Fast Path settle, and Stage 2 display-scoped `sessionId` + `displayId`. It does **not** add Magisk, Cyclone One tiles/installer, or 20 concurrent virtual displays / hot LLM agents.

Existing `com.cyclone.mobile.automation.skill.SkillCompiler` (MCP `phone_skill_save` draft capsules into AutomationStore) is **unchanged**. Stage 3 is the `com.cyclone.mobile.skills` runtime: learn → compile → replay.

## Why

Sanna learns NL playbooks per package but still pays an LLM every UI run. Cyclone's wedge is to learn like Sanna, then compile stable paths into deterministic PhoneToolExecutor routes so many sessions stay cheap:

1. Successful Fast Path runs leave a per-package NL playbook.
2. Stable sequences (semantic selectors, SAFE tools, 2+ successes) compile once.
3. Replay hits skip the model. Fast Path LLM / UI sub-agent is the miss path.
4. Vision is only on miss (empty tree / `perceptionMode=vision_escalate`).

## Control loop (compiled skill first, Fast Path on miss)

```text
perceive (a11y Page Card + elementIndex) on sessionId/displayId
→ try compiled skill replay (goal + pageKey + sessionId + displayId)
→ on hit: PhoneToolExecutor steps, Fast Path settle per hop; Unchanged is not a second click
→ on miss: Fast Path LLM / UI sub-agent (planner landing, one screen-changing act)
→ settle 300ms → fingerprint → if Unchanged: wait +500ms, re-observe; then +1000ms
→ if still Unchanged: verified=false warning; do not click again
→ vision only when tree empty or perceptionMode=vision_escalate
```

A compiled-skill hit is not task completion. Goal Contracts and semantic witnesses still decide whether the user goal is done. Learning still requires a semantically PASSED page transition.

Default-foreground remains display 0 (`sessionId=default-foreground`). A named workspace skill never binds or injects on display 0.

## What shipped

1. **Per-package NL playbook / hint store.** `PlaybookHintStore` persists playbooks keyed by package, goal signature, start `pageKey`, `sessionId`, `displayId`, and step-selector fingerprints. Successful Fast Path runs merge (`recordSuccess` increments `successCount`). A user override (`mergeUserOverride`) replaces the matching playbook and is preferred at compile time. Secrets are stripped before write. Coordinate-only steps are rejected. Named workspace playbooks cannot bind display 0.

2. **Compiler promotes stable sequences.** `SkillRouteCompiler` compiles only when the same selector sequence has succeeded at least twice (or is a user override), every step uses a semantic selector and a SAFE tool (`phone.click` / `open_app` / `launch_intent` / `scroll` / `back` / `wait_for` / `type` / `replace_text` / `home`), params contain no secrets, the goal is not approval-sensitive, and Stage 2 session/display binding is legal. Output is a `CompiledSkillRoute` that still mutates only through `PhoneToolExecutor`.

3. **Replay tries compiled skill first.** `CompiledSkillReplay.tryReplay` matches goal + package + start page + `sessionId` + `displayId`. Hit runs the compiled steps through `PhoneToolPort` (production: `PhoneToolExecutor`). Miss (`NO_MATCH`, page/selector/after-state mismatch, GATE, policy, cross-session, display mismatch) escalates to Fast Path LLM / UI sub-agent. Vision only on miss: empty tree or `perceptionMode=vision_escalate`. Unchanged after Fast Path settle is a miss, not a second click.

4. **MCP draft capsules stay on the old compiler.** Overlay DONE and MCP `phone_skill_save` still call `automation.skill.SkillCompiler.compile` into AutomationStore as disabled drafts. Stage 3 does not replace that path and does not write a second JSON brain.

5. **Fast Path + Session Kernel + 3.9.12 human control preserved.** Settle / fingerprint / nav isolation remain the per-turn contract. Take control / Continue / GATE remain. Named workspace skills carry `sessionId` + `displayId` and never fall back to display 0.

6. **`PhoneToolExecutor` remains the only mutation engine.** Compiled steps are ordinary phone tools with semantic selectors. No coordinate injector, no second executor, no Magisk/root shell.

## Fail-closed compile and replay

| Condition | Result |
| --- | --- |
| Playbook with fewer than 2 verified steps | not stored / not compiled |
| Fast Path successes below 2 (and not a user override) | not compiled |
| Non-semantic or coordinate-only selector | rejected before write / compile |
| Tool outside the SAFE set | rejected |
| Secret key or secret-shaped value in params | stripped; compile rejects if still unsafe |
| Approval-sensitive goal (pay / send / delete / auth) | not stored / not compiled |
| Named workspace playbook or route with display 0 | rejected (`DISPLAY_ZERO_WORKSPACE`) |
| Replay session ≠ compiled `sessionId` | miss → Fast Path LLM (`CROSS_SESSION`) |
| Replay display ≠ compiled `displayId` | miss → Fast Path LLM (`DISPLAY_MISMATCH`) |
| Start page / before-page / after-page mismatch | miss → Fast Path LLM |
| Selector not on the current page | miss → Fast Path LLM (vision if tree unusable) |
| Unchanged after a page-changing compiled step | miss → Fast Path LLM; do not click again |
| GATE / policy deny on a compiled step | miss → Fast Path LLM |
| Empty tree or `perceptionMode=vision_escalate` | miss → vision (not a compiled-skill inject) |

## OEM limits (honest)

- Android 15+ and Shizuku remain required for background workspace (existing 3.9.12 rule).
- ColorOS / OxygenOS may still ignore `OWN_DISPLAY_GROUP` or steal focus. Fail closed rather than acting on display 0.
- Virtual display + Accessibility `windowsOnAllDisplays` is OEM-dependent. An empty tree on a workspace is `perceptionMode=vision_escalate`, not permission to inject on the human display or to force a compiled skill.
- Physical Pixel 8 is **UNVERIFIED** this stage. CI green is not device evidence.
- Do not claim 20 concurrent compiled-skill sessions or 20 hot LLM agents. The product still gates to one hot background Ask task.

## What this stage does not change

- Phone remains authority for pay / send / delete / GATE.
- One hot Shizuku workspace in the product (`WorkspaceTasks`), Take control / Continue, exact-session progress.
- Fast Path 300ms settle + fingerprint ladder; Unchanged is not a second click.
- Session Kernel fail-closed identity (unknown session, display mismatch, no silent display-0 fallback).
- Gateway stays loopback-only.
- `automation.skill.SkillCompiler` MCP draft capsules (`phone_skill_save` / `phone_skill_run`).
- PC companion / Cyclone One packaging (Stage 4). MCP `session_id` is accepted/forwarded, not required.
- Magisk, root, or a second mutation engine.

## Version scheme

Mobile identity is **`4.0.0-alpha.3`** with `versionCode` **69**. PC companion, device-gateway, and MCP package versions remain `3.8.4`. `publication_authorized=false`. This is an alpha line on top of 3.9.12 + Fast Path + Session Kernel, not a published v4 APK.

## Tests

Intended JVM unit tests (no physical device):

- `PlaybookHintStoreTest` — persist; merge after successful Fast Path runs; user-override merge preferred; secrets/coordinate-only rejected; named workspace cannot bind display 0
- `SkillRouteCompilerTest` — 2+ successes + semantic SAFE tools compile; unsafe/secret/approval-sensitive/display-0 workspace rejected; match is package + goal + start page + session/display
- `CompiledSkillReplayTest` — hit runs PhoneToolExecutor steps; miss escalates to Fast Path LLM; Unchanged is not a second click; vision only on empty tree / `vision_escalate`; cross-session and display mismatch rejected
- `SkillRuntimeTest` — learn → compile → replay wiring; compiled skill tried before Fast Path LLM; GATE/policy miss does not bypass Android policy

Physical Pixel 8 remains **UNVERIFIED**.

## Stage 4 handoff

Stage 3 remains **DONE**. Cyclone One glass (`docs/V4_STAGE4_ONE_GLASS.md`, mobile `4.0.0-alpha.4` / versionCode 70) is DONE in the follow-on PR: One 0.2.1 JPEG/handoff, session.added/removed tiles, MCP requires `session_id`. Compiled skills already carry session/display; do not assume display 0. Vision only on miss. Keep `PhoneToolExecutor` as the only mutation engine.

Do not restart Stage 3.
