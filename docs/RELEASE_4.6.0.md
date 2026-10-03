# Cyclone Mobile 4.6.0

Android versionCode 120; builds on published 4.5.9.

This cut compiles the post-4.5.9 agent stack into one update: login autofill, automatic difficulty, installed-app routing, and optional personal notes.

## Login

Login walls offer **Autofill**, **Take Over**, and **I'm Done**. Autofill focuses the sign-in fields so Android's password manager can fill, then taps the unique Sign in control. Cyclone does not store or type passwords. Easy named-app opens still complete without this gate.

## Difficulty

The ask is classified locally. The screen can only raise the tier.

- **Easy** — open one named app or site. No model. Completes when that app or site is visible.
- **Medium** — one app with leftover work (DM, search, login). Page agent after landing. Chrome custom tabs are not a second app.
- **Hard** — two or more real destinations. Named apps get a local waypoint plan with no extra model call.

Aliases collapse (`facebook`/`fb`, `google maps`/`maps`). One-app sequences stay Medium.

## Installed apps

Unnamed jobs pick the app a person would open: “find a hotel close by” lands Maps (or Booking if that is the app they use); “look when I have that appointment” opens Calendar. Named apps still win.

## User notes

Optional `user.md` in Settings. When on, only matching lines attach to a task. The live agent never writes it. Finished runs add People/Apps locally. If Jacob is on Instagram and WhatsApp, Cyclone asks which app instead of guessing.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical Pixel 8 remains UNVERIFIED.
