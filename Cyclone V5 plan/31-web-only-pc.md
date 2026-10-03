# 31 — Web-only PC: `cyclone` and Glass (alpha.47)

**Status:** built in **alpha.47** (2026-09-27). The Windows package is built and smoke-tested on a Windows CI runner;
the owner's own PC (the one-liner, removing Cyclone One, pairing carried over) is still owed.

**The owner's asks:**
- "Make installing Cyclone Glass on the PC truly easy. Launch it by typing `cyclone` in the command prompt, like
  Claude or Grok. A small card on screen showing how to run and stop a session."
- "We want to migrate entirely to using the web Glass only."
- Keep from the desktop app: **Codex / AI connections**, **Remote MCP tunnel**, **ChatGPT Attach (VMOS)**. Drop the
  desktop Vault, Automations and Fleet pages (the phone and Glass already cover them).

---

## 1. The shape

| Before (Cyclone One 1.6) | After (Cyclone PC 2.0, web-only) |
|---|---|
| Tauri desktop window + NSIS installer | **No desktop window.** Glass (web) opens in its own Chrome/Edge app window |
| Runtime runs while the window is open | Runtime runs while the `cyclone` terminal is open |
| Installed from a `Setup.exe` | **One line** in PowerShell, per user, no admin |
| PC frozen at 1.6.0-alpha.43 | The PC runtime ships with **every** fast-lane release; `cyclone` updates itself |
| Tunnel, Attach, connectors in Rust | The same features in the Python gateway, shown as Glass pages |

**Install:**
```
irm https://github.com/premiumcentraal-boop/Cyclone/releases/download/<tag>/install.ps1 | iex
```
- Finds the newest release that has a `Cyclone-PC-<version>.zip`, and checks its SHA-256 against that release's
  `release-manifest.json`.
- Stops Cyclone's own processes. If Cyclone One (Tauri) is installed, runs its uninstaller silently: data and
  pairing are kept.
- Unpacks into `%LOCALAPPDATA%\Cyclone One`. It is the same folder as before, so Codex and Cursor MCP configs, the
  bundled adb and the saved gateway token keep working unchanged.
- Runs `CyclonePCRuntime.exe install-cli`: `cyclone.cmd` in `…\Cyclone One\bin`, added to the **user** PATH, and a
  one-time copy of the old window's runtime data (`%LOCALAPPDATA%\com.cyclone.pccompanion\runtime`) when the new one
  has none.
- Ends with the run/stop card.

**Update:** `cyclone` checks for a newer `Cyclone-PC` zip (at most every 6 hours; never blocks when offline). It asks,
downloads, verifies it against the manifest, then exits with code 10. The shim runs the bundled `install.ps1 -Zip …`,
which replaces the files and starts `cyclone` again. Glass is inside the runtime zip, so Glass updates arrive the
same way. `CYCLONE_GLASS_DIST` stays as a developer override.

## 2. The run/stop card

**In the terminal**, under the logo:
```
  ╭─ Cyclone is running ─────────────────────────────╮
  │  Glass    open in its own window                  │
  │  Stop     close this window, or press Ctrl+C      │
  │  Again    open a terminal and type  cyclone       │
  │  Keep this window open while Codex or ChatGPT     │
  │  uses your phone.                                 │
  ╰───────────────────────────────────────────────────╯
```

**In Glass:**
- The same words, on a small card the first time Glass opens.
- It has an X. Once closed it stays closed (remembered in `sessionStorage` plus the gateway's own flag, never
  `localStorage`).
- **Start and stop** in the sidebar shows it again.

## 3. Features moved into the gateway and Glass

All routes use the gateway's loopback bearer, like every other route. None of them takes a free-form command.

| Feature | Gateway routes (`pc/`) | Glass |
|---|---|---|
| **AI connections**: Codex, Cursor, Grok, OpenCode, Copilot, generic MCP | Already there since alpha.35: `GET /v1/pc/connections`, `POST /v1/pc/connections/{host}/connect` (fixed host list) | **Marketplace → This PC** |
| **Remote MCP tunnel** (ChatGPT, Grok chat) | `GET /v1/pc/tunnel`, `POST …/start` (`mode` readonly/full), `…/stop`, `…/restart`, `…/rotate`, `…/mode`, `…/smoke`, `GET …/token`, `GET …/docs`. Runs the same PowerShell pack as before | **Remote MCP** page |
| **ChatGPT Attach** (VMOS) | `GET/PUT /v1/pc/attach/fleet` (secrets DPAPI-protected, never returned), `POST …/sync`, `POST …/handoff` and `…/handoff/check`, `GET …/resources`, `GET …/share`, `POST …/share/start`, `POST …/share/stop` (the cloudflared HTTPS share) | **ChatGPT Attach** page |

**Secrets:**
- VMOS AccessKeys and SSH Connect Keys are typed into password fields and sent once to the gateway.
- The gateway stores them with DPAPI (Windows) and never returns them: only `hasKey` flags come back.
- Glass clears the field after saving and keeps no copy.
- Sync output is redacted; a handoff containing a key is refused.
- The tunnel bearer is shown only when the owner presses **Show token** (the same as before), and is never logged.

Copying goes through the browser clipboard; the gateway never writes the clipboard.

## 4. Build and release

- **`v5-publish.yml` gets a Windows job:**
  - builds `CyclonePCRuntime.exe` and `CycloneAgentMCP.exe` with PyInstaller, Glass inside the runtime;
  - stages the bundled adb, cloudflared, and the tunnel and Attach packs in the install layout;
  - zips it as `Cyclone-PC-<product>.zip`;
  - attaches it with `install.ps1`, and adds its SHA-256 to `release-manifest.json`.
- **No Tauri build.** The `apps/pc-companion` source stays in git for reference, but is no longer built or shipped.
- **Versions:** `pc_companion` becomes `2.0.0-alpha.47` (the web-only PC). The runtime reports the product version,
  so `cyclone` compares it with release tags.
- **`CycloneLivePhone.exe` is dropped:** Glass's Phone page shows the live screen through the gateway.

## 5. Proof

- **Gateway tests:**
  - every `/v1/pc/*` route needs the bearer;
  - the connector host list is fixed;
  - fleet secrets never come back;
  - handoffs with a key are refused;
  - tunnel mode is validated;
  - zip and manifest verification;
  - runtime data migration only when empty;
  - the `cyclone.cmd` update branch;
  - the terminal card.
- **Glass tests:** the pages, and the card's once-only behaviour. The Glass guard keeps secrets out of storage.
- **CI guard:** no Tauri build in the publisher; install and update verify SHA-256; the `pc` routes are all
  authenticated.
- **Windows (owner):** UNVERIFIED. The one-liner, the uninstall of Cyclone One, the PATH, `cyclone` and an update
  have not run on a real PC.

## 6. Honest limits

- **The runtime only runs while a `cyclone` terminal is open.** Codex MCP and the ChatGPT tunnel need that window
  open (the card says so). A tray or background service is a later step, if wanted.
- **Unsigned exes.** Windows Defender or SmartScreen may warn on first run until a code-signing certificate is added
  (the signing step already runs when credentials exist).
- **Rollback:** Cyclone One 1.6.0-alpha.43's installer stays downloadable from its release.

## 7. Roadmap

Parallel sessions moves to alpha.48; Drive to alpha.49–50.
