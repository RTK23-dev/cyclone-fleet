# Cyclone V5 Alpha 30 — USB mirror and desktop input

Developer alpha for owner testing, based on tagged Alpha 29. Mobile is
`5.0.0-alpha.30.dev1` (version code 172), Windows companion is `1.6.0-alpha.30`,
and the bundled Glass web app is `1.0.0-alpha.15`.

Glass uses USB H.264 focus video by default, keeps the stream across control
handoffs, and treats an unchanged screen as healthy. Decoder configuration is
retained by the media session. Desktop taps follow the displayed phone pixels;
the first click on a live mirror takes control and performs the same tap.

The large Ask overlay can appear in the mirror instead of blacking out most of
the underlying app. The separate Secrets Card remains capture-protected.

The release continuation also fixes reconnect lifecycle handling: a closed
browser is noticed even when the encoder sends nothing; stale subscribers do
not prevent a replacement producer; rapid reloads wait for the old producer to
finish before restarting; stopping video releases the viewer queues.

## Validation and limits

The original Alpha 30 source passed Mobile, Glass and Windows CI. The final
reconnect patch adds tests for orphaned subscriptions, rapid reloads and closing
a browser during a silent stream. Release artifacts must come from successful
CI builds of the final source commit and pass checksum/provenance checks.

The earlier agent reported H.264 viewing, idle-stream health and mirrored clicks
on a Pixel 8 running Alpha 27. That is not on-device verification of the Alpha 30
Android overlay fix. The final reconnect patch and signed Alpha 30 app still
need physical acceptance. This release is intended for the owner to perform it.

Install the APK as an update; do not uninstall to work around a signing mismatch.
The developer-alpha publisher verifies the historical development certificate
against the previous release. That signer is update-compatible but exposed in
repository history and unsuitable for production. The Windows installer is not
Authenticode-signed. Download both files from the matching GitHub release.

Suggested checks: open Glass Phone, click once to take control, open Gmail,
expand Ask and confirm the image remains visible, leave the phone idle, reload
Glass rapidly, unplug/reconnect USB, and confirm the Secrets Card stays hidden.
