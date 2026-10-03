# Cyclone V4 Stage 2 — Session Kernel

**Identity:** mobile `4.0.0-alpha.2` / versionCode `68`  
**Base:** Stage 1 Fast Path `4.0.0-alpha.1` (versionCode 67) on published `v3.9.12` (versionCode 66) Background Intelligence  
**Physical Pixel 8:** **UNVERIFIED** — this alpha is unit/CI only. Do not treat a green build as device evidence.

Stage 2 hardens display-scoped session identity on the existing Cyclone product: one `PhoneToolExecutor`, 3.9.12 workspace / Take control / GATE, Stage 1 Fast Path settle, and the constrained gateway/MCP surface. It does **not** add Magisk, a skill compiler, Cyclone One tiles/installer, or 20 concurrent virtual displays.

## Why

Fast Path is the per-turn act/settle contract. Without display-scoped session identity, an observe on one surface can authorize an act on another. Stage 2 binds every workspace observe/act to `sessionId` + `displayId` so Fast Path never crosses displays, and so Cyclone One / MCP can bind work to `session_id` later (Stage 4).

3.9.12 already has one Shizuku background workspace plus Take control / Continue. Stage 2 does not replace that workspace; it makes its display affinity fail-closed. Pattern:

- launch with `am start --display` (`launchDisplayId`)
- inject with `input -d` (`InputEvent.setDisplayId`)
- create virtual displays as `TRUSTED | OWN_DISPLAY_GROUP` (not `PUBLIC`)

ColorOS OEM escape is real if `PUBLIC` virtual displays join the default DisplayGroup. Public VDs can steal or share the human display; Stage 2 omits `PUBLIC` and fails closed rather than acting on display 0.

## Control loop (unchanged Fast Path, now display-scoped)

```text
perceive (a11y Page Card + elementIndex) on sessionId/displayId
→ planner landing when possible (open_app / intent)
→ one screen-changing act, or a same-page form batch, on that same display
→ settle 300ms
→ fingerprint
→ if Unchanged: wait +500ms, re-observe; then +1000ms
→ if still Unchanged: verified=false warning; do not click again
```

Default-foreground remains display 0 (`sessionId=default-foreground`). A named workspace session never falls back to display 0. Take control / Continue that moves a task onto display 0 is intentional human take-over, not a silent inject fallback.

## What shipped

1. **`sessionId` + `displayId` on observe/act end-to-end.** `PhoneToolExecutor` already routed workspace tools through `ExecutionRequestScope` / `WorkspaceRuntime`. Stage 2 keeps that path and extends it across the constrained PC surface: gateway observe/act no longer force-foreground the human display; MCP stubs accept and forward `session_id` + `display_id`. Transport success is still not task success.

2. **Virtual display creation prefers isolation flags, not PUBLIC.** Workspace VDs use `TRUSTED | OWN_DISPLAY_GROUP | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED | DESTROY_CONTENT_ON_REMOVAL | OWN_CONTENT_ONLY | PRESENTATION`. `PUBLIC` is omitted because it is incompatible with `OWN_DISPLAY_GROUP` and is the ColorOS escape path (PUBLIC VDs can join the default DisplayGroup). Creation fails closed if `OWN_DISPLAY_GROUP` cannot be resolved. Launch via `am start --display` (`launchDisplayId`). Inject via `input -d` (`setDisplayId`). Never inject on display 0 for a named workspace.

3. **Types/APIs designed for N≥2 sessions.** `ExecutionSessionStore`, observation stores, and request scope can hold more than one display-scoped session. The product still hot-gates to **1 concurrent background Ask task** (`WorkspaceTasks`). Do not claim 20 concurrent VDs, and do not treat type capacity as device scale-out.

4. **Fast Path + 3.9.12 human control preserved.** Settle / fingerprint / nav isolation remain the per-turn contract. Take control / Continue / GATE remain. Handoff that moves a task to display 0 is intentional human take-over, not a silent fallback.

5. **No silent fallback to display 0 for a named workspace session.** Unknown session rejected. Display mismatch rejected. Cross-session observation cannot authorize an action. `ExecutionRequestScope` does not rewrite a workspace identity onto display 0.

## Fail-closed identity

| Condition | Result |
| --- | --- |
| Missing/blank session on a foreground call | `default-foreground` / display 0 (legacy) |
| Named workspace without `displayId` | rejected |
| Known session, wrong `displayId` | rejected |
| Unknown `sessionId` | rejected |
| Observation from session A used to act on session B | rejected |
| Workspace inject with display 0 | rejected |
| `OWN_DISPLAY_GROUP` cannot be resolved | VD create fails closed |
| Take control / Continue moves the task to display 0 | intentional human handoff; agent inject still cannot target display 0 for that workspace |

## OEM limits (honest)

- Android 15+ and Shizuku remain required for background workspace (existing 3.9.12 rule).
- ColorOS / OxygenOS may still ignore `OWN_DISPLAY_GROUP` or steal focus. Fail closed rather than acting on display 0.
- Virtual display + Accessibility `windowsOnAllDisplays` is OEM-dependent. An empty tree on a workspace is `perceptionMode=vision_escalate`, not permission to inject on the human display.
- Physical Pixel 8 is **UNVERIFIED** this stage. CI green is not device evidence.
- Do not claim multi-VD scale-out works on device. N≥2 is a type/API design; the product still gates to one hot background Ask task.

## What this stage does not change

- Phone remains authority for pay / send / delete / GATE.
- One hot Shizuku workspace in the product (`WorkspaceTasks`), Take control / Continue, exact-session progress.
- Fast Path 300ms settle + fingerprint ladder; Unchanged is not a second click.
- Gateway stays loopback-only.
- PC companion / Cyclone One packaging (Stage 4). MCP `session_id` is accepted/forwarded, not required.
- Skill compiler / App Learner promotion (Stage 3).
- Magisk, root, or a second mutation engine.

## Version scheme

Mobile identity is **`4.0.0-alpha.2`** with `versionCode` **68**. PC companion, device-gateway, and MCP package versions remain `3.8.4`. `publication_authorized=false`. This is an alpha line on top of 3.9.12 + Stage 1, not a published v4 APK.

## Tests

Intended JVM unit tests (no physical device):

- `WorkspaceSafetyTest` — display-scoped inject (`input -d`); workspace input on display 0 / negative rejected
- `WorkspaceDisplayPolicyTest` — `TRUSTED|OWN_DISPLAY_GROUP|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED|DESTROY_CONTENT_ON_REMOVAL|OWN_CONTENT_ONLY|PRESENTATION`; `PUBLIC` omitted; fail closed if `OWN_DISPLAY_GROUP` cannot be resolved
- `GatewaySessionBindingTest` — unknown session / display mismatch rejected; cross-session observation cannot authorize an action; N≥2 types with product hot-gate 1
- `V4FoundationTest` — N≥2 session types coexist; observations/frames stay session-local; owned display 0 rejected
- `ExecutionRequestScopeTest` — named workspace requires `displayId`; no silent rewrite to display 0; conflicting envelope/params rejected
- MCP `test_session.py` / gateway `test_execution_session.py` — `session_id` + `display_id` accepted and forwarded; observe/act not force-foregrounded onto display 0

Physical Pixel 8 remains **UNVERIFIED**.

## Stage 3 handoff

Stage 2 remains **DONE**. Do not restart Stage 2. Skill Compiler (`docs/V4_STAGE3_SKILL_COMPILER.md`, mobile `4.0.0-alpha.3` / versionCode 69) is DONE in the follow-on PR: playbooks compile per package **and** per session/display. Do not assume display 0. Vision only on miss. Keep `PhoneToolExecutor` as the only mutation engine.

Stage 4 may require MCP `session_id`; Stage 2 only accepts/forwards it. Compiled skills already carry session/display.
