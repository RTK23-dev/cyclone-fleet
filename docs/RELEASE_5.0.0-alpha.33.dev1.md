# Cyclone V5 Alpha 33 — Cyclone Lab

Developer alpha for owner testing, built on Alpha 32 (Cyclone Tide overlay), which it includes. Mobile is
`5.0.0-alpha.33.dev1` (version code 175), Windows companion is `1.6.0-alpha.33`, and the bundled Glass web app is `1.0.0-alpha.16`.

## What changed

Cyclone can now measure itself on your phone. Open Glass → **Lab**, pick missions and variants, and press Start. The
PC runs each mission on the phone through the normal Cyclone Mind, then checks the result from what the phone really
shows — the setting that changed, the app on screen, the timer that is running, the answer compared with the phone's
own value — never from what Cyclone says it did.

- **29 built-in missions** (8 quick "smoke" ones): settings, questions answered from the phone, clock, calculator,
  web, Maps, Play Store, YouTube, Files, navigation, multi-app, questions to you, and a safety check that must stop
  for your approval. Add your own as JSON files.
- **Variants** for A/B tests: a different model, reasoning level, working time, numbered boxes on or off, starting
  fresh or with memory, and a prompt addition to try a prompt change without a new build.
- **Honest numbers**: success with a 95% range, how often Cyclone said "done" when the phone disagreed, safety
  failures, time, cost and turns; B vs A with an exact test that says when there are not enough runs yet; a mission ×
  variant table; failed runs grouped by mission with the check that failed, what Cyclone said, its last errors and a
  link to the run inspector. Export every run as JSONL.
- **Deep data on every mission**: each Mind mission now records what it did (tool calls and failures, screen changes,
  repeated actions, time waiting for you, model time, provider trouble).
- **For PC agents**: the Cyclone agent MCP adds `phone_lab_missions`, `phone_lab_start`, `phone_lab_report` and
  `phone_lab_stop`, so a coding agent can run experiments and read the failures itself.

The lab plays you only as far as is safe: it answers questions and fills details from the mission's script, declines
approvals (it never approves), and stops at secrets. It changes a few harmless settings for its missions and puts them
back afterwards. Lab missions start without your Mind memory and never add to it or to your recent missions.

## Validation and limits

Android unit tests, gateway tests (including an end-to-end experiment against a fake phone), agent MCP tests, Glass
tests and CI guards pass; the Lab page was checked rendered in Chromium against the real gateway routes with fixture
data. Physical Pixel 8 acceptance is UNVERIFIED — running the smoke suite on your phone is the first real measurement.

Keep the phone unlocked, awake and charging while the lab runs. Install the APK as an update; do not uninstall to work
around a signing mismatch. The developer-alpha publisher verifies the historical development certificate against the
previous release; that signer is update-compatible but exposed in repository history and unsuitable for production.
The Windows installer is not Authenticode-signed.

Suggested first run: Glass → Lab → smoke, variant A, 1 repetition. Then compare two models on core with 3 repetitions.
