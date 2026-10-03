# Cyclone Mobile 4.0.2 — One-button background install

Cyclone Mobile 4.0.2 replaces the long Background tasks / Shizuku setup guide introduced in 4.0.1 with a simple installer flow: **Install → confirm → Installing… → Installed**.

## Changed

- The Background tasks screen is now a clean installer instead of a stack of setup cards and numbered instructions.
- Cyclone detects the next missing prerequisite and opens it automatically in order: Shizuku install, Shizuku start, Cyclone's Shizuku authorization, Accessibility, then task notifications.
- When Android, Google Play or Shizuku must own an approval, Cyclone opens that surface and resumes the installation automatically when the user returns.
- The user no longer has to manually recheck individual setup rows.
- The old “close Cyclone and recheck on Home” setup step is removed. The existing task-start preflight remains responsible for the main-screen safety check when work actually begins.
- Existing fail-closed background-task runtime checks and the 4.0.1 background glass behavior are unchanged.

## Android boundary

Cyclone does not bypass Android security. Installing or starting Shizuku, enabling Accessibility and granting notifications can still require an Android- or Shizuku-owned confirmation. 4.0.2 orchestrates those confirmations instead of asking the user to follow a manual guide.

## Release status

- Version: **4.0.2**
- Android versionCode: **73**
- Functional base: Cyclone 4.0.1
- Physical device testing: **UNVERIFIED**
- UI acceptance: **UNVERIFIED**
- Automated test suites: **NOT RUN — release requested without tests; user will device-test**
- Android `lintVital` runs automatically as part of Gradle release packaging; no unit, instrumentation, Python or gateway test suites are invoked by this release workflow.
- APK signing is checked against the last known signed release, 3.9.10. The published 4.0.1 APK is unsigned and therefore cannot provide a signing-certificate baseline.
