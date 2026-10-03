# Cyclone V4 Stage 4 — Cyclone One glass

**Identity:** mobile `4.0.0-alpha.4` / versionCode `70`; companion / gateway / MCP `4.0.0-alpha.4`  
**Base:** Stage 3 Skill Compiler `4.0.0-alpha.3` (versionCode 69) on Stage 2 Session Kernel + Stage 1 Fast Path + published `v3.9.12` (versionCode 66) Background Intelligence  
**Physical Pixel 8:** **UNVERIFIED** — this alpha is unit/CI only. Do not treat a green build as device evidence.

Stage 4 pulls Cyclone One 0.2.1 JPEG live view + human/AI handoff onto the V4 stack and makes Cyclone One **glass/MCP**, not a second brain. Phone still owns Fast Path, Session Kernel, Skill Compiler, and `PhoneToolExecutor`. The PC shows sessions, streams JPEG live, hands control, and routes MCP with an exact `session_id` (`display_id` when applicable).

This stage does **not** cut `v4.0.0` / One 1.0, Magisk, or 20 concurrent VLMs.

## Why

Stages 1–3 made ordinary navigation cheap and display-scoped, then compiled stable paths. Without glass that binds to those sessions, MCP can still omit identity and a PC live view can still stall on an H.264 handshake while the phone is already JPEG/screenshot. One 0.2.1 already shipped JPEG-first live + handoff on a 3.9.x companion branch; Stage 4 ports that onto V4 and requires `session_id` at MCP.

## Control loop (phone authority, PC glass)

```text
Companion tiles bind session_id (+ displayId when present)
→ JPEG live (~2 fps adb-screenshot) on the focused phone
→ Human owns input on open (take_human)
→ Give control to AI / request_ai_control=true yields; locked phone is never stolen
→ MCP: phone_status → phone_locate(goal, session_id) → phone_act(session_id)
→ Phone Fast Path settle / compiled-skill replay / GATE still run on Android
→ Unchanged is not a second click; transport success is not task success
```

Default-foreground remains display 0 (`session_id=default-foreground`). A named workspace never silently rewrites to display 0. Take control / Continue that moves a task onto display 0 is intentional human take-over.

## What shipped

1. **One 0.2.1 JPEG live + handoff on the V4 companion.** Physical focus is JPEG/adb-screenshot first (~2 fps). The producer does not send a provisional `video/avc` handshake. Operator mouse stays available while the preview is CONNECTING / RECONNECTING / STREAM_ERROR on a READY phone. Overlay codes stay specific (`STREAM_INIT_TIMEOUT`, `FRAME_DECODE_FAILED`, `WEBSOCKET_ERROR`); `FRAME_RENDER_ERROR` is only the unknown fallback. Opening focused live takes HUMAN ownership. **Give control to AI** / `request_ai_control=true` yields so MCP can mutate; **Take control** reclaims the mouse. A locked/asleep phone is `PHONE_LOCKED`.

2. **Session fabric tiles.** Companion Active Tasks / session tiles bind to `session_id` (and `displayId` when present). Fleet websocket `/v1/fleet/events` emits `session.added` / `session.removed`. `default-foreground` is the executable human display; named workspaces are inventory (product still hot-gates 1 background Ask task). Named workspace tiles never fall back to display 0. Exact-session snapshots refuse foreground-substituted frames (`X-Cyclone-Foreground-Substitution: false`).

3. **MCP `session_id` is required** on observe/act and related UI tools: `phone_observe`, `phone_locate`, `phone_act`, `phone_ui_search`, `phone_inspect_element`, `phone_screenshot`, `phone_skill_run`, `phone_group_act`. Missing/blank identity is `SESSION_REQUIRED` (not a silent default-foreground). Named workspace without `display_id`, or `display_id` 0, is `SESSION_DISPLAY_MISMATCH`. `phone_status` / `phone_devices` / `phone_capabilities` stay unscoped inventory; `phone_status` forwards a gateway sessions list when present.

4. **Android session lifecycle over the existing Session Kernel.** `session.list/start/status/pause/continue/resume/handoff/stop/snapshot` are trusted Gateway ops. `session.list` is also legacy-read-only. `WorkspaceRuntime.create` still fail-closes if Android does not allocate an isolated display `> 0`. PC gateway sends `session.continue` (not `session.resume`) because the loopback allowlist rejects ops whose names contain shell-like tokens (`su` in `resume`).

5. **PC remains glass.** No second `PhoneToolExecutor`, Fast Path, or navigator on Windows. Mutations still execute on the phone.

6. **Stage 1–3 preserved.** Fast Path settle/fingerprint/nav isolation, Session Kernel no display-0 rewrite, Skill Compiler learn→compile→replay, 3.9.12 Take control / Continue / GATE.

## Fail-closed identity (Stage 4 additions)

| Condition | Result |
| --- | --- |
| MCP observe/act without `session_id` | `SESSION_REQUIRED` |
| Named workspace MCP call without `display_id` | `SESSION_DISPLAY_MISMATCH` |
| Named workspace `display_id` 0 | `SESSION_DISPLAY_MISMATCH` / rejected |
| Conflicting `session_id` / `sessionId` aliases | `SESSION_REQUIRED` |
| Companion owns input, MCP mutates without yield | `HUMAN_HAS_CONTROL` |
| `request_ai_control` while phone locked/asleep | `PHONE_LOCKED` (not stolen) |
| Workspace start without isolated display | `BACKGROUND_MODE_UNAVAILABLE` |
| Exact-session snapshot substituted from display 0 | refused (`X-Cyclone-Foreground-Substitution` must be `false`) |

## OEM limits (honest)

- Android 15+ and Shizuku remain required for background workspace (existing 3.9.12 rule).
- ColorOS / OxygenOS may still ignore `OWN_DISPLAY_GROUP` or steal focus. Fail closed rather than acting on display 0.
- JPEG live is sampled screenshot (~2 fps), not 20 concurrent H.264 workspaces.
- Physical Pixel 8 live view + MCP handoff is **UNVERIFIED** this stage. CI green is not device evidence.
- Do not claim 20 concurrent tiles or 20 hot LLM agents. Extra session tiles are inventory; the product still gates to one hot background Ask task.

## What this stage does not change

- Phone remains authority for pay / send / delete / GATE.
- One hot Shizuku workspace in the product (`WorkspaceTasks`), Take control / Continue, exact-session progress.
- Fast Path 300ms settle + fingerprint ladder; Unchanged is not a second click.
- Compiled skills still bind `sessionId` + `displayId`; vision only on miss.
- Gateway stays loopback-only.
- Magisk, root, or a second mutation engine.

## Version scheme

Mobile identity is **`4.0.0-alpha.4`** with `versionCode` **70**. PC companion (Cyclone One window), device-gateway, and MCP package versions are **`4.0.0-alpha.4`**. `publication_authorized=false`. This is an alpha line on top of 3.9.12 + Stages 1–3, not a published v4 APK or One 1.0 installer.

## Tests

Intended unit/contract tests (no physical device):

- Companion: operator input while reconnecting; 20s/15s timeouts; specific overlay codes; JPEG/`img` without provisional `video/avc`; session tile bind to `session_id`; `session.added` / `session.removed`; named workspace not rewritten to display 0; `yield_ai` / `take_human`
- Gateway: JPEG-first producer; `HUMAN_HAS_CONTROL` / `PHONE_LOCKED`; session REST list/start/stop; `session.added` / `session.removed`; Stage 2 `test_execution_session.py` no-regression
- MCP: missing `session_id` → `SESSION_REQUIRED`; `default-foreground` + display 0; workspace requires `display_id` > 0; aliases; cross-session element ids still stale
- Android: `GatewaySessionAdapterTest` list includes default-foreground; start fail-closed without isolated display; `GatewaySessionBindingTest` preserved

Physical Pixel 8 remains **UNVERIFIED**.

## Stage 5 handoff

Stage 4 remains **DONE**. Do not restart Stage 4. Stage 5 is DONE in the follow-on PR (this one): mobile `4.0.0` / versionCode 71 paired with Cyclone One `1.0.0`, CI tags, release notes, physical checklist (`docs/V4_STAGE5_RELEASE.md`). Do not claim physical evidence from CI. Do not claim the GitHub tag already exists.
