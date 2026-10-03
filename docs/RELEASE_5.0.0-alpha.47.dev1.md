# Cyclone V5 Alpha 47 — Web-only PC

Developer alpha for owner testing, built on Alpha 46 (Setup cards), which it includes.
- **Mobile:** `5.0.0-alpha.47.dev1` (version code 191). The phone app has no changes of its own.
- **Glass:** `1.0.0-alpha.27`.
- **Cyclone for Windows:** now web-only, as `Cyclone-PC-5.0.0-alpha.47.dev1.zip`.

This is plan 31. The Cyclone One desktop window is retired: on the PC, Cyclone is the runtime, Glass in your browser
and one command, `cyclone`.

## What changed

**Install with one line.** In PowerShell (no admin needed):

```
irm https://github.com/premiumcentraal-boop/Cyclone/releases/download/v5.0.0-alpha.47.dev1/install.ps1 | iex
```

What the installer does:
- **Checks the download:** it finds the newest Cyclone for Windows and refuses it unless its SHA-256 matches the
  release manifest.
- **Replaces the old window:** if Cyclone One is installed, it removes it silently. Your pairing, Remote MCP token and
  saved VMOS fleet are kept.
- **Installs for you only:** into the same folder as before (`%LOCALAPPDATA%\Cyclone One`), so Codex, Cursor and your
  other AI connections keep working.
- **Adds the command:** puts `cyclone` on your PATH and starts Cyclone.

**Run and stop it like Claude Code or Grok:**
- **Start:** open a terminal and type `cyclone`. Glass opens in its own window.
- **Stop:** close that terminal or press Ctrl+C.
- **Update:** type `cyclone update`. The download is checked against the release manifest, installed, and Cyclone
  starts again by itself.

**A small run/stop card:**
- **In the terminal:** under the Cyclone logo.
- **In Glass:** once, the first time it opens. **Start and stop** in the sidebar shows it again.

**What moved from the old window into Glass:**
- **Remote MCP** (ChatGPT and Grok chat connectors):
  - start, stop, read-only or full control;
  - the MCP address, **Show and copy token**, a new token, a test and the setup notes.
- **ChatGPT Attach** (VMOS cloud phones):
  - pads, pasted Connect commands, Sync fleet;
  - **Share to ChatGPT**, the handoff (copy or save), and the Custom GPT instructions and OpenAPI.
- **AI connections** (Codex, Cursor, Grok and others) were already in Glass → Marketplace.

**Dropped:** the desktop Vault, Automations and Fleet pages. The phone and Glass already cover them.

**Keys stay protected:**
- **VMOS AccessKeys and SSH Connect Keys:** typed into password fields, protected on this PC (Windows DPAPI) and never
  shown again; Glass only sees "Key saved".
- **Handoffs:** refused if they would contain a key.
- **The tunnel token:** appears only when you press Show.
- **Every new `/v1/pc` route:** needs Glass's private session.

**Codex and Cursor find Cyclone again.** When `cyclone` starts its own runtime, it now saves where the gateway is.
The old window used to do this.

## Validation and limits

Tests that pass:
- **Gateway** (including 16 new PC-feature tests):
  - every route needs the bearer;
  - tunnel modes are fixed;
  - keys are never returned;
  - sync output is redacted;
  - handoffs with keys are refused;
  - the HTTPS share starts and stops.
- **Terminal:** the package is verified against the manifest; the update runs through the installer; pairing is
  carried over once, never overwritten; the run/stop card.
- **Glass** (149): the new pages, the sync flow, the card once-only.
- **CI guards** (140), including the new web-only guard; the version and product guards.
- **The installer:** ran end to end under PowerShell 7 on Linux:
  - checksum verified and a tampered package refused;
  - pairing migrated;
  - secrets kept even when the old uninstaller deletes them.
- **The release build:** builds the Windows package on a Windows runner, installs it into a scratch profile, runs
  `cyclone version`, and starts the runtime to check it serves Glass and refuses `/v1/pc` without the bearer.

**Not yet seen on your PC.** The one-line install, removing Cyclone One and your pairing carrying over have not run
on your own Windows machine. Physical Pixel acceptance is also still UNVERIFIED.

Honest limits:
- **Cyclone runs while the `cyclone` window is open.** Codex MCP and the ChatGPT tunnel need that window open. A
  background mode is a later step, if you want one.
- **The Windows files are not code-signed yet.** Windows Defender or SmartScreen may ask the first time.
- **Rollback:** Cyclone PC Companion `1.6.0-alpha.43` stays downloadable from its release.

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.

Suggested checks:
1. In PowerShell, run the install line above. The old Cyclone One window should disappear, and Cyclone should start
   with the run/stop card.
2. Open a new terminal, type `cyclone`: Glass opens and your phone is still paired.
3. In Glass → Remote MCP, press **Start**, then **Show and copy token**.
4. Close the terminal: Glass closes with it.
