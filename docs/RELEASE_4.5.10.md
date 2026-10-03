# Cyclone Mobile 4.5.10

Android versionCode 119; builds on published 4.5.9.

Login walls now offer three real actions: **Autofill**, **Take Over**, and **I'm Done**.

- Autofill focuses the sign-in fields so Android's password manager can fill, then taps the unique Sign in control. Cyclone does not store or type passwords.
- Take Over hands the screen to you. I'm Done continues after you finish.
- Easy named-app opens still complete without this gate. "Open Facebook and login" and DMs stay Medium and cannot finish just because the app opened.

Difficulty is decided locally from the ask, then the screen can only raise it:

- Easy: open one named app or site. No model. Completes when that app or site is visible.
- Medium: one app with leftover work (DM, search, login). Page agent after landing. Chrome custom tabs do not count as a second app.
- Hard: two or more real destinations. Named apps/sites get a local waypoint plan with no extra model call. Unnamed long-horizon work still maps once.

Optional **User notes** (`user.md`): a short personal sheet you can edit in Settings. When on, Cyclone attaches only the matching lines (Jacob → Instagram/WhatsApp, ask which). The live agent never writes it. Finished runs add People/Apps locally; a cheap model only compresses after several new facts and never touches `# Me`.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates.
