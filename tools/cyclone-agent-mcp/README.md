# Cyclone Agent MCP

Generic official-SDK MCP server for Cyclone Desktop V1. The same `cyclone-phone` tool surface is used by Codex, OpenCode, Copilot CLI, and generic MCP harnesses.

- Primary transport: STDIO.
- Backend: authenticated loopback Cyclone PC Device Gateway only.
- Multi-device: `phone_list`; all phone-scoped tools accept optional `device_id`; omission is allowed only with exactly one READY device.
- `session_id` is required on observe/act/locate/search/inspect/screenshot/skill_run/group_act. Pass `default-foreground` for the live human display (display 0). Named workspace sessions require `display_id > 0`. Read session ids from `phone_status` when the gateway returns a sessions inventory.
- Layer 2 `phone_workspace` (`list`/`register`/`switch`/`pause`/`release`/`arm`/`next`) is default-foreground / display 0 only. After `switch`/`next`, mutating `phone_act.params` must include matching `workspaceId` and `workspaceGeneration`. Missing or stale generation fails closed (`MUTATE_LOCK`). Layer 2 is not a named VD session.
- Browse/open Chrome is `phone_act` with `tool=phone.open_app` and `params.package=com.android.chrome`. `phone.open_app` uses `params.package` only; do not send an app display name or `packageName`. This path does not use OpenRouter.
- No shell, PowerShell, arbitrary command execution, arbitrary ADB, root, `su`, subprocess, or script-evaluation tools.
- No gateway/model API secrets are written to generated MCP configs or audit logs.

Use `cyclone-agent-mcp --help` for connector and profile commands.
