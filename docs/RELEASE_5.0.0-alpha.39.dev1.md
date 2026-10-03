# Cyclone V5 Alpha 39 — One map, grounded skills and routines

Developer alpha for owner testing, built on Alpha 38 (mapping missions from Glass), which it includes. Mobile is
`5.0.0-alpha.39.dev1` (version code 181), Windows companion is `1.6.0-alpha.39`, and the bundled Glass web app is
`1.0.0-alpha.22`.

## What changed

**Mapping now makes runs faster.** Until now a mapping mission filled Glass's map but the knowledge runs route on came
only from Learn. Now, when a mapping pass ends, everything it walked is learned the same way a Learn press learns a
run, so the next run can `go_to` those screens. What a *test account* mapped counts as less certain: Cyclone prefers
ways confirmed on your own account and confirms a test-account way the first time it walks it with yours. The pass
report says how many screens and moves runs can now use.

**Skills know where they work.** Save skill on a finished run now also learns the run and remembers where the skill
lives on the app's map: the way in and the screen where the work happens. Each skill shows its health:
- *Route known*: Cyclone knows the way to where it works;
- *Destination known*: it knows the screen, and finds the way live;
- *Needs re-check*: the app changed and that screen is no longer on the map; the next run finds it again;
- *Not grounded*: saved before this alpha. Run it once and save it again.

When a skill runs, Cyclone is told where it works and walks there on the map instead of searching, checking every
screen, then finishes the goal itself. Every skill run that finishes re-grounds the skill where it worked, so skills
follow the app when it updates.

**Routines can run your skills.** In the Routine builder, **Run one of your skills** is now the first action. The
skill runs like an Ask (same approvals, same Secrets Card); if the phone is busy the routine run fails and says so.
Routines are labelled *Grounded on the map* or *Scripted taps · not grounded*. Your existing routines keep working as
before.

**Skills on Glass's map.** Each app has a **Skills** tab: every skill with its health, its saved way (where it works is
highlighted), *Show on the map* and *Run on the phone*. Selecting a place on the map lists the skills that pass or work
there, and the Apps fleet shows each app's skills and how many need a re-check.

## Validation and limits

Android unit tests (the mapping trail and its learning, test-account trust, route preference, skill anchors from real
trails, health against the map, the skill card, anchor storage and re-grounding, routine skill steps and refusals,
`skills.list`), lint, gateway contract (`skills.list` validation and route), both MCP suites, Glass (skills parsing,
Skills tab, the way on the Taught map, skills through a place, fleet counts) and companion tests, and the CI guards
pass. Glass was checked rendered in Chromium against fixtures. **Physical Pixel 8 acceptance is UNVERIFIED.**

Not in this alpha: converting an old scripted routine automatically, skills with typed inputs, and the reliable-typing
work (plan 21), which moves to Alpha 40.

Install the APK as an update; do not uninstall to work around a signing mismatch. The developer-alpha publisher
verifies the historical development certificate against the previous release; that signer is update-compatible but
exposed in repository history and unsuitable for production. The Windows installer is not Authenticode-signed.

Suggested checks:
1. Glass → Apps → Clock → Start mapping → *My account — look only*, 10 min. When it ends, ask on the phone "open the
   stopwatch in Clock": the run should show the map card and a `go_to`.
2. Ask "set a 10 minute timer", then press **Save skill** on the finished run. Glass → Clock → Skills shows it as
   *Route known*; *Show on the map* draws its way.
3. Routines → New → **Run one of your skills** → pick it → save; run the routine.

## Re-signed with the rotated release key (signing blocker closed)

Alpha.34 moved Cyclone to the rotated release key (`CN=Cyclone Mobile, OU=Release`, SHA-256 `e78c6e0b…dbf60`, with a
lineage from the historical key `cc2a7a5d…f3965`). Phones on alpha.34 refused alpha.35–39, which were signed with the
historical key only. On 2026-09-26 the owner supplied the release-key secrets to `mobile-release-approval`, and
`v5-alpha39-rotated-resign.yml` (run 36237463252) re-signed the exact alpha.39 CI build (Mobile CI run 36234949941,
source `7d6ac2ca`) and replaced the APK on the tag. Verified afterwards:
- signer `e78c6e0b…dbf60`, the same as alpha.34; the lineage includes `cc2a7a5d…f3965` (old installs can update too);
- `versionCode 181`, `versionName 5.0.0-alpha.39.dev1`; the published SHA-256 `56d7ed5c…c834` matches the sidecar and
  `release-manifest.json`;
- all 200 code and resource entries are identical to the CI build; only signature files changed.

Alpha.35–38 were not re-signed.
