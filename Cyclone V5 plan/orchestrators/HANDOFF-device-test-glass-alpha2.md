# Handoff — physical-device test of Cyclone Glass alpha.2 + Mobile alpha.8

> **Superseded** by [`HANDOFF-device-test-alpha13.md`](HANDOFF-device-test-alpha13.md) (alpha.13 + Glass alpha.6). Use that one.

**For:** a coding agent running on the owner's Windows PC, phone connected over USB.
**From:** the Glass front-runner session, 2026-09-23.
**Goal:** prove on real hardware what CI could not. Install the latest builds, run the test matrix below, and return
honest evidence. Nothing in V5 has been verified on a physical phone yet; every row here starts **UNVERIFIED**.

Read first (10 minutes): `AGENTS.md`, `Cyclone V5 plan/03-glass-v1.md` (what Glass is), `Cyclone V5 plan/11-run-inspector.md`
(what the run inspector must show).

## What you are testing

| Piece | Version | What is new |
|---|---|---|
| Cyclone Mobile (phone) | `5.0.0-alpha.8.dev1`, versionCode `149` | `apps.list` (every app + installed/mapped versions), `runs.list` / `runs.get` (steps + cause of death) |
| Cyclone One (Windows) | `1.6.0-alpha.8` | Settings → **Open Cyclone Glass**; its runtime now serves Glass |
| Cyclone Glass (browser) | `1.0.0-alpha.2` | Apps, App → Map, Phone (live view, take control, Ask), **Runs**, **Run inspector** |

Source: branch `release/cyclone-mobile-v5.0.0-alpha.8.dev1` (same code as `claude/cyclone-v5-handoff-review-9qrs40` @ `e9c2a20c`).

## Get the installs

1. Download from the GitHub prerelease **`v5.0.0-alpha.8.dev1`**:
   `Cyclone-5.0.0-alpha.8.dev1.apk` and `Cyclone-PC-Companion-1.6.0-alpha.8-Setup.exe` (plus `SHA256SUMS.txt`).
   Verify: `certutil -hashfile <file> SHA256` must match `SHA256SUMS.txt` / the `.sha256` file.
2. **If the prerelease does not exist yet, stop and report back.** Do not build and sideload your own APK: a differently
   signed APK cannot update the installed app, and uninstalling Cyclone erases its Atlas, Vault slots and settings.
   Never uninstall Cyclone without the owner saying so.

## Install

```powershell
adb devices                                   # the phone must show as "device" (authorize USB debugging on the phone)
adb shell dumpsys package com.cyclone.mobile | findstr "versionName versionCode"   # record the BEFORE version
adb install -r Cyclone-5.0.0-alpha.8.dev1.apk # update in place; must say Success
adb shell dumpsys package com.cyclone.mobile | findstr "versionName versionCode"   # expect 5.0.0-alpha.8.dev1 / 149
```

`adb` ships with Cyclone One (`%LOCALAPPDATA%\Cyclone One\…\android-platform-tools\adb.exe`) if it is not on PATH.

Windows: close Cyclone One, run `Cyclone-PC-Companion-1.6.0-alpha.8-Setup.exe` (SmartScreen may warn: the installer is
unsigned), start Cyclone One. Confirm Settings shows the phone as paired; re-pair only if it asks (QR / four-letter code).

On the phone: open Cyclone once, make sure the accessibility service is on and a model + API key are set.

## Safety rules (non-negotiable)

- Use test/dummy accounts where a login is involved. Never type a real password into a goal; passwords go only into the
  phone's Secrets Card.
- Never approve pay, send, delete, permission or sign-out prompts during these tests. If GATE asks, decline.
- Screenshots and logs can contain personal data. Keep raw evidence on this PC; commit only redacted summaries.
- Do not change signing, versions or app code. This task is observe-and-report. If you find a bug, describe it with evidence;
  do not fix it in this session unless the owner asks.
- Report only what you actually did. A skipped test is `NOT RUN`, a blocked one `BLOCKED (reason)`, never `PASS`.

## Test matrix

Record for each: result (PASS / FAIL / BLOCKED / NOT RUN), what you saw, evidence file names.

| ID | Test | Pass when |
|---|---|---|
| **D0** | Versions | Phone reports `5.0.0-alpha.8.dev1` / 149; One's About/Settings shows 1.6.0-alpha.8; Glass sidebar shows `1.0.0-alpha.2` |
| **G1** | Open Glass | One → Settings → **Open Cyclone Glass** opens the default browser at `http://127.0.0.1:<port>/glass/`; the `#code=` disappears from the address bar; reload keeps working; a **new tab** at the same address says "Open Cyclone Glass" (sessions are per tab); nothing listens on a non-loopback address (`netstat -ano | findstr <port>` shows 127.0.0.1 only) |
| **G2** | Apps page | Sidebar shows the phone as ready with its Cyclone version; Apps lists the phone's launcher apps. For 3 apps compare "Installed" with `adb shell dumpsys package <pkg> \| findstr versionName`. Filters and search work |
| **G3** | Map an app | Open **Clock** (or another simple app) → Start mapping. The phone walks tabs/menus on its own; Glass shows "Mapping on the phone", the current room pulses, new room cards appear. Pause → phone stops; Resume → continues; Stop → ends. It never pays, sends, deletes or grants. Afterwards the Apps row shows rooms/doors and a mapped version equal to the installed one |
| **G4** | Inspect the map | Click a room: purpose, doors out, confidence. Click a door line: from/to. Switch "Mapping pass" / "Your teaching" |
| **G5** | Watch without blocking | Phone page shows the live screen while the chip says **Cyclone has control**. Start an Ask (G7) while watching: it must run (watching must not take the phone) |
| **G6** | Take control | Take control → chip "You have control", live view gets a highlighted border; click an icon on the live view → the phone taps that icon; drag → scrolls; Back/Home work; Give back → "Cyclone has control". Lock the phone and try Take control → Glass says the phone is locked and does not unlock it |
| **G7** | Ask from Glass | Phone page → type `open the clock app and show the alarms tab` → Send. The phone runs it; Glass shows the title and milestones; when done the panel shows "See every step in Runs" |
| **R1** | Runs list | Runs shows the G7 run at the top as **Finished**, with steps, start time and duration. Older runs from before the update also appear |
| **R2** | Run inspector (success) | Open the G7 run: goal, status, metric tiles, a green "Finished" card, a step timeline; clicking steps shows their events; **Download report** saves a JSON |
| **R3** | Cause: login wall | Ask something that hits a login in an app/site where no password slot is set (e.g. a Chrome site you are logged out of, with a dummy account). Expect the run to wait on the Secrets Card. In Runs the cause is **Login wall** (`needs-secret`) and the inspector opens on the step with the login screen. Do not enter a real password |
| **R4** | Cause: stopped by you | Start an Ask, stop it on the phone mid-way. Cause **Stopped by you** (`cancelled`) |
| **R5** | Cause: you took the phone | Start an Ask, press Take control in Glass mid-run. Cause **You took the phone** (`human-took-control`) or the run pauses; record exactly what happened |
| **R6** | Cause matches reality | For every failed or stopped run you create, write one line: what really happened vs the cause Glass shows. Mismatches are the most valuable finding |
| **P1** | Privacy | After R3: search the downloaded report and the page for the dummy password / email you used (there must be none). Browser devtools → Application: `localStorage` empty for 127.0.0.1; `sessionStorage` holds only `cyclone.glass.session.v1` and `cyclone.glass.device.v1` |
| **H1** | Honest states | Unplug USB → within ~10 s Glass shows the phone not connected (no fake data); replug → recovers. Quit Cyclone One → Glass shows the gateway is not answering |
| **X1** | No regressions | On the phone: the overlay Ask still works; Settings → Appearance → Working indicator still offers the Trace Field styles. In One: Control / live phone still works |

## Evidence to collect

- `adb logcat -d -v time | findstr /i "cyclone"` after any FAIL (save to a file, redact before sharing).
- For each failed/stopped run: the Glass **Download report** JSON.
- One's diagnostics folder (One → Settings → Open diagnostics folder) if the gateway misbehaves.
- Screenshots of Apps, a mapped app's Map, Runs, and one inspector with a cause of death.

## Return

Write `Cyclone V5 plan/orchestrators/glass/returns/RETURN-device-test-glass-alpha2.md` with:

1. Device (model, Android version), PC (Windows version, browser), install sources and SHA256 checks.
2. The matrix table filled in (every row, honest status).
3. Bugs found: steps to reproduce, expected vs actual, evidence file names. Mark each **blocker** (stops a developer from
   using Glass) or **issue**.
4. R6 lines (real cause vs shown cause).

Commit it on a new branch `device-test/glass-alpha2` and push; do not push to `main`, `v5/integration` or release branches,
and do not commit raw logs or screenshots with personal data. If you cannot push, leave the file in the working tree and tell
the owner where it is.
