# Handoff — physical-device test of Cyclone V5 alpha.17 + Glass 1.0.0-alpha.10

**For:** a coding agent running on the owner's Windows PC, phone connected over USB.
**Replaces:** [`HANDOFF-device-test-alpha15.md`](HANDOFF-device-test-alpha15.md) (alpha.15). Same rules, newer builds, more to test.
**Goal:** prove on real hardware what CI could not. Install the latest builds, run the matrix, return honest evidence.
Nothing below has been verified on a physical phone yet; every row starts **UNVERIFIED**.

Read first (10 minutes): `AGENTS.md`, `Cyclone V5 plan/03-glass-v1.md` (what Glass is), `Cyclone V5 plan/11-run-inspector.md`.

## What you are testing

| Piece | Version | New since alpha.13 (all still to verify) |
|---|---|---|
| Cyclone Mobile (phone) | `5.0.0-alpha.17.dev1`, versionCode `158` | **Linked PCs** with Log out per PC; notice when a linked PC returns; `wrong-room` cause; mapping depth; `door-missing` cause; scenario counts in `apps.list`; **Sign in / Already signed in** scenarios; `knowledge.get` carries the never-pay list (counts only). Everything from alpha.13 (connect code, rooms per step, scenarios, versions, knowledge, you are here, mark as expected) |
| Cyclone One (Windows) | `1.6.0-alpha.17` | Its runtime serves Glass alpha.10 |
| Cyclone Glass (browser) | `1.0.0-alpha.10` | **Issues** tab, live runs, compare with the last good run, Ask again, Goals view, CSV, **Home** (opens here), mapping depth, type + scroll from the PC, Teach from the map, scenario health on Apps, Sign in scenarios, Mapping pass / Your teaching switch, **Never pressed** + Safety, scenarios a run reached, Ask → Open this run |

## Get the installs

1. From the GitHub prerelease **`v5.0.0-alpha.17.dev1`** download `Cyclone-5.0.0-alpha.17.dev1.apk`,
   `Cyclone-PC-Companion-1.6.0-alpha.17-Setup.exe` and `SHA256SUMS.txt`. Check: `certutil -hashfile <file> SHA256`.
2. **If that prerelease does not exist, stop and report.** Do not build and sideload your own APK (a differently signed APK
   cannot update the app; uninstalling erases Atlas, Vault slots and settings). Never uninstall Cyclone.

## Install

```powershell
adb devices
adb shell dumpsys package com.cyclone.mobile | findstr "versionName versionCode"   # record BEFORE
adb install -r Cyclone-5.0.0-alpha.17.dev1.apk                                     # must say Success
adb shell dumpsys package com.cyclone.mobile | findstr "versionName versionCode"   # expect 5.0.0-alpha.17.dev1 / 158
```

Windows: close Cyclone One (tray too), right-click the Setup.exe → Properties → Unblock if shown, run it (SmartScreen: More info →
Run anyway), start Cyclone One. Open Glass: Cyclone One → Settings → **Open Cyclone Glass**.

## Safety rules (unchanged, non-negotiable)

- Test/dummy accounts only for logins. Never type a real password into a goal; passwords go only into the phone's Secrets Card.
- Never approve pay, send, delete, permission or sign-out prompts. If GATE asks, decline.
- Keep raw screenshots/logs on this PC; commit only redacted summaries.
- Observe and report. Do not change signing, versions or app code unless the owner asks.
- `NOT RUN` / `BLOCKED (reason)` are honest results; never mark something PASS you did not see.

## Test matrix

| ID | Test | Pass when |
|---|---|---|
| **D0** | Versions | Phone `5.0.0-alpha.17.dev1` / 158; One `1.6.0-alpha.17`; Glass sidebar `1.0.0-alpha.10` |
| **L1** | Linked PCs | Phone → PC Gateway lists this PC under **Linked PCs** with "Active now"; Glass Devices says "The phone lists this PC as …" with the same name. **Log out** (confirm) → Glass says the phone logged this PC out and offers Connect; reconnect with a code |
| **I1** | Issues tab | App → **Issues** lists open problems critical first; each button opens the fix (route on the map, latest run, Versions). An app with nothing wrong says "No open issues" |
| **R6** | Live run + compare | Ask from the Phone page → **Watch it step by step** opens the inspector with "Live · updating" and steps appear; after a failed run of a sentence that once worked, the inspector shows "Compared with the last good run" |
| **HM1** | Home | With a phone connected Glass opens on **Home**: phone tile, apps mapped, runs today; Needs attention lists apps with a failed last run / critical scenarios / updated since mapped, each link opens the right place; Latest runs open the inspector |
| **M0** | Mapping depth | App → Map: the depth picker offers Quick / Standard / Deep; a Quick pass stops sooner than Deep on the same app |
| **T1** | Type + scroll | Phone page → Take control → type a word into a focused search field and scroll a list from the PC; Give back returns control |
| **S2** | Sign in scenarios | Map an app with a **dummy** login screen: Scenarios shows **Sign in** (Login chip, "fills the login from its Vault") and **Already signed in**; the Mapping pass / Your teaching switch reloads the list |
| **N1** | Never pressed | Knowledge → **Never pressed** lists apps with pay/send/delete doors found while mapping (counts only, no button text); Settings → Safety → the button opens Knowledge |
| **R5** | Run ↔ scenarios, Ask link | Inspector of an Ask run in a mapped app shows "Scenarios this run reached"; an Ask sent from the Phone page ends with **Open this run**, which opens that run |
| **C1** | Connect from Glass | If the phone is not connected: Glass opens on **Devices**; **Connect** shows a six-digit code; the phone shows a "Connect <PC>?" notification with the **same** code; tapping it opens the Allow card with that code; Allow → Glass shows the phone as Connected within ~5 s. If it was already connected: **Disconnect** (confirm) → reconnect this way |
| **C2** | Honest not-ready | Lock the phone, restart Cyclone One: Glass says why (e.g. "Unlock the phone…") with **Reconnect**, never a bare "Waiting for Cyclone"; unlock → Reconnect → ready |
| **C3** | Not now | Connect → on the phone tap **Not now** → Glass says the phone answered Not now |
| **G1** | Apps | Apps lists launcher apps with installed versions (compare 3 with `dumpsys`); after R-tests the rows show "Last run …"; filter "Last run failed" works |
| **M1** | Map an app | Clock (or another simple app) → Map → Start mapping. Rooms appear live; Stop ends it. Then **Versions** tab shows the installed version with doors, and no "Needs remap" |
| **M2** | Mapping pass is a run | Runs shows "Map Clock" with a **Mapping pass** chip; its inspector lists door steps ("Opening a … door", "Reached a … screen") and ends Finished or "Stopped by you" |
| **M3** | Screens tab | App → Screens lists the rooms with doors in/out; clicking one opens the Map with that room numbered |
| **S1** | Scenarios | App → Scenarios shows cards "Reach …" with a numbered route; **Board** view shows Start on the left then columns by doors away; Show on the map lights up the route |
| **R1** | Ask + run inspector v2 | Phone page → Ask `open the clock app and show the alarms tab`. Runs → that run: steps show **App** (Clock + version), **Room** / **Room after**, **Chosen by**; the Route on the map card lists rooms; **Show on the map** numbers them on the Clock map |
| **R2** | App → Runs | Clock → Runs tab lists that Ask run (and the mapping pass), with "From the map %" |
| **R3** | Causes | Login wall with a dummy account → **Login wall** (`needs-secret`); stop an Ask on the phone → **Stopped by you**; Take control in Glass mid-run → **You took the phone** or paused. For each, the cause card's buttons work (Go to step, Open the room on the map when shown) |
| **R4** | Mark as expected | On a stopped/failed run press **Mark as expected** → chip "Marked expected", Runs shows "Expected"; **Count it again** undoes it |
| **H1** | You are here | Phone page card "You are here" shows the foreground app + version and a room when inside a mapped app; on the home screen it says it is not inside an app; Show on the map works |
| **K1** | Knowledge | Knowledge page shows totals (places/rooms/doors), Vault per app as **set / not set** (after R3 the login app appears), skills and automations. No secret value anywhere |
| **P1** | Privacy | Search downloaded run reports and the Knowledge page for the dummy password/email: none. Devtools → Application: `localStorage` empty; `sessionStorage` only `cyclone.glass.session.v1` and `cyclone.glass.device.v1` |
| **X1** | No regressions | Phone overlay Ask works; Teal Matrix look intact; Trace Field + tools drawer work; One Control/live phone works |

## Evidence and return

- After any FAIL: `adb logcat -d -v time | findstr /i "cyclone"` (redact), the Glass **Download report** JSON for failed/stopped
  runs, screenshots of Devices, a Run inspector with rooms, Scenarios board, Versions, Knowledge.
- Write `Cyclone V5 plan/orchestrators/glass/returns/RETURN-device-test-alpha17.md`: device + PC facts and SHA256 checks; the
  matrix filled in (every row); bugs (steps, expected vs actual, evidence, **blocker** or **issue**); for every failed/stopped run
  one line "what really happened vs the cause Glass shows".
- Commit it on a new branch `device-test/alpha17` and push. Never push to `main`, `v5/integration` or release branches; never
  commit raw logs or screenshots with personal data.
