# Cyclone V5 Alpha 36 — Learn

Developer alpha for owner testing, built on Alpha 35 (Cyclone Marketplace), which it includes. Mobile is
`5.0.0-alpha.36.dev1` (version code 178), Windows companion is `1.6.0-alpha.36`, and the bundled Glass web app is
`1.0.0-alpha.19`.

## What changed

**Learn: one button per run that learns everything that run saw and did.** Ask Cyclone something simple, such as
"open my calculator", press **Learn** on the run, and Cyclone keeps:

- **every screen** the run passed through, so it recognises them next time;
- **every control on those screens**, not only the ones it pressed: all the calculator keys, the history button, the
  menu, stored as how to find them again (their id, description and role), not as pixel positions;
- **every move that worked** (on this screen, pressing this control leads to that screen). A step that failed is
  never kept as a route.

You get one sentence back, for example *"Learned 3 screens, 15 controls and 1 move in Launcher, Calculator."*
Learning the same run twice does nothing new, and learning a second run of the same app merges into the same screens.

**The next run uses it.** When Cyclone reads a screen it has learned, it is told what worked there before ("Learned
before: “History” → History") and which other screens it knows in that app, so it can go straight to the right
control instead of exploring. It is advice: Cyclone still acts on what is really on the screen and checks the screen
after every step.

**Where the buttons are**
- **Phone**: on each finished mission card, and on every run in **Brain → Recent outcomes**, which now has real
  buttons: **Learn**, **Save skill** and **View details**.
- **Glass**: the run inspector has a **Learn** button and shows the phone's sentence.

**Save skill**: on a completed run, saves what you asked as your own recipe in the Marketplace (**Your skills**,
published by "You"), already added, so it is one tap to run again. Goals that mention passwords or codes are not
saved. Removing it from the Marketplace deletes it.

**Privacy**: Learn keeps structure only. Typed text, field values, message contents and anything that looks like an
email, code or token are dropped before anything is stored; text fields are remembered by their hint ("Search"),
never by what was typed. Cyclone's own overlay is never learned.

Runs from before Alpha 36 have no record to learn from; Learn says so ("Run it again and press Learn").

What is not in this alpha: learning many runs at once (dropped: Learn is per run), routes that Cyclone walks by
itself without the model (`go_to`, Alpha 37), and mapping missions with a test account (Alpha 38). The plan is
`Cyclone V5 plan/20-app-mapping-build-plan.md`.

## Validation and limits

Android unit tests (including trail privacy, the calculator learn test and the learned-screen advice), lint, gateway
(`learn.run` contract and route), agent MCP, Glass (run inspector Learn) and companion tests, and the CI guards pass.
Physical Pixel 8 acceptance is UNVERIFIED.

Install the APK as an update; do not uninstall to work around a signing mismatch. The developer-alpha publisher
verifies the historical development certificate against the previous release; that signer is update-compatible but
exposed in repository history and unsuitable for production. The Windows installer is not Authenticode-signed.

Suggested checks: ask "open my calculator and work out 12 times 7"; when it is done, press **Learn** on the mission
card and read the sentence; ask again and check in Glass that the run reads the calculator as a screen it already knows; press **Save skill** and find it under
Marketplace → Your skills; open the run in Glass and press **Learn** there (it says it was already learned).
