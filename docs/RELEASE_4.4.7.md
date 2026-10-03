# Cyclone Mobile 4.4.7 — Startup recovery and request controls

Mobile **4.4.7 / versionCode 107** builds on published 4.4.6 and integrates the request-lifecycle repair in PR #115 with the tested reporting and composer fixes in PR #116.

## Requests and Outcomes

- Creates the run report before validation, runtime initialization and the first screen observation.
- Retries transient initial capture failures once on the same task scope after a bounded settle interval. Permission and scope blockers remain explicit failures.
- Waits for UI settling before measuring the capture boundary, preserving rejection of genuine capture races.
- Records startup exceptions, cancellation and direct app-launch outcomes. Late completion cannot overwrite a cancelled report or reopen a finished run.
- Cancels execution before writing the stop report, and binds completion callbacks to their original task.

## Ask Cyclone overlay

- The rightmost action is always a send arrow when idle, with a single separate microphone on its left.
- During a request, the action becomes Pause. A short tap pauses and arms a half-circle that retracts over three seconds; a second tap during that interval stops the request.
- Holding for two seconds fills the ring and stops the request.
- After stop confirmation expires, tapping the paused control resumes. An accessible Stop action is available.

## Validation and compatibility

The implementation passed Android unit tests, lint, release assembly, 83 repository guards and 185 gateway tests in Mobile CI 34989126088. Fifteen new JVM tests cover startup/cancellation, bounded observation recovery and gesture timing. The versioned release commit is independently validated by the release workflow before publication.

Preserves the 4.4.6 visual and observation infrastructure, Android package identity, Cyclone One 1.5.5 compatibility, approval boundaries and secret redaction. Upgrade installation is checked against the 4.4.6 signing certificate.

Physical-device startup, touch animation and Reddit acceptance remain **UNVERIFIED**. This release does not claim that automated tests reproduce every failure on the user's phone.

