# Cyclone Mobile 4.4.8

Android versionCode 108; builds on published 4.4.7 with the tested task-card swipe changes from PR #117.

Fixes Cyclone's accessibility overlay remaining above the lock screen. Screen-off broadcasts immediately hide all four overlay windows, including the composer, task result, halo and screen-share/border chrome. Keyguard and interactive-state checks prevent redisplay while locked. A lifecycle-bound 500ms check covers keyguard transitions without a screen broadcast. Unlock restores the existing task UI; dismiss unregisters the receiver and removes callbacks. Task/report state is preserved.

Task cards now support right-swipe Open and left-swipe Clear, with glass buttons, spring settling, terminal-only dismissal and Restore cleared cards in Brain Outcomes.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical-device lock/unlock and swipe validation remains UNVERIFIED.
