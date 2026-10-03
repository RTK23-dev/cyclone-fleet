# Cyclone Mobile 4.6.2

Android versionCode 122; builds on published 4.6.1.

4.6.1 shipped the Ask Cyclone workspace too early. The empty page still showed a 2D mark, example chips, and a model menu that pushed the canvas down. 4.6.2 matches the board: one compact header, a glowing orb, a composer, and a plus drawer.

## Ask Cyclone

- Header is one row: menu, short model name, profile. Opening the model list overlays the canvas and does not reflow the page.
- Empty state is the orb, **Ready when you are**, and **Tell Cyclone what to do on your phone.** Example chips are gone.
- The orb is a luminous sphere. The C mark is no longer the hero logo.
- **+** opens a 3×2 capability grid (Camera, Photos, Files, screenshot, open app, write text) with the remaining tools as rows.
- Voice is an immersive listening screen with audio bars, not a second composer.
- Composer send/voice stays a single filled control next to the field.

Launch recovery, installed-app Easy opens, and generic OpenRouter handling from 4.6.1 are unchanged.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical Pixel 8 remains UNVERIFIED.
