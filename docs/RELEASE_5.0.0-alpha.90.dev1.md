# Cyclone V5 Alpha 90: Cloud phones

Developer alpha for owner testing. It builds on Alpha 89 and includes it. This is run 1 of plan 44 (the cloud fleet):
**VMOS Cloud, DuoPlus and any remote-ADB phone join this PC's fleet, and Cyclone keeps them connected on its own.**

Versions:
- **Mobile:** `5.0.0-alpha.90.dev1` (version code 235). One phone fix (the alarm check below).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.90.dev1.exe` (runtime `5.0.0-alpha.90.dev1`).
- **Glass:** `1.0.0-alpha.51`.

## How to use it

Glass → **Devices** → **Cloud phones** → **Add cloud phones**:
- **VMOS Cloud:** paste the OpenAPI access key and secret key. The account's phones appear; press **Keep connected**
  on the ones Cyclone should use. Needs Windows' **OpenSSH Client** (Settings → System → Optional features).
- **DuoPlus:** paste the API key. In DuoPlus, turn on ADB for the phones and add this PC's internet address to the ADB
  whitelist; paste each phone's ADB address once if DuoPlus doesn't list it.
- **Remote ADB:** any phone at an ADB address (`host:port`).

A kept phone shows one line: Connecting → Connected · key renews in N days. Once connected it appears with the other
phones above: Cyclone is installed on it if it's missing (the verified build, through phone care), and the phone asks
"Connect this PC?". On a cloud phone, tap Allow in the provider's own viewer. (From run 2 on, no tap is needed.)

## What Cyclone does on its own

- **VMOS:** asks for 7-day remote ADB (the longest VMOS allows; the console gives 24 hours) and renews it when a fifth
  is left, well before it expires. It runs VMOS's SSH tunnel itself on a fixed local port per phone, so the phone keeps
  one identity across renewals, and restarts it when it dies. A refused key, or a tunnel that keeps dying, gets a new
  key. adb is reconnected whenever it drops.
- **DuoPlus and remote ADB:** adb is reconnected whenever it drops, with backoff (5 s, 15 s, 1 min, 5 min).
- **Problems in plain words:** a wrong key waits for you (checked again every 30 minutes, never hammered); a phone
  that is off says so; a missing OpenSSH Client says where to turn it on.
- **In the connection line,** a cloud phone that dropped reads "Cyclone is reopening its link", never a cable problem.

## Safety

- The provider is only the way in. Cyclone asks it for the phone list and a remote-ADB link, nothing else: no
  provider taps, typing or shell commands. `PhoneToolExecutor` on the phone stays the only thing that acts.
- Keys are encrypted for your Windows user (DPAPI) and never returned to Glass. VMOS's SSH key reaches ssh only
  through its password helper (the runtime prints it to ssh and exits), never on a command line or in a file.
- adb is used for connect, disconnect and the device list; the tunnel only listens on 127.0.0.1.
- No MCP or AI tool can add accounts or keep phones connected: it's your page in Glass.
- Provider API paths are defaults that an account can override, so if VMOS or DuoPlus answer differently than their
  documentation, it's a settings fix, not a release.

## Also fixed

- **Alarm check at night:** "set an alarm for …" could be marked done at 9 PM because an enabled 9 AM alarm showed as
  "09:00". A zero-padded hour is now read as 24-hour time only, so 09:00 is never 9 PM. Mobile CI caught it at 20:55.

## Checks

- Gateway: full `pytest` suite, including 29 new cloud tests: VMOS signing, the SSH line in any order, expiry
  reading, DuoPlus addresses, the tunnel's argv and askpass, renewal on the same port, dead tunnels, a refused key, a
  wrong key, a stopped phone, letting go, remote ADB, input checks, routes with no commands, the vault.
- CI guards: 266, including the new `test_cloud_fleet_guard.py`.
- MCP: `unittest` (191). Glass: 276 tests, `npm run build`, `glass_guard`.
- Mobile: Mobile CI on this commit.

**Not verified against real VMOS or DuoPlus accounts:** the build machine has no network access to either provider.
Run `scripts/pc/cloud_probe.py` (plan 44, run 0) on the PC with a test phone first; it records the providers' real
answers with the keys removed. Physical phone and Windows PC acceptance: **UNVERIFIED** until tested.
