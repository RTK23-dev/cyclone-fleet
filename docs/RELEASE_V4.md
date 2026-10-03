# Cyclone 4.0.0 — Session OS

Mobile **`4.0.0`** (Android versionCode **71**, package `com.cyclone.mobile`) paired with Cyclone One **`1.0.0`**. Device gateway and MCP adapters are **`4.0.0`** on the same cut.

Base: published mobile **`v3.9.12`** (`c9ed77a`, versionCode 66) Background Intelligence. Stacked Stages 1–4 on that line:

| Stage | PR | Branch / tip |
| --- | --- | --- |
| 1 Fast Path | #59 | `grok/cyclone-v4-s1-fastpath` |
| 2 Session Kernel | #60 | `grok/cyclone-v4-s2-session` |
| 3 Skill Compiler | #61 | `grok/cyclone-v4-s3-skills` |
| 4 One glass | #62 | `grok/cyclone-v4-s4-one` tip `4ad2667` |

Physical Pixel 8: **UNVERIFIED**. `publication_authorized=false`. This document is release notes for the V4 cut, not a claim that tag `v4.0.0` or a GitHub Release already exists.

## Session OS

V4 is Cyclone as a **Session OS**: reliable, low-token phone control that can scale toward many sessions without running a VLM per tap.

The phone remains authority. `PhoneToolExecutor` is the only mutation engine. Ordinary navigation uses accessibility Page Cards and a local fingerprint settle. Every workspace observe/act is bound to `sessionId` + `displayId`. Stable Fast Path sequences compile into deterministic skills so replay can skip the model. Cyclone One is glass: JPEG live view, human/AI handoff, session tiles, and MCP routed with an exact `session_id`. The PC does not run a second Fast Path, skill compiler, or `PhoneToolExecutor`.

3.9.12 Background Intelligence is retained: one hot Shizuku workspace, Take control / Continue, GATE confirm, exact-session progress. Types and APIs are designed for N≥2 sessions. The product still hot-gates **one** concurrent background Ask task (`WorkspaceTasks`).

Control loop:

```text
compiled skill replay (goal + pageKey + sessionId + displayId)
→ miss: planner landing (open_app / intent) or Fast Path LLM / UI sub-agent
→ a11y Page Card (elementIndex)
→ one screen-changing phone tool (or same-page form batch)
→ settle 300ms → fingerprint → if Unchanged: +500ms, then +1000ms
→ still Unchanged: verified=false; do not click again
→ Goal Contracts / semantic witnesses decide task completion
```

Transport success is not task success. Vision is an escalate when the tree is empty or `perceptionMode=vision_escalate`, not the primary loop.

## What shipped

### Fast Path (Stage 1, PR #59)

- Primary observation is a structured a11y Page Card with stable 1-based `elementIndex` (same lifetime as `elementId`).
- Planner vs UI split on the existing tools: `open_app` / `launch_intent` / wait / finish vs get-tree / index click-type-scroll. No second mutation engine.
- Intent / `phone.open_app` landing before icon hunting. Named-app match is the same signal 3.9.12 Ask→workspace already uses.
- Ordinary taps: settle 300ms, then fingerprint ladder +500 / +1000. Unchanged is a `verified=false` warning, not a second click or coordinate fallback.
- One screen-changing mutation per agent decision turn; same-page form fills may batch. Later nav in the same model response is dropped.

### Session Kernel (Stage 2, PR #60)

- `sessionId` + `displayId` on observe/act end-to-end. Gateway no longer force-foregrounds observe/act onto the human display.
- Default-foreground remains display 0 (`sessionId=default-foreground`). A named workspace never silently rewrites to display 0.
- Virtual displays: `TRUSTED | OWN_DISPLAY_GROUP | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED | DESTROY_CONTENT_ON_REMOVAL | OWN_CONTENT_ONLY | PRESENTATION`. `PUBLIC` omitted (ColorOS escape / incompatible with `OWN_DISPLAY_GROUP`). Fail closed if `OWN_DISPLAY_GROUP` cannot be resolved.
- Launch via `am start --display`; inject via `input -d`. Never inject on display 0 for a named workspace.
- Unknown session, display mismatch, and cross-session observation cannot authorize an action.
- N≥2 session types exist. Product still hot-gates 1 concurrent background Ask task.

### Skill Compiler (Stage 3, PR #61)

- `PlaybookHintStore` learns per-package NL playbooks after successful Fast Path runs. User-override merge is preferred at compile time.
- Secrets and coordinate-only steps never persist. Approval-sensitive goals (pay / send / delete / auth) are not compiled. Named workspace playbooks cannot bind display 0.
- `SkillRouteCompiler` promotes stable sequences (2+ successes, or a user override) with semantic selectors and SAFE tools into `CompiledSkillRoute` bound to `sessionId` + `displayId`.
- `CompiledSkillReplay` tries the compiled skill first. Hit runs `PhoneToolExecutor` steps with Fast Path settle per hop. Miss (no match, page/selector/after-state, GATE/policy, cross-session, display mismatch, Unchanged) escalates to Fast Path LLM / UI sub-agent. Vision only on miss.
- Existing MCP `phone_skill_save` / `automation.skill.SkillCompiler` draft capsules are unchanged. Stage 3 is the `com.cyclone.mobile.skills` runtime.

### Cyclone One glass (Stage 4, PR #62)

- One 0.2.1 JPEG live + human/AI handoff is the V4 companion path. Physical focus is JPEG / `adb-screenshot` first (~2 fps). No provisional `video/avc` handshake.
- Opening focused live takes HUMAN ownership. **Give control to AI** / `request_ai_control=true` yields so MCP can mutate. **Take control** reclaims the mouse. Locked/asleep phone is `PHONE_LOCKED` (not stolen). Companion-owned input without yield is `HUMAN_HAS_CONTROL`.
- Session tiles bind to `session_id` (+ `displayId` when present). Fleet events: `session.added` / `session.removed`. Named workspace tiles never fall back to display 0. Exact-session snapshots refuse foreground-substituted frames.
- MCP requires `session_id` on observe/act and related UI tools (`phone_observe`, `phone_locate`, `phone_act`, `phone_ui_search`, `phone_inspect_element`, `phone_screenshot`, `phone_skill_run`, `phone_group_act`). Missing/blank identity is `SESSION_REQUIRED` (not a silent default-foreground). Named workspace without `display_id`, or `display_id` 0, is `SESSION_DISPLAY_MISMATCH`.
- `phone_status` / `phone_devices` / `phone_capabilities` stay unscoped inventory.
- PC remains glass. Mutations still execute on the phone.

3.9.12 Take control / Continue / GATE, Fast Path settle, Session Kernel fail-closed identity, and Skill Compiler learn→compile→replay are preserved through the stack.

## Physical Pixel 8 checklist — UNVERIFIED

No item below was verified on a physical Pixel 8 for this cut. CI green is not device evidence. Leave every box unchecked until a human runs it on hardware.

- [ ] Pixel 8 USB connected — **UNVERIFIED**
- [ ] Shizuku authorized (Android 15+ required for background workspace) — **UNVERIFIED**
- [ ] Accessibility service and notification listener granted as required — **UNVERIFIED**
- [ ] Fast Path ordinary tap settle (300ms, then +500/+1000; Unchanged is not a second click) — **UNVERIFIED**
- [ ] Named workspace `sessionId` / `displayId` (no silent display-0 rewrite; inject stays on the owned display) — **UNVERIFIED**
- [ ] Take control / Continue (3.9.12 exact-task handoff to display 0 and back) — **UNVERIFIED**
- [ ] GATE confirm (one-use, 60s, action/node/fingerprint/gate-class bind; policy denials remain authoritative) — **UNVERIFIED**
- [ ] Skill compile + replay; miss escalates to Fast Path LLM (Unchanged is not a second click; vision only on empty tree / `vision_escalate`) — **UNVERIFIED**
- [ ] Cyclone One JPEG live view (~2 fps `adb-screenshot`, no H.264 handshake required) — **UNVERIFIED**
- [ ] Give control to AI / Take control; locked/asleep phone is `PHONE_LOCKED` and is not stolen — **UNVERIFIED**
- [ ] MCP `session_id` required on observe/act (`SESSION_REQUIRED` / `SESSION_DISPLAY_MISMATCH`) — **UNVERIFIED**

Background work still requires Android 15+, authorized Shizuku, Accessibility, and an app that supports isolated displays. Apps already on display 0, unsupported OEM task layouts, secure surfaces, unsupported text input and cross-app transitions fail closed or require handoff. Process death revokes execution and does not restore autonomous authority.

## Upgrade from 3.9.12 / One 0.2.1

| Surface | From | To |
| --- | --- | --- |
| Mobile APK | 3.9.12 / versionCode 66 | 4.0.0 / versionCode 71 |
| Cyclone One (Windows glass) | optional companion unchanged on the 3.9.12 tag; JPEG live + handoff lived as One **0.2.1** on a separate branch | One **1.0.0** glass on this V4 cut |
| Device gateway / MCP packages | 3.8.4 on the 3.9.12 tag (alpha line later carried `4.0.0-alpha.N`) | **4.0.0** |

Breaking / must-change:

- MCP clients must pass `session_id` on observe/act and related UI tools. Silent default-foreground is gone (`SESSION_REQUIRED`).
- Named workspace calls require `display_id` **> 0**. `display_id` 0 or missing on a named workspace is `SESSION_DISPLAY_MISMATCH`.
- Default-foreground remains `session_id=default-foreground` on display 0. Do not bind a named workspace skill or inject path to display 0.

Retained from 3.9.12:

- Take control / Continue (intentional human take-over onto display 0; not a silent inject fallback).
- GATE confirm for pay / send / delete / permission / authentication-sensitive actions.
- One hot background Ask workspace; follow-ups still require explicit start after releasing the previous workspace.
- Exact-session progress frames; no foreground screenshot fallback for a missing workspace frame.
- Phone remains authority. PC pairing is still optional for standalone Android Ask.

Install / pairing notes:

- Android `versionCode` 71 is greater than 66; in-place upgrade from 3.9.12 is the expected mobile path.
- Replace the optional 3.9.12-era companion (or the separate 0.2.1 One branch build) with the One 1.0.0 artifact from this cut. Do not mix a 0.2.1 glass with a 4.0.0 phone and expect Stage 4 MCP session rules.
- Loopback gateway only. Do not expose generic shell / root / ADB as model tools.

## Limits / non-goals

- No Magisk, root, or a second mutation engine on Windows.
- No 20 concurrent VLMs, 20 hot LLM agents, or 20 concurrent virtual-display workspaces. Extra session tiles are inventory.
- One hot background Ask task (`WorkspaceTasks`).
- JPEG live is a sampled screenshot stream (~2 fps), not an H.264 fleet or continuous provider video.
- ColorOS / OxygenOS may ignore `OWN_DISPLAY_GROUP` or steal focus. Fail closed rather than acting on display 0.
- Virtual display + Accessibility `windowsOnAllDisplays` is OEM-dependent. An empty workspace tree is `perceptionMode=vision_escalate`, not permission to inject on the human display.
- Secrets (passwords, OTPs, API keys, payment data, raw typed secret values) are not persisted in Brain, playbooks, compiled skills, or diagnostics.
- `publication_authorized=false` until a signed exact-source CI artifact exists **and** physical Pixel 8 evidence is recorded. This notes file does not flip that flag.

## How this is published

Do not treat these notes as a published GitHub Release. Tag **`v4.0.0`** only after the stack merges in order **#59 → #60 → #61 → #62**, then the Stage 5 release PR on `grok/cyclone-v4-s5-release`. Operator commands live in [`V4_STAGE5_RELEASE.md`](V4_STAGE5_RELEASE.md); dry-run `python scripts/ci/cut_v4_release.py`.

Intended cut:

1. Merge Fast Path, Session Kernel, Skill Compiler, and One glass (PRs #59–#62) without rewriting their history.
2. Land Stage 5 (this notes file + the version/CI/packaging work owned by the other Stage 5 agents).
3. Require Mobile CI (unit tests, lint, APK assembly) and repository product/security/version guards on the merged commit.
4. Create immutable tag `v4.0.0` from that commit. Pair the Android APK (`4.0.0` / versionCode 71) with the Cyclone One `1.0.0` installer artifact from the same source SHA when CI produces it.
5. Keep `publication_authorized=false` until the successful exact-source CI artifact, existing signer-continuity / protected-environment workflow, and physical Pixel 8 evidence are in place. Do not publish from a local Gradle tree.

Alpha identities on the way here (`4.0.0-alpha.1` … `alpha.4`, versionCodes 67–70) were not published v4 APKs. The release identity is **`4.0.0` / 71 + One `1.0.0`**.
