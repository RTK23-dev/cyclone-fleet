# Cyclone 4.0.4 — setup that does the work

Base: published v4.0.3, `28cfd216b803696796167124084a6755847c9aa3`. Android versionCode 75, development channel.

The previous release passed its automated build checks but still had three product failures: it sent Android 16 users to Shizuku's blocked Play Store listing, hid Back while installing, and offered technical workspace registration instead of creating a profile. These were implementation gaps, not a failed APK update.

Background setup now downloads the official Shizuku 13.6.0 release inside Cyclone, verifies a pinned SHA-256 and package identity, then hands it to Android's installer. No Play Store dependency or third-party mirror. Download failure and canceled approval leave a retryable screen with Back always available, including system Back. Android still asks permission to install from Cyclone and to install the helper; Shizuku startup/authorization and Accessibility approvals remain owned by those apps. Nothing silently grants permissions.

The Root features form has been replaced by a full-page Your app profiles experience: Profile A → Yes, create Profile B / Keep one profile → choose app names and icons → confirm → progress → Profile B apps. There are no package-name fields, Android user-ID fields, mutate-lock terms, or Shelter/Island shopping list in the normal flow.

For an already-rooted phone that allows Cyclone, setup creates an Android managed work profile, starts it, verifies it is unlocked, adds the selected installed apps with fresh per-profile data, verifies their installation, and automatically registers their workspaces. It does not copy accounts, credentials or existing app data. A durable creation journal reuses the same profile on retry; cancellation stops at the next bounded operation. Existing profiles are never deleted to make room. Android/OEM profile limits can still prevent creation, which is reported without claiming success. No auto-root, Magisk installation or boot patching. Non-root users can continue with their existing profile.

The app now permits installation on Android 13 (minSdk 33), with guards around newer screen-capture and foreground-service APIs. Foreground phone control and profile setup target Android 13–16. The existing isolated background display engine still requires Android 15+ because its input/focus isolation flags are unavailable on older Android. Android 13/14 get a clear foreground-mode explanation rather than an incompatible helper install. This is source/build compatibility, not physical-device verification across those versions.

Existing Session Kernel, GATE, one global mutation path, time-sliced workspaces and MCP identity remain. True parallel phone input remains deferred. Publication must reuse the exact green Mobile CI APK, then verify signed-output checksum and source/run provenance, with the historical update-compatible dev signer and full sidecars.

NO phone, Pixel, USB, adb or adb smoke testing was performed. Physical Pixel 8 and UI acceptance: UNVERIFIED. Automated CI status is available through the linked source/run sidecars; Android 16 Pixel 8 behavior still needs the user's acceptance test.

Upstream evidence: [Shizuku Android 16 Play Store issue](https://github.com/RikkaApps/Shizuku/issues/1974), [official downloads](https://shizuku.rikka.app/download/), [official v13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0), [AOSP profile commands](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java).
