# Cyclone V5 Alpha 31 — Owner Moments

Developer alpha for owner testing, built on Alpha 30. Mobile is `5.0.0-alpha.31.dev1` (version code 173), Windows
companion is `1.6.0-alpha.31`, and the bundled Glass web app is `1.0.0-alpha.15`. It contains everything in Alpha 30
(USB Glass mirror, exact desktop input, reconnect fixes).

## What changed

Whenever a task needs you, whichever agent runs it — Cyclone Mind, the classic agent or a background workspace — you
now see one kind of card: a question, a few details to fill in, an approval, a secure-input wall or "your turn". The
card is the same in the overlay, in Ask and on the mission card, and every button goes to the agent that owns the task.

The task notification is now actionable: it shows what Cyclone needs and the same buttons. Questions can be answered
inline from the notification. Details to fill in open the card when you tap the notification. Approve, Confirm, I'm
done, Autofill and inline replies only work on an unlocked phone; Stop always works. Secrets still go only through the
Secrets Card.

## Validation and limits

Android unit tests (1696), Task Kit / Owner Moments contract tests and CI guards pass locally; release artifacts come
from CI builds of the release commit and pass checksum/provenance checks. Physical Pixel 8 acceptance is UNVERIFIED.

Install the APK as an update; do not uninstall to work around a signing mismatch. The developer-alpha publisher
verifies the historical development certificate against the previous release. That signer is update-compatible but
exposed in repository history and unsuitable for production. The Windows installer is not Authenticode-signed.

Suggested checks: start a Mind mission that asks a question and answer it from the notification (phone unlocked);
lock the phone and confirm Reply/Approve ask to unlock while Stop works; trigger a sign-up check-in, tap the
notification and fill the card; run a classic task to its approval card and approve from the overlay; press "Not now"
on a check-in and confirm the mission continues.
