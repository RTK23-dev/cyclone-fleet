# Cyclone One 1.1.2 — Connections + Remote MCP quick setup

Cyclone One **`1.1.2`** (Windows glass / `Setup.exe`). Device Gateway and MCP adapters remain **`4.1.0`**. Mobile remains **`4.0.4`** / versionCode **75**. This cut does not ship a new APK.

Patch on published **`one-1.1.1`**. The goal is to make cloud-AI phone access understandable without reading MCP documentation.

Physical Pixel 8: **UNVERIFIED**. `publication_authorized=false` until the exact-source release candidate is green and hardware acceptance is performed.

## What changed

### Connections is now the AI setup home

The **Connections** tab now separates two concepts that were previously easy to confuse:

- **Remote MCP / cloud AI** — ChatGPT, Grok, and other cloud AI clients that support Remote MCP.
- **Local AI apps** — Codex and other local MCP clients running on the same PC.

The normal Remote MCP path is now three steps:

1. **Start Remote MCP** and choose **View only** or **Control phone**.
2. **Add Cyclone to the cloud AI** using the generated MCP URL and bearer token.
3. **Copy the universal Cyclone agent prompt** into the AI chat/task so the agent immediately knows how to discover, observe, navigate, act, and verify the phone.

Technical controls that normal users should not need — health URL, smoke test, restart, rotate token, stop, and connector docs — are grouped under **Advanced · diagnostics and security**.

### Universal cloud-agent prompt

The new prompt teaches any compatible cloud AI to:

- discover ready phones before acting;
- use `session_id="default-foreground"` for the live human display;
- observe before acting;
- prefer `phone_locate`, `phone_ui_search`, and `phone_inspect_element` over stale/guessed coordinates;
- use `phone_act` only when control tools are available;
- re-observe and verify after meaningful actions;
- explain clearly when Remote MCP is in View-only mode instead of pretending a write happened;
- avoid echoing bearer tokens, passwords, OTPs, payment data, or other secrets.

The connector setup text deliberately does **not** embed the bearer token. The token must be copied separately and pasted only into the cloud AI connector's Bearer/Authorization field.

## Security model unchanged

Remote MCP still uses the bundled architecture introduced in One 1.1.1:

```text
Cloud AI --HTTPS--> cloudflared
                      |
                      v
               127.0.0.1:8787 auth gateway
                 | Authorization: Bearer
                 | readonly or full
                 v
             CycloneAgentMCP.exe serve
                      |
                      v
                  paired phone
```

- Default access remains **View only** (`readonly`).
- **Control phone** maps to full mode and exposes mutating phone tools to the bearer holder.
- The gateway remains loopback-only on Windows; cloud access is through the HTTPS tunnel.
- Quick-tunnel hostnames change after Start/Restart, so the UI tells users to copy the new URL.
- Local Codex/stdio configuration is unchanged.

## Tests added

`cloud-agent.test.mjs` checks that the universal agent handoff includes the foreground-session contract, observation/navigation/control tools, verification behavior, and View-only fallback. It also checks that generated connector instructions use Bearer authentication without inventing or embedding a secret.

## Pairing / compatibility

- Cyclone One **1.1.2** pairs with mobile **>= 4.0.4**.
- Device Gateway / MCP remain **4.1.0**.
- Mobile remains **4.0.4** / versionCode **75**.
- No new Android package is part of this release.

## Physical-device checklist — UNVERIFIED

- [ ] Pixel 8 USB connected and paired — **UNVERIFIED**
- [ ] Connections -> Start secure connection -> Running — **UNVERIFIED**
- [ ] View only exposes observation tools — **UNVERIFIED**
- [ ] Control phone exposes mutation tools after confirmation — **UNVERIFIED**
- [ ] Copy MCP URL + Copy token works — **UNVERIFIED**
- [ ] ChatGPT custom Remote MCP attaches and can observe `default-foreground` — **UNVERIFIED**
- [ ] Universal agent prompt causes observe -> locate -> act -> verify behavior — **UNVERIFIED**
- [ ] Restart produces a new public URL and old URL is no longer presented — **UNVERIFIED**
- [ ] Local Codex one-click connection still works — **UNVERIFIED**

## Release procedure

Build the exact-source Windows candidate through existing `.github/workflows/pc-companion-release.yml` on `release/cyclone-one-v1.1.2`. Do not dispatch mobile release workflows for this One-only patch. Publish tag **`one-1.1.2`** only from the verified candidate source SHA and attach the generated Setup.exe, candidate archive/sidecars, checksums, and provenance files. Do not overwrite `one-1.1.1`.
