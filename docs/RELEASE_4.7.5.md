# Cyclone Mobile 4.7.5 — Keyboard and live task progress

Android versionCode 135, based on 4.7.4.

The floating Ask bar follows docked keyboards using animated IME insets plus an accessibility-window geometry fallback for devices that report zero overlay insets. The fallback reads window bounds only. Floating keyboards do not push the bar offscreen. The in-app composer consumes Scaffold insets and follows keyboard resizing without double navigation padding.

New tasks open their progress overview automatically, including after the previous task was collapsed. Checkpoints start expanded while working. Subsequent updates respect a user's manual collapse.

Foreground and background tasks now share a native notification: Cyclone status icon, target app icon when available, task title, current milestone, grounded or indeterminate progress, Stop task and View progress. Content taps route to the exact task; interruption/confirmation capabilities and lock-screen redaction are preserved. Working tasks request promoted ongoing Live Updates on supported Android versions. Android/OEM/user settings control promotion, status chips, card shape and colors; the reference's exact glass appearance cannot be imposed on System UI. Older versions receive the standard native progress notification.

## Verification

The release requires repository guards, Android unit tests, lint and release assembly in exact-source Mobile CI. The publisher checks artifact provenance and signing continuity with 4.7.4. Regression coverage includes zero IME insets, docked versus floating keyboard geometry, keyboard dismissal, task expansion after collapse, and truthful notification progress.

Physical-device keyboard animation, notification promotion and visual acceptance remain UNVERIFIED. CI does not substitute for an on-device check.
