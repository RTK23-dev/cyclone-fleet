# Cyclone One 1.1.1 — Settings MCP tunnel

Cyclone One **`1.1.1`** (Windows glass / `Setup.exe`). Device gateway and MCP adapters remain **`4.1.0`**. Mobile on this tree stays **`4.0.4`** / versionCode **75**. This cut does not ship a new APK.

Patch on published **`one-1.1.0`**. Merge: PR **#86** (`feat(one): Settings terminal for ChatGPT/Grok MCP tunnel`) into `release/cyclone-one-v1.1.0`.

Physical Pixel 8: **UNVERIFIED**. `publication_authorized=false`. CI green is not device evidence.

## What shipped

Settings card **Remote MCP (ChatGPT / Grok chat)** starts and stops the bundled HTTPS auth-gateway + cloudflared pack already in One.

- Pack: `apps/pc-companion/src-tauri/resources/mcp-tunnel/`
- Install: `%LOCALAPPDATA%\Cyclone One\mcp-tunnel\`
- Controls: Start / Stop / Restart / Smoke / Copy URL / Copy token (full token to clipboard only; UI shows last-4) / Rotate / Readonly vs Full
- Local Grok Build / Cursor stdio (`~/.grok/config.toml`, Cursor `mcp.json`) is unchanged
- Default mode remains **readonly**. Full mode is still bearer-gated and exposes mutating tools to the token holder

Architecture:

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

Quick tunnels mint a **new hostname on every Start/Restart**. Re-copy the URL into ChatGPT / grok.com after restart.

## Pairing

Same as One **1.1.0**:

- One **1.1.1** pairs with mobile **≥ 4.0.4**
- Full Layer 2 MCP still needs a phone that speaks Layer 2 (published **4.0.4**)
- This tree does not ship a new mobile APK
- Uninstall leftover Cyclone PC Companion **3.8.1**. Prefer `%LOCALAPPDATA%\Cyclone One`

## Upgrade from One 1.1.0

| Surface | From | To |
| --- | --- | --- |
| Cyclone One (Windows glass) | **1.1.0** | **1.1.1** |
| Device gateway / MCP packages | **4.1.0** | still **4.1.0** |
| Mobile APK | **4.0.4** / versionCode **75** | still **4.0.4** / **75** |

Install the One **1.1.1** `Setup.exe` from this cut over 1.1.0. Session Contract Glass from 1.1.0 is unchanged.

## Physical Pixel 8 checklist — UNVERIFIED

No item below was verified on a physical Pixel 8 for this cut. Leave every box unchecked until a human runs it on hardware.

- [ ] Pixel 8 USB connected — **UNVERIFIED**
- [ ] Settings → Remote MCP → Start tunnel → Running — **UNVERIFIED**
- [ ] Smoke: `/health` 200, `/mcp` without auth 401, initialize with bearer 200 — **UNVERIFIED**
- [ ] ChatGPT / grok.com connector attach with copied URL + bearer — **UNVERIFIED**
- [ ] Stop tunnel; loopback health down; `~/.grok/config.toml` unchanged — **UNVERIFIED**
- [ ] One 1.1.0 Session Contract / default-foreground browse still works — **UNVERIFIED**

## Limits / non-goals

- No Magisk, auto-root, or a second mutation engine on Windows
- Secrets (passwords, OTPs, API keys, payment data, raw typed secret values, full MCP bearer) are not persisted in Brain, diagnostics, or doctor output
- The public URL is not a live video feed; ChatGPT / Grok chat see tools only
- `publication_authorized=false` until a signed exact-source CI artifact exists
- Never replace GitHub tag / release `one-1.1.0`

## How this is published

Cut through existing `pc-companion-release.yml` on `release/cyclone-one-v1.1.0` after PR **#86** merged. Tag **`one-1.1.1`**. Attach `Cyclone-PC-Companion-1.1.1-Setup.exe` plus the candidate zip, `SHA256SUMS.txt`, and `release-provenance.json` when present. Do not dispatch `mobile-ci.yml` / `mobile-release.yml`. Do not overwrite `one-1.1.0`.
