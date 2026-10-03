# Cyclone V5 Alpha 95: The Drive orb is always there

Developer alpha for owner testing. It builds on Alpha 94 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.95.dev1` (version code 240).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.95.dev1.exe` (runtime `5.0.0-alpha.95.dev1`, no PC changes).
- **Glass:** `1.0.0-alpha.52` (unchanged).

## What was wrong

After updating to alpha.93, the owner's Drive orb was gone. Turning Driver mode off and on, or changing the orb's
size, did not bring it back. In the code, three things could each take the orb away for good:

1. **The orb depended on the rest of the overlay starting.** It was attached only as the last step of the main chrome's
   attach. If anything earlier in that attach failed, the orb's overlay never started. Its settings watcher never ran,
   so toggling Driver mode or resizing the orb had nothing listening.
2. **One failure ended the settings watcher.** An exception while rebuilding the orb stopped the watcher, and every
   later toggle was ignored.
3. **A failed window was never retried.** If adding the orb's window failed once, the overlay kept "no orb" until the
   service restarted.

## What changed

- **The orb attaches on its own.** The accessibility service attaches Driver mode directly, next to the chrome. A
  chrome failure can't take the orb away. Calling it twice is safe.
- **The settings watcher can't die.** Each toggle and size change is handled on its own. A failure is recorded in the
  diagnostics (`drive.orb.*`) instead of ending the watcher.
- **A keeper watches the orb.** Every 2 seconds while Driver mode is on, it checks the orb and repairs it:
  - missing windows → rebuilt;
  - the button hidden while AI mode is closed → shown;
  - the button off screen (after a rotation) → put back at its spot;
  - AI mode meant to be open but its window failed → rebuilt;
  - the overlay holding an old accessibility service → reattached to the live one.

  Repairs that keep failing back off (2 s up to 30 s), and each repair is written to the device log.

## Checked

- `OrbKeeperTest` (5 tests: each failure has a repair, back-off, off-screen detection) passes locally.
- The Drive overlay guard checks the wiring. Mobile CI compiles and runs the Android tests.
- On the phone: **UNVERIFIED**. Turn Driver mode on and check the orb is there. Then force-stop Cyclone and let
  Accessibility come back (the PC repairs it), rotate the phone, and resize the orb. The orb should come back within a
  few seconds each time.
