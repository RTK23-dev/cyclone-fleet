# Cloud AI Remote MCP setup

Use **Cyclone One -> Connections -> Remote MCP**. The normal setup is intentionally three steps; you should not need PowerShell or the technical docs below.

## Quick setup

1. In Connections, choose **View only** or **Control phone**, then press **Start secure connection**.
2. In your cloud AI's custom Remote MCP / connector screen, add:
   - Name: `Cyclone Phone`
   - MCP URL: press **Copy MCP URL** in Cyclone.
   - Authentication: **Bearer token**.
   - Token: press **Copy token** in Cyclone and paste it only into the authentication field.
3. Back in Cyclone, press **Copy agent prompt** and paste that prompt into a new AI chat/task after the connector is attached.

Quick tunnels mint a **new hostname every Start/Restart**. Re-copy the URL after a restart.

The bearer token is a secret. Do not paste it into normal chat messages, prompts, screenshots, logs, issue reports, or diagnostics.

## ChatGPT

1. Open ChatGPT settings and enable the option that allows custom/remote MCP apps if your plan/workspace supports it.
2. Create a custom Remote MCP app named `Cyclone Phone`.
3. Paste the MCP URL from Cyclone One.
4. Choose Bearer authentication and paste the token from **Copy token**.
5. Attach/enable the app for the chat, then paste the **Cyclone agent prompt**.

ChatGPT cannot connect to `localhost` on your PC; use Cyclone's generated HTTPS URL.

## Grok

1. Open Grok connectors.
2. Add a custom connector using the same Cyclone MCP URL and bearer token.
3. Attach the connector to the chat/task, then paste the **Cyclone agent prompt**.

Do not use the public Remote MCP URL in local Grok Build or Cursor configuration. Local MCP clients continue to use `CycloneAgentMCP.exe serve` over stdio.

## Access levels

- **View only** is the default. The cloud AI can inspect the phone with observation tools but cannot mutate it.
- **Control phone** exposes mutating tools such as `phone_act` to a client holding the bearer token. Only enable it for AI accounts/connectors you trust.

For the live human screen, Cyclone MCP tools use `session_id=default-foreground` (display 0). The universal agent prompt teaches this contract automatically.

## Advanced diagnostics

Connections -> Remote MCP -> **Advanced · diagnostics and security** contains:

- Check connection / smoke test
- Restart
- Rotate bearer token
- Stop Remote MCP
- Health URL
- Open technical docs

Expected smoke behavior is `/health` 200, `/mcp` without authorization 401, and MCP initialize with the bearer 200.
