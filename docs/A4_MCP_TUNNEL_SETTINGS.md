# A4 — Cyclone One Settings: MCP tunnel control terminal

**Status:** implemented in Cyclone One Settings. Local Grok Build / Cursor stdio MCP is unchanged.

## What shipped

The existing MCP HTTPS auth-gateway tunnel (cloudflared → `127.0.0.1:8787` bearer gateway → `CycloneAgentMCP.exe serve`) is compiled into Cyclone One as an easy control terminal on **Settings**.

- Bundled pack: `apps/pc-companion/src-tauri/resources/mcp-tunnel/`
- Installed at runtime to `%LOCALAPPDATA%\Cyclone One\mcp-tunnel\`
- Settings card: **Remote MCP (ChatGPT / Grok chat)**
- Tauri commands spawn the bundled start/stop/status/smoke/rotate scripts (no extra PowerShell hunting)

Architecture (unchanged):

```
ChatGPT / Grok chat  --HTTPS-->  cloudflared
                                      |
                                      v
                             127.0.0.1:8787  auth gateway
                               |  Authorization: Bearer required
                               |  GATEWAY_MODE=readonly (default)
                               v
                         CycloneAgentMCP.exe serve   (stdio)
```

`~/.grok/config.toml` and Cursor `mcp.json` stay on local stdio. Settings controls the **tunnel path only**.

## How to Start from Settings

1. Open **Cyclone One → Settings**.
2. Use the **Remote MCP (ChatGPT / Grok chat)** card.
3. Leave mode on **Readonly** (Phase 1). Full mode shows a warning and still requires the bearer.
4. Click **Start tunnel**. Status should become **Running**.
5. **Copy** the MCP URL (`https://…trycloudflare.com/mcp`) and **Copy token**.
6. Click **Smoke**. Expect 401 without auth and 200 initialize with the bearer.
7. Paste URL + bearer into ChatGPT Developer Mode (Remote MCP) and [grok.com/connectors](https://grok.com/connectors).
8. **Stop tunnel** when finished.

Quick tunnels mint a **new hostname on every Start/Restart**. Re-copy the URL after restart. Upgrade to a named Cloudflare tunnel when you can complete `cloudflared tunnel login`.

## Operator note for ChatGPT / Grok connector packs

Use Settings for the live URL and token. Do not keep using `mcp-tunnel-v1\artifacts\A1_PUBLIC_URL.txt` as the daily path.

- ChatGPT: Settings → Apps / Connectors → Remote MCP. Auth = Bearer. Never “No authentication”.
- grok.com: same URL + bearer. xAI rejects localhost.
- Do **not** paste the public URL into `~/.grok/config.toml`.

Short checklist is on the Settings card and in `%LOCALAPPDATA%\Cyclone One\mcp-tunnel\docs\CONNECTOR_SETUP.md`.

## Smoke

Automated (this change):

- Node gateway unit smoke in `apps/pc-companion/tests/mcp-tunnel-gateway.test.mjs`: health 200, POST `/mcp` without auth → **401**, initialize with bearer → **200**, `phone_act` blocked in readonly, non-loopback bind refused.
- Frontend unit tests for last-4, URL derivation, fail-closed status, existing Settings cards still present.

Live Agent-PC smoke (2026-09-08), using the same scripts Settings will spawn:

- Start → **Running** at `https://screening-fleet-parks-marketplace.trycloudflare.com/mcp` (quick tunnel; hostname will change next Start)
- Local smoke **PASSED**: `/health` 200, `/mcp` without auth **401**, initialize with bearer **200**, `phone_act` blocked in readonly
- Public `/health` 200; public `/mcp` without auth **401**
- Stop → loopback health down
- `~/.grok/config.toml` hash unchanged; no public URL written there

Token last-4 only in status (`38cc`). Full bearer was never printed.

## Residual risks

- Quick tunnel hostname **churn** on every Start/Restart — connectors must be updated.
- Tunnel dies if the PC sleeps or Node/cloudflared is missing.
- `GATEWAY_MODE=full` exposes mutating phone tools to whoever has the bearer. Default remains readonly.
- The public URL is not a live video feed; ChatGPT/Grok chat see tools only.
- Physical Pixel 8 attach through the tunnel remains **UNVERIFIED** until an operator evidence run.
