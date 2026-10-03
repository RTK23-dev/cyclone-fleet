# Cyclone Mobile 4.3.0 — Profile rescue and continuity

Base: published v4.2.9, ae52303b834d88e9e40983a7415c1b3bc1558896.
Mobile version 4.3.0 / versionCode 91. Other component versions unchanged.

Publication authorized by the user on September 10, 2026. Candidate checkpoint
40e7ab3e passed Android unit tests, lint and release assembly in Mobile CI run
34472901345. The final release source must independently pass the same pipeline,
then verify APK identity, checksum and signing continuity with published 4.2.9.

## Rescue an existing unconfigured profile

A secondary user without transferred settings opens a dedicated recovery screen before
agents, pairing, or setup-dependent screens initialize. It offers Return to Profile A,
Repair from Profile A, and the Android profile switcher. Return does not require a
local profile registry. Android's real main-user identity is discovered and the switch
is polled until verified.

Repair runs in the original profile so its settings and Keystore remain accessible.
The original registry and Android user/name/type must prove ownership of the target.
After a successful repair Cyclone reopens in that same target user. Failure returns to
the recovery screen; it does not silently claim setup succeeded.

## Prepare before switching

Every alternate-profile Open now prepares and verifies the receiving Cyclone before
changing the human's active user. The receiving package must be installed, enabled,
and in an unlocked user. Only Cyclone and installed allowlisted support packages are
installed-existing; ordinary apps' accounts/data are not copied.

Transferred Cyclone configuration:

- Model, reasoning/intelligence and phone autonomy preferences.
- Profile inventory and origin metadata.
- OpenRouter API key encrypted with the receiving profile's RSA public key, decrypted
  inside that profile, then stored using its own Android Keystore AES key.
- Already-granted microphone, calendar, notification and Shizuku runtime permissions.
- Cyclone Accessibility, notification listener, overlay, exact timing, battery exemption
  and Assistant role where enabled in the sending profile and supported by Android.

No plaintext API credential is passed in shell arguments, stored in transfer files,
written to logs, or copied as an unusable source-user Keystore blob. Transfers use
private device-protected files, exact user IDs and a per-request acknowledgement nonce.
The non-exported bootstrap service accepts only root-initiated local preparation.
Task state, live mutation leases, GATE approvals, PC pairing tokens, and unrelated app
preferences are deliberately excluded.

## Root and support apps

Automatic root continuity supports Magisk 26+ with compatible CLI/database schema.
A saved source Cyclone Allow policy is required. Only installed allowlisted support
apps that already have source Allow policies inherit those policies. Owner-only Magisk
mode changes to per-user mode; an existing owner-managed/per-user mode is preserved.
Before switching, a nested su probe originating as the destination Cyclone UID must
actually obtain uid 0. A failed proof cancels the switch.

Magisk modules and the su daemon are device-wide. Cloning their application data is
not a valid substitute for provisioning per-user authorization. Shizuku's declared
API_V23 runtime permission is inherited only if already granted; its existing in-app
Authorize Shizuku setup row remains available. Android/daemon restrictions still apply.

Implementation references:
- https://topjohnwu.github.io/Magisk/tools.html
- https://github.com/topjohnwu/Magisk/blob/master/native/src/core/db.rs
- https://github.com/topjohnwu/Magisk/blob/master/native/src/core/su/db.rs
- https://github.com/RikkaApps/Shizuku/blob/master/server/src/main/java/rikka/shizuku/server/ShizukuService.java

## Boundaries and device acceptance

No software update can bypass a root manager that denies root in an already stranded
profile. In that case the recovery screen opens Android's profile switcher directly;
return to the main profile and use Cyclone Open to prepare the secondary profile.
Existing locked-user credentials must be unlocked by the human. Biometric prompts,
MediaProjection/screen-capture consent, device unlock credentials, and unrelated app
logins are not transferable permissions. KernelSU/APatch automatic grant cloning is
not implemented: unsupported root backends fail before switching.

Physical phone / Pixel / USB / ADB testing: NOT PERFORMED, UNVERIFIED.
CI cannot prove OEM service-binding, root-manager policy-cache timing, user unlock,
or Shizuku daemon behavior on the owner's installed device. No claim of physical
acceptance is made. Exact-source CI tests/lint/unsigned assembly must pass before cut.

Device checklist:
1. Upgrade the existing installation in Profile B; verify recovery appears before setup.
2. Return to Profile A (or use the direct system switcher if root is denied).
3. Open B through Cyclone; confirm preparation completes before the user changes.
4. Confirm model, intelligence, autonomy, API access and inventory match the sender.
5. Confirm Accessibility binds, launcher works, notifications and microphone work.
6. Confirm Shizuku access and Magisk/root status; return B → A from Cyclone.
7. Repeat with another owned profile; verify unrelated app accounts remain separate.
8. Deny a required preparation step; verify A stays visible and no success is shown.
