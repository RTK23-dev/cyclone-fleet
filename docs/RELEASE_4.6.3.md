# Cyclone Mobile 4.6.3

Android versionCode 123; builds on published 4.6.2.

Ask Cyclone now follows the Gemini canvas discipline: a quiet gradient, one welcome line, the model name in the header, and the intelligence slider inside the plus sheet.

## Ask Cyclone

- Dark mode fades blue into black. Light mode stays white and cools to pale blue at the bottom.
- Empty state is a small orb and **Ready when you are**. No extra chips, cards, or helper buttons.
- The model name stays in the header and opens as an overlay. It does not reflow the page, including while the keyboard is open.
- **+** is a bottom sheet over the canvas. Camera, Photos, files, and phone actions stay there. The intelligence / autonomy slider lives at the bottom of that sheet instead of pushing the composer down.

Launch recovery and Easy named-app opens from 4.6.1/4.6.2 are unchanged.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical Pixel 8 remains UNVERIFIED after this canvas pass.

