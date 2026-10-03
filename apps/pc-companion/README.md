> **Retired (plan 31, alpha.47).** Cyclone for Windows is now web-only: install it with one line in PowerShell, type
> `cyclone`, and Glass opens in its own browser window. Remote MCP and ChatGPT Attach moved into Glass; AI connections
> are in Glass → Marketplace. This desktop window is no longer built; the source stays here for reference. See
> `Cyclone V5 plan/31-web-only-pc.md` and the release notes for the install line.

# Cyclone One

Tauri 2 + TypeScript desktop glass for controlling one or many Cyclone phones.

Installs per-user to `%LOCALAPPDATA%\Cyclone One`. If leftover **Cyclone PC Companion 3.8.x** sits beside One, uninstall it — the two products confuse MCP path resolution. `doctor` reports this. MCP and Cursor attach through the persisted gateway bearer, not process-environment scrape.

After installing an update, close any still-open window and launch **Cyclone One** again. Confirm the expected release version is visible at the bottom of the sidebar before pairing. Install the matching Cyclone Mobile release, keep the phone unlocked when starting live view, and use **Settings → PC Gateway & QR pairing → Scan PC QR** or the secure four-letter code. Cyclone may wake the display but deliberately cannot bypass Android's lock screen.

The **AI connections** page provides one-click Codex setup. It configures the packaged Cyclone MCP server without copying a Gateway token, reports live Gateway/phone/tool readiness, and recovers a long-running Codex session after One rotates its protected local connection. Restart Codex once after the first connection.

**Settings → Remote MCP (ChatGPT / Grok chat)** starts and stops the public HTTPS auth-gateway tunnel used by ChatGPT and grok.com connectors. Copy the MCP URL and bearer from that card. Local Grok Build / Cursor stdio MCP (`~/.grok/config.toml`) is not changed. See [`docs/A4_MCP_TUNNEL_SETTINGS.md`](../../docs/A4_MCP_TUNNEL_SETTINGS.md).

The **ChatGPT Attach** tab syncs VMOS Cloud pads over ADB and exports a one-file Custom GPT handoff. **Share to ChatGPT** publishes Cloud Control (`/cloud`) over HTTPS so Plus Actions can reach this PC. SSH Connect Keys stay on this PC. See [`docs/VMOS_ARCHITECTURE.md`](../../docs/VMOS_ARCHITECTURE.md).

## The `cyclone` terminal command

Installing Cyclone One also installs a `cyclone` command for your Windows user (`%LOCALAPPDATA%\Cyclone One\bin` is
added to your user PATH). Open a **new** terminal and type:

| Command | What it does |
|---|---|
| `cyclone` | Checks for a newer Cyclone release (at most every 6 hours, never blocks offline), offers to update, then opens Glass in its own window. Close the terminal and Glass closes with it. |
| `cyclone update` | Downloads the newest release installer, checks it against the release's `SHA256SUMS.txt`, installs it silently and opens Glass. |
| `cyclone --no-update` | Open Glass straight away. |
| `cyclone --browser` | Open Glass in your normal browser instead of its own window. |
| `cyclone version` | Show the installed Cyclone version. |

When Cyclone One is running, `cyclone` uses its runtime; otherwise it starts a runtime just for that terminal. Glass
still opens through a one-time launch link. The Glass window uses its own Chrome/Edge profile
(`%LOCALAPPDATA%\Cyclone One\glass-window`). Updating closes Cyclone One while the installer runs.

## Development

```bash
npm install
npm run dev
```

Use mock fleets without a backend:

```text
http://localhost:1420/?mock=1
http://localhost:1420/?mock=2
http://localhost:1420/?mock=4
http://localhost:1420/?mock=8
http://localhost:1420/?mock=12
```

Run focused frontend logic tests:

```bash
npm test
```

Start the Tauri development shell after Rust/Tauri prerequisites are installed:

```bash
npm run tauri dev
```

This package intentionally contains no ADB implementation, pairing cryptography, Device Gateway backend implementation, or installer publishing logic. It does own the user-facing one-click connector that asks the packaged `CycloneAgentMCP.exe` sidecar to update and verify Codex's shared MCP configuration. Gateway credentials remain outside the UI and Codex configuration.
