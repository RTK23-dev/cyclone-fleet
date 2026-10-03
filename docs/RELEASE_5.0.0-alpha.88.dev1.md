# Cyclone V5 Alpha 88: A connection that stays connected

Developer alpha for owner testing. It builds on Alpha 87 (Phone care) and includes it. With this release, all six
reliability builds from the 2026-09-30 PC test pass have shipped.

Versions:
- **Mobile:** `5.0.0-alpha.88.dev1` (version code 233).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.88.dev1.exe` (runtime `5.0.0-alpha.88.dev1`).
- **Glass:** `1.0.0-alpha.49`.

## The six builds

| # | What | Release |
|---|---|---|
| 1 | Update the phone from Glass, with Android's errors in plain words | alpha.87 |
| 2 | A busy phone is not "disconnected" (lighter event handling, freeze watchdog, `PHONE_APP_BUSY`) | alpha.87 |
| 3 | One truth for connection health | **alpha.88** |
| 4 | The broken link named, with one action; common ones fixed automatically | **alpha.88** |
| 5 | One tap to trust the PC, then silent resume | **alpha.88** |
| 6 | Why Cyclone stopped (Android's exit records, the freezes it caught) | alpha.87 |

## What changed in this release

### One truth for connection health

- **Trust belongs to one USB session.** A trusted session is only valid on the USB session it was opened on.
  - After an unplug and replug, the PC opens a fresh session by itself. It never reports the old one as trusted.
  - If the phone's app restarted and rejects the old token, the PC restores a session. Before this, it kept
    reporting `TRUSTED` / `TOKEN_SESSION_MATCHED` on a dead session and never retried until the PC runtime restarted.
- **A gone phone is gone.** When adb no longer lists the phone, it is reported as absent (`USB_ABSENT`), never
  "USB authorized" from its last cached state.
- **Status, devices and capabilities agree.**
  - Capability health says "busy" or "trust" when that is the problem, instead of "disconnected".
  - `phone_status` and `phone_capabilities` without a device id now read the one paired phone in the fleet (the
    phone `phone_devices` shows), not an older single-phone surface that could disagree with it.
- **The phone owns control.** When you take over on the phone, any AI control granted from the PC ends. One control
  summary says who is in control: you on the phone, AI, or the PC user.

### The broken link, named

"Connected" is a chain: the cable, the USB-debugging Allow, Cyclone installed and running, Allow this PC, an unlocked
phone, PC Gateway, Accessibility. Cyclone now names the first broken link in plain words, with at most one thing to
do. Glass, `/v1/devices` and the MCP all show this same answer, for example:
- "Phone not detected: plug it in with a cable that carries data, not a charge-only one";
- "Tap Allow on the phone" (USB debugging, or Connect this PC with its code);
- "Cyclone stopped on the phone · Starting it again…";
- "Unlock the phone: Cyclone reconnects as soon as it's unlocked";
- "Turn on Cyclone Accessibility" · **Open on the phone**.

Fixed automatically, or with one click:
- **A stopped Cyclone app is woken without showing anything on the phone.** Only adb or the system can send the wake
  signal (it is guarded by `android.permission.DUMP`). The PC tries at most every 30 seconds, five times per
  plug-in; after that it reports the problem instead of retrying.
- **One-click fixes:** Start Cyclone, open Accessibility settings, open Cyclone. These are fixed commands: nothing
  grants a permission or changes a setting for you.

### One tap, then it stays connected

- **The PC asks by itself.** Plug in a phone with Cyclone and allow USB debugging: the PC asks "Connect this PC?"
  once, with no need to open Glass.
- **The Allow card opens directly.** If the phone is unlocked and in use, the Allow card opens by itself (or tap the
  notification). One tap on **Allow** and you're connected; the PC finishes the handshake on its own.
- **Limits on asking:** at most once per plug-in, only over USB, never again for a day after **Not now**, and it can
  be turned off with `CYCLONE_AUTO_CONNECT=0`. Trust still only ever comes from your Allow on the phone.
- **It stays connected.** After a replug, a PC restart or an update, the trusted phone reconnects by itself, with no
  tap. A locked phone reconnects within about 2 seconds of being unlocked, instead of after up to 30.

## Checks

- Gateway: full `pytest` suite, including 26 new connection tests:
  - trust bound to the USB session;
  - restore after a replug or a rejected token;
  - the automatic ask and its limits;
  - every doctor verdict;
  - one verdict across surfaces;
  - control ownership;
  - capability health;
  - the medic's limits and fixed commands.
- MCP: `unittest` (191).
- Glass: 270 tests, `npm run build`, `glass_guard`.
- CI guards: 259, including the new `test_connection_guard.py`.
- Mobile: Mobile CI (unit tests and the APK build) on this commit.

Physical phone and Windows PC acceptance: **UNVERIFIED** until tested. On the Pixel 8, check:
- **Unplug and replug 20 times:** Glass shows Connected again within about 5 seconds, with no tap.
- **Restart the PC runtime 5 times:** same result.
- **First connect:** unplug, clear this PC under Linked PCs on the phone, plug in again. Allow appears by itself; one
  tap connects.
- **Force-stop Cyclone:** it comes back by itself within about 30 seconds, with nothing on screen.
