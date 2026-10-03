# Cyclone V5 Alpha 85: Sign-up mapping you can see and stop

Developer alpha for owner testing. It builds on Alpha 84 (Profiles from the PC) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.85.dev1` (version code 230).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.85.dev1.exe` (runtime `5.0.0-alpha.85.dev1`).
- **Glass:** `1.0.0-alpha.47`.

A fix for Accounts. Pressing **Map the sign-up** could leave an app on "Mapping…" with nothing happening on the
phone, no reason and no way to stop it.

## What changed

**Glass says why a mapping waits.** While an app's sign-up is being mapped, Accounts shows what the task is doing:
- waiting its turn;
- waiting for the phone, and why (another task is running, the overlay is off, you have the phone, the phone isn't
  ready);
- working on the phone, with the latest step;
- waiting for your answer, with **Open Inbox**.

**Cancel mapping.** A mapping can be cancelled from Accounts at any time. After a cancelled or failed try, Accounts
shows the cause and **Map it again**. A run that finished without saving a map says so too.

**The phone always maps in front.** Before this fix, a mapping started while another Cyclone task was running went
behind it, on a background screen you never see. Now a mapping or Account Setup run takes the phone's screen, or the
phone answers "busy" and the PC waits and tries again when the other task ends.

## Checks

- Glass: `npm test` (256 tests), `npm run build`, `glass_guard`.
- Mobile: Mobile CI (unit tests and the APK build) on this commit.

Physical phone and Windows PC acceptance: **UNVERIFIED** until tested. The reason your first mapping didn't start is
not confirmed on a device; with this build Accounts will show it.
