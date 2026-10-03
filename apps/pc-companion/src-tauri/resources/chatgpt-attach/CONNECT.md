# ChatGPT Attach - stable connection

Three steps:

1. Open Cyclone One -> ChatGPT Attach. Add a VMOS pad (host/port/Connect Key stay on this PC) and click **Sync fleet**.
2. Click **Share to ChatGPT**. Cyclone publishes Cloud Control over HTTPS and copies a handoff only after a real Cloud AI session exists.
3. Paste the handoff into your Custom GPT. The Action schema sends the handoff's short-lived `SESSION_TOKEN` as `X-Cyclone-Session-Token` on protected calls.

## One-time Custom GPT setup

- Click **Copy instructions** and use them as the GPT's driver instructions.
- With Share running, click **Copy OpenAPI** and paste that schema into Actions.
- Set the GPT Action **Authentication** setting to **None**. Cyclone still authenticates every protected call using the required `X-Cyclone-Session-Token` schema parameter.
- Do not store SSH Connect Keys, VMOS AccessKeys, the local Cyclone gateway bearer, or a short-lived `SESSION_TOKEN` in the GPT editor.

This avoids having to edit the GPT's configured API key every time **Sync fleet** mints a fresh short-lived session. The current session credential comes from the attach block you paste into chat.

## CONTROL_API

- Local only: `http://127.0.0.1:<gateway-port>/cloud`
- ChatGPT Actions: `https://<trycloudflare-host>/cloud` from **Share to ChatGPT**, or a stable HTTPS CONTROL_API you operate
- Health (no auth): `GET {CONTROL_API}/v1/health`
- Status/observe/mutations: `X-Cyclone-Session-Token: SESSION_TOKEN`
- Non-GPT Cyclone clients may continue to use `Authorization: Bearer SESSION_TOKEN`.

VMOS is ADB transport only. Mutations run through Cyclone Mobile `PhoneToolExecutor`. Never paste SSH Connect Keys or VMOS AccessKeys into ChatGPT.

Loopback URLs cannot be used as Custom GPT Action servers. Always Share to ChatGPT (or configure a stable public HTTPS CONTROL_API) before copying the handoff/OpenAPI schema.
