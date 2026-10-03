# Cyclone V5 Alpha 48 — One-click Windows setup

Developer alpha for owner testing, built on Alpha 47 (Web-only PC), which it includes.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.48.dev1.exe`, a normal Windows installer.
- **Mobile:** `5.0.0-alpha.48.dev1` (version code 192). The phone app has no changes of its own.
- **Glass:** `1.0.0-alpha.27` (unchanged).

## What changed

**Install Cyclone on Windows with one double-click.** Download `Cyclone-Setup-5.0.0-alpha.48.dev1.exe` and open it.
No zip to unpack, no PowerShell, no administrator rights.

What the setup does:
- **Replaces older Cyclone:**
  - stops Cyclone;
  - silently removes the old Cyclone One window;
  - replaces the old `cyclone` command, the one that kept offering alpha.43.
- **Keeps your data:** your phone pairing, Remote MCP token and saved VMOS fleet stay.
- **Installs for you only:** into the same folder as before, so Codex, Cursor and your other AI connections keep working.
- **Behaves like a Windows app:**
  - a **Cyclone** shortcut in the Start menu and on the desktop;
  - an entry in **Apps & features** with an uninstaller;
  - **Open Cyclone now** at the end.

Inside, the setup runs the same checked installer as alpha.47's one-line install. The install rules (checksum, what
is kept, what is removed) live in one place.

**Uninstall** from Apps & features: the program, the `cyclone` command and the shortcuts go. Your pairing and keys
stay in `%LOCALAPPDATA%\Cyclone One`; delete that folder to remove them too.

**Why the old `cyclone` kept offering alpha.43.** The `cyclone` command from Cyclone One (1.6.0-alpha.43) only
recognises the old `Cyclone-PC-Companion-…-Setup.exe` installers. The last release with one is alpha.43.dev1, so it
could never see alpha.47. Running this setup once replaces that command. From then on, `cyclone update` finds new
releases.

## Validation and limits

Tests that pass:
- **The release build** does all of this on a Windows runner:
  - builds the setup;
  - installs it silently into a scratch profile and checks that `cyclone version` answers `Cyclone 5.0.0-alpha.48.dev1`;
  - checks the Apps & features entry, and that no `uninstall.exe` is left behind;
  - uninstalls it and checks the program, the command and the entry are gone while your data stays.
- **Unchanged from alpha.47:** it installs and starts the package, and the runtime serves Glass and refuses `/v1/pc`
  without the bearer.
- **CI guards:** the web-only guard now also covers the setup (per user, no admin, wraps `install.ps1`, never names
  its uninstaller `uninstall.exe`, keeps your data on uninstall). The version and product guards pass too.

**Not yet seen on your PC.** The setup has not run on your own Windows machine yet. Physical Pixel acceptance is
also still UNVERIFIED.

Honest limits:
- **Not code-signed yet.** Windows may show "Windows protected your PC". Choose **More info**, then **Run anyway**.
  Removing this needs a paid code-signing certificate.
- **Cyclone runs while its window is open.** The Start menu shortcut opens the same window as typing `cyclone`;
  closing it stops Cyclone.
- **The alpha.47 one-line install and `cyclone update` still work.**

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.

Suggested checks:
1. Download `Cyclone-Setup-5.0.0-alpha.48.dev1.exe` and double-click it. If Windows warns, choose More info, then Run
   anyway.
2. The old Cyclone One should disappear. At the end, leave **Open Cyclone now** ticked: Glass opens and your phone is
   still paired.
3. Open a new terminal and type `cyclone version`: it should say `Cyclone 5.0.0-alpha.48.dev1`.
4. Find **Cyclone** in the Start menu and in Apps & features.
