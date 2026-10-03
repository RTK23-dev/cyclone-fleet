# Cyclone V5 Alpha 96: Cyclone Ports in Glass

Developer alpha for owner testing. It builds on Alpha 95 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.96.dev1` (version code 241). No phone changes.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.96.dev1.exe` (runtime `5.0.0-alpha.96.dev1`, with the Port Hub).
- **Glass:** `1.0.0-alpha.53`.

## What's new

**Cyclone Ports** is how you plug your own tools into Cyclone runs: a logger, a PC image picker, an SMS code bridge,
or anything built on the `cyclone.ports/1` contract. This is run 1 of plan 48. It covers the Port Hub in the PC
runtime and the **Ports** page in Glass.

Open Glass → Command Center → **Ports**:
- **At a glance:**
  - live plugins;
  - how much of the port catalog is covered;
  - messages today;
  - anything that needs you, each with the one action that fixes it.
- **Add plugin:** one guided sheet.
  1. **Address:** where the plugin runs. A plugin on this PC uses `http://127.0.0.1:<port>`; anywhere else needs
     https.
  2. **Review:** what the plugin is, and every port it serves with a switch. Personal and secret ports start off for
     a plugin on another computer.
  3. **Key:** shown once, with the line to set it in PowerShell, bash or Command Prompt. Cyclone keeps it sealed on
     this PC (Windows DPAPI) and never shows it again.
  4. **Checks:** the Port Hub runs the contract's checks against the real plugin. It's live only when they all pass,
     and each failure says what to fix.
- **Plugin drawer:**
  - status and next step;
  - port switches;
  - checks and details;
  - recent activity;
  - **Send a test** (a signed message the plugin shows);
  - Run checks, Pause, New key, and Remove (asks first).
- **The port catalog:** every port in both directions (phone → plugin, plugin → phone) and who serves it. Passwords
  and keys stay vault-only.
- **Watching:**
  - the Port Hub checks each plugin's health every 30 seconds;
  - a plugin that changes what it asks for gets nothing until you review the change;
  - a version bump alone is only noted.
- **The activity log** keeps metadata only: who, which port, the status and the time. It never holds a message, key
  or code.

Try it with the kit's example plugins from `tools/cyclone-ports-sdk` (see its README). The Ports page offers all
three the first time you open it.

Runs don't send to plugins yet; that comes in run 3 (live traffic) and run 4 (the phone side). This release covers
adding plugins, checking them, sending a signed test message, and managing them.

## Also in this release

- The Cyclone Ports kit (`tools/cyclone-ports-sdk`) is part of the PC package. The package build asks the installed
  runtime's Port Hub for its catalog before a release.
- Plans 45 (Run Ports), 46 (Skill Studio), 47 (the contract review) and 48 (Ports in Glass, the run plan).
- The connector handoff for builders: `Cyclone V5 plan/HANDOFF-build-a-connector.md`.

## Verified

- Gateway test suite, including 7 Port Hub tests against the kit's real example plugins over HTTP: green.
- Glass: 282 tests (6 new for Ports), typecheck and build: green.
- Kit tests (21) and CI guards: green.
- In a real browser (Chromium, light and dark), I added the three example plugins end to end:
  - one went live after 29 passed checks;
  - one was correctly held at "waiting for its key";
  - a signed test message reached the run logger.

## Not verified

- On the owner's Windows PC: **UNVERIFIED**. The Windows package build smoke-tests the Port Hub on CI.
- Physical phone: no phone changes in this release.
