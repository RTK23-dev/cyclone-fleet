# Cyclone Mobile 4.6.5

Android versionCode 125; builds on published 4.6.4.

Share screen was launching Android 14+ "Share one app" capture. That splits the display and never opens overlay chat.

## Share screen

- Ask Cyclone / Home Share screen now opens the overlay composer and requests **entire-screen** capture.
- After you allow it, Cyclone goes to the background so the overlay chat sits over the rest of the phone with live sharing on.
- Android 14+ always uses the default-display capture config, so the split "Share one app" picker is gone.
- The "Waiting for permission" overlay chip no longer draws over the system consent dialog.

Phone control still has to be on for the overlay to appear. Capture consent remains Android-owned and per session.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical Pixel 8 remains UNVERIFIED.
