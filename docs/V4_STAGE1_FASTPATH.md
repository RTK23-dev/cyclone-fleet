# Cyclone V4 Stage 1 — Fast Path harness

**Identity:** mobile `4.0.0-alpha.1` / versionCode `67`  
**Base:** published `v3.9.12` (versionCode 66) Background Intelligence  
**Physical Pixel 8:** **UNVERIFIED** — this alpha is unit/CI only. Do not treat a green build as device evidence.

Stage 1 copies ClosePaw loop economics onto the existing Cyclone product: one `PhoneToolExecutor`, 3.9.12 workspace / Take control / GATE, and the constrained gateway/MCP surface. It does **not** add Magisk, multi-AI, 20 virtual displays, a skill compiler, or a Cyclone One installer rewrite.

## Why

Frontier-model navigation was slow and flaky versus ClosePaw on weaker models because the harness over-verified, observed vaguely, and multi-checked. Fast Path makes ordinary navigation mid-model capable:

1. Structured a11y is the primary observation.
2. One screen-changing act per decision turn.
3. Local fingerprint settle is tap authority.
4. Unchanged is a warning, not a second click.

## Control loop

```text
perceive (a11y Page Card + elementIndex)
→ planner landing when possible (open_app / intent)
→ one screen-changing act, or a same-page form batch
→ settle 300ms
→ fingerprint
→ if Unchanged: wait +500ms, re-observe; then +1000ms
→ if still Unchanged: verified=false warning; do not click again
```

Task completion still uses Goal Contracts and semantic witnesses. Fast Path fingerprint change means **the tap landed**, not **the user goal is done**. Learning still requires a semantically PASSED page transition.

## A11y-first observation

- Page Cards carry stable 1-based `elementIndex` for the current observation (same lifetime as `elementId`).
- `perceptionMode=a11y` when at least one indexed interactive control exists.
- `perceptionMode=vision_escalate` when the tree is empty or a custom canvas (nodes without actable controls).
- Screenshot/vision is an escalate, never the primary loop.

## Nav isolation

- One screen-changing mutation per agent decision turn (`click` / `open_app` / `launch_intent` / `back` / `home` / `scroll` / …).
- `phone.type` / `phone.replace_text` may batch on the same page **before** the navigating act.
- Later screen-changing actions in the same model response are dropped by `FastPathNavIsolation`.

## Planner vs UI-subagent surface

Existing tools, split by role. No second mutation engine.

| ClosePaw name | Cyclone |
| --- | --- |
| `open_app` | `phone.open_app` / `phone_act(tool=phone.open_app)` |
| `intent` | `phone.launch_intent` |
| `wait` | `phone.wait_for` |
| `finish` | model `status=done` (not a phone mutation) |
| `get_tree` | `phone_observe` / `phone_locate` Page Card |
| index click / type | `phone_act` with current `elementId` or `elementIndex` |
| swipe | `phone.scroll` `direction=forward\|backward` (`phone.swipe` has no safe MCP route) |

MCP tool annotations include `cycloneSurface=planner|ui|shared` and `cycloneFastPath=true`.

`phone.click` keeps the One 0.2.x soft-success lesson: Android may accept the click (`performed=true`) while Fast Path reports `verified=false` / `UNCHANGED`. That is not permission to dispatch a coordinate tap or a second accessibility click.

## Intent / open_app before icon hunting

`FastPathLanding` resolves website goals to `phone.launch_intent` and named-app goals to `phone.open_app`. The named-app match is the same signal 3.9.12 Ask→workspace already uses (`OverlayChromeRuntime.submitRequest`). Fast Path does not replace workspace routing, Take control, Continue, or GATE confirm.

## What this stage does not change

- Phone remains authority for pay / send / delete / GATE.
- One Shizuku workspace, Take control / Continue, exact-session progress.
- Gateway stays loopback-only.
- PC companion / Cyclone One packaging (Stage 4).
- Skill compiler / App Learner promotion (Stage 3).
- Multi-session kernel (Stage 2).

## Version scheme

Mobile identity is **`4.0.0-alpha.1`** with `versionCode` **67**. PC companion, device-gateway, and MCP package versions remain `3.8.4`. `publication_authorized=false`. This is an alpha line on top of 3.9.12, not a published v4 APK.

## Tests

Covered by JVM unit tests (no physical device):

- `FastPathLoopTest` — 300ms settle, 500/1000 ladder, Unchanged warning, no second click
- `FastPathTreeTest` — stable indices, vision escalate when tree useless
- `FastPathNavIsolationTest` — form batch + one nav; later nav dropped
- `FastPathLandingTest` — open_app / launch_intent before icon hunting
- `FastPathSurfaceTest` — planner vs UI split
- `PageAgentProtocolTest` — parse-time isolation
- MCP compact / protocol / tools — `elementIndex` act path and surface annotations

## Stage 2 handoff

Stage 1 remains **DONE**. Session Kernel (`docs/V4_STAGE2_SESSION_KERNEL.md`, mobile `4.0.0-alpha.2` / versionCode 68) takes this Fast Path loop as the per-turn act/settle contract and adds display-scoped `sessionId` + `displayId`. Do not regress settle / fingerprint / nav isolation, Take control / Continue, or GATE.

Do not start Stage 3 in the Session Kernel PR.
