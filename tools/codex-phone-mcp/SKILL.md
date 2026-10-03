# Cyclone Phone MCP: four-tool default loop

Default PC AI surface (V3.7):

```
phone_status → phone_locate(goal) → phone_act → phone_skill_save | phone_skill_run
```

Everything else on this server is advanced. Do not invent a fifth default tool.

1. `phone_status` — gateway, ADB, bridge, Accessibility readiness. Use its sessions inventory when present.
2. `phone_locate(goal)` — bounded Page Card (`pageText` + `pageSummary` must survive) plus goal-ranked hits.
   If a **verified** skill matches `goal` + `pageKey`, `matchedSkill.skipModel` is true: call `phone_skill_run` instead of debating the screen. A draft match never sets skipModel. The Page Card is always returned.
3. `phone_act` — one typed mutation. HTTP 200 is not success. Read `ok`, `pageChanged`, `before`, `after.pageCard`, `delta`, `errorClass`, `generation`. Ordinary taps use Fast Path fingerprint settle (300ms, then +500/+1000). UNCHANGED is `verified=false` — do not click again.
4. `phone_skill_save` / `phone_skill_run` — the existing AutomationStore / `SkillCompiler.compile` draft path. No second JSON brain.

## Fast Path (V4 Stage 1)

Planner vs UI split on the existing tools (no second executor):

| ClosePaw name | Cyclone surface |
| --- | --- |
| open_app / intent / wait / finish | `phone_act(tool=phone.open_app, params.package=com.android.chrome)` / `phone.wait_for` / model `done` |
| get_tree | `phone_observe` / `phone_locate` Page Card (`elementIndex`) |
| index click / type / swipe | `phone_act` with current `elementId` or `elementIndex`; swipe → `phone.scroll` |

Prefer `phone.open_app` with `params.package` only (Android package id, e.g. `com.android.chrome`) or an allowlisted intent before hunting a launcher icon. Do not send an app display name or `packageName`. Browse/open Chrome is `phone_act` `tool=phone.open_app` `params.package=com.android.chrome`; it does not use OpenRouter. 3.9.12 Ask→workspace already routes a uniquely named installed app. One screen-changing mutation per decision turn; form fills may batch. Screenshot only when `perceptionMode=vision_escalate`.

## Session identity (required)

`session_id` is required on `phone_observe`, `phone_locate`, `phone_act`, `phone_ui_search`, `phone_inspect_element`, `phone_screenshot`, `phone_skill_run`, and `phone_group_act`. Do not omit it and do not invent `default-foreground` silently.

- `session_id=default-foreground` is the live human display (`display_id` 0, or omit display).
- Named workspace sessions require `session_id` **and** `display_id > 0`. Display 0 or a missing display fails closed.
- `sessionId` / `executionContext.sessionId` aliases are accepted. Conflicting aliases fail closed.
- `phone_status` / `phone_devices` / `phone_skill_save` do not require `session_id`.

## Locate, then act

Use `phone_locate` before every phone mutation.

1. Pick a current `elementId` or `elementIndex` from the Page Card or semantic search result.
2. Pass that ID/index to `phone_act`; do **not** create text, fuzzy, bounds, or coordinate selectors.
3. Treat every `elementId`/`elementIndex` as observation-scoped. They expire after a mutation — never reuse them.
4. Prefer the verified route, matched skill, or Fast Path landing (`open_app` / intent) returned by Cyclone.
5. If Page Card context is truncated, search and inspect before using screenshots. Vision is an escalate when the a11y tree is useless.

## Skills

- `phone_skill_save` compiles **only** when 2+ steps are verified. It writes `status=draft` (disabled for review) into the same AutomationStore Agent 3 uses. Unverified steps do not write. Secret slots are stripped, never persisted.
- `phone_skill_run` runs only `status=verified`. A draft without `dryRun=true` is denied. Each step returns an act envelope. Execution goes through PhoneToolExecutor via the gateway, not a side executor.
- Workers cannot flip `draft → verified`. Policy (pay/send/delete/grant) stays on the phone.

## Forbidden

The server exposes no shell, ADB, root, PowerShell, or generic command execution.
Do not dump raw accessibility trees. Do not put plaintext passwords in locate or skill payloads.
