# Cyclone Mobile 4.5.2

Android versionCode 111; builds on published 4.5.1.

The Ask Cyclone overlay now moves with the keyboard. TYPE_ACCESSIBILITY_OVERLAY windows do not resize like activities; `ADJUST_RESIZE` made the bar jump or sit under the IME. The window is lifted with IME animation (`y = max(nav + 26dp, ime + 8dp)`) so the composer rests above the nav bar, then rides 8dp above the keys with no extra Compose padding fight.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical-device IME tracking remains UNVERIFIED.
